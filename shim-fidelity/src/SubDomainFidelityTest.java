import okhttp3.internal.publicsuffix.PublicSuffixDatabase;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Cookie 分桶（getSubDomain）保真度测试。
 *
 * Legado 的 Cookie 存储以 getSubDomain(url) 的返回值为 key：
 *   - CookieStore.setCookie   → appDb.cookieDao.insert(Cookie(domain, cookie))
 *   - CookieStore.getCookie   → 用同一个 domain 去查
 *   - CookieManager.loadRequest / saveResponse 同样用 getSubDomain
 *
 * 所以 getSubDomain 一旦对同一个站点算出两个不同的 key，登录态就丢；
 * 算出过于宽泛的 key，两个不同站点的 cookie 就会混在一起。
 *
 * 本测试对照 Legado 的 NetworkUtils.getSubDomain 实现，验证四条边界行为。
 * 依赖：okhttp 4.12.0 + okio-jvm 3.6.0 + kotlin-stdlib 1.9.10（见 run.sh）
 */
public class SubDomainFidelityTest {

    private static final List<String> failures = new ArrayList<>();
    private static int total = 0;

    // ==================== Legado 原实现（NetworkUtils.kt:191-222）====================

    /** NetworkUtils.getBaseUrl(url) */
    static String getBaseUrl(String url) {
        if (url == null) return null;
        if (url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8)) {
            int index = url.indexOf('/', 9);
            return index == -1 ? url : url.substring(0, index);
        }
        return null;
    }

    /** NetworkUtils.getSubDomain(url) —— 逐行对照原实现 */
    static String getSubDomain(String url) {
        String baseUrl = getBaseUrl(url);
        if (baseUrl == null) return url;
        try {
            URL mUrl = new URL(baseUrl);
            String host = mUrl.getHost();
            // 原实现这里有 isIPAddress(host) 守卫
            if (isIPAddress(host)) return host;
            // PublicSuffixDatabase 处理域名
            String e = PublicSuffixDatabase.Companion.get().getEffectiveTldPlusOne(host);
            return e != null ? e : host;
        } catch (Throwable t) {
            return baseUrl;
        }
    }

    /** NetworkUtils.isIPAddress（hutool Validator.isIpV4 的等价实现） */
    static boolean isIPAddress(String host) {
        if (host == null || host.isEmpty()) return false;
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
            int v = Integer.parseInt(p);
            if (v < 0 || v > 255) return false;
        }
        return true;
    }

    // ==================== 测试 ====================

    public static void main(String[] args) {
        System.out.println("=== Cookie 分桶（getSubDomain）保真度测试 ===");
        System.out.println();

        section("A. 正常站点：同一站点的不同子域必须归到同一个 key");
        sameBucket("https://www.example.com/book/1", "https://example.com/x", "example.com");
        sameBucket("https://m.biquge.com.cn/a", "https://www.biquge.com.cn/b", "biquge.com.cn");
        sameBucket("https://a.b.example.com/x", "https://example.com/y", "example.com");
        sameBucket("https://api.github.com/repos", "https://github.com/x", "github.com");
        sameBucket("https://www.legado.cc/a", "https://legado.cc/b", "legado.cc");

        section("B. 多级后缀（PSL 必须生效，否则会跨站串 cookie）");
        sameBucket("https://www.example.co.uk/a", "https://example.co.uk/b", "example.co.uk");
        sameBucket("https://shop.example.co.uk/a", "https://www.example.co.uk/b", "example.co.uk");

        section("C. IP 地址：必须返回原文，不能被 PSL 砍成 3.4");
        expect("getSubDomain(\"http://1.2.3.4/x\")", getSubDomain("http://1.2.3.4/x"), "1.2.3.4");
        expect("getSubDomain(\"http://192.168.3.100:8080/a\")",
                getSubDomain("http://192.168.3.100:8080/a"), "192.168.3.100");
        // 反证：如果没有 isIPAddress 守卫，PSL 会给什么
        String rawPsl = PublicSuffixDatabase.Companion.get().getEffectiveTldPlusOne("1.2.3.4");
        System.out.println("    （反证：裸 PSL 对 1.2.3.4 返回 " + rawPsl + "，所以守卫必须存在）");
        check("[反证] 裸 PSL 不是 1.2.3.4", !"1.2.3.4".equals(rawPsl), true);

        section("D. 大小写：PSL 不归一，Legado 也不归一 —— 这是继承来的行为");
        String upper = getSubDomain("http://WWW.EXAMPLE.COM/a");
        String lower = getSubDomain("http://www.example.com/a");
        System.out.println("    WWW.EXAMPLE.COM -> " + upper);
        System.out.println("    www.example.com -> " + lower);
        // 记录实际行为而不是"应该怎样"：两个 key 不同 = cookie 会分成两桶
        check("大小写不同 → 分到不同桶（原版行为）", !upper.equals(lower), true);
        System.out.println("    ⚠ 后果：站点若在中途换大小写，登录态会丢。这不是 bug，是继承的原版行为。");

        section("E. 私有后缀（blogspot 等）：每个子域独立分桶");
        String x = getSubDomain("http://x.blogspot.com/a");
        String y = getSubDomain("http://y.blogspot.com/a");
        System.out.println("    x.blogspot.com -> " + x);
        System.out.println("    y.blogspot.com -> " + y);
        check("不同 blogspot 子域分到不同桶", !x.equals(y), true);

        section("F. 野卡后缀（*.ck）：PSL 与朴素实现差异最大的地方");
        // 朴素做法（取最后两段）会把 a.b.ck 和 x.y.ck 都算成 b.ck / y.ck
        sameBucket("http://a.b.ck/x", "http://a.b.ck/y", "a.b.ck");
        String ck = getSubDomain("http://ck/a");
        System.out.println("    ck -> " + ck + "   （PSL 返回 null → 回落 host）");

        section("G. 非 http(s) 输入：getBaseUrl 返回 null → 原样返回 url");
        expect("getSubDomain(\"ftp://x.com/a\")", getSubDomain("ftp://x.com/a"), "ftp://x.com/a");
        expect("getSubDomain(\"file:///tmp/a\")", getSubDomain("file:///tmp/a"), "file:///tmp/a");

        section("H. 空串：PSL 直接抛异常，原实现靠 runCatching 兜住");
        String empty = getSubDomain("");
        System.out.println("    getSubDomain(\"\") -> \"" + empty + "\"");
        check("空串不抛异常（被 runCatching 兜住）", true, true);
        try {
            PublicSuffixDatabase.Companion.get().getEffectiveTldPlusOne("");
            check("[反证] 裸 PSL 对空串抛异常", false, true);
        } catch (Throwable t) {
            check("[反证] 裸 PSL 对空串抛 " + t.getClass().getSimpleName(), true, true);
        }

        section("I. 朴素实现（取最后两段）会错在哪 —— 这些是「预期差异」，不是测试失败");
        // 这四个用例的目的是证明朴素实现是错的，所以 naive != PSL 正是期望结果。
        // 如果哪天它们相等了，反而说明 PSL 没生效。
        String[][] hostsToBite = {
                {"www.example.co.uk", "co.uk", "example.co.uk"},
                {"a.b.ck", "b.ck", "a.b.ck"},
                {"x.blogspot.com", "blogspot.com", "x.blogspot.com"},
                {"www.city.kawasaki.jp", "kawasaki.jp", "city.kawasaki.jp"},
        };
        int proven = 0;
        for (String[] row : hostsToBite) {
            String h = row[0];
            String naive = naiveLastTwoLabels(h);
            String correct = PublicSuffixDatabase.Companion.get().getEffectiveTldPlusOne(h);
            boolean naiveIsWrong = !naive.equals(correct);
            total++;
            if (naiveIsWrong) {
                proven++;
                System.out.printf("    OK     %-24s 朴素=%-18s PSL=%-18s ← 朴素确实错%n",
                        h, naive, correct);
            } else {
                failures.add("预期朴素实现会错，但 " + h + " 上它对了（PSL 可能未生效）");
                System.out.printf("    FAIL   %-24s 朴素=%-18s PSL=%-18s ← 朴素居然对了%n",
                        h, naive, correct);
            }
            // 顺带校验 PSL 的结果就是硬编码的期望值
            if (!correct.equals(row[2])) {
                failures.add(h + " 的 PSL 结果 " + correct + " 与期望 " + row[2] + " 不符");
            }
        }
        System.out.printf("    → 4 组朴素实现在这 %d 组上全部出错，PSL 全部正确%n", proven);

        // ==================== 汇总 ====================
        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.printf("用例总数: %d%n", total);
        System.out.printf("失败: %d%n", failures.size());
        if (!failures.isEmpty()) {
            System.out.println();
            for (String f : failures) System.out.println("  · " + f);
        }
        System.out.println();
        System.out.println("结论：getSubDomain 不能用「取最后两段」实现，必须用 PublicSuffixDatabase。");
        System.out.println("      PSL 是 OkHttp jar 内的 publicsuffixes.gz 资源，纯 JVM 可用，但版本随 OkHttp 绑定。");
    }

    // ==================== 工具 ====================

    static String naiveLastTwoLabels(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    static void section(String s) {
        System.out.println();
        System.out.println("--- " + s + " ---");
    }

    static void check(String name, boolean actual, boolean expected) {
        total++;
        boolean ok = actual == expected;
        if (!ok) failures.add(name);
        System.out.println("    " + (ok ? "OK  " : "FAIL") + "  " + name);
    }

    static void expect(String name, String actual, String expected) {
        total++;
        boolean ok = expected.equals(actual);
        if (!ok) failures.add(name + " 实际=" + actual + " 期望=" + expected);
        System.out.printf("    %s  %s -> %s%s%n",
                ok ? "OK  " : "FAIL", name, actual, ok ? "" : "  （期望 " + expected + "）");
    }

    static void sameBucket(String u1, String u2, String expected) {
        total++;
        String d1 = getSubDomain(u1);
        String d2 = getSubDomain(u2);
        boolean ok = d1.equals(d2) && d1.equals(expected);
        if (!ok) failures.add(u1 + " / " + u2 + " 分桶不一致: " + d1 + " vs " + d2);
        System.out.printf("    %s  %-24s & %-24s -> %s%n",
                ok ? "OK  " : "FAIL", u1, u2, d1);
    }
}
