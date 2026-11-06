package com.huyafree;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * 主题配色。
 *
 * <p>之前所有颜色都硬编码在两个 Activity 里（约 40 处 {@code Color.parseColor("#...")}），
 * 想换主题只能改代码重编译。这里集中成一张表，运行时切换即可。
 *
 * <p>用两套完整的调色板而不是"反色"，因为暗色主题里视频区必须是纯黑
 * （否则黑边发灰很难看），亮色主题里视频区也要保持黑（画面本身的背景），
 * 简单的颜色反转做不到这一点。
 */
public final class Theme {

    /** 主题名，存 SharedPreferences 用。 */
    public static final String DARK = "dark";
    public static final String LIGHT = "light";

    /**
     * 首次启动用的主题。
     *
     * <p>之前默认是暗色（全黑），用户反馈"默认全黑要改一下"。
     * 现在默认亮色，可在首页右上角随时切换，选择会被记住。
     */
    private static final String DEFAULT = LIGHT;

    private static final String PREF = "theme_pref";
    private static final String KEY = "theme_name";

    // ---------------- 暗色（默认，原来的配色） ----------------
    /** 页面背景。 */
    public static final int DARK_BG = 0xFF121212;
    /** 顶栏 / 底栏 / 卡片。 */
    public static final int DARK_SURFACE = 0xFF141414;
    /** 稍亮一层的卡片。 */
    public static final int DARK_CARD = 0xFF1C1C1C;
    /** 输入框 / 按钮底色。 */
    public static final int DARK_INPUT = 0xFF1E1E1E;
    /** 选中态底色。 */
    public static final int DARK_SELECTED = 0xFF2A1A12;
    /** 播放器根背景（始终纯黑）。 */
    public static final int DARK_PLAYER = 0xFF000000;
    /** 主文字。 */
    public static final int DARK_TEXT = 0xFFFFFFFF;
    /** 次要文字。 */
    public static final int DARK_TEXT_SUB = 0xFFAAAAAA;
    /** 提示文字。 */
    public static final int DARK_TEXT_HINT = 0xFF888888;
    /** 占位块。 */
    public static final int DARK_PLACEHOLDER = 0xFF2A2A2A;
    /** 分隔线。 */
    public static final int DARK_DIVIDER = 0xFF2A2A2A;

    // ---------------- 亮色 ----------------
    public static final int LIGHT_BG = 0xFFF5F5F5;
    public static final int LIGHT_SURFACE = 0xFFFFFFFF;
    public static final int LIGHT_CARD = 0xFFFFFFFF;
    public static final int LIGHT_INPUT = 0xFFEDEDED;
    public static final int LIGHT_SELECTED = 0xFFFFE8DC;
    /** 亮色主题下视频区仍然是纯黑 —— 画面背景就该是黑的。 */
    public static final int LIGHT_PLAYER = 0xFF000000;
    public static final int LIGHT_TEXT = 0xFF1A1A1A;
    public static final int LIGHT_TEXT_SUB = 0xFF666666;
    public static final int LIGHT_TEXT_HINT = 0xFF999999;
    public static final int LIGHT_PLACEHOLDER = 0xFFE0E0E0;
    public static final int LIGHT_DIVIDER = 0xFFDDDDDD;

    // ---------------- 强调色（两套共用） ----------------
    /** 主强调色（虎牙橙）。 */
    public static final int ACCENT = 0xFFFF5A1F;
    /** 状态色：正常。 */
    public static final int OK = 0xFF7ED321;
    /** 状态色：警告/加载。 */
    public static final int WARN = 0xFFFF8A4C;
    /** 状态色：错误。 */
    public static final int ERROR = 0xFFD0021B;

    private Theme() {
    }

    // ---------------- 读写 ----------------

    public static boolean isLight(Context ctx) {
        return LIGHT.equals(prefs(ctx).getString(KEY, DEFAULT));
    }

    public static void setLight(Context ctx, boolean light) {
        prefs(ctx).edit().putString(KEY, light ? LIGHT : DARK).apply();
    }

    /** 在暗/亮之间切换，返回切换后的状态（true = 亮色）。 */
    public static boolean toggle(Context ctx) {
        boolean now = !isLight(ctx);
        setLight(ctx, now);
        return now;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    // ---------------- 取色 ----------------

    public static int bg(Context c) {
        return isLight(c) ? LIGHT_BG : DARK_BG;
    }

    public static int surface(Context c) {
        return isLight(c) ? LIGHT_SURFACE : DARK_SURFACE;
    }

    public static int card(Context c) {
        return isLight(c) ? LIGHT_CARD : DARK_CARD;
    }

    public static int input(Context c) {
        return isLight(c) ? LIGHT_INPUT : DARK_INPUT;
    }

    public static int selected(Context c) {
        return isLight(c) ? LIGHT_SELECTED : DARK_SELECTED;
    }

    /** 播放器根背景，两套主题都是纯黑。 */
    public static int playerBg(Context c) {
        return isLight(c) ? LIGHT_PLAYER : DARK_PLAYER;
    }

    public static int text(Context c) {
        return isLight(c) ? LIGHT_TEXT : DARK_TEXT;
    }

    public static int textSub(Context c) {
        return isLight(c) ? LIGHT_TEXT_SUB : DARK_TEXT_SUB;
    }

    public static int textHint(Context c) {
        return isLight(c) ? LIGHT_TEXT_HINT : DARK_TEXT_HINT;
    }

    public static int placeholder(Context c) {
        return isLight(c) ? LIGHT_PLACEHOLDER : DARK_PLACEHOLDER;
    }

    public static int divider(Context c) {
        return isLight(c) ? LIGHT_DIVIDER : DARK_DIVIDER;
    }

    /** 状态栏 / 导航栏颜色，跟随页面背景。 */
    public static int systemBar(Context c) {
        return bg(c);
    }

    /**
     * 状态栏图标该用深色还是浅色。
     *
     * <p>亮色主题下状态栏是浅底，图标必须变深，否则看不见。
     * 返回 true 表示"用深色图标"。
     */
    public static boolean lightSystemBarIcons(Context c) {
        return isLight(c);
    }

    /** 把颜色转成 #RRGGBB 字符串（诊断弹窗等地方要用）。 */
    public static String hex(int color) {
        return String.format("#%06X", 0xFFFFFF & color);
    }

    /** 确保透明通道为不透明，避免误用。 */
    public static int opaque(int color) {
        return Color.argb(255, Color.red(color), Color.green(color), Color.blue(color));
    }
}
