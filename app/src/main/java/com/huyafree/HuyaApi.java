package com.huyafree;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 虎牙接口封装。
 *
 * 【关键：antiCode 必须本地重算签名】
 * 虎牙 Web 页面下发的 antiCode 里 wsSecret 对持续拉流无效 —— 实测拉满约 1MB 后
 * CDN 就主动断连（Connection: close，无 Content-Length），重连时旧签名 403，
 * 播放器表现为无限转圈。
 *
 * 判定条件（与 yt-dlp huya 提取器一致）：
 *     screenType == 0 且 liveSourceType in (0, 8, 13)  → 必须重算
 *
 * 重算算法：
 *     ct   = int(wsTime, 16) + random()
 *     uid  = lPresenterUid（streamName 不以 uid 开头时）
 *     u    = (uid & 0xffffffff00000000) | ((uid & 0xffffffff) >> 24) | ((uid & 0xffffff) << 8)
 *     seqid = int(ct*1000) + uid
 *     ss    = md5(seqid|ctype|t)
 *     wsSecret = md5(fm_u_streamName_ss_wsTime)
 *
 * 接口来源：
 *   房间/线路 : www.huya.com/<room> 页面里的 `stream: {...}`（字段比 mp 接口全）
 *   分类目录 : cache.php?m=Game&do=gameList
 *   直播列表 : cache.php?m=LiveList&do=getLiveListByPage
 *   搜索联想 : search.cdn.huya.com?m=Search&do=getSearchContent&typ=-7
 */
public final class HuyaApi {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    /** 供 EndlessFlvDataSource 使用。 */
    public static final String UA_MOBILE = UA;
    private static final String UA_PC =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String HOST = "https://www.huya.com";
    private static final String SEARCH = "https://search.cdn.huya.com";

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /**
     * 默认码率。
     *
     * <p>ratio 不是"请求码率"，它就是虎牙的<b>码率档位选择器</b>，
     * 直接映射到 CDN 实际下发的分辨率（实测房间 660000 线路 AL）：
     * <pre>
     *   ratio=0     → 1920×1080（原画，不指定档位）
     *   ratio=500   →  800×450
     *   ratio=2000  → 1280×720
     *   ratio=4000  → 1920×1080
     *   ratio=6000  → 1920×1080
     * </pre>
     *
     * <p>之前默认 2000，等于强制 720p —— 在 1080p 手机上放大就糊，
     * 这才是"分辨率低/画面拉伸"的主因之一。
     *
     * <p>现在默认 0（原画，交给 CDN 给最高档），用户可在播放页切档。
     */
    public static final int DEFAULT_BITRATE = 0;

    /** 可选档位，UI 上循环切换用。0 = 原画。 */
    public static final int[] BITRATES = {0, 500, 2000, 4000, 6000};

    /** 档位显示名，与 {@link #BITRATES} 一一对应。 */
    public static final String[] BITRATE_NAMES = {"原画", "流畅", "高清", "超清", "蓝光"};

    /**
     * App 端 ctype。这是能否长连接的分水岭：
     * huya_live（网页）→ CDN 只回一个缓存 GOP 就断；
     * huya_adr （App） → 真正的直播长连接。
     */
    private static final String CTYPE_APP = "huya_adr";

    private HuyaApi() {
    }

    public interface Cb<T> {
        void ok(T data);

        void fail(String msg);
    }

    // ---------------- 数据模型 ----------------

    public static final class Game {
        public final String gid;
        public final String name;
        public final String icon;

        public Game(String gid, String name, String icon) {
            this.gid = gid;
            this.name = name;
            // 页面给的是 //host/path 或 https://host/path，统一补全协议
            if (icon == null || icon.isEmpty()) {
                this.icon = "";
            } else if (icon.startsWith("//")) {
                this.icon = "https:" + icon;
            } else if (icon.startsWith("http")) {
                this.icon = icon;
            } else {
                this.icon = "https://" + icon;
            }
        }
    }

