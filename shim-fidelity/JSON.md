# 宽松 JSON：`GSON` vs `GSONStrict` 的差集

> 结论先说：**这一块不需要写任何实现，也不需要引入第三方库。两行 GsonBuilder 就完全等价。**
> 真正的价值是搞清楚「差集是什么」，这样移植时才知道不能省哪一行。

## Legado 的实现（`GsonExtensions.kt`）

```kotlin
val GSON: Gson by lazy {
    INITIAL_GSON.newBuilder()
        .registerTypeAdapter(ExploreRule::class.java, ExploreRule.jsonDeserializer)
        // ... 6 个 rule 反序列化器
        .create()
}

val GSONStrict: Gson by lazy {
    GSON.newBuilder()
        .setStrictness(Strictness.STRICT)     // ← 唯一差别
        .create()
}
```

**两个实例只差 `Strictness` 一个枚举。**

## `@put` 走双路径（`AnalyzeRule.kt:408 splitPutRule`）

```kotlin
val putJson = GSONStrict.fromJsonObject<Map<String, String>>(putJsonStr).getOrNull()
if (putJson != null) { putMap.putAll(putJson); continue }     // 严格路径

GSON.fromJsonObject<Map<String, String>>(putJsonStr).getOrNull()?.let {
    if (!loggedNonStandardJSON) {
        Debug.log("≡@put 规则 JSON 格式不规范，请改为规范格式")
        loggedNonStandardJSON = true                          // ← 实例字段，只打一次
    }
    putMap.putAll(it)                                          // 宽松路径
}
```

移植时如果只实现了一条路径，**只有 `@put` 里塞非标准 JSON 的源会静默失效**。

## 实测结果（`已实测`，27 个用例）

环境：Gson 2.13.2（Legado 的 `libs.versions.toml` 锁定版本）。

### 默认值不是你以为的那个

```java
Gson.DEFAULT_STRICTNESS = null        // Gson.java:154
// 运行时映射：
jsonReader.setStrictness(strictness == null ? Strictness.LEGACY_STRICT : strictness)  // :1078
```

**`new GsonBuilder().create()` 内部是 `LEGACY_STRICT`，不是 `STRICT`，也不是 `LENIENT`。**

三个档位的实际含义：

| 档位 | 含义 |
|---|---|
| `LENIENT` | 允许大量偏离 JSON 规范 |
| `LEGACY_STRICT` | 为兼容历史行为，允许**少量**偏离（Gson 默认） |
| `STRICT` | 严格 RFC 8259 |

### 差集只有 5 条 —— 这就是全部风险面

| 用例 | LEGACY_STRICT(GSON) | STRICT(GSONStrict) | LENIENT |
|---|:---:|:---:|:---:|
| `{"a":TRUE}` 大写关键字 | ✅ | ❌ | ✅ |
| `{"a":NULL}` 大写关键字 | ✅ | ❌ | ✅ |
| 字符串含裸换行 | ✅ | ❌ | ✅ |
| 字符串含裸 `\u0001` | ✅ | ❌ | ✅ |
| `{"a":"x\'y"}` 转义 `\'` | ✅ | ❌ | ✅ |

**只接受严格、不接受宽松的用例：0 条。** 也就是说 GSON 是 GSONStrict 的严格超集。

### `Strictness` 在 `JsonReader` 里只影响 6 个分支（`已读源码`）

| 行号 | 分支 | STRICT 拒绝 |
|---|---|---|
| `:662` | `consumeNonExecutePrefix()` 前导 `)]}'` | LENIENT 才允许 |
| `:759` | 大写关键字 `TRUE`/`FALSE`/`NULL` | ✅ 拒绝 |
| `:1056` | `NaN` / `Infinity` | LENIENT 才允许（非 STRICT 即可） |
| `:1140` | 字符串内裸控制字符 `U+0000–U+001F` | ✅ 拒绝 |
| `:1612` | `checkLenient()`：单引号、无引号 key/value、注释、`;`/`=` 分隔、尾随逗号 | LENIENT 才允许 |
| `:1790` `:1798` | 转义 `\n` 和 `\'` | ✅ 拒绝 |

**6 个分支全部可枚举 → 差集可以穷举 → 不需要"猜 Gson 到底接受什么"。**

### 一个反直觉的实测结果

`{"a":"1",}`（对象尾随逗号）**连 LENIENT 也不接受**：

```
FAIL {"a":"1",}  -> MalformedJsonException: Expected name at line 1 column 11
```

Gson 只在**数组**尾随逗号上宽松（`["1","2",]` → LENIENT 通过），对象尾随逗号一律报错。
我原本按源码推断成"LENIENT 都过"，实测纠正了。

### 不存在现成的 Java 宽松 JSON 库

查了 Maven Central 的 `json5`：全是 npm 包的 webjar 封装，没有原生 Java 实现。

**而且不需要**——Gson 已经是现成实现，而且 Legado 就在用它。

## 走 A 之后要做什么

**两行代码，零成本：**

```kotlin
// reader-core
val GSON: Gson = GsonBuilder()
    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
    .disableHtmlEscaping()
    .registerTypeAdapter(...)      // 6 个 rule 反序列化器，从原版照搬
    .create()

val GSONStrict: Gson = GSON.newBuilder()
    .setStrictness(Strictness.STRICT)
    .create()
```

再加两个手写的 `JsonDeserializer`（都是纯 JVM，直接照搬）：

| 类 | 作用 | 行数 |
|---|---|---:|
| `MapDeserializerDoubleAsIntFix` | 数字 `3.0` 转成 `long` 而不是 `double` | ~70 |
| `StringJsonDeserializer` | 非字符串的 JSON 值转成 `toString()` | ~15 |
| `IntJsonDeserializer` | int 转换失败时跳过而不是抛异常 | ~20 |

`MapDeserializerDoubleAsIntFix` 很关键：没有它，`{"page":3}` 会被读成 `3.0`，
而 `{{page}}` 插值到 URL 里就变成 `?page=3.0` —— **请求直接 404**。

## 已知差异要写进 `THIRD-PARTY.md`

```
Gson 版本: 2.13.2（与 Legado libs.versions.toml 一致）
风险:      Gson 2.11 之前没有 Strictness API，Legado 的实现依赖 2.11+
           升级 Gson 时这三个 deserializer 的行为可能有变化
验证方式:  跑 shim-fidelity 的 lenient-json 测试（27 用例）
```

## 跑法

```bash
cd shim-fidelity && ./run.sh json
```
