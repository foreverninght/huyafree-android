package com.huyafree;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/**
 * 后台音频播放的前台服务。
 *
 * <p>播放器本身仍然归 {@link PlayerActivity} 持有 —— 画面要绑 Surface，搬进
 * Service 只会把 Surface 的生命周期问题翻倍。这里只做两件事：
 * <ol>
 *   <li><b>保活</b>：把进程提到前台，退到后台后不会被系统当成可回收的普通进程；</li>
 *   <li><b>控制面</b>：MediaSession + 常驻通知，让锁屏、通知栏、蓝牙耳机、
 *       车机都能暂停/继续。</li>
 * </ol>
 *
 * <p>两侧用 {@link Control} 静态接口通信，不走 Binder：播放器全局只有一个，
 * Binder 的跨进程能力在这里是纯开销，还会额外引入一层生命周期错配。
 */
public class PlaybackService extends Service {

    /** 通知栏「继续」按钮。 */
    public static final String ACTION_TOGGLE = "com.huyafree.action.TOGGLE";
    /** 通知栏「关闭」按钮。 */
    public static final String ACTION_STOP = "com.huyafree.action.STOP";

    private static final String CHANNEL_ID = "playback";
    private static final int NOTIF_ID = 0x1001;

    /** Activity 侧的播放控制。Service 只调这几个方法，不碰播放器细节。 */
    public interface Control {
        /** 播放/暂停取反。 */
        void togglePlayPause();

        boolean isPlaying();
        String roomId();
        void stopPlayback();
        void serviceStopped();

        /** 通知标题（主播名）。 */
        String nowTitle();

        /** 通知副标题（房间名）。 */
        String nowSubtitle();
    }

    private static volatile Control sControl = null;

    /** Service 存活时指向自己，供 {@link #refresh()} 更新通知。 */
    private static volatile PlaybackService sInstance = null;

    /** Activity 在 initPlayer 之后注册，退出时注销。 */
    public static void attach(Control c) {
        sControl = c;
        refresh();
    }

    public static void detach(Control c) {
        if (sControl == c) {
            sControl = null;
        }
    }

    /** 播放状态或标题变化时调用，重画通知与 MediaSession。 */
    public static void refresh() {
        PlaybackService s = sInstance;
        if (s != null) {
            try {
                s.update();
            } catch (Exception ignored) {
            }
        }
    }

    private MediaSession session;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        sInstance = this;

        session = new MediaSession(this, "huyafree");
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                Control c = sControl;
                if (c != null && !c.isPlaying()) {
                    c.togglePlayPause();
                }
                update();
            }

            @Override
            public void onPause() {
                Control c = sControl;
                if (c != null && c.isPlaying()) {
                    c.togglePlayPause();
                }
                update();
            }

            @Override
            public void onStop() {
                Control c = sControl;
                if (c != null) c.stopPlayback();
                stopSelf();
            }
        });
        session.setActive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先 startForeground，否则 API 26+ 会在 5 秒内抛
        // RemoteServiceException 把进程干掉。
        startAsForeground();

        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            Control c = sControl;
            if (c != null) c.stopPlayback();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE.equals(action)) {
            Control c = sControl;
            if (c != null) {
                c.togglePlayPause();
            }
        }
        update();
        // 不用 START_STICKY：进程被杀说明播放器也没了，重启一个空服务没有意义。
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    /** 状态变化后重画通知，保持播放/暂停图标与实际一致。 */
    private void update() {
        if (session == null) {
            return;
        }
        Control c = sControl;
        boolean playing = c != null && c.isPlaying();
        String t = c == null ? "" : nz(c.nowTitle());
        String s = c == null ? "" : nz(c.nowSubtitle());

        long actions = PlaybackState.ACTION_PLAY
                | PlaybackState.ACTION_PAUSE
                | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());
        session.setMetadata(new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, s)
                .build());

        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            try {
                nm.notify(NOTIF_ID, buildNotification());
            } catch (Exception ignored) {
            }
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "后台播放",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("直播在后台继续播放音频");
        // 不要角标、不要提示音 —— 这是常驻控制条，不是提醒。
        ch.setShowBadge(false);
        ch.setSound(null, null);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Control c = sControl;
        boolean playing = c != null && c.isPlaying();
        String t = c == null ? "" : nz(c.nowTitle());
        String s = c == null ? "" : nz(c.nowSubtitle());
        if (t.isEmpty()) {
            t = "虎牙直播";
        }

        // 点通知回到播放页。REORDER_TO_FRONT 让已有实例回到栈顶，
        // 不会新建一个（新建就会多一个播放器 → 多一路音频）。
        Intent open = new Intent(this, c == null ? MainActivity.class : PlayerActivity.class);
        if (c != null) open.putExtra(PlayerActivity.EXTRA_ROOM_ID, c.roomId());
        open.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent toggle = PendingIntent.getService(this, 1,
                new Intent(this, PlaybackService.class).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent close = PendingIntent.getService(this, 2,
                new Intent(this, PlaybackService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        int icon = playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(t)
                .setContentText(s)
                .setContentIntent(content)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(new Notification.Action(icon, playing ? "暂停" : "播放", toggle))
                .addAction(new Notification.Action(
                        android.R.drawable.ic_menu_close_clear_cancel, "关闭", close));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && session != null) {
            b.setStyle(new Notification.MediaStyle()
                    .setMediaSession(session.getSessionToken())
                    .setShowActionsInCompactView(0, 1));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            b.setCategory(Notification.CATEGORY_TRANSPORT);
        }
        return b.build();
    }

    @Override
    public void onDestroy() {
        Control c = sControl;
        if (c != null) c.serviceStopped();
        if (sInstance == this) {
            sInstance = null;
        }
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }
}
