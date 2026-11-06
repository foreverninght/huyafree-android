package com.huyafree;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Rational;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;

/**
 * 播放页。SurfaceView + ExoPlayer 渲染，不依赖 media3-ui。
 *
 * <p>横竖屏：视频区域始终 16:9，支持旋转到横屏全屏。竖屏时视频在上、控件在下；
 * 横屏时隐藏顶栏/控制条，画面铺满，点一下屏幕唤出控件。
 *
 * <p>小窗（画中画）：Android 8.0+ 支持，点「小窗」或按 Home 自动进入。
 *
 * <p>线路选择：虎牙每条线路带 iMobilePriorityRate，官方客户端按它选线。
 * 实测某房间 TX=60、HS=40、AL=-1，AL 只能拉 0.1 秒就断、TX 能拉满 120 秒。
 * 所以这里按优先级排序后取第一条，并在重连时逐条降级。
 */
@SuppressWarnings("UnstableApiUsage")
public class PlayerActivity extends Activity {

    public static final String EXTRA_ROOM_ID = "room_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_ANCHOR = "anchor";
    public static final String EXTRA_GAME = "game";
    public static final String EXTRA_COVER = "cover";
    public static final String EXTRA_VIEWERS = "viewers";

    /** 底部两栏在小窗/横屏被合并进一个容器时用的 id。 */
    private static final int VIEW_ID_BOTTOM_BARS = 0x7F0F0001;

    /** 当前活着的播放页。用来在新建时顶掉旧实例，避免多路音频叠加。 */
    private static java.lang.ref.WeakReference<PlayerActivity> sAlive = null;

    private static final int MAX_RETRY_PER_LINE = 2;

    private ExoPlayer player;
    private SurfaceView surface;
    private FrameLayout videoBox;
    private ProgressBar loading;
    private TextView status;
    private TextView titleView;
    private Button playBtn;
    private Button favBtn;
    private Button pipBtn;
    private LinearLayout topBar;
    private LinearLayout ctrlBar;
    private TextView infoBar;
    private TextView qtag;
    private LinearLayout root;
    private DanmakuView danmakuView;
    private ChatPanel chatPanel;
    private Button dmBtn;
    private LinearLayout settingPanel;

    /** 弹幕长连接。跟播放器同生命周期，但独立于取流失败重试。 */
    private final DanmakuClient danmaku = new DanmakuClient();
    private long presenterUid = 0;
    private String danmakuGuid = null;

    private String roomId;
    private String title;
    private String anchor;
    private String game;
    private String cover;
    private long viewers;

    private int retry = 0;
    private boolean userStopped = false;
    private boolean inPip = false;
    /** 已请求进小窗、系统回调还没到。挡住这段窗口期的生命周期误判。 */
    private boolean enteringPip = false;
    private boolean pipSession = false;
    private boolean activityStopped = false;
    private boolean landscape = false;
    private boolean controlsVisible = true;

    private int currentBitrate = HuyaApi.DEFAULT_BITRATE;
    private int appliedBitrate = 0;
    private int[] qualityRatios = {0};
    private String[] qualityNames = {"原画"};
    private String requestedCdn = null;
    private boolean streamSwitching = false;
    private android.widget.PopupWindow selectionPopup;
    private String audioTrackInfo = "等待音轨";
    private int cdnIndex = -1;           // -1 = 自动按优先级

    // 诊断用
    private String currentCdn = "";
    private boolean resigned = false;
    private String lastUrl = "";
    private String lastState = "启动";
    private String lastError = "";
    private int videoW = 0;
    private int videoH = 0;
    /** 上次的容器尺寸，和 Surface 计算一起做防抖。 */
    private int boxW = 0;
    private int boxH = 0;
    /** 上次的窗口宽度，用来检测分屏/小窗的尺寸变化。 */
    private int rootW = 0;
    /** videoBox 上次的 weight，用来防止 setLayoutParams 自激。 */
    private boolean resumeAfterStop = false;
    private int streamGeneration = 0;
    /** 三条栏当前是否已浮到视频上（relayoutBars 的幂等标记）。 */
    private boolean barsOverlaid = false;
    private int cdnFallback = 0;         // 已降级次数
    private String[] cdns = new String[0];

    private final Handler ui = new Handler(Looper.getMainLooper());

    /** 退到后台是否继续播放音频。 */
    private static final String PREF_BG = "player_pref";
    private static final String KEY_BG_AUDIO = "bg_audio";

    /** 音频焦点与后台播放状态。 */
    private android.media.AudioManager audioManager;
    private android.media.AudioAttributes audioAttrs;
    private Object focusRequest;                 // AudioFocusRequest，API 26+
    private boolean hasAudioFocus = false;
    private boolean bgAudioOn = false;
    /** 是否因切后台/失焦而临时静音（回来时恢复）。 */
    private boolean pausedForBackground = false;
    private boolean foregroundServiceStarted = false;

    private boolean loadBgPref() {
        return getSharedPreferences(PREF_BG, MODE_PRIVATE)
                .getBoolean(KEY_BG_AUDIO, true);
    }

    private void saveBgPref(boolean on) {
        getSharedPreferences(PREF_BG, MODE_PRIVATE)
                .edit().putBoolean(KEY_BG_AUDIO, on).apply();
    }

