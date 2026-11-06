package com.huyafree;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 主界面：搜索 + 分类横滑 + 直播列表。纯原生控件，不依赖 AppCompat/Glide。 */
public class MainActivity extends Activity {

    private static final int TAB_RECOMMEND = 0;
    private static final int TAB_FAVORITE = 1;

    private EditText searchBox;
    private LinearLayout chipBar;
    private ListView listView;
    private ProgressBar progress;
    private TextView emptyView;
    private Button moreBtn;
    private Button themeBtn;

    private final List<HuyaApi.Live> items = new ArrayList<>();
    private final List<HuyaApi.Game> games = new ArrayList<>();
    private LiveAdapter adapter;

    private int tab = TAB_RECOMMEND;
    private String gameId = "";
    private int page = 1;
    private boolean loading = false;
    private String keyword = "";

    // 封面图缓存，避免滚动时反复下载
    private static final Map<String, Bitmap> IMG_CACHE = new HashMap<>();
    private static final ExecutorService IMG_POOL = Executors.newFixedThreadPool(3);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // 窗口背景在 onCreate 之前就画了，styles.xml 里没法跟随主题。
        // 这里第一时间换成实际主题色，避免亮色主题下闪一下深色。
        try {
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Theme.bg(this)));
        } catch (Exception ignored) {
        }
        buildUi();
        applySystemBar();
        loadGames();
        reload();
    }


    /** 状态栏/导航栏跟随主题。 */
    private void applySystemBar() {
        try {
            getWindow().setStatusBarColor(Theme.systemBar(this));
            getWindow().setNavigationBarColor(Theme.systemBar(this));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                View dv = getWindow().getDecorView();
                int flags = dv.getSystemUiVisibility();
                if (Theme.lightSystemBarIcons(this)) {
                    // 亮底 → 深色图标
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                dv.setSystemUiVisibility(flags);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 切换黑白主题。
     *
     * <p>用 recreate() 重建 Activity 而不是手动遍历视图改颜色。
     * 因为颜色分散在 ListView 的 adapter 回调里（getView 时才创建），
     * 手动改只能覆盖当前可见的几项，滚动后新生成的项还是旧颜色。
     * recreate() 会走完整的 onCreate → buildUi，保证全部一致。
     */
    private void switchTheme() {
        boolean light = Theme.toggle(this);
        Toast.makeText(this, light ? "已切换亮色主题" : "已切换暗色主题",
                Toast.LENGTH_SHORT).show();
        recreate();
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.bg(this));
        root.setFitsSystemWindows(true);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(16), dp(14), dp(12), dp(8));

        TextView title = new TextView(this);
        title.setText("虎牙 · 纯净版");
        title.setTextColor(Theme.text(MainActivity.this));
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        themeBtn = new Button(this);
        themeBtn.setText(Theme.isLight(this) ? "☀ 亮色" : "☾ 暗色");
        themeBtn.setTextSize(12);
        themeBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        themeBtn.setOnClickListener(v -> switchTheme());
        titleRow.addView(themeBtn);

        root.addView(titleRow);

        LinearLayout searchRow = new LinearLayout(this);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setPadding(dp(12), 0, dp(12), dp(8));

        searchBox = new EditText(this);
        searchBox.setHint("搜游戏 / 主播 / 房间");
        searchBox.setHintTextColor(Theme.textHint(this));
        searchBox.setTextColor(Theme.text(this));
        searchBox.setTextSize(14);
        searchBox.setSingleLine(true);
        searchBox.setBackgroundColor(Theme.input(this));
        searchBox.setPadding(dp(12), dp(9), dp(12), dp(9));
        searchRow.addView(searchBox, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button searchBtn = new Button(this);
        searchBtn.setText("搜索");
        searchBtn.setTextSize(13);
        searchBtn.setOnClickListener(v -> doSearch());
        searchRow.addView(searchBtn);
        root.addView(searchRow);

        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        chipBar = new LinearLayout(this);
        chipBar.setOrientation(LinearLayout.HORIZONTAL);
        chipBar.setPadding(dp(10), dp(2), dp(10), dp(6));
        hsv.addView(chipBar);
        root.addView(hsv);

        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(dp(6));
        listView.setPadding(dp(10), dp(4), dp(10), dp(4));
        listView.setClipToPadding(false);
        adapter = new LiveAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> openRoom(items.get(pos)));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(dp(12), dp(6), dp(12), dp(8));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        bottom.addView(progress, new LinearLayout.LayoutParams(dp(22), dp(22)));

        emptyView = new TextView(this);
        emptyView.setTextColor(Theme.textHint(this));
        emptyView.setTextSize(13);
        emptyView.setPadding(dp(10), 0, 0, 0);
        bottom.addView(emptyView);

        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));

        moreBtn = new Button(this);
        moreBtn.setText("换一批");
        moreBtn.setTextSize(12);
        moreBtn.setOnClickListener(v -> {
            if (keyword.isEmpty()) {
                loadPage(page + 1);
            } else {
                doSearch();
            }
        });
        bottom.addView(moreBtn);
        root.addView(bottom);

        setContentView(root);
        addChip("推荐", TAB_RECOMMEND, "");
        addChip("收藏", TAB_FAVORITE, null);
    }

    private void addChip(String label, int whichTab, String gid) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setPadding(dp(14), dp(7), dp(14), dp(7));
        chip.setTag(gid);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            keyword = "";
            searchBox.setText("");
            if (gid == null) {
                tab = whichTab;
                gameId = "";
            } else {
                tab = TAB_RECOMMEND;
                gameId = gid;
            }
            refreshChipHighlight();
            reload();
        });
        chipBar.addView(chip);
    }

    private void refreshChipHighlight() {
        for (int i = 0; i < chipBar.getChildCount(); i++) {
            TextView c = (TextView) chipBar.getChildAt(i);
            Object tag = c.getTag();
            String gid = tag == null ? null : tag.toString();
            boolean active;
            if (i == TAB_FAVORITE) {
                active = tab == TAB_FAVORITE;
            } else if (i == TAB_RECOMMEND) {
                active = tab == TAB_RECOMMEND && gameId.isEmpty();
            } else {
                active = tab == TAB_RECOMMEND && gid != null && gid.equals(gameId);
            }
            c.setTextColor(active ? Theme.ACCENT : Theme.text(this));
            c.setBackgroundColor(active ? Theme.selected(this) : Theme.input(this));
        }
    }

    private void loadGames() {
        HuyaApi.games(new HuyaApi.Cb<List<HuyaApi.Game>>() {
            @Override
            public void ok(List<HuyaApi.Game> data) {
                games.clear();
                games.addAll(data);
                while (chipBar.getChildCount() > 2) {
                    chipBar.removeViewAt(2);
                }
                for (HuyaApi.Game g : data) {
                    addChip(g.name, TAB_RECOMMEND, g.gid);
                }
                refreshChipHighlight();
            }

            @Override
            public void fail(String msg) {
                toast("分类加载失败：" + msg);
            }
        });
    }

    private void reload() {
        page = 1;
        items.clear();
        adapter.notifyDataSetChanged();
        if (tab == TAB_FAVORITE) {
            showFavorites();
        } else {
            loadPage(1);
        }
    }

    private void showFavorites() {
        items.clear();
        for (Favorites.Room r : Favorites.favorites(this)) {
            items.add(new HuyaApi.Live(r.roomId, r.title, r.anchor, r.game,
                    r.cover, r.viewers, false));
        }
        adapter.notifyDataSetChanged();
        emptyView.setText(items.isEmpty() ? "还没有收藏，进直播间点 ♥ 添加" : "");
        moreBtn.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void loadPage(int p) {
        if (loading) {
            return;
        }
        loading = true;
        progress.setVisibility(View.VISIBLE);
        emptyView.setText("加载中…");
        moreBtn.setVisibility(View.VISIBLE);

        HuyaApi.liveList(gameId, p, new HuyaApi.Cb<List<HuyaApi.Live>>() {
            @Override
            public void ok(List<HuyaApi.Live> data) {
                loading = false;
                progress.setVisibility(View.GONE);
                if (p == 1 || data.isEmpty()) {
                    items.clear();
                }
                page = p;
                items.addAll(data);
                adapter.notifyDataSetChanged();
                emptyView.setText(items.isEmpty() ? "这里没有直播" : "");
                if (data.isEmpty() && p > 1) {
                    toast("没有更多了");
                }
            }

            @Override
            public void fail(String msg) {
                loading = false;
                progress.setVisibility(View.GONE);
                emptyView.setText("加载失败：" + msg);
            }
        });
    }

    private void doSearch() {
        final String q = searchBox.getText().toString().trim();
        if (q.isEmpty()) {
            toast("输入要搜的内容");
            return;
        }
        keyword = q;
        tab = TAB_RECOMMEND;
        refreshChipHighlight();
        progress.setVisibility(View.VISIBLE);
        emptyView.setText("搜索中…");
        items.clear();
        adapter.notifyDataSetChanged();

        HuyaApi.search(q, new HuyaApi.Cb<List<HuyaApi.Live>>() {
            @Override
            public void ok(List<HuyaApi.Live> data) {
                progress.setVisibility(View.GONE);
                items.clear();
                items.addAll(data);
                adapter.notifyDataSetChanged();
                emptyView.setText(items.isEmpty()
                        ? "没搜到「" + q + "」，试试游戏全名或主播名" : "");
                moreBtn.setText("重新搜索");
            }

            @Override
            public void fail(String msg) {
                progress.setVisibility(View.GONE);
                emptyView.setText("搜索失败：" + msg);
            }
        });
    }

    private void openRoom(HuyaApi.Live live) {
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_ROOM_ID, live.roomId);
        i.putExtra(PlayerActivity.EXTRA_TITLE, live.title);
        i.putExtra(PlayerActivity.EXTRA_ANCHOR, live.anchor);
        i.putExtra(PlayerActivity.EXTRA_GAME, live.game);
        i.putExtra(PlayerActivity.EXTRA_COVER, live.cover);
        i.putExtra(PlayerActivity.EXTRA_VIEWERS, live.viewers);
        startActivity(i);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    static String formatCount(long n) {
        if (n >= 10000) {
            return String.format(java.util.Locale.US, "%.1f万", n / 10000.0);
        }
        return String.valueOf(n);
    }

    /** 简易图片加载：内存缓存 + 后台下载。够用，且不引入 Glide 的一大串依赖。 */
    private void loadImage(final String url, final ImageView iv) {
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(null);
            iv.setBackgroundColor(Theme.placeholder(this));
            return;
        }
        Bitmap cached = IMG_CACHE.get(url);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        iv.setBackgroundColor(Theme.placeholder(this));
        iv.setTag(url);
        IMG_POOL.execute(() -> {
            Bitmap bmp = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(10000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (InputStream in = c.getInputStream()) {
                    bmp = BitmapFactory.decodeStream(in);
                } finally {
                    c.disconnect();
                }
            } catch (Exception ignored) {
            }
            final Bitmap result = bmp;
            if (result != null) {
                IMG_CACHE.put(url, result);
            }
            ui.post(() -> {
                if (url.equals(iv.getTag()) && result != null) {
                    iv.setImageBitmap(result);
                }
            });
        });
    }

    private final class LiveAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int i) {
            return items.get(i);
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            LinearLayout row;
            Holder h;
            if (convert == null) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBackgroundColor(Theme.card(MainActivity.this));
                row.setPadding(dp(8), dp(8), dp(8), dp(8));

                ImageView cover = new ImageView(MainActivity.this);
                cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(cover, new LinearLayout.LayoutParams(dp(130), dp(78)));

                LinearLayout info = new LinearLayout(MainActivity.this);
                info.setOrientation(LinearLayout.VERTICAL);
                info.setPadding(dp(10), 0, 0, 0);
                row.addView(info, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

                TextView title = new TextView(MainActivity.this);
                title.setTextColor(Theme.text(MainActivity.this));
                title.setTextSize(14);
                title.setMaxLines(2);
                info.addView(title);

                TextView sub = new TextView(MainActivity.this);
                sub.setTextColor(Theme.textSub(MainActivity.this));
                sub.setTextSize(12);
                sub.setPadding(0, dp(4), 0, 0);
                info.addView(sub);

                TextView meta = new TextView(MainActivity.this);
                meta.setTextColor(Theme.ACCENT);
                meta.setTextSize(11);
                meta.setPadding(0, dp(4), 0, 0);
                info.addView(meta);

                h = new Holder(cover, title, sub, meta);
                row.setTag(h);
            } else {
                row = (LinearLayout) convert;
                h = (Holder) row.getTag();
            }

            HuyaApi.Live l = items.get(pos);
            h.title.setText(l.title.isEmpty() ? ("房间 " + l.roomId) : l.title);
            h.sub.setText(l.anchor + "  ·  " + l.game);
            h.meta.setText(formatCount(l.viewers) + (l.bluray ? "  ·  蓝光" : ""));
            loadImage(l.cover, h.cover);
            return row;
        }
    }

    private static final class Holder {
        final ImageView cover;
        final TextView title;
        final TextView sub;
        final TextView meta;

        Holder(ImageView c, TextView t, TextView s, TextView m) {
            this.cover = c;
            this.title = t;
            this.sub = s;
            this.meta = m;
        }
    }
}
