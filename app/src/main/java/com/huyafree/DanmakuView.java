package com.huyafree;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;

/**
 * Canvas danmaku layer drawn on top of the video SurfaceView.
 *
 * <pDisplay area, text size, opacity and speed are all user settings, because a
 * full-screen wall of text is unreadable on a phone. The official client
 * exposes the same four knobs; defaults here match its "half screen, 100 %"
 * preset.
 *
 * <pRows are packed top-down inside the display area only, and a row is reused
 * once its tail has cleared the right edge, so lines never overtake each other.
 */
final class DanmakuView extends View {

    // ---- preference keys ----
    static final String PREF = "danmaku_pref";
    static final String KEY_AREA = "dm_area";      // 0=1/4 1=1/2 2=3/4 3=full
    static final String KEY_SCALE = "dm_scale";    // 0=小 1=标准 2=大 3=特大
    static final String KEY_ALPHA = "dm_alpha";    // 0..3
    static final String KEY_SPEED = "dm_speed";    // 0=慢 1=标准 2=快

    private static final float[] AREA_RATIO = {0.25f, 0.5f, 0.75f, 1.0f};
    private static final String[] AREA_NAME = {"1/4 屏", "半屏", "3/4 屏", "全屏"};

    private static final float[] SCALE_VALUE = {0.78f, 1.0f, 1.28f, 1.6f};
    private static final String[] SCALE_NAME = {"小", "标准", "大", "特大"};

    private static final float[] ALPHA_VALUE = {0.5f, 0.75f, 0.9f, 1.0f};
    private static final String[] ALPHA_NAME = {"50%", "75%", "90%", "100%"};

    private static final float[] SPEED_VALUE = {0.75f, 1.0f, 1.4f};
    private static final String[] SPEED_NAME = {"慢", "标准", "快"};

    /** Speed in dp/s at the standard setting. */
    private static final float BASE_SPEED_DP = 110f;

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rnd = new Random();

    private final List<Line> lines = new ArrayList<>();
    private final LinkedList<DanmakuProtocol.Msg> pending = new LinkedList<>();

    private final float density;
    private int textSizePx;
    private float rowHeight;
    private float speedPx;
    private float baseTextPx;

    private long lastFrameMs;

    private boolean enabled = true;
    private int areaLevel = 1;
    private int scaleLevel = 1;
    private int alphaLevel = 3;
    private int speedLevel = 1;

    private int maxRows = 8;
    private final int maxPending = 120;

    private static final class Line {
        String text;
        int color;
        float x;
        float y;
        float width;

        boolean offscreen() {
            return x + width < 0;
        }
    }

    DanmakuView(Context c) {
        super(c);
        density = getResources().getDisplayMetrics().density;
        baseTextPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                15, getResources().getDisplayMetrics());

        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setStyle(Paint.Style.FILL);
        textPaint.setSubpixelText(true);
        textPaint.setShadowLayer(2f * density, 0f, 0f, 0xCC000000);

        strokePaint.setTypeface(Typeface.DEFAULT);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(Math.max(2.5f, 2.5f * density));
        strokePaint.setColor(0xE6000000);

        setBackgroundColor(Color.TRANSPARENT);
        setWillNotDraw(false);