    /**
     * Android 13+ 通知权限。
     *
     * <p>没这个权限前台服务照样能跑（不会崩），但通知栏那条控制条不显示，
     * 用户就没法从锁屏/通知栏暂停，也看不到在播什么。
     * 这里只在首次进播放页时问一次，拒绝也不影响播放。
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        try {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED) {
                return;
            }
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 音频焦点 ----------------

    /**
     * 申请音频焦点。
     *
     * <p>不申请也能出声，但会被别的 App 抢走之后永久哑掉 —— 系统只会通知
     * "申请过焦点"的播放器，没申请的那个既收不到失焦也收不到回来，
     * 用户会以为播放器坏了。
     *
     * <p>返回 false 表示拿不到焦点（例如正在通话），此时不开始播放。
     */
    private boolean requestAudioFocus() {
        if (audioManager == null) {
            audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
        }
        if (audioManager == null) {
            return true;   // 拿不到 AudioManager 就不拦着，宁可出声也别黑屏
        }
        if (hasAudioFocus) {
            return true;
        }
        int r;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (audioAttrs == null) {
                audioAttrs = new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build();
            }
            if (focusRequest == null) {
                android.media.AudioFocusRequest.Builder b =
                        new android.media.AudioFocusRequest.Builder(
                                android.media.AudioManager.AUDIOFOCUS_GAIN)
                                .setAudioAttributes(audioAttrs)
                                // 暂停过就自动恢复，直播场景符合直觉
                                .setWillPauseWhenDucked(false)
                                .setOnAudioFocusChangeListener(this::onFocusChange);
                focusRequest = b.build();
            }
            r = audioManager.requestAudioFocus(
                    (android.media.AudioFocusRequest) focusRequest);
        } else {
            r = audioManager.requestAudioFocus(this::onFocusChange,
                    android.media.AudioManager.STREAM_MUSIC,
                    android.media.AudioManager.AUDIOFOCUS_GAIN);
        }
        hasAudioFocus = r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (!hasAudioFocus || audioManager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (focusRequest != null) {
                    audioManager.abandonAudioFocusRequest(
                            (android.media.AudioFocusRequest) focusRequest);
                }
            } else {
                audioManager.abandonAudioFocus(this::onFocusChange);
            }
        } catch (Exception ignored) {
        }
        hasAudioFocus = false;
    }

    /**
     * 焦点变化。
     *
     * <p>LOSS / LOSS_TRANSIENT 一律暂停，不 ducking（降音量继续）：
     * 直播不是背景音乐，被来电压着半声听不清，暂停更符合预期。
     */
    private void onFocusChange(int change) {
        switch (change) {
            case android.media.AudioManager.AUDIOFOCUS_LOSS:
                hasAudioFocus = false;
                // 分屏下两个 App 各自持有一半屏幕，谁点到谁拿焦点，
                // 不能因为对方拿了就永久静音 —— 标记成"可恢复"，
                // 等用户点回我们这一半（onWindowFocusChanged）再续上。
                if (inMultiWindow()) {
                    pausedForBackground = true;
                    if (player != null) {
                        player.setPlayWhenReady(false);
                    }
                } else if (player != null) {
                    player.setPlayWhenReady(false);
                }
                break;
            case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
            case android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                if (player != null) {
                    player.setPlayWhenReady(false);
                }
                pausedForBackground = true;
                break;
            case android.media.AudioManager.AUDIOFOCUS_GAIN:
                hasAudioFocus = true;
                if (player != null && pausedForBackground) {
                    pausedForBackground = false;
                    player.setPlayWhenReady(true);
                }
                break;
            default:
                break;
        }
        updatePlaybackService();
    }

    /**
     * 窗口焦点变化。
     *
     * <p>分屏/侧边栏里 Activity 可能长时间停在 onPause（另一半被操作），
     * onResume 不会触发，于是音频焦点被对方拿走之后就再也回不来 ——
     * 用户看到的就是"一进小窗声音就没了"。
     *
     * <p>这里用窗口焦点做恢复信号：用户点回我们这一半，窗口拿到焦点，
     * 立刻重新申请音频焦点并继续播放。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || player == null) {
            return;
        }
        if (inMultiWindow() && pausedForBackground) {
            pausedForBackground = false;
            requestAudioFocus();
            player.setPlayWhenReady(true);
            updatePlaybackService();
        }
    }

    // ---------------- 后台播放 ----------------

    /**
     * 启停前台服务。
     *
     * <p>"退到后台继续播放"必须挂一个前台服务，否则系统会把进程当成
     * 可回收的普通后台进程，几秒到几十秒内就被清掉 —— 表现就是
     * 切出去一会儿声音自己没了。
     */
    private void startPlaybackService() {
        if (foregroundServiceStarted || player == null) {
            return;
        }
        try {
            Intent i = new Intent(this, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i);
            } else {
                startService(i);
            }
            foregroundServiceStarted = true;
            PlaybackService.attach(serviceControl);
        } catch (Exception e) {
            // 没有 FOREGROUND_SERVICE 权限或系统禁止时不要崩，
            // 退化成普通后台播放（仍可用，只是保活弱）
            foregroundServiceStarted = false;
        }
    }

    private void stopPlaybackService() {
        if (!foregroundServiceStarted) {
            return;
        }
        PlaybackService.detach(serviceControl);
        try {
            stopService(new Intent(this, PlaybackService.class));
        } catch (Exception ignored) {
        }
        foregroundServiceStarted = false;
    }

    /** 播放状态变化后同步通知栏。 */
    private void updatePlaybackService() {
        if (foregroundServiceStarted) {
            PlaybackService.refresh();
        }
    }

    /** 给 Service 用的控制面。 */
    private final PlaybackService.Control serviceControl = new PlaybackService.Control() {
        @Override
        public void togglePlayPause() {
            ui.post(PlayerActivity.this::togglePlay);
        }

        @Override
        public boolean isPlaying() {
            return player != null && player.getPlayWhenReady();
        }

        @Override
        public String roomId() { return roomId; }

        @Override
        public void stopPlayback() { hardRelease(); finish(); }

        @Override
        public void serviceStopped() { foregroundServiceStarted = false; }

        @Override
        public String nowTitle() {
            return anchor == null || anchor.isEmpty() ? title : anchor;
        }

        @Override
        public String nowSubtitle() {
            return title == null ? "" : title;
        }
    };

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean pipSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_PICTURE_IN_PICTURE);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        roomId = getIntent().getStringExtra(EXTRA_ROOM_ID);
        title = safe(getIntent().getStringExtra(EXTRA_TITLE));
        anchor = safe(getIntent().getStringExtra(EXTRA_ANCHOR));
        game = safe(getIntent().getStringExtra(EXTRA_GAME));
        cover = safe(getIntent().getStringExtra(EXTRA_COVER));
        viewers = getIntent().getLongExtra(EXTRA_VIEWERS, 0);

        if (roomId == null || roomId.isEmpty()) {
            Toast.makeText(this, "房间号为空", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // 先把可能还活着的上一个播放页彻底关掉，再初始化自己。
        // 顺序很重要：必须在 initPlayer 之前，否则会先多出一路音频再被释放。
        releasePreviousInstance();

        bgAudioOn = loadBgPref();
        requestNotificationPermission();

        buildUi();
        Favorites.touchHistory(this,
                new Favorites.Room(roomId, title, anchor, game, cover, viewers));
        initPlayer();
        loadStream(false);
        updatePipParams();
        // 初始方向：让 applyOrientation 决定布局，避免竖屏下视频区被拉伸
        safeLayout(getResources().getConfiguration());
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * singleTask 下再次打开同一页面走这里，而不是新建实例。
     *
     * <p>之前每次点直播间都是 startActivity 新建一个 Activity，旧的那个
     * 在小窗被叉掉时没被销毁，它的 ExoPlayer 一直在出声。再进一次就多一路
     * 音频，叠到用户听见"双重声音、点一次多一层"。
     *
     * <p>现在系统只保留一个实例：同房间直接恢复播放，换房间才重建播放器。
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String newRoom = intent.getStringExtra(EXTRA_ROOM_ID);
        if (newRoom == null || newRoom.isEmpty()) {
            return;
        }
        if (newRoom.equals(roomId)) {
            // 同房间：只需恢复播放，绝不能再建一个播放器
            if (player != null) {
                player.setPlayWhenReady(true);
            }
            if (presenterUid > 0 && !danmaku.isRunning()) {
                startDanmaku();
            }
            return;
        }
        // 换房间：彻底释放旧的再按新参数重建
        switchRoom(newRoom, safe(intent.getStringExtra(EXTRA_TITLE)),
                safe(intent.getStringExtra(EXTRA_ANCHOR)),
                safe(intent.getStringExtra(EXTRA_GAME)),
                safe(intent.getStringExtra(EXTRA_COVER)),
                intent.getLongExtra(EXTRA_VIEWERS, 0));
    }

    /** 在原实例里切换到另一个房间，避免 Activity 反复创建。 */
    private void switchRoom(String newRoom, String newTitle, String newAnchor,
                            String newGame, String newCover, long newViewers) {
        stopDanmaku();
        presenterUid = 0;
        if (chatPanel != null) chatPanel.clearMessages();
        if (player != null) {
            try {
                player.stop();
                player.clearMediaItems();
            } catch (Exception ignored) {
            }
        }
        roomId = newRoom;
        title = newTitle;
        anchor = newAnchor;
        game = newGame;
        cover = newCover;
        viewers = newViewers;

        retry = 0;
        cdnFallback = 0;
        cdnIndex = -1;
        currentBitrate = HuyaApi.DEFAULT_BITRATE;
        appliedBitrate = 0;
        requestedCdn = null;
        qualityRatios = new int[]{0};
        qualityNames = new String[]{"原画"};
        cdns = new String[0];
        videoW = 0;
        videoH = 0;
        if (danmakuView != null) {
            danmakuView.clearAll();
        }
        if (titleView != null) {
            titleView.setText(title.isEmpty() ? ("房间 " + roomId) : title);
        }
        if (infoBar != null) {
            infoBar.setText(anchor + (game.isEmpty() ? "" : "  ·  " + game)
                    + "  · " + MainActivity.formatCount(viewers) + " 人气");
        }
        refreshFavBtn();
        Favorites.touchHistory(this,
                new Favorites.Room(roomId, title, anchor, game, cover, viewers));
        updatePipParams();
        loadStream(false);
    }

    // ---------------- UI ----------------

    /** 紧凑小按钮。默认 Button 最小宽 88dp，五个就撑爆 360dp 屏，必须压掉。 */
    private Button miniBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        b.setBackgroundColor(Theme.input(this));
        b.setTextColor(Theme.text(this));
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.playerBg(this));
        // 初值跟随当前方向：竖屏要 inset 避开状态栏，横屏必须铺到边。
        // applyOrientation 随后会再校正一次，这里只是避免首帧闪一下边距。
        root.setFitsSystemWindows(
                getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_LANDSCAPE);

        // 顶栏：返回 | 标题 | 状态 | ♥ | 小窗
        LinearLayout bar = new LinearLayout(this);
        topBar = bar;
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Theme.surface(this));
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));

        Button back = miniBtn("返回", v -> finish());
        bar.addView(back);

        titleView = new TextView(this);
        titleView.setText(title.isEmpty() ? ("房间 " + roomId) : title);
        titleView.setTextColor(Theme.text(this));
        titleView.setTextSize(12);
        titleView.setMaxLines(1);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setPadding(dp(6), 0, dp(6), 0);
        bar.addView(titleView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // 状态标签就放右上角，始终可见
        qtag = new TextView(this);
        qtag.setText("连接中…");
        qtag.setTextColor(Theme.WARN);
        qtag.setTextSize(10);
        qtag.setMaxLines(1);
        qtag.setPadding(dp(4), 0, dp(6), 0);
        bar.addView(qtag);

        favBtn = miniBtn("♡", v -> toggleFav());
        bar.addView(favBtn);

        pipBtn = miniBtn("小窗", v -> enterPip());
        pipBtn.setVisibility(pipSupported() ? View.VISIBLE : View.GONE);
        bar.addView(pipBtn);
        root.addView(bar);

        // 视频区
        videoBox = new FrameLayout(this);
        videoBox.setBackgroundColor(Color.BLACK);
        surface = new SurfaceView(this);
        // SurfaceView 不能直接 MATCH_PARENT 填满容器：
        // MediaCodec 会把画面直接拉伸到 Surface 尺寸，导致画面变形。
        // 这里用 CENTER 重心 + 按视频宽高比计算的实际尺寸，等价于
        // media3-ui 里 AspectRatioFrameLayout 的 FIT 模式。
        FrameLayout.LayoutParams lpSurface = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lpSurface.gravity = Gravity.CENTER;
        videoBox.addView(surface, lpSurface);

        // 弹幕层压在 SurfaceView 上面。SurfaceView 是独立图层，普通 View
        // 叠在它上面在部分机型会被 Surface 覆盖，所以这里必须 setZOrder。
        danmakuView = new DanmakuView(this);
        FrameLayout.LayoutParams lpDm = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        videoBox.addView(danmakuView, lpDm);
        danmakuView.setTag("danmaku-layer");

        loading = new ProgressBar(this);
        FrameLayout.LayoutParams lpLoad = new FrameLayout.LayoutParams(dp(40), dp(40));
        lpLoad.gravity = Gravity.CENTER;
        videoBox.addView(loading, lpLoad);

        status = new TextView(this);
        status.setTextColor(Theme.textSub(this));
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lpSt = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lpSt.gravity = Gravity.CENTER;
        videoBox.addView(status, lpSt);

        videoBox.setOnClickListener(v -> toggleControls());

        // 容器尺寸变化的唯一可靠信号。小窗进入/退出、旋转、进出全屏都会走到这里，
        // 不依赖某个回调的调用顺序 —— 之前小窗只显示一半，就是因为只在一个
        // 回调里重算，而那条路径拿到的容器尺寸还是旧的。
        videoBox.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> {
            int nw = r - l;
            int nh = bo - t;
            if (nw <= 0 || nh <= 0) {
                return;
            }
            // 尺寸真变了才重算，避免布局抖动导致无限 requestLayout
            if (nw == boxW && nh == boxH) {
                return;
            }
            boxW = nw;
            boxH = nh;
            // 排到下一帧：在 onLayout 里同步改子 View 尺寸会再触发布局
            v.post(this::applySurfaceFit);
        });

        root.addView(videoBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 窗口宽度变化的信号源。
        //
        // 分屏、侧边栏小窗、桌面悬浮窗都会改 root 的宽度，但 videoBox
        // 的布局参数是按旧窗口宽度算出来的固定高度，不会自己跟着变 ——
        // 表现就是画面不缩小、一截露在窗口外。root 尺寸一变就重算一次。
        root.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> {
            int nw = r - l;
            if (nw <= 0) {
                return;
            }
            if (nw == rootW) {
                return;
            }
            rootW = nw;
            // 只改高度不改宽度，且用 post 排到下一帧。
            // 直接在 onLayout 回调里 setLayoutParams 会再次触发布局，
            // 假如宽高相互依赖就会无限循环，界面表现为"卡住不动"。
            if (videoBox != null) {
                videoBox.post(() -> applyVideoBoxRatio(landscape));
            }
        });
        // 控制条：紧凑按钮，一行放得下
        LinearLayout ctrl = new LinearLayout(this);
        ctrlBar = ctrl;
        ctrl.setOrientation(LinearLayout.HORIZONTAL);
        ctrl.setGravity(Gravity.CENTER_VERTICAL);
        ctrl.setBackgroundColor(Theme.surface(this));
        ctrl.setPadding(dp(4), dp(3), dp(4), dp(3));

        playBtn = miniBtn("暂停", v -> togglePlay());
        ctrl.addView(playBtn);

        ctrl.addView(miniBtn("重载", v -> {
            retry = 0;
            cdnFallback = 0;
            loadStream(true);
        }));

        ctrl.addView(miniBtn("画质", v -> cycleBitrate()));
        ctrl.addView(miniBtn("换线", v -> cycleLine()));
        ctrl.addView(miniBtn("全屏", v -> toggleOrientation()));
        ctrl.addView(miniBtn("信息", v -> showInfo()));

        dmBtn = miniBtn("弹幕", v -> toggleDanmaku());
        ctrl.addView(dmBtn);

        ctrl.addView(miniBtn("设置", v -> showDanmakuSettings()));

        ctrl.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        root.addView(ctrl);

        // 底部信息
        TextView info = new TextView(this);
        infoBar = info;
        info.setText(anchor + (game.isEmpty() ? "" : "  ·  " + game)
                + "  · " + MainActivity.formatCount(viewers) + " 人气");
        info.setTextColor(Theme.textSub(this));
        info.setTextSize(11);
        info.setBackgroundColor(Theme.surface(this));
        info.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(info);

        chatPanel = new ChatPanel(this);
        root.addView(chatPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        refreshFavBtn();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** 横屏全屏时点画面，隐藏/显示控件。 */
    // ---------------- 弹幕 ----------------

    /** 开关弹幕：只影响渲染层，长连接保持，重新打开立刻有内容。 */
    private void toggleDanmaku() {
        if (danmakuView == null) {
            return;
        }
        boolean on = !danmakuView.isDanmakuEnabled();
        danmakuView.setDanmakuEnabled(on);
        dmBtn.setText(on ? "弹幕" : "弹幕关");
        dmBtn.setTextColor(on ? Theme.text(this) : Theme.textHint(this));
    }

    /**
     * 弹幕设置面板。
     *
     * <p>原版把显示区域、字号、透明度、速度都做成可调项；这里同样四项，
     * 每项循环切换并在按钮上回显当前值，不引入对话框层级，误触代价低。
     */
    private void showDanmakuSettings() {
        if (danmakuView == null) {
            return;
        }
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(Theme.card(this));
        int pad = dp(14);
        box.setPadding(pad, dp(10), pad, dp(10));

        TextView head = new TextView(this);
        head.setText("播放设置");
        head.setTextColor(Theme.text(this));
        head.setTextSize(14);
        head.setPadding(0, 0, 0, dp(8));
        box.addView(head);

        // 后台播放开关：退到后台（Home/锁屏/切应用）继续出声音。
        final Button bgBtn = settingRow(box, "后台播放", bgAudioOn ? "开" : "关");
        bgBtn.setOnClickListener(v -> {
            bgAudioOn = !bgAudioOn;
            saveBgPref(bgAudioOn);
            updatePipParams();
            bgBtn.setText(bgAudioOn ? "开" : "关");
            bgBtn.setTextColor(bgAudioOn ? Theme.ACCENT : Theme.textHint(this));
            if (!bgAudioOn) {
                stopPlaybackService();
            } else if (player != null) {
                requestAudioFocus();
                startPlaybackService();
            }
        });
        bgBtn.setTextColor(bgAudioOn ? Theme.ACCENT : Theme.textHint(this));

        final Button areaBtn = settingRow(box, "显示区域",
                DanmakuView.areaName(danmakuView.getAreaLevel()));
        final Button scaleBtn = settingRow(box, "字号",
                DanmakuView.scaleName(danmakuView.getScaleLevel()));
        final Button alphaBtn = settingRow(box, "透明度",
                DanmakuView.alphaName(danmakuView.getAlphaLevel()));
        final Button speedBtn = settingRow(box, "速度",
                DanmakuView.speedName(danmakuView.getSpeedLevel()));

        areaBtn.setOnClickListener(v -> {
            danmakuView.cycleArea();
            areaBtn.setText(DanmakuView.areaName(danmakuView.getAreaLevel()));
            danmakuView.savePrefs(this);
        });
        scaleBtn.setOnClickListener(v -> {
            danmakuView.cycleScale();
            scaleBtn.setText(DanmakuView.scaleName(danmakuView.getScaleLevel()));
            danmakuView.savePrefs(this);
        });
        alphaBtn.setOnClickListener(v -> {
            danmakuView.cycleAlpha();
            alphaBtn.setText(DanmakuView.alphaName(danmakuView.getAlphaLevel()));
            danmakuView.savePrefs(this);
        });
        speedBtn.setOnClickListener(v -> {
            danmakuView.cycleSpeed();
            speedBtn.setText(DanmakuView.speedName(danmakuView.getSpeedLevel()));
            danmakuView.savePrefs(this);
        });

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(miniBtn("清屏", v -> danmakuView.clearAll()));
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        actions.addView(miniBtn("关闭", v -> {
            if (settingPanel != null && settingPanel.getParent() != null) {
                videoBox.removeView(settingPanel);
            }
            settingPanel = null;
        }));
        box.addView(actions);

        if (settingPanel != null && settingPanel.getParent() != null) {
            videoBox.removeView(settingPanel);
        }
        settingPanel = box;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                dp(260), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        videoBox.addView(box, lp);
    }

    /** 一行「标签 + 当前值按钮」。 */
    private Button settingRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Theme.textSub(this));
        tv.setTextSize(12);
        row.addView(tv, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button b = miniBtn(value, null);
        b.setTextColor(Theme.ACCENT);
        row.addView(b);
        parent.addView(row);
        return b;
    }

    /**
     * 起弹幕长连接。
     *
     * <p>订阅组是 chat:{lPresenterUid}，不是房间号 —— 虎牙的弹幕组以主播 uid
     * 命名，房间号只用于取流。拿到 presenterUid 才能订阅。
     */
    private void startDanmaku() {
        if (presenterUid <= 0) {
            return;
        }
        if (danmakuGuid == null) {
            danmakuGuid = newGuid();
        }
        if (danmaku.isRunning()) {
            return;
        }
        danmaku.addListener(danmakuListener);
        danmaku.start(danmakuGuid, String.valueOf(presenterUid),
                android.os.Build.MODEL == null ? "" : android.os.Build.MODEL);
    }

    private void stopDanmaku() {
        danmaku.removeListener(danmakuListener);
        danmaku.stop();
    }

    private static String newGuid() {
        // 32 位十六进制，与官方 HAL.getGuid() 的形态一致。
        StringBuilder sb = new StringBuilder(32);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 32; i++) {
            sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        }
        return sb.toString();
    }

    private final DanmakuClient.Listener danmakuListener =
            new DanmakuClient.Listener() {
                @Override
                public void onDanmaku(DanmakuProtocol.Msg msg) {
                    if (chatPanel != null) chatPanel.append(msg);
                    if (danmakuView != null) {
                        danmakuView.push(msg);
                    }
                }

                @Override
                public void onState(String text, boolean connected) {
                    if (chatPanel != null) chatPanel.setConnectionState(text, connected);
                    if (connected && dmBtn != null) {
                        dmBtn.setTextColor(Theme.text(PlayerActivity.this));
                    }
                }
            };

    private void toggleControls() {
        if (!landscape) {
            return;
        }        controlsVisible = !controlsVisible;
        int v = controlsVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v);
        ctrlBar.setVisibility(v);
        infoBar.setVisibility(v);
    }

    /**
     * 横竖屏切换。
     *
     * <p>之前用 FLAG_FULLSCREEN + 手动 setVisibility 的组合，在 cutout 屏上会留黑边，
     * 且 setRequestedOrientation 之后回调时序不稳导致控件状态错乱。
     * 现在统一走 {@link #applyOrientation}，由它根据当前 Configuration 决定一切，
     * 不再依赖「按钮点了什么」这种隐式状态。
     */
    private void toggleOrientation() {
        if (inPip) {
            setTag("● 小窗中", "#FF8A4C");
            return;
        }
        boolean toLandscape = !landscape;
        setRequestedOrientation(toLandscape
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void root_setFits(boolean fit) {
        if (root == null) return;
        if (root.getFitsSystemWindows() == fit) return;
        root.setFitsSystemWindows(fit);
        root.requestApplyInsets();
    }

    /**
     * 横竖屏 UI 推导。
     *
     * <p>三条硬性要求，缺一条用户就会看到"不是真全屏"：
     * <ol>
     *   <li>横屏必须 {@code setDecorFitsSystemWindows(false)}，让内容铺到系统栏底下；</li>
     *   <li>root 的 {@code fitsSystemWindows} 必须关掉，否则即使系统栏隐藏，
     *       之前留下的 inset padding 仍然占着位置，画面四周会有一圈黑边；</li>
     *   <li>必须声明 cutout 穿透，否则挖孔屏左右会各留一条黑边。</li>
     * </ol>
     */
    private void applyOrientation(Configuration cfg) {
        boolean nowLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (nowLandscape != landscape) {
            // 进入新方向时控件默认显示，之后点画面才隐藏
            controlsVisible = true;
        }
        landscape = nowLandscape;

        if (inPip || enteringPip || isInPipMode()) {
            applyPipLayout();
            return;
        }

        // 横屏彻底铺满，竖屏恢复正常 insets。
        root_setFits(!landscape);
        if (root != null) {
            root.setPadding(0, 0, 0, 0);
        }
        applyCutoutMode(landscape);

        applyImmersive(landscape);

        // 横屏时把上下条浮到画面上，让视频真正占满整块屏幕。
        relayoutBars(landscape);
        if (chatPanel != null) chatPanel.setVisibility(landscape ? View.GONE : View.VISIBLE);
        if (danmakuView != null) danmakuView.setVisibility(View.VISIBLE);

        if (landscape) {
            topBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
            infoBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
            ctrlBar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
        } else {
            topBar.setVisibility(View.VISIBLE);
            infoBar.setVisibility(View.VISIBLE);
            ctrlBar.setVisibility(View.VISIBLE);
        }
        // 视频区在竖屏按 16:9，横屏铺满
        applyVideoBoxRatio(landscape);
    }

    /**
     * 挖孔屏穿透控制。
     *
     * <p>{@code LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES} 才允许内容进入刘海区；
     * 默认的 {@code DEFAULT} 在横屏时会把整个窗口往内缩，肉眼就是左右两条黑边。
     */
    private void applyCutoutMode(boolean fillDisplay) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        int want = fillDisplay
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        if (lp.layoutInDisplayCutoutMode == want) {
            return;
        }
        lp.layoutInDisplayCutoutMode = want;
        getWindow().setAttributes(lp);
    }

    /**
     * 视频区尺寸。
     *
     * <p>三种模式：
     * <ul>
     *   <li>竖屏：锁 16:9 固定高度</li>
     *   <li>横屏：weight=1 铺满剩余空间</li>
     *   <li>小窗：<b>必须</b>铺满窗口。竖屏留下的 16:9 固定高度是按全屏宽度算的
     *       （例如 1080×607），而 PiP 窗口只有约 480×270，容器比窗口高出一倍多，
     *       画面被顶到上沿、下半截落在窗口外 —— 这就是"小窗只显示一半"。</li>
     * </ul>
     *
     * <p>横屏时视频区还给了一个 viewport 级的负上/下边距，把顶栏和控制条
     * "抬"到画面上方参与叠放 —— 否则那两个条会实打实吃掉画面高度，
     * 横屏看起来总是比屏幕矮一截，也就是用户说的"不是真全屏"。
     */
    private void applyVideoBoxRatio(boolean full) {
        if (videoBox == null) {
            return;
        }
        ViewGroup.LayoutParams lp = videoBox.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) lp;
            int oldWidth = p.width, oldHeight = p.height;
            float oldWeight = p.weight;
            int oldTop = p.topMargin, oldBottom = p.bottomMargin;
            if (full || inPip || enteringPip || isInPipMode()) {
                p.width = ViewGroup.LayoutParams.MATCH_PARENT;
                p.height = 0;
                p.weight = 1f;
            } else {
                // 竖屏：按 16:9 定高。
                //
                // 宽度必须取「当前窗口」的宽度，不是「屏幕」的宽度。
                // 分屏、侧边栏小窗、桌面悬浮窗下窗口比屏幕窄得多，
                // 用屏幕宽度算出来的高度会超出窗口 —— 表现就是
                // 画面不跟着窗口缩小，一截跑到窗口外面去。
                int w = windowWidthPx();
                p.width = ViewGroup.LayoutParams.MATCH_PARENT;
                p.height = w * 9 / 16;
                p.weight = 0f;
            }
            p.topMargin = 0;
            p.bottomMargin = 0;
            // 参数没变就不要 setLayoutParams —— 它会无条件 requestLayout，
            // 在这个方法被布局回调反复触发的场景下等于自激。
            if (oldWidth == p.width && oldHeight == p.height
                    && oldWeight == p.weight && oldTop == 0 && oldBottom == 0) {
                videoBox.post(this::applySurfaceFit);
                return;
            }
            videoBox.setLayoutParams(p);
        }
        // 容器尺寸变了，Surface 的等比尺寸要重算
        videoBox.post(this::applySurfaceFit);
    }

    /**
     * 当前窗口的可用宽度（像素）。
     *
     * <p>{@code getResources().getDisplayMetrics().widthPixels} 在多窗口模式下
     * 返回的仍是<b>整块屏幕</b>的宽度，分屏/侧边栏/悬浮窗里都会偏大。
     * 窗口宽度只能从 {@code root} 的实际测量宽度拿；还没布局完时退回
     * {@code Configuration.screenWidthDp}（它跟随窗口，不是屏幕）。
     */
    private int windowWidthPx() {
        if (root != null && root.getWidth() > 0) {
            return root.getWidth();
        }
        Configuration cfg = getResources().getConfiguration();
        float density = getResources().getDisplayMetrics().density;
        if (cfg.screenWidthDp > 0) {
            return Math.round(cfg.screenWidthDp * density);
        }
        return getResources().getDisplayMetrics().widthPixels;
    }

    /** 当前是否处在多窗口（分屏/侧边栏/悬浮窗）。 */
    private boolean inMultiWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return isInMultiWindowMode();
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /**
     * 横屏时把顶栏/控制条/信息条从布局流里摘出来，浮到视频上。
     *
     * <p>它们在竖屏是正常的上下条；横屏若仍参与 LinearLayout 的排布，
     * 会占掉两条各 ~40dp 的高度，视频只能拿到剩下的部分，
     * 结果就是上下都有黑边、画面明显变矮。
     *
     * <p>做法是把它们设成 GONE 参与布局是不可能的（会连叠加一起消失），
     * 所以改成「从 root 移除，加到 videoBox 顶部/底部」—— videoBox 是
     * FrameLayout，天然支持叠放，视频继续占满整个 root。
     */
    private void relayoutBars(boolean overlay) {
        if (root == null || videoBox == null || topBar == null
                || ctrlBar == null || infoBar == null) {
            return;
        }
        // 必须幂等。applyOrientation 每次都会调过来，而 ViewGroup.addView
        // 在子 View 已有父容器时会直接抛
        // "The specified child already has a parent"。
        // 状态没变就什么都不做，是唯一安全的写法。
        if (overlay == barsOverlaid) {
            return;
        }
        barsOverlaid = overlay;

        // 先把三条栏从任何父容器上摘下来，避免残留绑定
        detach(topBar);
        detach(ctrlBar);
        detach(infoBar);
        View oldWrap = videoBox.findViewById(VIEW_ID_BOTTOM_BARS);
        if (oldWrap != null && oldWrap.getParent() == videoBox) {
            videoBox.removeView(oldWrap);
        }

        if (overlay) {
            FrameLayout.LayoutParams top = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            top.gravity = Gravity.TOP;
            videoBox.addView(topBar, top);

            FrameLayout.LayoutParams bottom = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            bottom.gravity = Gravity.BOTTOM;
            LinearLayout wrap = new LinearLayout(this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setId(VIEW_ID_BOTTOM_BARS);
            wrap.addView(ctrlBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            wrap.addView(infoBar, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            videoBox.addView(wrap, bottom);
        } else {
            // 按原来的顺序放回：顶栏 → 视频 → 控制条 → 信息条
            //
            // 这里绝不能用 root.removeAllViews()：它会把 videoBox 一起摘掉，
            // 连带摘掉里面的 SurfaceView，Surface 被销毁 → ExoPlayer 卡在
            // 等 surface，表现就是"直播间直接卡住"。
            // 只动需要挪动的三条栏，videoBox 始终留在 root 上。
            detach(topBar);
            detach(ctrlBar);
            detach(infoBar);

            if (videoBox.getParent() != root) {
                detach(videoBox);
                root.addView(videoBox, 0,
                        new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            }
            // 顶栏插在视频前面，另外两条追加在后面
            root.removeView(topBar);
            int insertAt = Math.max(0, root.indexOfChild(videoBox));
            root.addView(topBar, insertAt);
            int afterVideo = root.indexOfChild(videoBox) + 1;
            root.addView(ctrlBar, afterVideo);
            root.addView(infoBar, afterVideo + 1);
        }
        // 弹幕层压在视频上，重新挂一次保证它在最上层
        if (danmakuView != null && danmakuView.getParent() == videoBox) {
            danmakuView.bringToFront();
        }
    }

    /** 从父容器摘掉，没有父容器时什么都不做。 */
    private static void detach(View v) {
        if (v == null) {
            return;
        }
        ViewParent p = v.getParent();
        if (p instanceof ViewGroup) {
            ((ViewGroup) p).removeView(v);
        }
    }

    /**
     * 按视频实际宽高比设置 SurfaceView 尺寸，居中留黑边（FIT 模式）。
     *
     * <p>这是解决"画面被拉伸变形"的关键。
     *
     * <p>SurfaceView 的画面由 MediaCodec 直接渲染到它的 Surface 上，
     * MediaCodec 会无条件把图像<b>拉伸</b>到 Surface 的像素尺寸。
     * 所以 {@code player.setVideoScalingMode(SCALE_TO_FIT)} 对 SurfaceView
     * <b>完全无效</b> —— 那个参数只影响 media3 内部纹理的变换矩阵，
     * 而 SurfaceView 走的是独立的 Surface 合成路径，不经过那个矩阵。
     *
     * <p>唯一可靠的解法是让 Surface 本身的宽高比等于视频宽高比，
     * 多余的空间由父容器（黑底）填充。
     */
    private void applySurfaceFit() {
        if (surface == null || videoBox == null) {
            return;
        }
        int vw = videoW;
        int vh = videoH;
        // VideoSize 未就绪时，用 pixelWidthHeightRatio 修正过宽高比
        boolean known = vw > 0 && vh > 0;
        if (!known) {
            return;
        }

        int cw = videoBox.getWidth();
        int ch = videoBox.getHeight();
        if (cw <= 0 || ch <= 0) {
            return;
        }
        boxW = cw;
        boxH = ch;

        // 按比例把视频框进容器，取较小的缩放系数
        float scale = Math.min((float) cw / vw, (float) ch / vh);
        int w = Math.round(vw * scale);
        int h = Math.round(vh * scale);
        // 避免取整为 0
        if (w < 1) {
            w = 1;
        }
        if (h < 1) {
            h = 1;
        }
        // 容忍 1px 的抖动，避免每帧都触发 requestLayout
        ViewGroup.LayoutParams lp = surface.getLayoutParams();
        if (Math.abs(lp.width - w) <= 1 && Math.abs(lp.height - h) <= 1) {
            return;
        }
        lp.width = w;
        lp.height = h;
        surface.setLayoutParams(lp);
    }

    /** 沉浸式：横屏隐藏状态栏/导航栏，竖屏恢复。API 30+ 走 WindowInsetsController。 */
    private void applyImmersive(boolean immersive) {
        Window w = getWindow();
        // 竖屏跟随主题；横屏沉浸式时状态栏隐藏，颜色无所谓，统一用黑色
        int barColor = immersive ? Color.BLACK : Theme.systemBar(this);
        w.setStatusBarColor(barColor);
        w.setNavigationBarColor(barColor);
        // 亮色主题下状态栏是浅底，图标必须转深色，否则看不见
        boolean lightIcons = !immersive && Theme.lightSystemBarIcons(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.setDecorFitsSystemWindows(!immersive);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                if (immersive) {
                    c.hide(WindowInsets.Type.systemBars());
                    c.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    c.show(WindowInsets.Type.systemBars());
                    c.setSystemBarsAppearance(
                            lightIcons ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                }
            }
        } else {
            if (immersive) {
                w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                int flags = View.SYSTEM_UI_FLAG_VISIBLE;
                if (lightIcons && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                w.getDecorView().setSystemUiVisibility(flags);
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        safeLayout(cfg);
        // 分屏/侧边栏拽动时窗口尺寸连续变化，此刻 root 的测量宽度还是旧的。
        // 排到下一帧，等新宽度生效后再算一次 16:9 高度。
        if (root != null) {
            root.post(() -> {
                applyVideoBoxRatio(landscape);
                applySurfaceFit();
            });
        }
    }

    /**
     * 布局推导的兜底包装。
     *
     * <p>布局代码出异常时用户看到的是"点直播间直接闪退"，没有任何提示。
     * 这里把它降级成"布局没调好但还能看"：播放、弹幕、声音都不受影响，
     * 只是控件位置可能不对。例外只捕获 {@link RuntimeException}，
     * {@link OutOfMemoryError} 之类的仍然照常抛。
     */
    private void safeLayout(Configuration cfg) {
        try {
            applyOrientation(cfg);
        } catch (RuntimeException e) {
            android.util.Log.e("huyafree", "applyOrientation failed", e);
        }
    }
    /**
     * 进入/退出多窗口（分屏、侧边栏小窗、桌面悬浮窗）。
     *
     * <p>多窗口下窗口比例和全屏完全不同，必须重新排布：
     * <ul>
     *   <li>视频区高度按<b>窗口</b>宽度重算，否则画面不缩小、一截露在窗口外；</li>
     *   <li>控件条全部恢复显示 —— 小窗口里没有"沉浸式"这回事，
     *       藏起来用户就没法操作了；</li>
     *   <li>退出多窗口后才重新按方向决定要不要铺满。</li>
     * </ul>
     */
    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindow, Configuration cfg) {
        super.onMultiWindowModeChanged(isInMultiWindow, cfg);
        if (inPip || enteringPip || isInPipMode()) {
            applyPipLayout();
            return;
        }
        if (isInMultiWindow) {
            // 小窗里系统栏是宿主画的，insets 必须让出来，否则控件被盖住
            root_setFits(true);
            applyCutoutMode(false);
            controlsVisible = true;
            if (topBar != null) {
                topBar.setVisibility(View.VISIBLE);
            }
            if (infoBar != null) {
                infoBar.setVisibility(View.VISIBLE);
            }
            if (ctrlBar != null) {
                ctrlBar.setVisibility(View.VISIBLE);
            }
            relayoutBars(false);
        }
        if (root != null) {
            root.post(() -> {
                safeLayout(getResources().getConfiguration());
                applyVideoBoxRatio(landscape);
                applySurfaceFit();
            });
        }
        updatePlaybackService();
    }

    private void refreshFavBtn() {
        favBtn.setText(Favorites.isFavorite(this, roomId) ? "♥" : "♡");
    }

    private void toggleFav() {
        boolean on = Favorites.toggle(this,
                new Favorites.Room(roomId, title, anchor, game, cover, viewers));
        Toast.makeText(this, on ? "已加入收藏" : "已取消收藏", Toast.LENGTH_SHORT).show();
        refreshFavBtn();
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        pausedForBackground = false;
        resumeAfterStop = false;
        if (player.getPlayWhenReady()) {
            player.pause();
            playBtn.setText("播放");
        } else {
            // 手动继续时要确保还持有焦点，否则可能出了画面没声音
            if (requestAudioFocus()) player.play();
            playBtn.setText("暂停");
        }
        updatePlaybackService();
    }

    /** 显示当前连接详情，出问题时方便定位。 */
    private void showInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("房间：").append(roomId).append('\n');
        sb.append("线路：").append(currentCdn.isEmpty() ? "-" : currentCdn)
                .append("（全部 ")
                .append(cdns.length == 0 ? "-" : android.text.TextUtils.join("/", cdns))
                .append("）\n");
        sb.append("码率：").append(currentBitrate > 0 ? currentBitrate + "K" : "默认").append('\n');
        sb.append("签名：").append(resigned ? "本地重算 huya_adr" : "沿用原始").append('\n');
        sb.append("状态：").append(lastState).append('\n');
        sb.append("画质：").append(qualityName(appliedBitrate)).append('\n');
        sb.append("音轨：").append(audioTrackInfo).append('\n');
        if (player != null) {
            sb.append("分辨率：").append(videoW > 0 ? videoW + "×" + videoH : "未知").append('\n');
            sb.append("缓冲：").append(player.getBufferedPercentage()).append("%\n");
            sb.append("播放中：").append(player.isPlaying() ? "是" : "否").append('\n');
        }
        if (!lastError.isEmpty()) {
            sb.append("错误：").append(lastError).append('\n');
        }
        sb.append("流地址：\n");
        String u = lastUrl.isEmpty() ? "-" : lastUrl;
        // 长 URL 折行显示，只留关键部分
        sb.append(u.length() > 300 ? u.substring(0, 300) + "…" : u);

        new android.app.AlertDialog.Builder(this)
                .setTitle("连接信息")
                .setMessage(sb.toString())
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("huya", sb.toString()));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    /**
     * 画质循环。
     *
     * <p>ratio 是虎牙的码率档位选择器，直接决定 CDN 下发的分辨率：
     * 0=原画(1080p+) / 500=800×450 / 2000=1280×720 / 4000=1080p / 6000=1080p。
     * 默认用 0（原画），避免 720p 在 1080p 屏上被放大而糊。
     */
    private String qualityName(int ratio) {
        for (int i = 0; i < qualityRatios.length; i++) {
            if (qualityRatios[i] == ratio) return qualityNames[i];
        }
        return ratio == 0 ? "原画" : "档位 " + ratio;
    }

    private void refreshSelectionStatus() {
        if (infoBar == null) return;
        String actual = currentCdn.isEmpty() ? "待连接" : currentCdn;
        String size = videoW > 0 ? " · " + videoW + "×" + videoH : "";
        String state = streamSwitching
                ? "切换中 → " + qualityName(currentBitrate) + " / "
                    + (requestedCdn == null ? "自动线路" : requestedCdn)
                : qualityName(appliedBitrate) + " / " + actual;
        infoBar.setText(state + size + "\n" + anchor);
    }

    private interface SelectionHandler { void accept(int selected); }

    private void showSelectionPanel(String heading, String[] choices, int selected,
                                    SelectionHandler onSelect) {
        if (selectionPopup != null) selectionPopup.dismiss();
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Theme.surface(this));
        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextColor(Theme.text(this));
        title.setTextSize(14);
        title.setPadding(dp(12), dp(12), dp(12), dp(8));
        panel.addView(title);
        android.widget.ListView list = new android.widget.ListView(this);
        list.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new android.widget.ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_single_choice, choices) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(Theme.text(PlayerActivity.this));
                return view;
            }
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
        list.setItemChecked(selected, true);
        int width = Math.min(dp(260), Math.max(dp(120), videoBox.getWidth() - dp(16)));
        int height = Math.min(dp(52 * (choices.length + 1)), Math.max(dp(100), videoBox.getHeight() - dp(16)));
        selectionPopup = new android.widget.PopupWindow(panel, width, height, true);
        selectionPopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Theme.surface(this)));
        selectionPopup.setOutsideTouchable(true);
        selectionPopup.setElevation(dp(8));
        list.setOnItemClickListener((parent, view, position, id) -> {
            selectionPopup.dismiss();
            onSelect.accept(position);
        });
        int[] location = new int[2];
        videoBox.getLocationOnScreen(location);
        selectionPopup.showAtLocation(videoBox, Gravity.TOP | Gravity.LEFT,
                location[0] + videoBox.getWidth() - width - dp(8), location[1] + dp(8));
        list.setSelection(selected);
    }

    private void cycleBitrate() {
        if (lastUrl.isEmpty()) {
            Toast.makeText(this, "请等待线路和画质加载", Toast.LENGTH_SHORT).show();
            return;
        }
        int checked = 0;
        for (int i = 0; i < qualityRatios.length; i++) {
            if (qualityRatios[i] == currentBitrate) checked = i;
        }
        showSelectionPanel("画质 · 当前 " + qualityName(appliedBitrate), qualityNames, checked, which -> {
            currentBitrate = qualityRatios[which];
            loadStream(true);
        });
    }

    private void cycleLine() {
        if (cdns.length == 0) {
            Toast.makeText(this, "线路列表未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] choices = new String[cdns.length + 1];
        choices[0] = "自动选择";
        int checked = 0;
        for (int i = 0; i < cdns.length; i++) {
            choices[i + 1] = cdns[i] + (cdns[i].equals(currentCdn) ? "（当前）" : "");
            if (cdns[i].equals(requestedCdn)) checked = i + 1;
        }
        showSelectionPanel("线路 · 当前 " + currentCdn, choices, checked, which -> {
            requestedCdn = which == 0 ? null : cdns[which - 1];
            loadStream(true);
        });
    }

    // ---------------- 小窗 ----------------

    /**
     * PiP 参数。
     *
     * <p>{@code setAspectRatio} 必须用<b>视频实际宽高比</b>，不能用固定的 16:9。
     * 系统按这个比例裁窗口，如果视频是 4:3 或竖屏，16:9 的窗口会把画面裁掉
     * 上下或左右，表现就是"小窗里画面看不全"。
     *
     * <p>同时 PiP 的合法宽高比有范围限制（系统要求约 0.418 ~ 2.39），
     * 竖屏视频直接传 9:16 会被系统拒绝并抛异常，所以需要夹到合法区间内。
     */
    private PictureInPictureParams buildPipParams() {
        Rational ratio = videoAspectRational();
        PictureInPictureParams.Builder b = new PictureInPictureParams.Builder()
                .setAspectRatio(ratio);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 开了后台播放就不允许系统自动把小窗弹出来：两者语义冲突，
            // 用户按 Home 是想听声音，不是想画面还占着屏幕。
            b.setAutoEnterEnabled(!bgAudioOn);
            b.setSeamlessResizeEnabled(true);
        }
        return b.build();
    }

    /** 按视频实际尺寸算出 PiP 用的宽高比，夹在系统允许的区间内。 */
    private Rational videoAspectRational() {
        int w = videoW;
        int h = videoH;
        if (w <= 0 || h <= 0) {
            return new Rational(16, 9);
        }
        // 约掉公因数，避免 Rational 里出现过大的数字
        int g = gcd(w, h);
        int rw = w / g;
        int rh = h / g;

        // 系统对 PiP 宽高比有上下限，超出会抛 IllegalArgumentException。
        // 上界约 2.39:1，下界约 1:2.39。
        final double MAX = 2.39;
        double r = (double) rw / rh;
        if (r > MAX) {
            return new Rational(239, 100);
        }
        if (r < 1.0 / MAX) {
            return new Rational(100, 239);
        }
        return new Rational(rw, rh);
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = a % b;
            a = b;
            b = t;
        }
        return a == 0 ? 1 : a;
    }

    private void updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && pipSupported()) {
            try {
                setPictureInPictureParams(buildPipParams());
            } catch (Exception ignored) {
            }
        }
    }

    private void enterPip() {
        if (!pipSupported()) {
            Toast.makeText(this, "系统不支持小窗（需 Android 8.0+）", Toast.LENGTH_SHORT).show();
            return;
        }
        if (player == null) {
            return;
        }
        // enterPictureInPictureMode 是<b>异步</b>的：它会先让 Activity 走
        // onPause/onStop，之后才回调 onPictureInPictureModeChanged。
        // 那段时间里 isInPictureInPictureMode() 还是 false —— onStop 会
        // 误判成"退到后台"，于是解绑 Surface + 启后台音频服务，
        // 小窗根本不会出现，用户看到的就是"点了小窗直接变后台音频"。
        // 用一个显式标记把这段窗口期挡住。
        enteringPip = true;
        armEnteringPipTimeout();
        try {
            boolean ok = enterPictureInPictureMode(buildPipParams());
            if (!ok) {
                enteringPip = false;
                Toast.makeText(this, "进小窗被系统拒绝", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            enteringPip = false;
            Toast.makeText(this, "进小窗失败：" + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /** 是否正处在"已请求进小窗、回调还没来"的窗口期。 */
    private boolean canDismissStoppedPip() {
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return !isChangingConfigurations() && power != null && power.isInteractive()
                && (keyguard == null || !keyguard.isKeyguardLocked());
    }

    private boolean pipPending() {
        return enteringPip || isInPipMode();
    }

    /**
     * 给 enteringPip 挂一个超时兜底。
     *
     * <p>系统偶尔会因为窗口管理忙而<b>不</b>回调 onPictureInPictureModeChanged。
     * 那样 enteringPip 会永久为 true，之后所有 onStop 都以为还在小窗里，
     * 后台播放功能就彻底失效了。1.5 秒没等到回调就自动清掉。
     */
    private void armEnteringPipTimeout() {
        ui.postDelayed(() -> {
            if (enteringPip && !isInPipMode()) {
                enteringPip = false;
            }
        }, 1500);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPip, Configuration cfg) {
        super.onPictureInPictureModeChanged(isInPip, cfg);
        inPip = isInPip;
        if (isInPip) {
            pipSession = true;
            enteringPip = false;
            setPipUiVisible(false);
            // 小窗窗口比例和内屏完全不同：必须先把容器改成填满窗口，
            // 再让 Surface 按新容器重算。只调 applySurfaceFit 是不够的，
            // 因为那时容器还是竖屏的固定高度，算出来的仍是错的尺寸。
            root_setFits(false);
            if (root != null) {
                root.setPadding(0, 0, 0, 0);
            }
            applyCutoutMode(true);
            applyVideoBoxRatio(true);
            if (videoBox != null) {
                videoBox.post(() -> {
                    applyVideoBoxRatio(true);
                    applySurfaceFit();
                });
            }
        } else {
            enteringPip = false;
            if (pipSession && activityStopped && canDismissStoppedPip()) {
                hardRelease();
                finish();
                return;
            }
            setPipUiVisible(true);
            // 退出小窗后按当前屏幕方向重新推导布局
            safeLayout(getResources().getConfiguration());
            if (videoBox != null) {
                videoBox.post(() -> {
                    applyVideoBoxRatio(landscape);
                    applySurfaceFit();
                });
            }
        }
        // PiP 宽高比要随视频更新，否则系统仍按旧比例裁窗口
        updatePipParams();
    }

    private void applyPipLayout() {
        setPipUiVisible(false);
        root_setFits(false);
        if (root != null) root.setPadding(0, 0, 0, 0);
        applyVideoBoxRatio(true);
        applySurfaceFit();
    }

    private void setPipUiVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (chatPanel != null) chatPanel.setVisibility(visible && !landscape ? View.VISIBLE : View.GONE);
        if (danmakuView != null) danmakuView.setVisibility(v);
        if (selectionPopup != null) selectionPopup.dismiss();
        if (settingPanel != null) settingPanel.setVisibility(View.GONE);
        if (status != null) status.setVisibility(v);
        if (!visible && loading != null) loading.setVisibility(View.GONE);
        if (titleView != null) {
            titleView.setVisibility(v);
        }
        if (favBtn != null) {
            favBtn.setVisibility(v);
        }
        if (pipBtn != null) {
            pipBtn.setVisibility(visible && pipSupported() ? View.VISIBLE : View.GONE);
        }
        if (ctrlBar != null) {
            ctrlBar.setVisibility(v);
        }
        if (infoBar != null) {
            infoBar.setVisibility(v);
        }
        if (topBar != null) {
            topBar.setVisibility(v);
        }
    }

    // ---------------- 播放 ----------------

    /**
     * 播放器初始化。
     *
     * <p><b>用官方同款方式</b>：一条普通 HTTP 长连接，零自定义拼接。
     *
     * <p>实测虎牙 CDN 单连接可持续 300 秒以上不间断（49.75MB / 300s / 1.4Mbps），
     * 原版 APK 的 {@code LiveRoomStreamIniter} 也是直接 GET，没有任何重连改写逻辑。
     *
     * <p>早期版本用自定义 DataSource 做「短连接 + 重新签名 + 时间戳改写」，
     * 那是建立在错误测量上的：断流是我测试脚本的 {@code break} 造成的假象。
     * 后果是画面跳跃、卡顿、流量翻倍。现已全部删除。
     *
     * <p>重连只交给 ExoPlayer 自己的 {@code setLoadErrorHandlingPolicy}，
     * 它不会改动任何字节，也不会重排时间戳。
     */
    private void initPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(HuyaApi.UA_MOBILE)
                .setDefaultRequestProperties(
                        java.util.Collections.singletonMap("Referer", "https://www.huya.com/"))
                // 直播流不需要 Content-Length，关掉压缩避免额外解码
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000);

        DataSource.Factory factory = http;

        // 直播断流时让 ExoPlayer 自己重试，不动任何字节。
        DefaultMediaSourceFactory msf = new DefaultMediaSourceFactory(factory);
        msf.setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(6));

        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(msf)
                .build();
        player.setVideoSurfaceView(surface);
        player.setVolume(1f);
        player.setVideoScalingMode(androidx.media3.common.C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        // 断流重连时不要跳到默认位置
        player.setHandleAudioBecomingNoisy(true);

        // 声明成影视内容流：退到后台后系统才会把它当"正在播放的媒体"对待。
        //
        // 第二个参数 handleAudioFocus 必须是 false —— 传 true 会让 ExoPlayer
        // 自己去申请音频焦点，而本类也用 AudioManager 申请，两方互相抢：
        // ExoPlayer 收到自己触发的那次 AUDIOFOCUS_LOSS，立刻暂停，
        // 表现就是"点进直播间直接卡住"。焦点统一由本类管理。
        player.setAudioAttributes(
                new androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                false);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) {
                    // 只有真正长时间没数据才显示转圈，短暂重连不打扰
                    ui.removeCallbacks(showSpinner);
                    ui.postDelayed(showSpinner, 1200);
                } else if (state == Player.STATE_READY) {
                    ui.removeCallbacks(showSpinner);
                    loading.setVisibility(View.GONE);
                    status.setText("");
                    playBtn.setText(player.isPlaying() ? "暂停" : "播放");
                    streamSwitching = false;
                    refreshSelectionStatus();
                    lastState = player.getPlayWhenReady() ? "播放中" : "已暂停";
                    setTag(player.isPlaying() ? "● 播放中" : "● 已暂停", "#7ED321");
                } else if (state == Player.STATE_ENDED) {
                    // 直播流理论上不会自然结束，走到这里说明连接真的断了
                    if (!userStopped) {
                        lastState = "流已结束";
                        setTag("● 已结束", "#D0021B");
                        if (!inPip) {
                            status.setText("流已结束，点「重载」再试");
                        }
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean playing) {
                playBtn.setText(player != null && player.getPlayWhenReady() ? "暂停" : "播放");
                refreshSelectionStatus();
                if (!streamSwitching && player != null && player.getPlaybackState() == Player.STATE_READY) {
                    lastState = playing ? "播放中" : "已暂停";
                    setTag(playing ? "● 播放中" : "● 已暂停", "#7ED321");
                }
                updatePlaybackService();
            }

            @Override
            public void onTracksChanged(androidx.media3.common.Tracks tracks) {
                audioTrackInfo = "未选中音轨";
                for (androidx.media3.common.Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() != androidx.media3.common.C.TRACK_TYPE_AUDIO) continue;
                    for (int i = 0; i < group.length; i++) {
                        if (!group.isTrackSelected(i)) continue;
                        androidx.media3.common.Format f = group.getTrackFormat(i);
                        audioTrackInfo = "音频 " + f.sampleMimeType + " · " + f.sampleRate + "Hz · " + f.channelCount + "声道";
                    }
                }
                refreshSelectionStatus();
            }

            @Override
            public void onVideoSizeChanged(androidx.media3.common.VideoSize size) {
                // width/height 是存储尺寸；pixelWidthHeightRatio 才是显示宽高比。
                // 非方形像素（部分摄像头/推流端）必须乘上去，否则照样变形。
                videoW = size.width;
                videoH = size.height;
                refreshSelectionStatus();
                float par = size.pixelWidthHeightRatio;
                if (par > 0f && Math.abs(par - 1f) > 1e-3f) {
                    videoW = Math.round(size.width * par);
                }
                // PiP 下也要重算：小窗容器尺寸和内屏不同，
                // 之前用 !inPip 拦住了，导致小窗里画面不对。
                if (videoBox != null) {
                    videoBox.post(PlayerActivity.this::applySurfaceFit);
                }
                // 宽高比变了，PiP 窗口比例要同步更新
                if (isInPipMode()) {
                    updatePipParams();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                ui.removeCallbacks(showSpinner);
                loading.setVisibility(View.GONE);
                lastError = shortMsg(error);
                if (userStopped) {
                    return;
                }
                lastState = "出错";
                setTag("● 出错", "#D0021B");
                if (!inPip) {
                    status.setText("出错：" + lastError + "\n点「重载」再试");
                }
            }
        });
    }

    private final Runnable showSpinner = () -> {
        if (!inPip && player != null && player.getPlaybackState() == Player.STATE_BUFFERING) {
            loading.setVisibility(View.VISIBLE);
        }
    };

    private void setTag(String text, String color) {
        if (qtag != null) {
            qtag.setText(text);
            qtag.setTextColor(Color.parseColor(color));
        }
    }

    private static String shortMsg(PlaybackException e) {
        String m = e.getMessage();
        if (m == null) {
            return "未知错误";
        }
        return m.length() > 100 ? m.substring(0, 100) : m;
    }

    /**
     * 只加载一次。后续的断流/重新签名/换线全部由 EndlessFlvDataSource 在数据层处理，
     * 这里不再做任何重连 —— 重建 MediaItem 会 reset 解码器，那正是黑屏转圈的来源。
     */
    private void loadStream(boolean isRetry) {
        final int generation = ++streamGeneration;
        final ExoPlayer requestedPlayer = player;
        final boolean continuePlayback = lastUrl.isEmpty() || (player != null && player.getPlayWhenReady());
        streamSwitching = true;
        refreshSelectionStatus();
        setTag("● 切换中", "#FF8A4C");
        if (!isRetry && !inPip) {
            loading.setVisibility(View.VISIBLE);
            status.setText("获取直播源…");
        }
        HuyaApi.stream(roomId, currentBitrate, requestedCdn,
                new HuyaApi.Cb<HuyaApi.Stream>() {
            @Override
            public void ok(HuyaApi.Stream s) {
                if (generation != streamGeneration || player == null || player != requestedPlayer
                        || userStopped || isFinishing() || isDestroyed()) return;
                if (!s.live) {
                    loading.setVisibility(View.GONE);
                    if (!inPip) {
                        status.setText("主播尚未开播");
                    }
                    setTag("○ 未开播", "#FF8A4C");
                    return;
                }
                if (s.title != null && !s.title.isEmpty()) {
                    title = s.title;
                    titleView.setText(title);
                }
                if (s.anchor != null && !s.anchor.isEmpty()) {
                    anchor = s.anchor;
                }
                cdns = s.cdns == null ? new String[0] : s.cdns;
                currentCdn = s.cdn;
                qualityRatios = s.qualityRatios;
                qualityNames = s.qualityNames;
                appliedBitrate = s.selectedRatio;
                currentBitrate = s.selectedRatio;
                refreshSelectionStatus();
                resigned = s.resigned;
                lastUrl = s.flv;
                lastState = "取流成功，缓冲中";
                setTag("● " + s.cdn, "#FF8A4C");

                // 主播 uid 是弹幕订阅组的房间标识，取流成功就能拿到。
                // 变了才重启连接，避免每次重载都断一次弹幕。
                if (s.presenterUid > 0 && s.presenterUid != presenterUid) {
                    presenterUid = s.presenterUid;
                    stopDanmaku();
                    startDanmaku();
                } else if (s.presenterUid > 0 && !danmaku.isRunning()) {
                    presenterUid = s.presenterUid;
                    startDanmaku();
                }

                // 关键：整个生命周期只 setMediaItem 一次
                player.setMediaItem(MediaItem.fromUri(s.flv));
                player.prepare();
                player.setPlayWhenReady(continuePlayback && requestAudioFocus());
                updatePlaybackService();
            }

            @Override
            public void fail(String msg) {
                if (generation != streamGeneration || player == null || player != requestedPlayer
                        || userStopped || isFinishing() || isDestroyed()) return;
                loading.setVisibility(View.GONE);
                streamSwitching = false;
                currentBitrate = appliedBitrate;
                refreshSelectionStatus();
                Toast.makeText(PlayerActivity.this, "切换失败：" + msg, Toast.LENGTH_LONG).show();
                lastState = "取流失败";
                lastError = msg;
                setTag("● 失败", "#D0021B");
                if (!inPip) {
                    status.setText("获取失败：" + msg + "\n点「重载」再试");
                }
            }
        });
    }

    // ---------------- 生命周期 ----------------

    /**
     * 按 Home 时的行为。
     *
     * <p>Android 8~11 没有「自动进小窗」的系统能力，只能在这里手动进。
     * 但开了后台播放之后这两件事互相排斥：
     * <ul>
     *   <li>后台播放 = 画面消失、声音继续；</li>
     *   <li>小窗 = 画面继续占着屏幕。</li>
     * </ul>
     *
     * <p>所以要了后台播放就<b>不</b>自动进小窗，否则用户按 Home 想听声音，
     * 结果画面还糊在屏幕上（Android 12+ 的自动 PiP 也在 buildPipParams
     * 里按同一开关关掉了）。想要小窗时用控制条上的「小窗」按钮。
     */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (bgAudioOn) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                && pipSupported() && player != null && player.isPlaying()) {
            try {
                // 和 enterPip() 一样要先立标记：进入是异步的，
                // onStop 会先到，那时 PiP 状态还是 false。
                enteringPip = true;
                armEnteringPipTimeout();
                boolean ok = enterPictureInPictureMode(buildPipParams());
                if (!ok) {
                    enteringPip = false;
                }
            } catch (Exception e) {
                enteringPip = false;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 这里不再动播放状态。
        //
        // 进 PiP 时 onPause 先于 onPictureInPictureModeChanged 触发，那一刻
        // inPip 还是 false —— 按它判断会把小窗里的画面停掉。
        // 后台是否继续出声统一交给 onStop 决定。
        updatePlaybackService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isInPipMode()) pipSession = false;
        enteringPip = false;
        if (player != null && resumeAfterStop) {
            resumeAfterStop = false;
            player.setPlayWhenReady(requestAudioFocus());
        }
        if (bgAudioOn && player != null) startPlaybackService();
        // 回到前台恢复弹幕（onStop 里断开了）
        if (presenterUid > 0 && !danmaku.isRunning()) {
            startDanmaku();
        }
        updatePlaybackService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        activityStopped = true;
        if (pipSession && canDismissStoppedPip()) {
            hardRelease();
            finish();
            return;
        }
        // PiP 下画面仍然可见，不走后台音频分支 —— 那条路径会解绑 Surface，
        // 小窗就黑掉了。必须用 pipPending()：enterPictureInPictureMode 是
        // 异步的，onStop 会先于 onPictureInPictureModeChanged 触发，
        // 那一刻 isInPictureInPictureMode() 还是 false。
        if (pipPending()) {
            updatePlaybackService();
            return;
        }
        // 分屏 / 侧边栏小窗 / 桌面悬浮窗：窗口<b>仍然可见</b>，只是变窄了。
        // 这时绝不能走后台分支 —— 那条路会解绑 Surface 让画面变黑。
        // 有的 ROM 在这种状态下会补一个 onStop，所以必须显式挡住。
        if (inMultiWindow()) {
            updatePlaybackService();
            return;
        }
        boolean keepAudio = bgAudioOn && player != null && !userStopped;
        if (keepAudio) {
            // 后台播放：画面解绑，音频继续。
            //
            // 必须解 Surface —— Activity 进后台后 Surface 会被系统回收，
            // 播放器还挂着它就会在渲染时拿到一块失效的 buffer，
            // 轻则黑帧重则花屏，回到前台再绑一次即可。
            try {
                player.clearVideoSurface();
            } catch (Exception ignored) {
            }
            // 前台服务保活 + 通知栏控制
            startPlaybackService();
            updatePlaybackService();
            // 弹幕是纯 UI，后台没人看，断开省电省流量
            stopDanmaku();
        } else {
            if (player != null) {
                resumeAfterStop = player.getPlayWhenReady();
                player.setPlayWhenReady(false);
            }
            stopDanmaku();
            stopPlaybackService();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStopped = false;
        // 回到前台：把 Surface 重新绑回去。
        // SurfaceView 在 Activity 重建/回前台时可能换了底层 Surface，
        // 不重绑就一直是黑屏或最后一帧。
        if (player != null && surface != null) {
            try {
                player.setVideoSurfaceView(surface);
            } catch (Exception ignored) {
            }
        }
    }

    /** 直接问系统 PiP 状态，比自己维护的 inPip 标志可靠（不受回调顺序影响）。 */
    private boolean isInPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return isInPictureInPictureMode();
            } catch (Exception e) {
                return inPip;
            }
        }
        return inPip;
    }

    /**
     * 彻底释放本页持有的全部资源。必须幂等 —— onDestroy、被新实例顶掉、
     * 静态兜底三条路径都会调它。
     *
     * <p>顺序不能反：先 mute 再 stop 再 release。直接 release 有时会因为
     * 音频焦点还没交还而残留一小段声音。
     */
    private void hardRelease() {
        userStopped = true;
        streamGeneration++;
        if (selectionPopup != null) selectionPopup.dismiss();
        resumeAfterStop = false;
        pausedForBackground = false;
        PlaybackService.detach(serviceControl);
        stopDanmaku();
        abandonAudioFocus();
        stopPlaybackService();

        ExoPlayer p = player;
        player = null;
        if (p != null) {
            try {
                p.setPlayWhenReady(false);
            } catch (Exception ignored) {
            }
            try {
                p.stop();
            } catch (Exception ignored) {
            }
            try {
                p.clearVideoSurface();
            } catch (Exception ignored) {
            }
            try {
                p.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 保证同一时刻只有一个播放页活着。
     *
     * <p>小窗被叉掉时部分 ROM 只走 onStop 不走 onDestroy，旧 ExoPlayer 会
     * 继续出声；用户再进同一房间就多一路音频，每进一次叠一层，无限叠加。
     * 这里在 onCreate 阶段主动把上一个实例彻底关掉，不依赖系统是否调了
     * onDestroy。
     */
    private void releasePreviousInstance() {
        PlayerActivity prev = sAlive == null ? null : sAlive.get();
        if (prev != null && prev != this) {
            try {
                prev.hardRelease();
            } catch (Exception ignored) {
            }
            try {
                prev.finish();
            } catch (Exception e) {
                try {
                    prev.finish();
                } catch (Exception ignored) {
                }
            }
        }
        sAlive = new java.lang.ref.WeakReference<>(this);
    }

    @Override
    protected void onDestroy() {
        if (sAlive != null && sAlive.get() == this) {
            sAlive = null;
        }
        hardRelease();
        super.onDestroy();
    }
}