    public static final class Live {
        public final String roomId;
        public final String title;
        public final String anchor;
        public final String game;
        public final String cover;
        public final long viewers;
        public final boolean bluray;

        public Live(String roomId, String title, String anchor, String game,
                    String cover, long viewers, boolean bluray) {
            this.roomId = roomId;
            this.title = title;
            this.anchor = anchor;
            this.game = game;
            this.cover = cover;
            this.viewers = viewers;
            this.bluray = bluray;
        }

        public Favorites.Room toRoom() {
            return new Favorites.Room(roomId, title, anchor, game, cover, viewers);
        }
    }

    public static final class Stream {
        public final String flv;
        public final boolean live;
        public final String title;
        public final String anchor;
        public final boolean resigned;
        public final String cdn;
        /** 本房间全部可用线路（已按移动端优先级从高到低排序）。 */
        public final String[] cdns;
        /** 主播 uid，弹幕房间号。原版用它拼 chat:{pid} 订阅组。 */
        public final long presenterUid;
        public final int[] qualityRatios;
        public final String[] qualityNames;
        public final int selectedRatio;

        public Stream(String flv, boolean live, String title, String anchor,
                      boolean resigned, String cdn, String[] cdns,
                      long presenterUid) {
            this(flv, live, title, anchor, resigned, cdn, cdns, presenterUid,
                    new int[]{0}, new String[]{"原画"}, 0);
        }

        public Stream(String flv, boolean live, String title, String anchor,
                      boolean resigned, String cdn, String[] cdns, long presenterUid,
                      int[] qualityRatios, String[] qualityNames, int selectedRatio) {
            this.flv = flv;
            this.live = live;
            this.title = title;
            this.anchor = anchor;
            this.resigned = resigned;
            this.cdn = cdn;
            this.cdns = cdns;
            this.presenterUid = presenterUid;
            this.qualityRatios = qualityRatios;
            this.qualityNames = qualityNames;
            this.selectedRatio = selectedRatio;
        }
    }

    /**
     * 一条 CDN 线路。
     *
     * <p>官方优先级字段 iPCPriorityRate / iWebPriorityRate / iMobilePriorityRate
     * 决定线路好坏：实测某房间 TX=60、HS=40、AL=-1，
     * 而 AL（-1，即对移动端不评级）只能拉 0.1 秒就断，TX 能稳定拉满 120 秒。
     * 所以必须按优先级选线，不能固定用第一条。
     */
    private static final class Line {
        final String cdn;
        final String base;
        final String name;
        final String suffix;
        final String anti;
        final long uid;
        final int priority;

        Line(String cdn, String base, String name, String suffix,
             String anti, long uid, int priority) {
            this.cdn = cdn;
            this.base = base;
            this.name = name;
            this.suffix = suffix;
            this.anti = anti;
            this.uid = uid;
            this.priority = priority;
        }

        String url(String query) {
            return base + "/" + name + "." + suffix + "?" + query;
        }
    }

    // ---------------- 网络 ----------------

