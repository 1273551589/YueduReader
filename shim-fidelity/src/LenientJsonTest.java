import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GSON vs GSONStrict 的差集测试。
 *
 * 背景：Legado 的 @put 解析走两条路（AnalyzeRule.kt:408 splitPutRule）：
 *   1. GSONStrict.fromJsonObject<Map<String,String>>(...)   ← 先试严格
 *   2. 失败则回退 GSON.fromJsonObject<Map<String,String>>(...)  ← 再试宽松，并打一次日志
 *
 * 所以「GSON 比 GSONStrict 多接受哪些非法形式」= 真正的兼容风险面。
 * 如果移植时只实现了一条路径，这些形式会静默失效。
 *
 * 关键结论（先给出）：这两个 Gson 实例只差 Strictness 一个开关，
 * 而 Strictness 在 JsonReader 里只影响 6 个分支 —— 全部可枚举。
 */
public class LenientJsonTest {

    /** 与 Legado GSON 的最小等价配置（去掉了 Rule 反序列化器等无关部分） */
    static Gson gson() {
        return new GsonBuilder()
                .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
                .disableHtmlEscaping()
                .create();
    }

    /** 与 Legado GSONStrict 等价 */
    static Gson gsonStrict() {
        return gson().newBuilder()
                .setStrictness(Strictness.STRICT)
                .create();
    }

    /** 与 Legado 的 GSON 默认值对照：GsonBuilder 默认 strictness 是什么 */
    static Gson gsonLegacy() {
        return gson().newBuilder()
                .setStrictness(Strictness.LEGACY_STRICT)
                .create();
    }

    record Case(String name, String json, String expect) {}

    static final List<Case> CASES = List.of(
            // ========== A. 合法 JSON：两边都该过 ==========
            new Case("标准对象", "{\"a\":\"1\",\"b\":\"2\"}", "both"),
            new Case("空对象", "{}", "both"),
            new Case("嵌套对象", "{\"a\":{\"b\":\"c\"}}", "both"),

            // ========== B. Strictness 的 6 个分叉点 ==========
            // ① JsonReader:662  文档开头的 non-execute 前缀（LENIENT 才有）
            new Case("① 前导 )]}'", ")]}'\n{\"a\":\"1\"}", "lenient"),
            // ② JsonReader:759  大写关键字（LEGACY_STRICT 和 LENIENT 允许，STRICT 拒）
            new Case("② 大写关键字 TRUE", "{\"a\":TRUE}", "both"),  // 值是字符串目标，不会解析成 bool
            new Case("② 大写关键字 NULL", "{\"a\":NULL}", "both"),
            // ③ JsonReader:1056  NaN / Infinity（LENIENT 才允许）
            new Case("③ NaN", "{\"a\":NaN}", "lenient"),
            new Case("③ Infinity", "{\"a\":Infinity}", "lenient"),
            // ④ JsonReader:1140  字符串里的裸控制字符（STRICT 拒）
            new Case("④ 字符串含裸换行", "{\"a\":\"line1\nline2\"}", "legacy"),
            new Case("④ 字符串含裸 \\u0001", "{\"a\":\"x\u0001y\"}", "legacy"),
            // ⑤ JsonReader:1612  checkLenient —— 一堆宽松语法
            new Case("⑤ 单引号", "{'a':'1'}", "lenient"),
            new Case("⑤ 未加引号的键", "{a:\"1\"}", "lenient"),
            new Case("⑤ 未加引号的值", "{\"a\":abc}", "lenient"),
            new Case("⑤ 尾随逗号（对象）", "{\"a\":\"1\",}", "neither"),  // 实测：Gson 连 LENIENT 也不接受
            new Case("⑤ 尾随逗号（数组）", "[\"1\",\"2\",]", "lenient"),
            new Case("⑤ 分号分隔", "{\"a\":\"1\";\"b\":\"2\"}", "lenient"),
            new Case("⑤ 等号分隔", "{\"a\"=\"1\"}", "lenient"),
            new Case("⑤ 注释 //", "{//c\n\"a\":\"1\"}", "lenient"),
            new Case("⑤ 注释 /* */", "{/*c*/\"a\":\"1\"}", "lenient"),
            new Case("⑤ 无引号 key 且尾逗号", "{a:\"1\",}", "lenient"),
            // ⑥ JsonReader:1790  转义 \n 和 \' （STRICT 拒）
            new Case("⑥ 转义 \\n（字符串内）", "{\"a\":\"x\\ny\"}", "both"),  // 这是合法转义
            new Case("⑥ 转义 \\'", "{\"a\":\"x\\'y\"}", "legacy"),

            // ========== C. 结构性错误：两边都该失败 ==========
            new Case("C 缺右括号", "{\"a\":\"1\"", "neither"),
            new Case("C 缺冒号", "{\"a\" \"1\"}", "neither"),
            new Case("C 双逗号", "{\"a\":\"1\",,\"b\":\"2\"}", "neither"),
            new Case("C 裸字符串顶层", "abc", "neither"),
            new Case("C 空字符串", "", "neither")
    );

