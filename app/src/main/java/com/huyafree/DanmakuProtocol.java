package com.huyafree;

import java.util.ArrayList;
import java.util.List;

/**
 * Huya danmaku wire protocol: WebSocketCommand framing + WUP + MessageNotice.
 *
 * Everything here is transcribed from the real APK / taf-signal JS, not guessed:
 *
 *  WebSocketCommand.writeTo (7 fields):
 *      0 int32  iCmdType
 *      1 bytes  vData
 *      2 int64  lRequestId
 *      3 string traceId
 *      4 int32  iEncryptType
 *      5 int64  lTime
 *      6 string sMD5
 *
 *  Wup.encode():
 *      sBuffer = MAP(0){ "tReq" -> STRUCT(1){ ... } }
 *      head    = WUP header struct
 *      wire    = int32(4 + head.length) + head     (raw, no SIMPLELIST)
 *
 *  WUP header:
 *      1 int16 iVersion(=3), 2 int8 cPacketType, 3 int32 iMessageType,
 *      4 int32 iRequestId, 5 string sServantName, 6 string sFuncName,
 *      7 bytes sBuffer, 8 int32 iTimeout, 9 map context, 10 map status
 *
 *  UserId (WupHelper.getUserId):  0 lUid, 1 sGuid, 2 sToken, 3 sHuYaUA,
 *                                 4 sCookie, 5 iTokenType, 6 sDeviceInfo, 7 sQIMEI
 *
 *  MessageNotice: 0 tUserInfo{0 lUid, 2 sNick}, 1 lTid, 2 lSid, 3 sContent,
 *                 4 iShowMode, 5 tFormat, 6 tBulletFormat, 7 iTermType ...
 */
final class DanmakuProtocol {

    static final String WS_HOST = "cdnws.api.huya.com";

    static final int CMD_REGISTER_RSP = 2;
    static final int CMD_WUP_REQ = 3;
    static final int CMD_WUP_RSP = 4;
    static final int CMD_MSG_PUSH = 7;
    static final int CMD_REGISTER_GROUP_REQ = 16;
    static final int CMD_REGISTER_GROUP_RSP = 17;
    static final int CMD_HEARTBEAT_REQ = 20;
    static final int CMD_MSG_PUSH_V2 = 22;
    static final long URI_MESSAGE_NOTICE = 1400;

    /** Real client UA, taken from WupHelper.getHuYaUA of the shipped APK. */
    static final String APP_UA = "hyadr&13.3.52&0";
    static final String WEB_UA = "webh5&13.3.52&websocket";

    private DanmakuProtocol() {
    }

    // ============ baseinfo for the wss:// handshake ============