        loadPrefs(c);
        applyMetrics();
    }

    // ===================== settings =====================

    void loadPrefs(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        areaLevel = clamp(p.getInt(KEY_AREA, 1), 0, AREA_RATIO.length - 1);
        scaleLevel = clamp(p.getInt(KEY_SCALE, 1), 0, SCALE_VALUE.length - 1);
        alphaLevel = clamp(p.getInt(KEY_ALPHA, 3), 0, ALPHA_VALUE.length - 1);
        speedLevel = clamp(p.getInt(KEY_SPEED, 1), 0, SPEED_VALUE.length - 1);
    }

    void savePrefs(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt(KEY_AREA, areaLevel)
                .putInt(KEY_SCALE, scaleLevel)
                .putInt(KEY_ALPHA, alphaLevel)
                .putInt(KEY_SPEED, speedLevel)
                .apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Recompute pixel metrics after any setting change. */
    private void applyMetrics() {
        textSizePx = Math.max(8, (int) (baseTextPx * SCALE_VALUE[scaleLevel]));
        textPaint.setTextSize(textSizePx);
        strokePaint.setTextSize(textSizePx);
        strokePaint.setStrokeWidth(Math.max(2.5f, 2.5f * density));
        speedPx = BASE_SPEED_DP * density * SPEED_VALUE[speedLevel];
        rowHeight = textSizePx * 1.5f;
        recomputeRows();
        clearAll();
    }

    private void recomputeRows() {
        int usable = (int) (getHeight() * AREA_RATIO[areaLevel]) - (int) (4 * density);
        maxRows = Math.max(1, (int) (usable / rowHeight));
    }

    int getAreaLevel() {
        return areaLevel;
    }

    int getScaleLevel() {
        return scaleLevel;
    }

    int getAlphaLevel() {
        return alphaLevel;
    }

    int getSpeedLevel() {
        return speedLevel;
    }

    static String areaName(int v) {
        return AREA_NAME[clamp(v, 0, AREA_NAME.length - 1)];
    }

    static String scaleName(int v) {
        return SCALE_NAME[clamp(v, 0, SCALE_NAME.length - 1)];
    }

    static String alphaName(int v) {
        return ALPHA_NAME[clamp(v, 0, ALPHA_NAME.length - 1)];
    }

    static String speedName(int v) {
        return SPEED_NAME[clamp(v, 0, SPEED_NAME.length - 1)];
    }

    void cycleArea() {
        areaLevel = (areaLevel + 1) % AREA_RATIO.length;
        applyMetrics();
    }

    void cycleScale() {
        scaleLevel = (scaleLevel + 1) % SCALE_VALUE.length;
        applyMetrics();
    }

    void cycleAlpha() {
        alphaLevel = (alphaLevel + 1) % ALPHA_VALUE.length;
        invalidate();
    }

    void cycleSpeed() {
        speedLevel = (speedLevel + 1) % SPEED_VALUE.length;
        applyMetrics();
    }

    // ===================== data =====================

    void push(DanmakuProtocol.Msg m) {
        if (m == null || !m.usable()) {
            return;
        }
        synchronized (pending) {
            if (pending.size() >= maxPending) {
                pending.removeFirst();
            }
            pending.addLast(m);
        }
        if (enabled) {
            postInvalidateOnAnimation();
        }
    }

    void setDanmakuEnabled(boolean on) {
        enabled = on;
        if (!on) {
            clearAll();
        } else {
            invalidate();
        }
    }

    boolean isDanmakuEnabled() {
        return enabled;
    }

    void clearAll() {
        synchronized (lines) {
            lines.clear();
        }
        synchronized (pending) {
            pending.clear();
        }
        lastFrameMs = 0;
        invalidate();
    }

    // ===================== layout =====================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        recomputeRows();
        synchronized (lines) {
            lines.clear();
        }
    }

    // ===================== draw =====================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!enabled) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        float dt = lastFrameMs == 0 ? 0f : (now - lastFrameMs) / 1000f;
        lastFrameMs = now;
        if (dt > 0.2f) {
            dt = 0.2f;
        }

        int w = getWidth();
        float areaH = getHeight() * AREA_RATIO[areaLevel];

        spawnPending(w);

        int save = canvas.save();
        // Clip to the display area so lines never spill past the chosen band.
        canvas.clipRect(0, 0, w, areaH);

        if (alphaLevel < ALPHA_VALUE.length - 1) {
            canvas.saveLayerAlpha(0, 0, w, (int) areaH,
                    (int) (ALPHA_VALUE[alphaLevel] * 255));
        }

        synchronized (lines) {
            Iterator<Line> it = lines.iterator();
            while (it.hasNext()) {
                Line ln = it.next();
                ln.x -= speedPx * dt;
                if (ln.offscreen()) {
                    it.remove();
                }
            }
            for (Line ln : lines) {
                canvas.drawText(ln.text, ln.x, ln.y, strokePaint);
                textPaint.setColor(ln.color);
                canvas.drawText(ln.text, ln.x, ln.y, textPaint);
            }
        }
        canvas.restoreToCount(save);

        boolean busy;
        synchronized (lines) {
            busy = !lines.isEmpty();
        }
        synchronized (pending) {
            busy = busy || !pending.isEmpty();
        }
        if (busy) {
            postInvalidateOnAnimation();
        } else {
            lastFrameMs = 0;
        }
    }

    private void spawnPending(int w) {
        while (true) {
            DanmakuProtocol.Msg m;
            synchronized (pending) {
                m = pending.pollFirst();
            }
            if (m == null) {
                return;
            }
            int row = pickRow(w);
            if (row < 0) {
                // Every row is still busy: drop this line instead of stacking
                // text on top of text, which is what made the layer unreadable.
                continue;
            }
            Line ln = new Line();
            ln.text = composeText(m);
            ln.color = m.fontColor == 0 ? 0xFFFFFFFF : (0xFF000000 | m.fontColor);
            ln.width = textPaint.measureText(ln.text);
            ln.x = w;
            ln.y = (row + 1) * rowHeight;
            synchronized (lines) {
                lines.add(ln);
            }
        }
    }

    /**
     * Pick a free row. A row counts as busy while the tail of its newest line
     * is still on screen, which is exactly when a follower could overtake it.
     */
    private int pickRow(int w) {
        boolean[] used = new boolean[maxRows];
        synchronized (lines) {
            for (Line ln : lines) {
                int row = Math.round(ln.y / rowHeight) - 1;
                if (row < 0 || row >= maxRows) {
                    continue;
                }
                if (ln.x + ln.width + 16 * density > w) {
                    used[row] = true;
                }
            }
        }
        for (int i = 0; i < maxRows; i++) {
            if (!used[i]) {
                return i;
            }
        }
        // Sparse spill so a very busy room still feels alive without a wall.
        if (rnd.nextFloat() < 0.15f && maxRows > 0) {
            return rnd.nextInt(maxRows);
        }
        return -1;
    }

    private String composeText(DanmakuProtocol.Msg m) {
        // Video overlay shows message content; sender metadata belongs in chat.
        return m.content;
    }
}
