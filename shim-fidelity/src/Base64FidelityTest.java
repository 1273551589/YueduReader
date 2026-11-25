import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;

/**
 * shim 保真度测试：AndroidBase64（AOSP 移植）vs java.util.Base64（朴素替代）。
 *
 * 目的不是让测试变绿，而是把「朴素的 java.util.Base64 为什么不够」变成可执行的证据。
 *
 * 两种「朴素替代」都要测，因为它们是不同阶段的错误：
 *   naive0 = java.util.Base64.getEncoder() 一行搞定，完全不处理换行  ← 第一反应
 *   naive1 = naive0 + 自己按 76 字符补换行                          ← 意识到问题后的补丁
 *
 * 「不一致」的行就是必须在 P0 里处理掉的语义差异。
 */
public class Base64FidelityTest {

    private static int total = 0;
    private static int diffs = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("=== Base64 shim 保真度测试 ===");
        System.out.println("android = AOSP 移植    naive = java.util.Base64 朴素替代");
        System.out.println();

        // ---------- 编码：行宽 ----------
        section("编码行宽（DEFAULT 标志，UTF-8）");
        for (int n : new int[]{0, 1, 2, 3, 54, 55, 56, 57, 58, 75, 76, 113, 114, 115, 171, 172}) {
            byte[] input = "x".repeat(n).getBytes(StandardCharsets.UTF_8);
            compareEncode("len=" + n + " DEFAULT", input, AndroidBase64.DEFAULT, n);
        }

        // ---------- 编码：padding ----------
        section("NO_PADDING 与补位");
        for (int n : new int[]{1, 2, 3, 4, 5}) {
            byte[] input = "abcdefg".substring(0, n).getBytes(StandardCharsets.UTF_8);
            compareEncode("len=" + n + " DEFAULT", input, AndroidBase64.DEFAULT, n);
            compareEncode("len=" + n + " NO_PADDING", input, AndroidBase64.NO_PADDING, n);
        }

        // ---------- 编码：URL_SAFE ----------
        section("URL_SAFE 字母表（+ / → - _）");
        // 0xfb 0xef 0xbe 这类会产生 + 和 /
        byte[] tricky = new byte[]{(byte) 0xfb, (byte) 0xef, (byte) 0xbe, (byte) 0xff, (byte) 0xfe};
        compareEncode("tricky bytes URL_SAFE", tricky, AndroidBase64.NO_WRAP | AndroidBase64.URL_SAFE, -1);
        compareEncode("tricky bytes STD", tricky, AndroidBase64.NO_WRAP, -1);

        // ---------- 编码：CRLF ----------
        section("CRLF（行尾 \\r\\n）");
        byte[] big = "y".repeat(100).getBytes(StandardCharsets.UTF_8);
        compareEncode("len=100 DEFAULT|CRLF", big, AndroidBase64.CRLF, 100);
        compareEncode("len=100 DEFAULT", big, AndroidBase64.DEFAULT, 100);

        // ---------- 编码：flag 位组合 ----------
        section("flag 位组合（不是 switch 分支！）");
        compareEncode("NO_WRAP|URL_SAFE", tricky, AndroidBase64.NO_WRAP | AndroidBase64.URL_SAFE, -1);
        compareEncode("NO_WRAP|NO_PADDING|URL_SAFE", tricky,
                AndroidBase64.NO_WRAP | AndroidBase64.NO_PADDING | AndroidBase64.URL_SAFE, -1);
        compareEncode("CRLF|URL_SAFE", big, AndroidBase64.CRLF | AndroidBase64.URL_SAFE, 100);

        // ---------- 解码：换行容忍 ----------
        section("解码换行容忍（这是最容易被忽略的一条）");
        decodeCase("含 \\n 的标准 base64", "YWJj\nZGVm", AndroidBase64.DEFAULT, "abcdef");
        decodeCase("含 \\r\\n", "YWJj\r\nZGVm", AndroidBase64.DEFAULT, "abcdef");
        decodeCase("含空格", "YWJj ZGVm", AndroidBase64.DEFAULT, "abcdef");
        decodeCase("带 padding", "YWJjZGVm", AndroidBase64.DEFAULT, "abcdef");

        // ---------- 解码：非法字符 ----------
        section("解码非法字符（Android 跳过，java.util 抛异常）");
        decodeCase("含 ! 号", "YWJj!ZGVm", AndroidBase64.DEFAULT, "abcdef");
        decodeCase("含 0x00", "YWJj\u0000ZGVm", AndroidBase64.DEFAULT, "abcdef");

        // ---------- 解码：错误输入 ----------
        section("解码错误输入（两边都应失败，失败方式可能不同）");
        decodeShouldFail("截断 (1 个字符)", "Y", AndroidBase64.DEFAULT);
        decodeShouldFail("单个 padding", "YWJjZ=", AndroidBase64.DEFAULT);
        decodeShouldFail("非法 padding 位置", "Y=Jj", AndroidBase64.DEFAULT);
        decodeShouldFail("URL_SAFE 表遇到 +", "a+b/", AndroidBase64.URL_SAFE);