    /**
     * WSConnectParaInfo, serialised then base64 + urlencoded.
     * 0 lUid, 1 sGuid, 2 sUA, 3 sAppSrc, 4 sMid, 5 sExp, 6 iTokenType,
     * 7 sToken, 8 sCookie, 9 sTraceId, 10 mCustomHeaders
     */
    static String baseInfo(String guid) {
        Jce.Writer w = new Jce.Writer();
        w.int64(0, 0);
        w.string(1, guid);
        w.string(2, WEB_UA);
        w.string(3, "");
        w.string(4, "");
        w.string(5, "");
        w.int32(6, 0);
        w.string(7, "");
        w.string(8, "");
        w.string(9, "");
        w.map(10, Jce.pairs(
                Jce.string(0, "HUYA_NET"), Jce.int32(0, 0),
                Jce.string(0, "HUYA_VSDKUA"), Jce.string(0, WEB_UA)));
        byte[] raw = w.data();
        return "?baseinfo=" + urlEncode(android.util.Base64.encodeToString(
                raw, android.util.Base64.NO_WRAP));
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ============ command frames ============

    static byte[] wsCommand(int cmdType, byte[] vData, long reqId, String trace) {
        Jce.Writer w = new Jce.Writer();
        w.int32(0, cmdType);
        w.bytes(1, vData);
        w.int64(2, reqId);
        w.string(3, trace == null ? "" : trace);
        w.int32(4, 0);
        w.int64(5, 0);
        w.string(6, "");
        return w.data();
    }

    /** RegisterGroupReq: tag0 vector<string> groups, tag1 string. */
    static byte[] registerGroup(String[] groups) {
        List<byte[]> items = new ArrayList<>();
        for (String g : groups) {
            items.add(Jce.string(0, g));
        }
        Jce.Writer inner = new Jce.Writer();
        inner.list(0, items);
        Jce.Writer w = new Jce.Writer();
        w.raw(inner.data());
        w.string(1, "");
        return wsCommand(CMD_REGISTER_GROUP_REQ, w.data(), System.nanoTime() % 100000,
                "");
    }

    static byte[] heartbeat() {
        return wsCommand(CMD_HEARTBEAT_REQ, new byte[0], 0, "");
    }

    // ============ user id ============

    static byte[] userId(String guid, String deviceModel) {
        Jce.Writer w = new Jce.Writer();
        w.int64(0, 0);                       // lUid
        w.string(1, guid);                   // sGuid
        w.string(2, "");                     // sToken
        w.string(3, APP_UA);                 // sHuYaUA
        w.string(4, "");                     // sCookie
        w.int32(5, 0);                       // iTokenType
        w.string(6, deviceModel == null ? "" : deviceModel); // sDeviceInfo
        w.string(7, "");                     // sQIMEI
        return w.data();
    }

    // ============ WUP ============

    static byte[] wup(String servant, String func, byte[] tReqStruct, int reqId) {
        // sBuffer = MAP(0){ "tReq" -> STRUCT(1){ tReqStruct } }
        Jce.Writer inner = new Jce.Writer();
        inner.struct(1, tReqStruct);
        Jce.Writer sbuf = new Jce.Writer();
        sbuf.map(0, Jce.pairs(Jce.string(0, "tReq"), inner.data()));

        Jce.Writer head = new Jce.Writer();
        head.int16(1, 3);                    // iVersion
        head.int8(2, 0);                     // cPacketType
        head.int32(3, 0);                    // iMessageType
        head.int32(4, reqId);                // iRequestId
        head.string(5, servant);
        head.string(6, func);
        head.bytes(7, sbuf.data());          // sBuffer
        head.int32(8, 15);                   // iTimeout
        head.emptyMap(9);
        head.emptyMap(10);
        byte[] body = head.data();

        Jce.Writer wrap = new Jce.Writer();
        wrap.int32(0, body.length + 4);
        wrap.raw(body);
        return wrap.data();
    }

    /** GetHistoryMessageReq: 0 tUserId, 1 lPid */
    static byte[] reqHistoryMessage(long pid, String guid, String model) {
        Jce.Writer w = new Jce.Writer();
        w.struct(0, userId(guid, model));
        w.int64(1, pid);
        return w.data();
    }

    /** GetHistoryChannelMessageReq: 0 tUserId, 1 lPid, 2 sChannelId */
    static byte[] reqHistoryChannel(long pid, String channelId, String guid,
                                    String model) {
        Jce.Writer w = new Jce.Writer();
        w.struct(0, userId(guid, model));
        w.int64(1, pid);
        w.string(2, channelId);
        return w.data();
    }

    // ============ inbound parsing ============

    /** One decoded danmaku line. */
    static final class Msg {
        String nick = "";
        String content = "";
        long uid;
        long tid;
        long sid;
        int fontColor = 0xFFFFFF;
        int fontSize = 15;
        int showMode;

        boolean usable() {
            return content != null && content.length() > 0;
        }

        @Override
        public String toString() {
            return nick + ": " + content;
        }
    }

    /** Result of splitting a WS frame. */
    static final class Frame {
        int cmdType;
        byte[] vData;
        long reqId;
    }

    static Frame splitCommand(byte[] d) {
        Frame f = new Frame();
        Jce.Reader r = new Jce.Reader(d);
        r.tag();
        f.cmdType = (int) r.number();
        if (r.atEnd()) {
            f.vData = new byte[0];
            return f;
        }
        r.tag();
        f.vData = r.bytes();
        if (!r.atEnd()) {
            try {
                r.tag();
                f.reqId = r.number();
            } catch (Exception ignored) {
            }
        }
        return f;
    }

    /**
     * Pull every MessageNotice blob out of a push payload.
     *
     * <p>Two shapes exist in the wild:
     * <ul>
     *   <li>cmd=7  → vData is a bare WSPushMessage</li>
     *   <li>cmd=22 → vData is { 0 sGroupId, 1 LIST&lt;WSPushMessage&gt; }</li>
     * </ul>
     * Both funnel into the same parser, so callers never branch on cmd.
     */
    static List<byte[]> extractMessages(int cmdType, byte[] vData) {
        List<byte[]> out = new ArrayList<>();
        if (vData == null || vData.length == 0) {
            return out;
        }
        try {
            if (cmdType == CMD_MSG_PUSH) {
                Push push = parsePush(vData);
                byte[] m = push.msg;
                if (push.uri == URI_MESSAGE_NOTICE && m != null && m.length > 0) {
                    out.add(m);
                }
                return out;
            }
            if (cmdType != CMD_MSG_PUSH_V2) {
                return out;
            }
            Jce.Reader r = new Jce.Reader(vData);
            while (!r.atEnd()) {
                int t = r.tag();
                int typ = r.type();
                if (typ == Jce.EN_STRING1 || typ == Jce.EN_STRING4
                        || typ == Jce.EN_ZERO) {
                    r.string();
                    continue;
                }
                if (typ == Jce.EN_LIST) {
                    int n = r.listSize();
                    for (int i = 0; i < n && !r.atEnd(); i++) {
                        int start = r.pos();
                        r.tag();
                        if (r.type() != Jce.EN_STRUCTBEGIN) {
                            r.seek(start);
                            r.skipValue();
                            continue;
                        }
                        int bodyStart = r.pos();
                        r.skipStruct();
                        int bodyEnd = Math.max(bodyStart, r.pos() - 1);
                        if (bodyEnd <= bodyStart) {
                            continue;
                        }
                        byte[] body = new byte[bodyEnd - bodyStart];
                        System.arraycopy(vData, bodyStart, body, 0, body.length);
                        try {
                            Push push = parsePush(body);
                            byte[] m = push.msg;
                            if (push.uri == URI_MESSAGE_NOTICE && m != null && m.length > 0) {
                                out.add(m);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                    continue;
                }
                r.skipBody();
            }
        } catch (Exception ignored) {
            // A malformed frame must never cost us the messages we already have.
        }
        return out;
    }

    /** WSPushMessage: 0 ePushType, 1 iUri, 2 sMsg, 3 iProtocolType, 4 sGroupId */
    static final class Push {
        long uri;
        byte[] msg;
        String groupId = "";
    }

    static Push parsePush(byte[] vd) {
        Push p = new Push();
        Jce.Reader r = new Jce.Reader(vd);
        while (!r.atEnd()) {
            int t = r.tag();
            int typ = r.type();
            if (typ == Jce.EN_STRUCTBEGIN || typ == Jce.EN_STRUCTEND) {
                continue;
            }
            if (typ == Jce.EN_SIMPLELIST) {
                byte[] b = r.bytes();
                if (t == 2) {
                    p.msg = b;
                }
                continue;
            }
            if (typ == Jce.EN_LIST) {
                int n = r.listSize();
                for (int i = 0; i < n; i++) {
                    r.skipValue();
                }
                continue;
            }
            if (typ == Jce.EN_MAP) {
                r.skipMap();
                continue;
            }
            if (typ == Jce.EN_STRING1 || typ == Jce.EN_STRING4
                    || typ == Jce.EN_ZERO) {
                String s = r.string();
                if (t == 4) {
                    p.groupId = s == null ? "" : s;
                }
                continue;
            }
            long v = r.number();
            if (t == 1) {
                p.uri = v;
            }
        }
        return p;
    }

    /**
     * MessageNotice:
     *   0 tUserInfo(struct)  1 lTid  2 lSid  3 sContent  4 iShowMode
     *   5 tFormat(struct)  6 tBulletFormat(struct)  7 iTermType  8 vDecorationPrefix
     */
    static Msg parseMessageNotice(byte[] blob) {
        Msg m = new Msg();
        Jce.Reader r = new Jce.Reader(blob);
        while (!r.atEnd()) {
            int t = r.tag();
            int typ = r.type();
            if (typ == Jce.EN_STRUCTEND) {
                continue;
            }
            if (typ == Jce.EN_STRUCTBEGIN) {
                if (t == 0) {
                    parseSender(r, m);
                } else {
                    r.skipStruct();
                }
                continue;
            }
            if (typ == Jce.EN_SIMPLELIST) {
                r.bytes();
                continue;
            }
            if (typ == Jce.EN_LIST) {
                int n = r.listSize();
                for (int i = 0; i < n; i++) {
                    r.skipValue();
                }
                continue;
            }
            if (typ == Jce.EN_MAP) {
                r.skipMap();
                continue;
            }
            if (typ == Jce.EN_STRING1 || typ == Jce.EN_STRING4
                    || typ == Jce.EN_ZERO) {
                String s = r.string();
                if (t == 3 && s != null) {
                    m.content = s;
                }
                continue;
            }
            long v = r.number();
            if (t == 1) {
                m.tid = v;
            } else if (t == 2) {
                m.sid = v;
            } else if (t == 4) {
                m.showMode = (int) v;
            }
        }
        return m;
    }

    /**
     * SenderInfo: field order is not fixed across message kinds, so collect all
     * top-level strings and pick the one that reads like a nickname.
     * URLs, pure digits and over-long blobs are decorations, not nicknames.
     */
    private static void parseSender(Jce.Reader r, Msg m) {
        int depth = 0;
        String candidate = null;
        while (!r.atEnd()) {
            int t = r.tag();
            int typ = r.type();
            if (typ == Jce.EN_STRUCTEND) {
                if (depth == 0) {
                    break;
                }
                depth--;
                continue;
            }
            if (typ == Jce.EN_STRUCTBEGIN) {
                depth++;
                continue;
            }
            if (typ == Jce.EN_SIMPLELIST) {
                r.bytes();
                continue;
            }
            if (typ == Jce.EN_LIST) {
                int n = r.listSize();
                for (int i = 0; i < n; i++) {
                    r.skipValue();
                }
                continue;
            }
            if (typ == Jce.EN_MAP) {
                r.skipMap();
                continue;
            }
            if (typ == Jce.EN_STRING1 || typ == Jce.EN_STRING4
                    || typ == Jce.EN_ZERO) {
                String s = r.string();
                if (depth == 0 && candidate == null && looksLikeNick(s)) {
                    candidate = s;
                }
                continue;
            }
            long v = r.number();
            if (t == 0 && depth == 0) {
                m.uid = v;
            }
        }
        if (candidate != null) {
            m.nick = candidate;
        }
    }

    private static boolean looksLikeNick(String s) {
        if (s == null || s.length() == 0 || s.length() > 40) {
            return false;
        }
        if (s.startsWith("http") || s.startsWith("HTTP")) {
            return false;
        }
        boolean allDigits = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                allDigits = false;
                break;
            }
        }
        return !allDigits;
    }
}
