# shim 保真度：`android.util.Base64` → 纯 JVM

> 这份目录解决一个问题：走 A（原码照搬 Legado）之后，`android.util.Base64`
> 必须有一个纯 JVM 替代品，而这个替代品**必须逐字节等价**，不能只是"能编过"。

## 为什么这件事重要

走 A 之后，`reader-core` 和 oracle 是同一份源码，差分测试会变成同义反复。
真正的风险从「我的调度层写错了」变成「**我的 shim 和 Android 真实实现不一样**」。

`java.base64` 在 62 条样本源里出现 **25 次**，是所有 JS API 里最高频的。
base64 通常用于解码接口返回的混淆数据 —— **多一个 `\n`、少一个 `=`，
会让 JSON 解析失败或字符串比对失败，而且不会报错，只会"抽不到内容"**。

## 文件

| 文件 | 作用 |
|---|---|
| `src/AndroidBase64.java` | AOSP API 34 `android.util.Base64` 的移植（逐行对照 Encoder/Decoder 状态机） |
| `src/Base64FidelityTest.java` | Base64 保真度测试：AOSP 移植 vs 两种「朴素替代」 |
| `src/SubDomainFidelityTest.java` | Cookie 分桶（`getSubDomain`）保真度测试 |
| `COOKIE.md` | Cookie 分桶的实测结论（另一半 shim 风险） |
| `lib/` | okhttp 4.12.0 / okio-jvm 3.6.0 / kotlin-stdlib 1.9.10 |
| `run.sh` | 一键编译 + 运行 |

## 怎么跑

```bash
cd shim-fidelity
./run.sh            # 全部
./run.sh base64     # 只跑 Base64
./run.sh subdomain  # 只跑 Cookie 分桶
```

## 两个 shim，两种风险

走 A 之后，`reader-core` 和 oracle 是同一份源码，差分测试会变成同义反复。
真正需要独立验证的只有 **shim 保真度**。目前覆盖两个：

| shim | 风险等级 | 结论 |
|---|---|---|
| `android.util.Base64` | 🔴 高 | 朴素替代 40/49 用例错；必须照 AOSP 移植 |
| `NetworkUtils.getSubDomain` | 🟡 中 | **照搬原码即可**，但 PSL 依赖随 OkHttp 版本绑定 |
| `TextUtils.isEmpty` | 🟢 无 | 只判 `null \|\| length()==0`，三行，无坑 |

## 实测结果（`已实测`，2026-11-25）

49 个用例 + 1203 组长度自校验。

### 编码：`java.util.Base64` **不能**直接顶替

| 做法 | 结果 |
|---|---|
| `Base64.getEncoder().encodeToString()` | ❌ **40/49 用例不一致** |
| `+ 自己按 76 字符补换行` | ✅ 编码侧全部一致 |

典型差异（1 字节输入 `"x"`）：

```
android DEFAULT : "eA==\n"   (len=5)
naive0         : "eA=="      (len=4)   ← 少了换行
```

**关键点：`android.util.Base64` 在 `NO_WRAP` 未设时，即使输出不足 76 字符，
末尾也会补一个 `\n`。** 这不是"每 76 字符换行"能描述的，它有个尾部补行分支
（AOSP 源码 `:716`：`else if (do_newline && op > 0 && count != LINE_GROUPS)`）。

### 解码：差异更大，而且是静默的

| 输入 | android | `java.util.Base64` |
|---|---|---|
| `"YWJj\nZGVm"` | `abcdef` ✅ | **抛 `IllegalArgumentException`** |
| `"YWJj\r\nZGVm"` | `abcdef` ✅ | **抛 `IllegalArgumentException`** |
| `"YWJj ZGVm"` | `abcdef` ✅ | **抛 `IllegalArgumentException`** |
| `"YWJj!ZGVm"` | `abcdef` ✅（跳过 `!`） | **抛 `IllegalArgumentException`** |
| `"YWJj\u0000ZGVm"` | `abcdef` ✅（跳过 `\0`） | **抛 `IllegalArgumentException`** |
| `"a+b/"` + `URL_SAFE` | 空结果 | **抛 `IllegalArgumentException`** |

**Android 的解码器极其宽容**：非法字符静默跳过，只保留字母表内的字符。
`java.util.Base64` 的 basic decoder 则严格拒绝。

这是最危险的一条：**源里的 base64 数据带着换行（很常见，因为服务端可能用了
带换行的 encoder），用 `java.util.Base64` 解码直接抛异常，而异常会被
`runCatching` 吞掉，最后表现为"这本书抽不到内容"** —— 排查时完全看不出跟 base64 有关。

### flag 是位组合，不是枚举

```java
NO_WRAP | URL_SAFE              // 合法
NO_WRAP | NO_PADDING | URL_SAFE // 合法
CRLF | URL_SAFE                 // 合法
```

写成 `when (flags) { DEFAULT -> ...; URL_SAFE -> ... }` 会漏掉所有组合。
`AndroidBase64` 里全部用 `flags & X != 0` 判断。

## 与 AOSP 源码的对应关系

| 行为 | AOSP 位置 |
|---|---|
| `LINE_GROUPS = 19`（19×4 = 76 字符行宽） | `Encoder.LINE_GROUPS`，`:550` |
| 尾部补换行分支 | `Encoder.process()` `:716` |
| `count` 计数（非"每 76 字符"） | `:652` `:674` |
| 解码 0–5 状态机 + 收尾 | `Decoder.process()` `:265` 起 |
| `SKIP = -1` / `EQUALS = -2` | `:226` `:227` |
| DECODE 表把 0x00–0x20 全部设为 `-1` | `:183` |

## ⚠️ 待真机确认（本文件尚未做的事）

以下是我**读源码推断**、但**没有用 instrumentation 在真机上验证**的：

1. **`NO_PADDING` 与尾部换行的交互** —— 源码看是独立的两个 `if`，但我没跑过真机
2. **流式 `encode(input, offset, len, flags)` 的跨调用行为** —— 我只实现了单次调用版本；
   Legado 的 `EncoderUtils` 用的是单次版本，所以 P0 够用，但记录在此
3. **超长输入（>1MB）时 AOSP 的 `maxOutputSize` 溢出行为**
4. **`decode` 遇到 0x80–0xFF 的高位字节** —— DECODE 表长度是 256，我按 `& 0xff` 索引，
   预期是 SKIP，但需要真机确认

**验证方式**（2–3 人日，见方案 §3.2 的「真机 instrumentation 对比套件」）：

```
真机 instrumentation：
  读本地 fixture → 跑 android.util.Base64（各种 flag × 各种输入）
  → 输出 JSON（含十六进制字节） → adb pull
  → 和 AndroidBase64 的输出逐字节 diff
```

## 结论

走 A 之后，`android.util.Base64` 的替代品**不能是 30 行的 flag 映射**。
按 AOSP 移植后约 200 行，能过 1203 组长度自校验，但解码宽容度仍需真机确认。

方案 §3.2 里「Base64 shim 1–2 人日」这个估算是对的 —— 但前提是**照源码移植**，
不是照文档写个映射表。