        // ---------- 往返 ----------
        section("encode → decode 往返（多种 flag）");
        for (int flags : new int[]{
                AndroidBase64.DEFAULT,
                AndroidBase64.NO_WRAP,
                AndroidBase64.NO_PADDING,
                AndroidBase64.URL_SAFE,
                AndroidBase64.NO_WRAP | AndroidBase64.URL_SAFE,
                AndroidBase64.NO_WRAP | AndroidBase64.NO_PADDING | AndroidBase64.URL_SAFE,
        }) {
            roundTrip(flags);
        }

        // ---------- 自校验：用 AOSP 源码里的长度公式反推 ----------
        section("自校验：输出长度必须等于 AOSP 源码 :512-533 的公式");
        // 源码：
        //   output_len = 4*(len/3) [+ padding 相关]
        //   if (do_newline && len > 0)
        //       output_len += (((len-1) / (3 * LINE_GROUPS)) + 1) * (do_cr ? 2 : 1)
        int formulaChecks = 0;
        int formulaFails = 0;
        for (int n = 0; n <= 400; n++) {
            byte[] input = new byte[n];
            for (int i = 0; i < n; i++) input[i] = (byte) (i * 37);
            for (int flags : new int[]{AndroidBase64.DEFAULT, AndroidBase64.NO_WRAP, AndroidBase64.CRLF}) {
                int actual = AndroidBase64.encode(input, flags).length;
                int expected = aospOutputLength(n, flags);
                formulaChecks++;
                total++;
                if (actual != expected) {
                    formulaFails++;
                    diffs++;
                    failures.add(String.format("长度公式不符 n=%d flags=%d 实际=%d 公式=%d",
                            n, flags, actual, expected));
                    System.out.printf("n=%-3d flags=%-2d 实际=%d 公式=%d  ✗%n", n, flags, actual, expected);
                }
            }
        }
        if (formulaFails == 0) {
            System.out.printf("%d 组全部符合 AOSP 长度公式（n=0..400 × 3 种 flag）%n", formulaChecks);
        } else {
            System.out.printf("%d/%d 组不符 ✗%n", formulaFails, formulaChecks);
        }

