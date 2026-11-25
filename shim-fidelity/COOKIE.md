# Cookie 持久化：`getSubDomain` 是唯一的真风险

> 上一轮我说 `TextUtils.isEmpty` 有坑，那是说重了 —— 它只判 `null || length()==0`，
> 三行就够。**真正有坑的是 Cookie 的分桶键。**

## 为什么关键

Legado 的 Cookie 存储整个围绕一个 key 转：

```kotlin
// CookieStore.setCookie
val domain = NetworkUtils.getSubDomain(url)
appDb.cookieDao.insert(Cookie(domain, cookie))

// CookieStore.getCookie / CookieManager.loadRequest / saveResponse
val domain = NetworkUtils.getSubDomain(url)   // 同一个函数
```

**`getSubDomain` 对同一个站点算出两个不同的 key → 登录态丢失。**
**算出过宽的 key → 两个不同站点的 cookie 混在一起。**

而它只在登录态场景生效 —— 失败表现是"这本书要登录"，而不是报错。
排查时完全看不出跟域名解析有关。

## `getSubDomain` 的实现（`NetworkUtils.kt:211`）

```kotlin
fun getSubDomain(url: String): String {
    val baseUrl = getBaseUrl(url) ?: return url      // 非 http(s) 原样返回
    return runCatching {
        val host = URL(baseUrl).host
        if (isIPAddress(host)) return host            // ← 守卫①
        PublicSuffixDatabase.get()                    // ← 依赖②
            .getEffectiveTldPlusOne(host) ?: host     // ← 空值回落③
    }.getOrDefault(baseUrl)                           // ← 异常兜底④
}
```

**四个分支，每个都会静默改变行为。**

## 实测结果（`已实测`，21 个用例，0 失败）

### ① IP 守卫不能省

```
裸 PSL:  "1.2.3.4"  →  "3.4"      ← 灾难
有守卫:  "1.2.3.4"  →  "1.2.3.4"  ✅
```

`PublicSuffixDatabase` 不知道什么是 IP，只按 label 切。没有 `isIPAddress` 守卫，
`1.2.3.4` 和 `5.6.3.4` 会共用同一个 cookie 桶。

### ② PSL 必须用，不能用"取最后两段"

| host | 朴素（末两段） | PSL |
|---|---|---|
| `www.example.co.uk` | `co.uk` ❌ | `example.co.uk` ✅ |
| `a.b.ck` | `b.ck` ❌ | `a.b.ck` ✅ |
| `x.blogspot.com` | `blogspot.com` ❌ | `x.blogspot.com` ✅ |
| `www.city.kawasaki.jp` | `kawasaki.jp` ❌ | `city.kawasaki.jp` ✅ |

四个全部错。`co.uk` 这类多级后缀会让**整个英国站点共用 cookie 桶**。

### ③ PSL 是 OkHttp jar 内的资源文件，不是纯代码

```
okhttp3/internal/publicsuffix/publicsuffixes.gz   41 KB
okhttp3/internal/publicsuffix/PublicSuffixDatabase.kt
```

加载方式：`PublicSuffixDatabase::class.java.getResourceAsStream("publicsuffixes.gz")`

**纯 JVM 可用**（已实测跑通）。但有两个后果：

- **版本随 OkHttp 绑定** —— 升级 OkHttp 会换 PSL 数据。某些域名可能从 A 桶跳到 B 桶，
  已有 cookie 失效。**这是个静默的跨版本行为变化**，方案里要记一笔。
- `PublicSuffixDatabase` 在 `okhttp3.internal` 包下 —— **是内部 API**，
  OkHttp 不保证跨大版本兼容。Legado 现在这么用，你照搬没问题，
  但升级 OkHttp 主版本时要回归这一块。

### ④ 大小写不归一（继承的原版行为，不是 bug）

```
http://WWW.EXAMPLE.COM/a  →  "EXAMPLE.COM"
http://www.example.com/a  →  "example.com"
```

两个不同的 key。**站点若在中途把域名大小写改了，登录态会丢。**

原版如此。你要不要修？建议**不修**——修了就和 oracle 不一致了，
而这不是你遇到的真实问题。记在案即可。

### ⑤ 私有后缀（blogspot 等）每个子域独立分桶

```
x.blogspot.com  →  x.blogspot.com
y.blogspot.com  →  y.blogspot.com
```

`blogspot.com` 在 PSL 里是私有后缀，所以每个用户站点独立。
这对书源无影响（不会有人用 blogspot 当书源），但说明 PSL 数据是完整的。

### ⑥ 空串抛异常，靠 `runCatching` 兜住

```
裸 PSL:  getEffectiveTldPlusOne("")  →  IllegalArgumentException
有兜底:  getSubDomain("")            →  ""（原样返回）
```

原实现的 `runCatching { }.getOrDefault(baseUrl)` 是必需的，不能"简化掉"。

## 走 A 之后要做什么

| 项 | 做法 | 成本 |
|---|---|---|
| `getSubDomain` | **原码照搬**（只在 `NetworkUtils.kt` 里，无 Android 依赖） | 0 |
| `PublicSuffixDatabase` | 直接用 OkHttp 的，不自己实现 | 0 |
| **PSL 版本锁定** | 在 `THIRD-PARTY.md` 里记录 OkHttp 版本 + PSL 数据日期 | 0 |
| 朴素实现 | **不要写**，写了就是 bug | — |

**结论：这一块几乎零成本 —— 条件是照搬原码，而不是重写。**

真正需要写的是 `CookieManager` / `CookieStore` 的存储层（它们碰 `appDb`），
但那是**存储**问题，不是**分桶**问题：

```
getSubDomain      ← 照搬（0 成本，本测试覆盖）
CookieManager     ← 重写：android.webkit.CookieManager → 纯 JVM（1–2 天）
CookieStore       ← 重写：appDb.cookieDao → SQLite/JDBC（含在上一条）
CacheManager      ← android·LruCache → 自己的 LRU（0.5 天）
```

## 已知差异要写进 `THIRD-PARTY.md`

```
PSL 数据源: OkHttp 4.12.0 内置 publicsuffixes.gz
数据日期:   2023-10-17（取自 jar entry mtime）
风险:       升级 OkHttp 主版本会改变分桶结果 → 已有 cookie 可能失效
验证方式:   升级后跑 shim-fidelity 的 subdomain 测试 + 抽查 3 个真实站点的登录态
```

## 跑法

```bash
cd shim-fidelity && ./run.sh subdomain
```

依赖已放在 `lib/`：`okhttp-4.12.0.jar` / `okio-jvm-3.6.0.jar` / `kotlin-stdlib-1.9.10.jar`。