    public static void main(String[] args) {
        Gson lenient = gsonLegacy();   // LEGACY_STRICT —— Gson 的默认
        Gson strict = gsonStrict();    // STRICT
        Gson lenientMode = gson().newBuilder().setStrictness(Strictness.LENIENT).create();

        System.out.println("=== GSON vs GSONStrict 差集测试 ===");
        System.out.println();
        System.out.println("Legado 的实现（GsonExtensions.kt:53）：");
        System.out.println("  GSONStrict = GSON.newBuilder().setStrictness(Strictness.STRICT).create()");
        System.out.println("  GSON       = GSONStrict 的基底，strictness 用 GsonBuilder 默认值");
        System.out.println();
        System.out.println("GsonBuilder 默认值实测：" + describeDefault());
        System.out.println();

        System.out.printf("%-26s %-8s %-8s %-10s %-10s %s%n",
                "用例", "LEGACY", "STRICT", "LENIENT", "期望", "判定");
        System.out.println("-".repeat(92));

        List<String> diffs = new ArrayList<>();
        int onlyLenient = 0, onlyLegacy = 0;

        for (Case c : CASES) {
            boolean okLegacy = tryParse(lenient, c.json());
            boolean okStrict = tryParse(strict, c.json());
            boolean okLenient = tryParse(lenientMode, c.json());

            String verdict;
            if (okLegacy != okStrict) {
                verdict = okLegacy ? "← 差集：仅宽松接受" : "← 差集：仅严格接受(!)";
                diffs.add(c.name());
                onlyLegacy++;
            } else if (okLenient != okLegacy) {
                verdict = okLenient ? "（LENIENT 才过）" : "（LENIENT 反而不过?）";
                onlyLenient++;
            } else {
                verdict = "";
            }

            System.out.printf("%-26s %-8s %-8s %-10s %-10s %s%n",
                    c.name(),
                    okLegacy ? "OK" : "FAIL",
                    okStrict ? "OK" : "FAIL",
                    okLenient ? "OK" : "FAIL",
                    c.expect(),
                    verdict);
        }

        System.out.println();
        System.out.println("=== 汇总 ===");
        System.out.printf("用例总数: %d%n", CASES.size());
        System.out.printf("GSON 与 GSONStrict 结果不同的: %d 条%n", diffs.size());
        System.out.printf("只有 LENIENT 才过的: %d 条%n", onlyLenient);
        System.out.println();
        if (!diffs.isEmpty()) {
            System.out.println("【这就是 @put 解析回退路径的兼容风险面】");
            for (String d : diffs) System.out.println("  · " + d);
        }
        System.out.println();
        System.out.println("结论：");
        System.out.println("  · GSON 与 GSONStrict 的唯一差别是 Strictness 一个枚举");
        System.out.println("  · Strictness 在 JsonReader 里只影响 6 个分支，已全部枚举在注释里");
        System.out.println("  · 移植时两行代码即可完全等价：");
        System.out.println("      GSON       = new GsonBuilder().create()                    // 默认 LEGACY_STRICT");
        System.out.println("      GSONStrict = GSON.newBuilder().setStrictness(STRICT).create()");
        System.out.println("  · 不需要引入任何第三方宽松 JSON 库");
    }

    static String describeDefault() {
        try {
            Gson g = new GsonBuilder().create();
            var f = Gson.class.getDeclaredField("strictness");
            f.setAccessible(true);
            Object v = f.get(g);
            if (v == null) {
                // Gson.java:154  DEFAULT_STRICTNESS = null
                // Gson.java:1078 jsonReader.setStrictness(strictness == null ? LEGACY_STRICT : strictness)
                return "null（未显式设置）→ 运行时映射为 LEGACY_STRICT（Gson.java:1078）";
            }
            return String.valueOf(v);
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    static boolean tryParse(Gson g, String json) {
        try {
            java.lang.reflect.Type t =
                    new TypeToken<LinkedHashMap<String, Object>>() {}.getType();
            Map<String, Object> m = g.fromJson(json, t);
            return m != null;
        } catch (Throwable t) {
            // 数组也试一下，因为有些用例是顶层数组
            try {
                Object o = g.fromJson(json, Object.class);
                return o != null;
            } catch (Throwable t2) {
                return false;
            }
        }
    }
}
