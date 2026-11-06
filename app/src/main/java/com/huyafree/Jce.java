package com.huyafree;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Tars/JCE codec, ported 1:1 from Huya's taf-signal DataHelp constants.
 *
 * The two constants that matter and are easy to get wrong:
 *   EN_ZERO       = 12   (a zero value costs ONE byte: 0x0c)
 *   EN_STRUCTEND  = 11   (struct close also costs one byte: 0x0b)
 * Swapping them silently makes the server drop every packet.
 */
final class Jce {

    static final Charset UTF8 = Charset.forName("UTF-8");

    static final int EN_INT8 = 0;
    static final int EN_INT16 = 1;
    static final int EN_INT32 = 2;
    static final int EN_INT64 = 3;
    static final int EN_FLOAT = 4;
    static final int EN_DOUBLE = 5;
    static final int EN_STRING1 = 6;
    static final int EN_STRING4 = 7;
    static final int EN_MAP = 8;
    static final int EN_LIST = 9;
    static final int EN_STRUCTBEGIN = 10;
    static final int EN_STRUCTEND = 11;
    static final int EN_ZERO = 12;
    static final int EN_SIMPLELIST = 13;

    private Jce() {
    }

    // ======================= writer =======================

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(256);

        private void head(int tag, int type) {
            if (tag < 15) {
                out.write(((tag << 4) & 0xF0) | type);
            } else {
                out.write(0xF0 | type);
                out.write(tag);
            }
        }

        Writer int8(int tag, int v) {
            if (v == 0) {
                head(tag, EN_ZERO);
            } else {
                head(tag, EN_INT8);
                out.write(v & 0xFF);
            }
            return this;
        }

        Writer int16(int tag, int v) {
            if (v >= -128 && v <= 127) {
                return int8(tag, v);
            }
            head(tag, EN_INT16);
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
            return this;
        }

        Writer int32(int tag, int v) {
            if (v >= -32768 && v <= 32767) {
                return int16(tag, v);
            }
            head(tag, EN_INT32);
            out.write((v >> 24) & 0xFF);
            out.write((v >> 16) & 0xFF);
            out.write((v >> 8) & 0xFF);
            out.write(v & 0xFF);
            return this;
        }