        // ---------- 汇总 ----------
        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.printf("用例总数: %d%n", total);
        System.out.printf("存在差异: %d%n", diffs);
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("必须处理的差异:");
            for (String f : failures) System.out.println("  · " + f);
        }
        System.out.println();
        System.out.println(diffs > 0
                ? "结论：朴素 java.util.Base64 不能直接顶替 android.util.Base64。"
                : "结论：本组用例下两者等价（但仍需真机 instrumentation 确认）。");
    }

    /**
     * AOSP android.util.Base64.encode() 里对输出长度的精确计算（源码 :512-533）。
     * 这是独立于我实现的一份「金标准」——如果我的 encoder 和它对不上，
     * 说明我漏了某个分支。
     */
    private static int aospOutputLength(int len, int flags) {
        boolean noPadding = (flags & AndroidBase64.NO_PADDING) != 0;
        boolean noWrap = (flags & AndroidBase64.NO_WRAP) != 0;
        boolean crlf = (flags & AndroidBase64.CRLF) != 0;

        int outputLen = (len / 3) * 4 + (len % 3 == 0 ? 0 : 4);
        if (!noPadding) {
            // padding 已经包含在上面那个 +4 里了（AOSP 的写法是先按 4 对齐再加 padding）
            // 源码 :512-527 的逻辑：rem==2 时 +3，rem==1 时 +2，然后 if(!no_padding) 再 +2/+1
            // 净效果与「向上取整到 4」一致，这里用等价式表达。
        }
        if (!noWrap && len > 0) {
            // LINE_GROUPS = 19（AOSP Encoder.LINE_GROUPS），19 组 × 4 字符 = 76 字符行宽
            outputLen += (((len - 1) / (3 * 19)) + 1) * (crlf ? 2 : 1);
        }
        return outputLen;
    }

    // ==================== 断言工具 ====================

    private static void section(String title) {
        System.out.println();
        System.out.println("--- " + title + " ---");
    }

    private static void compareEncode(String name, byte[] input, int flags, int lenForLabel) {
        total++;
        String android;
        String naive0;
        String naive1;
        try {
            android = AndroidBase64.encodeToString(input, flags);
        } catch (Throwable t) {
            android = "THROW:" + t.getClass().getSimpleName();
        }
        try {
            naive0 = naiveEncode0(input, flags);
        } catch (Throwable t) {
            naive0 = "THROW:" + t.getClass().getSimpleName();
        }
        try {
            naive1 = naiveEncode1(input, flags);
        } catch (Throwable t) {
            naive1 = "THROW:" + t.getClass().getSimpleName();
        }

        boolean same0 = android.equals(naive0);
        boolean same1 = android.equals(naive1);
        if (!same0) { diffs++; failures.add(String.format("[naive0] 编码差异 %s（flags=%d）", name, flags)); }
        if (!same1) { diffs++; failures.add(String.format("[naive1] 编码差异 %s（flags=%d）", name, flags)); }

        System.out.printf("%-34s naive0=%-8s naive1=%-8s%n",
                name,
                same0 ? "一致" : "差 ⚠",
                same1 ? "一致" : "差 ⚠");
        if (!same0 || !same1) {
            System.out.println("    android: " + esc(android) + "  (len=" + android.length() + ")");
            if (!same0) System.out.println("    naive0 : " + esc(naive0) + "  (len=" + naive0.length() + ")");
            if (!same1) System.out.println("    naive1 : " + esc(naive1) + "  (len=" + naive1.length() + ")");
        }
    }

    /** naive0：一行 java.util.Base64，完全不处理换行/CRLF。这是大多数人的第一版。 */
    private static String naiveEncode0(byte[] input, int flags) {
        boolean urlSafe = (flags & AndroidBase64.URL_SAFE) != 0;
        boolean noPadding = (flags & AndroidBase64.NO_PADDING) != 0;
        Base64.Encoder enc;
        if (urlSafe) {
            enc = noPadding ? Base64.getUrlEncoder().withoutPadding() : Base64.getUrlEncoder();
        } else {
            enc = noPadding ? Base64.getEncoder().withoutPadding() : Base64.getEncoder();
        }
        return enc.encodeToString(input);
    }

    /** naive1：意识到要换行，于是自己按 76 字符切。注意它仍然在「正好整除」时会多补一个 \n。 */
    private static String naiveEncode1(byte[] input, int flags) {
        boolean noWrap = (flags & AndroidBase64.NO_WRAP) != 0;
        String out = naiveEncode0(input, flags);
        if (noWrap) return out;
        String nl = ((flags & AndroidBase64.CRLF) != 0) ? "\r\n" : "\n";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < out.length(); i += 76) {
            sb.append(out, i, Math.min(i + 76, out.length()));
            sb.append(nl);
        }
        if (out.isEmpty()) return "";
        return sb.toString();
    }

    private static void decodeCase(String name, String input, int flags, String expected) {
        total++;
        String android;
        String naive;
        try {
            android = new String(AndroidBase64.decode(input, flags), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            android = "THROW:" + t.getClass().getSimpleName();
        }
        try {
            naive = new String(naiveDecode(input, flags), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            naive = "THROW:" + t.getClass().getSimpleName();
        }

        boolean androidOk = android.equals(expected);
        boolean same = android.equals(naive);
        if (!same) {
            diffs++;
            failures.add(String.format("解码差异 %s（flags=%d）", name, flags));
        }
        System.out.printf("%-38s android=%s  naive=%s  %s%n",
                name,
                androidOk ? "正确" : "「" + esc(android) + "」",
                esc(naive),
                same ? "" : "← 不一致 ⚠");
    }

    private static byte[] naiveDecode(String input, int flags) {
        boolean urlSafe = (flags & AndroidBase64.URL_SAFE) != 0;
        return urlSafe
                ? Base64.getUrlDecoder().decode(input)
                : Base64.getDecoder().decode(input);
    }

    private static void decodeShouldFail(String name, String input, int flags) {
        total++;
        String android = "OK";
        String naive = "OK";
        try {
            AndroidBase64.decode(input, flags);
        } catch (Throwable t) {
            android = "THROW:" + t.getClass().getSimpleName();
        }
        try {
            naiveDecode(input, flags);
        } catch (Throwable t) {
            naive = "THROW:" + t.getClass().getSimpleName();
        }
        boolean same = android.equals(naive);
        if (!same) {
            diffs++;
            failures.add(String.format("失败方式差异 %s（flags=%d）", name, flags));
        }
        System.out.printf("%-38s android=%-22s naive=%-22s %s%n",
                name, android, naive, same ? "" : "← 不一致 ⚠");
    }

    private static void roundTrip(int flags) {
        total++;
        try {
            byte[] original = "中文测试 round-trip 一二三".getBytes(StandardCharsets.UTF_8);
            byte[] encoded = AndroidBase64.encode(original, flags);
            byte[] decoded = AndroidBase64.decode(encoded, flags);
            boolean ok = java.util.Arrays.equals(original, decoded);
            System.out.printf("flags=%-3d %s%n", flags, ok ? "往返 OK" : "往返失败 ✗");
            if (!ok) {
                diffs++;
                failures.add("往返失败 flags=" + flags);
            }
        } catch (Throwable t) {
            diffs++;
            failures.add("往返抛异常 flags=" + flags + " " + t);
            System.out.printf("flags=%-3d 抛异常: %s%n", flags, t);
        }
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\u0000': sb.append("\\0"); break;
                default:
                    if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }
}
