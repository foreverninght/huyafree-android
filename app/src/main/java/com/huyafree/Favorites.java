package com.huyafree;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 收藏 + 最近观看，存 SharedPreferences。 */
public final class Favorites {

    private static final String PREF = "huya_free";
    private static final String K_FAV = "favorites";
    private static final String K_HIS = "history";

    private Favorites() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 一个可序列化的房间摘要。 */
    public static final class Room {
        public final String roomId;
        public final String title;
        public final String anchor;
        public final String game;
        public final String cover;
        public final long viewers;

        public Room(String roomId, String title, String anchor,
                    String game, String cover, long viewers) {
            this.roomId = roomId;
            this.title = title;
            this.anchor = anchor;
            this.game = game;
            this.cover = cover;
            this.viewers = viewers;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("roomId", roomId);
                o.put("title", title);
                o.put("anchor", anchor);
                o.put("game", game);
                o.put("cover", cover);
                o.put("viewers", viewers);
            } catch (Exception ignored) {
            }
            return o;
        }

        public static Room fromJson(JSONObject o) {
            return new Room(o.optString("roomId"), o.optString("title"),
                    o.optString("anchor"), o.optString("game"),
                    o.optString("cover"), o.optLong("viewers"));
        }
    }

    private static List<Room> load(Context c, String key) {
        List<Room> out = new ArrayList<>();
        String raw = sp(c).getString(key, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(Room.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, String key, List<Room> list) {
        JSONArray arr = new JSONArray();
        for (Room r : list) {
            arr.put(r.toJson());
        }
        sp(c).edit().putString(key, arr.toString()).apply();
    }

    public static List<Room> favorites(Context c) {
        return load(c, K_FAV);
    }

    public static boolean isFavorite(Context c, String roomId) {
        for (Room r : favorites(c)) {
            if (r.roomId.equals(roomId)) {
                return true;
            }
        }
        return false;
    }

    /** 切换收藏，返回切换后是否为收藏状态。 */
    public static boolean toggle(Context c, Room room) {
        List<Room> list = favorites(c);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).roomId.equals(room.roomId)) {
                list.remove(i);
                save(c, K_FAV, list);
                return false;
            }
        }
        list.add(0, room);
        save(c, K_FAV, list);
        return true;
    }

    public static List<Room> history(Context c) {
        return load(c, K_HIS);
    }

    /** 记一次观看，最多保留 50 条，去重后置顶。 */
    public static void touchHistory(Context c, Room room) {
        List<Room> list = load(c, K_HIS);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).roomId.equals(room.roomId)) {
                list.remove(i);
                break;
            }
        }
        list.add(0, room);
        while (list.size() > 50) {
            list.remove(list.size() - 1);
        }
        save(c, K_HIS, list);
    }
}