        Writer int64(int tag, long v) {
            if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
                return int32(tag, (int) v);
            }
            head(tag, EN_INT64);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((v >> (i * 8)) & 0xFF));
            }
            return this;
        }

        Writer string(int tag, String s) {
            byte[] raw = s == null ? new byte[0] : s.getBytes(UTF8);
            if (raw.length > 255) {
                head(tag, EN_STRING4);
                out.write((raw.length >> 24) & 0xFF);
                out.write((raw.length >> 16) & 0xFF);
                out.write((raw.length >> 8) & 0xFF);
                out.write(raw.length & 0xFF);
            } else {
                head(tag, EN_STRING1);
                out.write(raw.length);
            }
            out.write(raw, 0, raw.length);
            return this;
        }

        /** JceOutputStream.writeBytes: SIMPLELIST + elem type + packed length + raw */
        Writer bytes(int tag, byte[] d) {
            head(tag, EN_SIMPLELIST);
            head(0, EN_INT8);
            int32(0, d.length);
            out.write(d, 0, d.length);
            return this;
        }

        /** BinBuffer.writeBytes: raw append, no wrapper at all */
        Writer raw(byte[] d) {
            out.write(d, 0, d.length);
            return this;
        }

        Writer struct(int tag, byte[] body) {
            head(tag, EN_STRUCTBEGIN);
            out.write(body, 0, body.length);
            head(0, EN_STRUCTEND);
            return this;
        }

        Writer emptyMap(int tag) {
            head(tag, EN_MAP);
            int32(0, 0);
            return this;
        }

        Writer map(int tag, List<byte[]> pairs) {
            head(tag, EN_MAP);
            int32(0, pairs.size() / 2);
            for (byte[] p : pairs) {
                out.write(p, 0, p.length);
            }
            return this;
        }

        Writer list(int tag, List<byte[]> items) {
            head(tag, EN_LIST);
            int32(0, items.size());
            for (byte[] it : items) {
                out.write(it, 0, it.length);
            }
            return this;
        }

        byte[] data() {
            return out.toByteArray();
        }
    }

    // ======================= reader =======================

    /**
     * Cursor over a JCE buffer.
     *
     * <p>Contract: {@link #tag()} consumes a header and records its tag/type.
     * {@link #number()}, {@link #string()}, {@link #bytes()} then consume the
     * <em>body only</em> — they never re-read a header. Re-reading a header
     * inside those methods desynchronises the stream on the second field,
     * which is exactly the bug that made an earlier revision parse nothing.
     */
    static final class Reader {
        private final byte[] d;
        private int p;
        private final int end;
        private int lastTag;
        private int lastType;

        Reader(byte[] data) {
            this(data, 0, data.length);
        }

        Reader(byte[] data, int off, int len) {
            d = data;
            p = off;
            end = Math.min(data.length, off + len);
        }

        boolean atEnd() {
            return p >= end;
        }

        int pos() {
            return p;
        }

        void seek(int newPos) {
            p = Math.max(0, Math.min(end, newPos));
        }

        int tag() {
            if (p >= end) {
                lastType = EN_STRUCTEND;
                lastTag = -1;
                return -1;
            }
            int b = d[p] & 0xFF;
            p++;
            int tag = (b & 0xF0) >> 4;
            int typ = b & 0x0F;
            if (tag == 15 && p < end) {
                tag = d[p] & 0xFF;
                p++;
            }
            lastType = typ;
            lastTag = tag;
            return tag;
        }

        int type() {
            return lastType;
        }

        /** Body of the field whose header was just consumed. */
        long number() {
            switch (lastType) {
                case EN_ZERO:
                    return 0;
                case EN_INT8:
                    if (p + 1 > end) {
                        p = end;
                        return 0;
                    }
                    return d[p++];
                case EN_INT16: {
                    if (p + 2 > end) {
                        p = end;
                        return 0;
                    }
                    int v = ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
                    p += 2;
                    return (short) v;
                }
                case EN_INT32: {
                    if (p + 4 > end) {
                        p = end;
                        return 0;
                    }
                    long v = ((long) (d[p] & 0xFF) << 24)
                            | ((long) (d[p + 1] & 0xFF) << 16)
                            | ((long) (d[p + 2] & 0xFF) << 8)
                            | (d[p + 3] & 0xFF);
                    p += 4;
                    return (int) v;
                }
                case EN_INT64: {
                    if (p + 8 > end) {
                        p = end;
                        return 0;
                    }
                    long v = 0;
                    for (int i = 0; i < 8; i++) {
                        v = (v << 8) | (d[p + i] & 0xFF);
                    }
                    p += 8;
                    return v;
                }
                default:
                    return 0;
            }
        }

        String string() {
            if (lastType == EN_ZERO) {
                return "";
            }
            int n;
            if (lastType == EN_STRING1) {
                if (p + 1 > end) {
                    p = end;
                    return "";
                }
                n = d[p] & 0xFF;
                p++;
            } else if (lastType == EN_STRING4) {
                if (p + 4 > end) {
                    p = end;
                    return "";
                }
                n = ((d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                        | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
                p += 4;
            } else {
                return null;
            }
            if (n < 0 || p + n > end) {
                n = Math.max(0, end - p);
            }
            String s = new String(d, p, n, UTF8);
            p += n;
            return s;
        }

        /**
         * Body of a bytes field. SIMPLELIST carries an element-type byte, LIST
         * does not — conflating them shifts the cursor by one byte per list and
         * silently corrupts everything after it.
         */
        byte[] bytes() {
            if (lastType == EN_SIMPLELIST) {
                tag();                 // element type
            } else if (lastType != EN_LIST) {
                return new byte[0];
            }
            tag();                     // length header
            int n = (int) number();    // length value
            if (n < 0 || p + n > end) {
                n = Math.max(0, end - p);
            }
            byte[] v = new byte[n];
            System.arraycopy(d, p, v, 0, n);
            p += n;
            return v;
        }

        /** LIST body length; the caller walks the elements itself. */
        int listSize() {
            tag();
            return (int) number();
        }

        void skipValue() {
            tag();
            skipBody();
        }

        void skipBody() {
            switch (lastType) {
                case EN_ZERO:
                    return;
                case EN_INT8:
                case EN_INT16:
                case EN_INT32:
                case EN_INT64:
                    number();
                    return;
                case EN_STRING1:
                case EN_STRING4:
                    string();
                    return;
                case EN_SIMPLELIST:
                    bytes();
                    return;
                case EN_LIST: {
                    int n = listSize();
                    for (int i = 0; i < n; i++) {
                        skipValue();
                    }
                    return;
                }
                case EN_MAP:
                    skipMap();
                    return;
                case EN_STRUCTBEGIN:
                    skipStruct();
                    return;
                default:
                    return;
            }
        }

        void skipStruct() {
            int depth = 1;
            while (depth > 0 && !atEnd()) {
                int t = tag();
                switch (lastType) {
                    case EN_STRUCTBEGIN:
                        depth++;
                        break;
                    case EN_STRUCTEND:
                        depth--;
                        break;
                    case EN_ZERO:
                        break;
                    case EN_INT8:
                    case EN_INT16:
                    case EN_INT32:
                    case EN_INT64:
                        number();
                        break;
                    case EN_STRING1:
                    case EN_STRING4:
                        string();
                        break;
                    case EN_SIMPLELIST:
                        bytes();
                        break;
                    case EN_LIST: {
                        int n = listSize();
                        for (int i = 0; i < n; i++) {
                            skipValue();
                        }
                        break;
                    }
                    case EN_MAP:
                        skipMap();
                        break;
                    default:
                        return;
                }
            }
        }

        void skipMap() {
            // JCE maps carry a count followed by alternating tagged key/value fields.
            tag();                 // length header
            int n = (int) number();
            for (int i = 0; i < n; i++) {
                skipValue();
                skipValue();
            }
        }

        byte[] rest() {
            byte[] v = new byte[end - p];
            System.arraycopy(d, p, v, 0, v.length);
            p = end;
            return v;
        }
    }

    // ======================= helpers =======================

    static byte[] int32(int tag, int v) {
        return new Writer().int32(tag, v).data();
    }

    static byte[] int64(int tag, long v) {
        return new Writer().int64(tag, v).data();
    }

    static byte[] string(int tag, String v) {
        return new Writer().string(tag, v).data();
    }

    static List<byte[]> pairs(byte[]... kv) {
        List<byte[]> l = new ArrayList<>();
        for (byte[] b : kv) {
            l.add(b);
        }
        return l;
    }
}
