package com.huyafree;

import android.content.Context;
import android.graphics.Color;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.ArrayList;

/** Main-thread, bounded portrait chat history, independent of the overlay toggle. */
final class ChatPanel extends FrameLayout {
    private static final int MAX_MESSAGES = 200;
    private static final int[] DARK_NICK_COLORS = {0xFF80CBC4, 0xFF90CAF9, 0xFFCE93D8, 0xFFFFCC80};
    private static final int[] LIGHT_NICK_COLORS = {0xFF00695C, 0xFF1565C0, 0xFF7B1FA2, 0xFF9A4500};
    private final int[] nickColors;
    private final int bodyColor;
    private final ArrayList<Row> rows = new ArrayList<>();
    private final ListView list;
    private final TextView empty;
    private final ChatAdapter adapter = new ChatAdapter();
    private long nextId;
    private boolean following = true;
    private boolean touching;
    private int scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private boolean restoring;
    private long anchorId;
    private int anchorTop;

    private final Runnable scrollToBottom = this::followBottom;

    private void followBottom() {
        if (following && !touching && scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE
                && !rows.isEmpty()) {
            list.setSelection(rows.size() - 1);
        }
    }

    ChatPanel(Context context) {
        super(context);
        setBackgroundColor(Theme.surface(context));
        nickColors = Theme.isLight(context) ? LIGHT_NICK_COLORS : DARK_NICK_COLORS;
        bodyColor = Theme.text(context);
        list = new ListView(context) {
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                restoring = false;
            }
        };
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
        list.setAdapter(adapter);
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        empty = new TextView(context);
        empty.setTextColor(Theme.textSub(context));
        empty.setTextSize(13);
        empty.setGravity(Gravity.CENTER);
        addView(empty, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        list.setEmptyView(empty);
        list.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                touching = true;
                list.removeCallbacks(scrollToBottom);
                following = atBottom();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP
                    || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                touching = false;
                following = atBottom();
            }
            return false;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                scrollState = state;
                if (!restoring) following = atBottom();
            }
            @Override public void onScroll(AbsListView view, int first, int count, int total) {
                if (!restoring && scrollState != SCROLL_STATE_IDLE) following = atBottom();
            }
        });
        setConnectionState("", false);
    }

    private boolean atBottom() {
        if (rows.isEmpty()) return true;
        int count = list.getChildCount();
        if (count == 0) return following;
        return list.getLastVisiblePosition() == rows.size() - 1
                && list.getChildAt(count - 1).getBottom() <= list.getHeight() - list.getPaddingBottom();
    }

    void append(DanmakuProtocol.Msg m) {
        if (m == null || m.content == null || m.content.isEmpty()) return;
        boolean follow = following && !touching
                && scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
        if (!follow && rows.size() >= MAX_MESSAGES && !restoring && list.getChildCount() > 0) {
            int first = list.getFirstVisiblePosition();
            if (first >= 0 && first < rows.size()) {
                anchorId = rows.get(first).id;
                anchorTop = list.getChildAt(0).getTop();
                restoring = true;
            }
        }
        String nick = m.nick == null || m.nick.isEmpty() ? "\u89c2\u4f17" : m.nick;
        String body = m.content;
        SpannableStringBuilder text = new SpannableStringBuilder(nick + ": ");
        text.setSpan(new ForegroundColorSpan(nickColors[(nick.hashCode() & 0x7fffffff)
                % nickColors.length]), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int bodyStart = text.length();
        text.append(body);
        text.setSpan(new ForegroundColorSpan(bodyColor), bodyStart, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        rows.add(new Row(nextId++, text));
        if (rows.size() > MAX_MESSAGES) rows.remove(0);
        adapter.notifyDataSetChanged();
        list.removeCallbacks(scrollToBottom);
        if (follow) {
            list.post(scrollToBottom);
        } else if (restoring) {
            // Keep the same message and pixel offset even when the oldest row is evicted.
            int position = (int) Math.max(0L, anchorId - rows.get(0).id);
            list.setSelectionFromTop(Math.min(position, rows.size() - 1), anchorTop);
        }
    }

    void clearMessages() {
        list.removeCallbacks(scrollToBottom);
        rows.clear();
        following = true;
        restoring = false;
        adapter.notifyDataSetChanged();
        setConnectionState("", false);
    }

    void setConnectionState(String text, boolean connected) {
        empty.setText(connected ? "\u5df2\u8fde\u63a5\uff0c\u7b49\u5f85\u804a\u5929\u6d88\u606f\u2026"
                : (text == null || text.isEmpty() ? "\u7b49\u5f85\u804a\u5929\u8fde\u63a5\u2026" : text));
    }

    private static final class Row {
        final long id;
        final CharSequence text;
        Row(long id, CharSequence text) { this.id = id; this.text = text; }
    }

    private final class ChatAdapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return getItem(position).id; }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TextView text = recycled instanceof TextView ? (TextView) recycled : new TextView(getContext());
            if (recycled == null) {
                float density = getResources().getDisplayMetrics().density;
                int horizontal = Math.round(12 * density), vertical = Math.round(5 * density);
                text.setPadding(horizontal, vertical, horizontal, vertical);
                text.setTextSize(14);
            }
            text.setText(getItem(position).text);
            return text;
        }
    }
}