    private static String get(String url, String ua, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", ua);
            c.setRequestProperty("Referer", HOST + "/");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) {
                throw new RuntimeException("HTTP " + code);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private static <T> void async(Cb<T> cb, Worker<T> w) {
        POOL.execute(() -> {
            try {
                T r = w.run();
                MAIN.post(() -> cb.ok(r));
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.toString() : e.getMessage();
                MAIN.post(() -> cb.fail(m));
            }
        });
    }

    private interface Worker<T> {
        T run() throws Exception;
    }

    // ---------------- 签名 ----------------

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** query 字符串 → 有序 Map。 */
    private static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null) {
            return m;
        }
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i < 0) {
                continue;
            }
            try {
                String k = java.net.URLDecoder.decode(pair.substring(0, i), "UTF-8");
                String v = java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static String encodeQuery(Map<String, String> m) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), "UTF-8"));
        }
        return sb.toString();
    }

    /** 判断是否必须本地重算 wsSecret。 */
    private static boolean needResign(JSONObject live) {
        int screenType = live.optInt("screenType", 0);
        int srcType = live.optInt("liveSourceType", -1);
        return screenType == 0 && (srcType == 0 || srcType == 8 || srcType == 13);
    }

    /**
     * 重算 antiCode。返回新的 query 字符串。
     *
     * <p>算法与官方 CDNLine / yt-dlp 一致。必须注意两个点：
     *
     * <ol>
     *   <li><b>ctype 必须改成 {@code huya_adr}</b>。网页下发的是 {@code huya_live}，
     *       用那个值 CDN 会走"缓存 GOP 切片"模式：一次只吐约 1.5MB（5-6 秒）、
     *       速度近 10GB/s 然后主动断连，播放器表现就是不停转圈重连。
     *       换成 App 用的 {@code huya_adr} 才是真正的直播长连接 —— 实测
     *       60 秒 × 3 轮、三线路、四档码率全部稳定不断。</li>
     *   <li>{@code wsSecret} 依赖 ctype 参与 md5，所以改 ctype 之后必须用
     *       新 ctype 重新算 ss，不能沿用原来的签名。</li>
     * </ol>
     */
    private static String resign(String antiCode, String streamName,
                                 long presenterUid) throws Exception {
        Map<String, String> p = parseQuery(antiCode);

        String wsTime = p.get("wsTime");
        if (wsTime == null) {
            throw new RuntimeException("antiCode 缺少 wsTime");
        }

        // 关键：切到 App 端的 ctype
        p.put("ctype", CTYPE_APP);

        double ct = (double) Long.parseLong(wsTime, 16) + Math.random();

        long uid;
        if (!streamName.startsWith(String.valueOf(presenterUid))) {
            uid = presenterUid;
        } else {
            uid = (long) (ct % 1e7 * 1e6 % 0xFFFFFFFFL);
        }

        long u1 = uid & 0xFFFFFFFF00000000L;
        long u2 = uid & 0xFFFFFFFFL;
        long u3 = uid & 0xFFFFFFL;
        long u = u1 | (u2 >> 24) | (u3 << 8);

        long seqid = (long) (ct * 1000) + uid;
        long uuid = (long) (ct % 1e7 * 1e6 % 0xFFFFFFFFL);

        p.put("u", String.valueOf(u));
        p.put("seqid", String.valueOf(seqid));
        p.put("ver", "1");
        p.put("uuid", String.valueOf(uuid));
        p.put("t", "100");

        String fmRaw = p.get("fm");
        String fm = new String(Base64.getDecoder().decode(fmRaw), "UTF-8");
        int us = fm.indexOf('_');
        if (us > 0) {
            fm = fm.substring(0, us);
        }

        String ctype = p.get("ctype");
        String ss = md5(seqid + "|" + ctype + "|" + p.get("t"));

        p.put("wsSecret", md5(fm + "_" + u + "_" + streamName + "_" + ss + "_" + wsTime));

        return encodeQuery(p);
    }

    // ---------------- 直播源 ----------------

    /**
     * 从 Web 页面里抠出 `stream: {...}` JSON。
     * 用括号配平而不是正则 —— 正则在嵌套 JSON 上会被截断。
     */
    private static JSONObject extractStreamJson(String html) throws Exception {
        int i = html.indexOf("stream:");
        if (i < 0) {
            throw new RuntimeException("页面里找不到 stream 配置");
        }
        int start = html.indexOf('{', i);
        if (start < 0) {
            throw new RuntimeException("stream 配置格式异常");
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int k = start; k < html.length(); k++) {
            char c = html.charAt(k);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return new JSONObject(html.substring(start, k + 1));
                }
            }
        }
        throw new RuntimeException("stream 配置不完整");
    }

    /** 取直播源。默认自动选移动端优先级最高的线路。 */
    public static void stream(String roomId, Cb<Stream> cb) {
        stream(roomId, DEFAULT_BITRATE, -1, cb);
    }

    /**
     * @param cdnIndex -1 表示自动按优先级选，否则强制用排序后的第几路
     */
    public static void stream(String roomId, int bitrate, int cdnIndex, Cb<Stream> cb) {
        async(cb, () -> fetchStream(roomId, bitrate, cdnIndex));
    }

    private static Stream fetchStream(String roomId, int bitrate, int cdnIndex)
            throws Exception {
        return fetchStream(roomId, bitrate, cdnIndex, null);
    }

    public static void stream(String roomId, int bitrate, String cdnName, Cb<Stream> cb) {
        async(cb, () -> fetchStream(roomId, bitrate, -1, cdnName));
    }

    private static LinkedHashMap<Integer, String> qualities(JSONObject streamCfg) {
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        JSONArray raw = streamCfg.optJSONArray("vMultiStreamInfo");
        if (raw != null) {
            for (int i = 0; i < raw.length(); i++) {
                JSONObject q = raw.optJSONObject(i);
                // HEVC/HDR entries require codec negotiation beyond a ratio selector.
                if (q == null || q.optInt("iCodecType", 0) != 0
                        || q.optInt("iCompatibleFlag", 0) != 0) continue;
                int ratio = q.optInt("iBitRate", -1);
                if (ratio < 0 || out.containsKey(ratio)) continue;
                String name = q.optString("sDisplayName", "").trim();
                out.put(ratio, name.isEmpty() ? (ratio == 0 ? "原画" : ratio + "K") : name);
            }
        }
        if (out.isEmpty()) out.put(0, "原画");
        return out;
    }

    private static int supportedRatio(LinkedHashMap<Integer, String> qualities, int requested) {
        if (qualities.containsKey(requested)) return requested;
        return qualities.containsKey(0) ? 0 : qualities.keySet().iterator().next();
    }

    private static Stream fetchStream(String roomId, int bitrate, int cdnIndex, String cdnName)
            throws Exception {
        String html = get(HOST + "/" + roomId, UA_PC, 25000);
        JSONObject streamCfg = extractStreamJson(html);
        LinkedHashMap<Integer, String> qualityMap = qualities(streamCfg);
        bitrate = supportedRatio(qualityMap, bitrate);

        JSONArray arr = streamCfg.optJSONArray("data");
        if (arr == null || arr.length() == 0) {
            throw new RuntimeException("未开播或房间不存在");
        }
        JSONObject data = arr.getJSONObject(0);
        JSONObject live = data.optJSONObject("gameLiveInfo");
        JSONArray raw = data.optJSONArray("gameStreamInfoList");
        if (raw == null || raw.length() == 0) {
            throw new RuntimeException("没有可用线路（可能未开播）");
        }

        // 无条件重签。
        //
        // 之前用 needResign(live) 判断，依赖 screenType / liveSourceType 两个
        // 房间字段。任意一个不匹配就沿用页面下发的 antiCode —— 那份的 ctype
        // 是 huya_live，CDN 会走"缓存 GOP 切片"模式，几秒就断。
        // Python 探针是无条件签的，所以能跑 300 秒；App 侧判定不同，
        // 表现就是"看几秒提示流已结束"。这里统一成无条件签。
        boolean resign = true;

        // 收集线路并按移动端优先级降序排列
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject o = raw.getJSONObject(i);
            String base = o.optString("sFlvUrl");
            String name = o.optString("sStreamName");
            String anti = o.optString("sFlvAntiCode");
            if (base.isEmpty() || name.isEmpty() || anti.isEmpty()) {
                continue;
            }
            int prio = o.optInt("iMobilePriorityRate", -1);
            if (prio < 0) {
                prio = o.optInt("iPCPriorityRate", -1);
            }
            lines.add(new Line(
                    o.optString("sCdnType", "?"),
                    base, name,
                    o.optString("sFlvUrlSuffix", "flv"),
                    anti,
                    o.optLong("lPresenterUid", 0),
                    prio));
        }
        if (lines.isEmpty()) {
            throw new RuntimeException("线路字段缺失");
        }
        // 稳定排序：优先级高的在前，-1 的排最后
        lines.sort((a, b) -> Integer.compare(b.priority, a.priority));

        int idx = cdnIndex < 0 ? 0 : Math.min(cdnIndex, lines.size() - 1);
        if (cdnName != null && !cdnName.isEmpty()) {
            idx = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (cdnName.equals(lines.get(i).cdn)) { idx = i; break; }
            }
            if (idx < 0) throw new RuntimeException("Selected CDN is no longer available: " + cdnName);
        }
        Line line = lines.get(idx);

        String query = resign
                ? resign(line.anti, line.name, line.uid)
                : encodeQuery(parseQuery(line.anti));

        // 官方 getFlvFullUrl 的顺序：antiCode → ratio → codec。
        // ratio 只有非 0 才加；实测 huya_adr 下 500/2000/4000/6000 全部稳定。
        if (bitrate > 0) {
            query = query + "&ratio=" + bitrate;
        }

        String[] cdns = new String[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            cdns[i] = lines.get(i).cdn;
        }

        String title = live == null ? "" : liveTitle(live);
        String anchor = live == null ? "" : live.optString("nick");
        boolean isLive = live == null || live.optInt("liveStatus", 1) != 0;

        int[] qualityRatios = new int[qualityMap.size()];
        String[] qualityNames = new String[qualityMap.size()];
        int qualityIndex = 0;
        for (Map.Entry<Integer, String> quality : qualityMap.entrySet()) {
            qualityRatios[qualityIndex] = quality.getKey();
            qualityNames[qualityIndex++] = quality.getValue();
        }
        return new Stream(line.url(query), isLive, title, anchor,
                resign, line.cdn, cdns, line.uid, qualityRatios, qualityNames, bitrate);
    }

    // ---------------- 供 EndlessFlvDataSource 用的同步接口 ----------------

    /**
     * 每次重连都调这里拿新地址。会给 antiCode 重新签名（wsTime 只有 5 分钟有效期），
     * 并在连续失败时自动轮换 CDN 线路。
     *
     * @param failStreak 当前连续失败次数，用来决定是否换线
     */
    public static String streamUrlSync(String roomId, int bitrate,
                                       int cdnIndex, int failStreak) throws Exception {
        String html = get(HOST + "/" + roomId, UA_PC, 20000);
        JSONObject streamCfg = extractStreamJson(html);
        bitrate = supportedRatio(qualities(streamCfg), bitrate);
        JSONArray arr = streamCfg.optJSONArray("data");
        if (arr == null || arr.length() == 0) {
            throw new RuntimeException("未开播或房间不存在");
        }
        JSONObject data = arr.getJSONObject(0);
        JSONObject live = data.optJSONObject("gameLiveInfo");
        JSONArray raw = data.optJSONArray("gameStreamInfoList");
        if (raw == null || raw.length() == 0) {
            throw new RuntimeException("没有可用线路");
        }

        // 无条件重签。
        //
        // 之前用 needResign(live) 判断，依赖 screenType / liveSourceType 两个
        // 房间字段。任意一个不匹配就沿用页面下发的 antiCode —— 那份的 ctype
        // 是 huya_live，CDN 会走"缓存 GOP 切片"模式，几秒就断。
        // Python 探针是无条件签的，所以能跑 300 秒；App 侧判定不同，
        // 表现就是"看几秒提示流已结束"。这里统一成无条件签。
        boolean resign = true;

        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject o = raw.getJSONObject(i);
            String base = o.optString("sFlvUrl");
            String name = o.optString("sStreamName");
            String anti = o.optString("sFlvAntiCode");
            if (base.isEmpty() || name.isEmpty() || anti.isEmpty()) {
                continue;
            }
            int prio = o.optInt("iMobilePriorityRate", -1);
            if (prio < 0) {
                prio = o.optInt("iPCPriorityRate", -1);
            }
            lines.add(new Line(o.optString("sCdnType", "?"), base, name,
                    o.optString("sFlvUrlSuffix", "flv"), anti,
                    o.optLong("lPresenterUid", 0), prio));
        }
        if (lines.isEmpty()) {
            throw new RuntimeException("线路字段缺失");
        }
        lines.sort((a, b) -> Integer.compare(b.priority, a.priority));

        // 第 3 次连续失败开始轮换线路，避免死守一条已经废掉的线
        int idx;
        if (cdnIndex >= 0) {
            idx = Math.min(cdnIndex, lines.size() - 1);
        } else {
            idx = (failStreak >= 3) ? (failStreak - 3) % lines.size() : 0;
        }
        Line line = lines.get(idx);

        String query = resign
                ? resign(line.anti, line.name, line.uid)
                : encodeQuery(parseQuery(line.anti));
        if (bitrate > 0) {
            query = query + "&ratio=" + bitrate;
        }
        return line.url(query);
    }

    // ---------------- 分类目录 ----------------

    /**
     * 游戏分类卡片。
     *
     * <p>真实结构（2024 版页面）：
     * <pre>
     * &lt;li class="g-gameCard-item" data-gid="1" title=英雄联盟&gt;
     *   &lt;a class="g-gameCard-link" href="..." data-gid="1"&gt;
     *     &lt;img class="g-gameCard-img" src="..." alt=英雄联盟 data-default-img="84x84"/&gt;
     *     &lt;p class="g-gameCard-fullName"&gt;英雄联盟&lt;/p&gt;
     * </pre>
     *
     * <p>两个坑：
     * <ol>
     *   <li>{@code title} 和 {@code alt} 都<b>没有引号</b>，值一直延伸到下一个空格或
     *       {@code >}，所以旧正则的 {@code alt=...} 会把后面的
     *       {@code data-default-img="84x84"} 一起吞进来，界面上就多出
     *       "data default img" 这种文字；</li>
     *   <li>名字的真正出处是 {@code <p class="g-gameCard-fullName">}，比 alt
     *       更稳定，也不会被属性顺序影响。</li>
     * </ol>
     *
     * <p>所以这里只从 {@code li} 的 data-gid 定位块，再从块里分别取
     * {@code g-gameCard-fullName} 的文本和 {@code g-gameCard-img} 的 src。
     */
    private static final Pattern GAME_CARD = Pattern.compile(
            "data-gid=\"(\\d+)\"[^>]*>(.*?)</li>",
            Pattern.DOTALL);

    private static final Pattern GAME_NAME = Pattern.compile(
            "g-gameCard-fullName\"[^>]*>\\s*([^<]+?)\\s*<",
            Pattern.DOTALL);

    private static final Pattern GAME_ICON = Pattern.compile(
            "g-gameCard-img\"[^>]*?src=\"([^\"]+)\"");

    public static void games(Cb<List<Game>> cb) {
        async(cb, () -> fetchGames());
    }

    private static List<Game> fetchGames() throws Exception {
        String html = get(HOST + "/cache.php?m=Game&do=gameList", UA_PC, 20000);
        return parseGames(html);
    }

    /** 从分类页 HTML 里抽 gid / 名称 / 图标，并按 gid 去重。 */
    static List<Game> parseGames(String html) {
        List<Game> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = GAME_CARD.matcher(html);
        while (m.find()) {
            String gid = m.group(1);
            if (!seen.add(gid)) {
                continue;
            }
            String block = m.group(2);

            String name = "";
            Matcher nm = GAME_NAME.matcher(block);
            if (nm.find()) {
                name = nm.group(1).trim();
            }

            String icon = "";
            Matcher im = GAME_ICON.matcher(block);
            if (im.find()) {
                icon = im.group(1).trim();
            }

            // 名称都拿不到就跳过：说明这个块不是正常的分类卡片，
            // 塞一个空名字进去只会在界面上留一个无名标签。
            if (name.isEmpty()) {
                continue;
            }
            out.add(new Game(gid, name, icon));
        }
        return out;
    }

    // ---------------- 直播列表 ----------------

    public static void liveList(String gameId, int page, Cb<List<Live>> cb) {
        async(cb, () -> fetchLiveList(gameId, page));
    }

    /**
     * 直播列表里的标题。
     *
     * <p>接口已经不返回 {@code roomName} 了（实测 120 条全部为空字符串），
     * 之前一直读它，界面上就全变成"房间 10188"这种占位文字。
     * 真实可用的字段是 {@code introduction}（主播自己写的简介），
     * 商品化直播间还会带 {@code recommendTagName}（如"超级明星"）。
     */
    private static String liveTitle(JSONObject o) {
        String[] keys = {"introduction", "roomName", "liveDesc", "nick"};
        for (String k : keys) {
            String v = o.optString(k, "");
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static List<Live> fetchLiveList(String gameId, int page) throws Exception {
        String url = HOST + "/cache.php?m=LiveList&do=getLiveListByPage"
                + "&tagAll=0&page=" + page
                + (gameId == null || gameId.isEmpty() ? "" : "&gameId=" + gameId);
        JSONObject j = new JSONObject(get(url, UA_PC, 20000));
        JSONArray arr = j.getJSONObject("data").optJSONArray("datas");
        List<Live> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Live(
                        o.optString("profileRoom"),
                        liveTitle(o),
                        o.optString("nick"),
                        o.optString("gameFullName"),
                        o.optString("screenshot"),
                        o.optLong("totalCount"),
                        o.optInt("isBluRay", 0) == 1));
            }
        }
        return out;
    }

    // ---------------- 搜索 ----------------

    public static void suggest(String q, Cb<List<String>> cb) {
        async(cb, () -> {
            String url = SEARCH + "/?m=Search&do=getSearchContent&q="
                    + URLEncoder.encode(q, "UTF-8")
                    + "&uid=0&v=4&typ=-7&livestate=0&rows=10&callback=cb";
            String raw = get(url, UA_PC, 15000).trim();
            int a = raw.indexOf('(');
            int b = raw.lastIndexOf(')');
            if (a < 0 || b <= a) {
                return new ArrayList<String>();
            }
            JSONObject j = new JSONObject(raw.substring(a + 1, b));
            JSONArray docs = j.getJSONObject("response")
                    .getJSONObject("1024").getJSONArray("docs");
            List<String> out = new ArrayList<>();
            for (int i = 0; i < docs.length(); i++) {
                String w = docs.getJSONObject(i).optString("associate_word");
                if (!w.isEmpty()) {
                    out.add(w);
                }
            }
            return out;
        });
    }

    /**
     * 搜索直播间。虎牙搜索接口 typ 只接受 -7（联想词），
     * 所以：联想词/关键词 → 匹配分类名拿 gid → 拉分类列表 → 本地再过滤。
     */
    public static void search(String q, Cb<List<Live>> cb) {
        async(cb, () -> {
            String kw = q.toLowerCase();

            List<Game> all = fetchGames();
            List<String> gids = new ArrayList<>();
            for (Game g : all) {
                String n = g.name.toLowerCase();
                if (n.contains(kw) || kw.contains(n)) {
                    if (!gids.contains(g.gid)) {
                        gids.add(g.gid);
                    }
                }
            }

            List<Live> out = new ArrayList<>();
            if (!gids.isEmpty()) {
                for (int i = 0; i < Math.min(3, gids.size()); i++) {
                    out.addAll(fetchLiveList(gids.get(i), 1));
                    if (out.size() >= 60) {
                        break;
                    }
                }
            }

            for (int p = 1; p <= 3 && out.size() < 60; p++) {
                for (Live l : fetchLiveList("", p)) {
                    String hay = (l.title + " " + l.anchor + " " + l.game).toLowerCase();
                    if (hay.contains(kw) && !containsRoom(out, l.roomId)) {
                        out.add(l);
                    }
                }
            }
            return out;
        });
    }

    private static boolean containsRoom(List<Live> list, String id) {
        for (Live l : list) {
            if (l.roomId.equals(id)) {
                return true;
            }
        }
        return false;
    }
}
