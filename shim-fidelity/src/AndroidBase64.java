import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * android.util.Base64 的纯 JVM 等价实现。
 *
 * 本类逐行对照 AOSP API 34 的 luni/src/main/java/android/util/Base64.java 移植，
 * 保留了原实现的 Encoder/Decoder 状态机语义（含 count/LINE_GROUPS 计数、
 * 尾部换行的特殊分支、解码的 0..6 状态机）。
 *
 * 与 java.util.Base64 的差异见 README.md。
 *
 * 证据等级：本文件是 已读源码 的移植，尚未与真机逐字节比对。
 *           README 的「待真机确认」一节列出了必须 instrumentation 验证的点。
 */
public final class AndroidBase64 {

    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static final int CRLF = 4;
    public static final int URL_SAFE = 8;
    public static final int NO_CLOSE = 16;

    /** 对应 AOSP Encoder.LINE_GROUPS：每 19 个 4 字符组换行 = 76 字符行宽 */
    private static final int LINE_GROUPS = 19;

    private static final byte[] ENCODE = new byte[]{
            'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P',
            'Q','R','S','T','U','V','W','X','Y','Z','a','b','c','d','e','f',
            'g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v',
            'w','x','y','z','0','1','2','3','4','5','6','7','8','9','+','/',
    };

    private static final byte[] ENCODE_WEBSAFE = new byte[]{
            'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P',
            'Q','R','S','T','U','V','W','X','Y','Z','a','b','c','d','e','f',
            'g','h','i','j','k','l','m','n','o','p','q','r','s','t','u','v',
            'w','x','y','z','0','1','2','3','4','5','6','7','8','9','-','_',
    };

    private static final int SKIP = -1;
    private static final int EQUALS = -2;

    private static final int[] DECODE = buildDecode(ENCODE, '+', '/');
    private static final int[] DECODE_WEBSAFE = buildDecode(ENCODE_WEBSAFE, '-', '_');

    private static int[] buildDecode(byte[] alphabet, char c62, char c63) {
        int[] table = new int[256];
        java.util.Arrays.fill(table, SKIP);
        for (int i = 0; i < alphabet.length; i++) {
            table[alphabet[i] & 0xff] = i;
        }
        table[c62] = 62;
        table[c63] = 63;
        table['='] = EQUALS;
        // AOSP 的 DECODE 表在 0x00-0x20 及 0x7f+ 都是 -1(SKIP)，
        // 所以空白字符天然被跳过。
        return table;
    }

    private AndroidBase64() {}

    // ==================== encode ====================

    public static String encodeToString(byte[] input, int flags) {
        return new String(encode(input, flags), StandardCharsets.US_ASCII);
    }

    public static byte[] encode(byte[] input, int flags) {
        final boolean doPadding = (flags & NO_PADDING) == 0;
        final boolean doNewline = (flags & NO_WRAP) == 0;
        final boolean doCr = (flags & CRLF) != 0;
        final byte[] alphabet = ((flags & URL_SAFE) == 0) ? ENCODE : ENCODE_WEBSAFE;
        final String nl = doCr ? "\r\n" : "\n";

        StringBuilder sb = new StringBuilder(maxOutputSize(input.length));
        int count = doNewline ? LINE_GROUPS : -1;
        int p = 0;
        final int len = input.length;

        while (p + 3 <= len) {
            int v = ((input[p] & 0xff) << 16)
                  | ((input[p + 1] & 0xff) << 8)
                  |  (input[p + 2] & 0xff);
            p += 3;
            sb.append((char) (alphabet[(v >> 18) & 0x3f] & 0xff))
              .append((char) (alphabet[(v >> 12) & 0x3f] & 0xff))
              .append((char) (alphabet[(v >> 6) & 0x3f] & 0xff))
              .append((char) (alphabet[v & 0x3f] & 0xff));
            if (--count == 0) {
                sb.append(nl);
                count = LINE_GROUPS;
            }
        }

        final int rem = len - p;
        if (rem == 1) {
            int v = ((input[p] & 0xff) << 16);
            sb.append((char) (alphabet[(v >> 18) & 0x3f] & 0xff))
              .append((char) (alphabet[(v >> 12) & 0x3f] & 0xff));
            if (doPadding) sb.append("==");
            if (doNewline) sb.append(nl);
        } else if (rem == 2) {
            int v = ((input[p] & 0xff) << 16) | ((input[p + 1] & 0xff) << 8);
            sb.append((char) (alphabet[(v >> 18) & 0x3f] & 0xff))
              .append((char) (alphabet[(v >> 12) & 0x3f] & 0xff))
              .append((char) (alphabet[(v >> 6) & 0x3f] & 0xff));
            if (doPadding) sb.append('=');
            if (doNewline) sb.append(nl);
        } else {
            // ⚠️ 这是 AOSP 的关键分支（源码 :716）：
            //     do_newline && op > 0 && count != LINE_GROUPS
            // 含义：输入长度正好是 3 的倍数、且上一行恰好写满换行后，
            // 不再补多余的换行符。写成无条件 append 会多出一个 \n。
            if (doNewline && sb.length() > 0 && count != LINE_GROUPS) {
                sb.append(nl);
            }
        }

        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static int maxOutputSize(int len) {
        return len * 8 / 5 + 10;
    }

    // ==================== decode ====================

    public static byte[] decode(String input, int flags) {
        return decode(input.getBytes(StandardCharsets.US_ASCII), flags);
    }

    public static byte[] decode(byte[] input, int flags) {
        final int[] alphabet = ((flags & URL_SAFE) == 0) ? DECODE : DECODE_WEBSAFE;
        final ByteArrayOutputStream out = new ByteArrayOutputStream(input.length * 3 / 4 + 3);

        int state = 0;
        int value = 0;

        for (byte b : input) {
            int d = alphabet[b & 0xff];

            switch (state) {
                case 0:
                    if (d >= 0) {
                        value = d;
                        state = 1;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64 (state 0)");
                    }
                    break;
                case 1:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        state = 2;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64 (state 1)");
                    }
                    break;
                case 2:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        state = 3;
                    } else if (d == EQUALS) {
                        out.write((byte) (value >> 4));
                        state = 4;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64 (state 2)");
                    }
                    break;
                case 3:
                    if (d >= 0) {
                        value = (value << 6) | d;
                        out.write((byte) (value >> 16));
                        out.write((byte) (value >> 8));
                        out.write((byte) value);
                        state = 0;
                    } else if (d == EQUALS) {
                        out.write((byte) (value >> 10));
                        out.write((byte) (value >> 2));
                        state = 5;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64 (state 3)");
                    }
                    break;
                case 4:
                    if (d == EQUALS) {
                        state = 5;
                    } else if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64 (state 4)");
                    }
                    break;
                case 5:
                    if (d != SKIP) {
                        throw new IllegalArgumentException("bad base-64 (trailing garbage)");
                    }
                    break;
                default:
                    throw new IllegalArgumentException("bad base-64 (state " + state + ")");
            }
        }

        // 收尾：对应 AOSP process(finish=true) 的 switch(state)
        switch (state) {
            case 0:
            case 5:
                break;
            case 2:
                out.write((byte) (value >> 4));
                break;
            case 3:
                out.write((byte) (value >> 10));
                out.write((byte) (value >> 2));
                break;
            case 1:
            case 4:
                throw new IllegalArgumentException("bad base-64 (truncated, state " + state + ")");
            default:
                throw new IllegalArgumentException("bad base-64 (state " + state + ")");
        }

        return out.toByteArray();
    }
}
