# 开发过程记录

> 这份文档是逐轮迭代的工作日志，记录了每个 bug 怎么被发现、怎么验证的。
> 想了解「现在能用什么」看根目录 [README.md](../README.md)；
> 想了解「每个版本改了什么」看 [CHANGELOG.md](../CHANGELOG.md)。

---


> 依据 `方案.md`（v0.11）与 `shim-fidelity/` 的实测结论实现的**可运行首版**。
> 服务器 / NAS 书库 / Web 端**按你的要求全部搁置**，本文末尾列出所有未完成项。

---

## 一、首版做了什么

一个能装、能搜、能读的 Android App。规则引擎按 Legado 语义自己实现（Kotlin），
不依赖原版源码，也不做 WebView 兜底。

```
android/                     可编译的 Android 工程（AGP 9.1 / Gradle 9.3.1 / Kotlin 2.3）
  app/src/main/java/com/ayanami/reader/
    core/
      model/BookSource.kt    Legado 书源实体（22 顶层字段 + 6 rule 子对象）
      model/Book.kt          搜索结果 / 详情 / 目录 / 正文模型
      rule/RuleAnalyzer.kt   `&&` `||` `##正则##` `@put/@get` `@css/@json/@xpath/@js` 切分
      rule/AnalyzeRule.kt    有状态规则求值（CSS / JSON path / XPath / {{}} 插值）
      rule/LenientJson.kt    GSONStrict 严格 → GSON 宽松 的双路径
      rule/RuleError.kt      引擎错误枚举（11 类，与 UI 状态分开）
      rule/UrlHelper.kt      相对地址解析
      net/HttpFetcher.kt     HTTP + 编码猜测 + 302 跟随 + 每域名最小间隔
      pipeline/SourceSession.kt   搜索 → 详情 → 目录 → 正文（阶段级实例隔离）
      pipeline/SourceImporter.kt  数组 / 单对象 / 分组对象三种形态导入 + 按 URL 去重
      pipeline/SourceChecker.kt   体检：一次抽样 / 六个 UI 状态 / 总耗时 / 卡在哪一步
    mod/ModLoader.kt         声明式 MOD（theme / sources / categories），零代码执行
    data/Repository.kt       JSON 持久化（书源 / 体检结果 / 进度 / MOD）
    ui/                      Compose：书架 / 搜索 / 详情 / 阅读 / 书源 / MOD
sources/sfacg-lightnovel.json    轻小说书源（SF轻小说，非 R18）
mods/warm-theme/                 主题型 MOD 示例
mods/light-novel-sources/        书源包 MOD 示例
dist/*.mod.zip                   上面两个 MOD 的可安装包
docs/screenshots/                真机（模拟器）截图
```

### 已实测通过的三条链路

| 链路 | 结果 |
|---|---|
| 书源导入 | JSON 数组 / 单对象 / 分组对象都能解析，按 `bookSourceUrl` 去重，坏条目单独列出 |
| 体检 | `抽样可读` / 1514ms / **5 次请求** / `卡在=done`，四步串行 |
| 真实阅读 | 搜索 26 条 → 详情 → 目录 421 章 → 正文 **24374 字**（合并分页后） |

**81 个用例全绿**：切分 9 + `@js`/正则 18 + JS 中断 7 + 预算隔离 3 + 阶段上下文 4 + 分页 6 + 端到端 8 + 持久化 7 + 失败计数 6 + 坏正则 5 + 规则警告 4 + **旧格式兼容 4**。

---

## 二、已验证的书源

`sources/sfacg-lightnovel.json` —— **SF轻小说**，非 R18。

选择它的原因：`linovelib`（哔哩轻小说）**搜索必过人机验证码**，`wenku8` 直接 403，
`linovel.net` 连不上。SF 的 `s.sfacg.com/?Key=` 搜索是纯 HTML，能直连解析。

**实测抓取**（《Sword Art Online刀剑神域》）：

```
搜索：26 条命中
详情：Sword Art Online刀剑神域 / 天闻角川 / 游戏 / 3698559字[连载中]
目录：421 章（单页，该源没有目录分页）
正文：序章 24374 字（分页合并后；合并前只有 409 字）
```

⚠️ 关于「421 章只拿到首页」那条 —— **已核实是我之前说错了**。
实测 `MainIndex/` 单页里就有 29 个 `catalog-list` 块、421 个 `<li>`，没有分页标记。
`nextTocUrl` 对 SF 无用，但对别的站仍然必要，v0.2 已实现（见下）。

规则里踩到并修正的坑（都写进代码注释了）：

1. **`##正则##` 的语义是「替换匹配片段」，不是「提取」**
   → 想只留作者，正则必须吃掉整段再用 `$1` 还原，否则会留下前后残文
2. **移动 UA 会被 302 到 `m.sfacg.com`**，选择器完全不同
   → `HttpFetcher` 默认用桌面 UA，书源也可以自带 header 覆盖
3. **`HttpURLConnection` 不跨协议跟随 302**，要手动读 `Location` 再请求一次
4. **书源里的 `header` 字段必须真的发出去**，漏了会被站点判成爬虫

---

## 三、MOD 功能（按方案 §8.5④ 路线 A：纯声明式）

风险边界：MOD **不能带可执行代码**。理由是方案里那条硬约束 ——
「书源是你导入的」这个信任模型不能扩大到「MOD 作者」，否则 §6 所有"不做沙箱"的论证失效。

### 目录结构

```
mods/
  my-theme/
    mod.json      { id, name, version, author, type, entry, priority }
    theme.json    颜色 / 圆角 / 间距
  my-sources/
    mod.json
    sources/xxx.json   Legado 格式书源包（装完自动并入）
  my-categories/
    mod.json
    mapping.json
```

### 加载规则（照 MC 的教训）

- 启动扫 `mods/`，`mod.json` 坏的**跳过并记日志**，不崩
- 未知 `type` 直接忽略（前向兼容）
- 同 type 按 `priority` 升序，后覆盖前者
- **不支持热加载**，重启生效
- 每个 MOD 目录可单独删除 = 禁用

### 实测

| 验证项 | 结果 |
|---|---|
| 主题型 MOD | 装完重启，背景/主色/圆角全部换成 MOD 里的值（截图对比明显） |
| 书源包 MOD | 装完重启，书源自动并入列表，体检直接可用 |
| 坏 `mod.json` | 跳过并计数，App 正常启动 |
| 未知 type | 静默忽略 |
| 缺 `id` | 跳过并记警告 |

---

## 四、还没完成的地方

按"会影响可用性"排序。**这些不是遗漏，是刻意搁置或首版砍掉的。**

### 4.1 引擎能力缺口

| 缺什么 | 影响 | 为什么首版不做 |
|---|---|---|
| ~~`@js` 脚本规则~~ | ✅ **v0.2 已支持** | Rhino 1.7.15，8 个 `java.*` API |
| `@js` 里的 `Packages.*` | 1/62 源不可用 | 安全策略禁用（`javax.crypto` 等），会报 `JsThrown` |
| `@js` 里的 `webView` / `startBrowser` | 少数过 CF 的源不可用 | 显式抛错并记 `lastUnsupported`，不静默 |
| **`{{}}` 跨阶段变量回退** | 少数靠 `@put` 传登录 token 的源会失败 | 只实现了"阶段内 + 共享表"，没有实现 `chapter→book→ruleData→source` 四级回退 |
| ~~目录分页 `nextTocUrl`~~ | ✅ **v0.2 已实现** | 最多 50 页，防环，失败保留已抓内容 |
| ~~正文分页 `nextContentUrl`~~ | ✅ **v0.2 已实现** | 最多 20 页，防环，失败用手上内容 |
| **`webView` / `webJs`** | 需过 Cloudflare 的源不可用 | 明确不做（方案 §2.2 定为服务端返回 `UNSUPPORTED_WEBVIEW`） |
| **`Packages.*`（含 javax.crypto）** | 1/62 源不可用 | 安全策略禁掉，代价可接受 |
| ~~正则超时保护~~ | ✅ **v0.2 已实现** | 800ms 上限 + 泄漏熔断；注意 JDK 9+ 已优化常见 ReDoS 形态 |
| **`stringRuleCache` 无上限** | 长会话可能缓慢吃内存 | 方案 §3.3.1 的修复点，首版缓存随实例销毁，风险被阶段隔离压小了 |

### 4.2 功能缺口

| 缺什么 | 说明 |
|---|---|
| **书架 UI** | 只记录阅读进度到 `progress.json`，列表页是占位 |
| **跨源聚合的源切换** | 搜索已按「书名+作者」聚合，但 UI 上只打开第一个源，不能切换 |
| **阅读设置** | 字号 / 行距 / 背景色 / 翻页模式全都没有 |
| **阅读位置续读** | 进度已存，但没有"打开书跳回上次位置" |
| **目录搜索 / 卷展开折叠** | 421 章的目录只能硬滚 |
| **书源编辑** | 只能导入/删除/启停，不能改规则（方案 §5.8 的编辑器是 3–5 人日） |
| **URL 订阅** | 只支持文件 / 粘贴（方案明确延后） |
| **封面图加载** | `coverUrl` 已抓，UI 不显示（没接图片库） |
| **图片型源渲染** | 图片列表已抓到，UI 只列 URL |
| **连续失败计数 → 建议删除** | 体检结果存了，但没有"连续 3 次失败"的统计列 |
| **定时巡检** | 只有手动"全部重测" |
| **深色模式手动切换** | 只跟随系统 |
| **书源规则里 `@js` 的提示 UI** | 体检会显示"不支持的能力"，但没引导用户怎么办 |

### 4.3 按你的要求搁置的

- **服务器 / NAS 书库扫描**（方案 P1）—— 只读目录、三种格式打开、预切章索引，全部未做
- **Web 端 / Vue 前端**（方案 P1）—— 未做
- **登录 / 自有域名 / 同步**（方案 §8.5 ①②③）—— 未做
- **Tailscale 组网**—— 未做
- **跨源分类聚合**（方案 §8.5⑤）—— MOD 的 `categories` 类型已能解析映射，但没有聚合 UI

### 4.4 工程债

| 项 | 状态 |
|---|---|
| 持久化 | **JSON 文件**，不是方案 §2.2 选定的 SQLite。结构已按可迁移 shape 设计，换 Room 只改 `Repository` |
| 并发 | 搜索/体检都是**串行**，一次只能跑一个源。方案 §3.2.5 允许的上限是 4 并发的不同域名 |
| 网络库 | `HttpURLConnection`，不是 OkHttp。Cookie 分桶、TLS 配置、EventListener 计时都没有 |
| 依赖版本 | lifecycle / navigation 钉在 2.9.x，因为 BOM 2026.03 的 2.11 要求 `compileSdk 37`，而 AGP 9.1 只到 36 |
| 单元测试 | 81 个（12 个测试类）；仍没有 62 条源的 fixture 回归池 |
| 签名 | release 用 debug key |
| `minSdk` | 26（Android 8.0），未在低于 API 36 的设备上验证 |
| ProGuard / R8 | 全关 |

---

## 五、怎么跑

> ✅ 已加 Gradle Wrapper（`gradlew`），clone 下来不需要预装 Gradle。

```bash
cd android
./gradlew testDebugUnitTest      # 81 个测试（含 8 条真实联网端到端）
./gradlew assembleDebug          # 产出 debug APK
```

> `local.properties` 里的 `sdk.dir` 是**本机路径**，已加入 `.gitignore`。
> 换机器需要自己建一个，或用 `ANDROID_HOME` 环境变量。

```bash
# 环境（这台机器上已验证可用）
export JAVA_HOME=~/.local/opt/jdk-17.0.21.1+2/Contents/Home
export ANDROID_HOME=/Users/mac/android-sdk
GRADLE=~/.gradle/wrapper/dists/gradle-9.3.1-bin/23ovyewtku6u96viwx3xl3oks/gradle-9.3.1/bin/gradle

cd android
$GRADLE assembleDebug          # 出 APK
$GRADLE testDebugUnitTest      # 81 个用例（含真实联网的端到端）
```

APK：`android/app/build/outputs/apk/debug/app-debug.apk`（约 12.7MB）

跳过联网测试：`-DskipOnline=1`

### 在 App 里走一遍

1. 首页点「书源」→「选文件导入」，选 `sources/sfacg-lightnovel.json`
2. 回到列表点「测」→ 应显示 `抽样可读` + 耗时 + "四步通"
3. 回首页点「搜索」→ 输关键词（中文需用输入法，`adb input text` 不支持中文）
4. 点「打开」→ 详情 + 目录 → 点任意一章 → 正文
5. 装 MOD：把 `dist/warm-theme.mod.zip` 放到设备 → MOD 页「装 MOD 包」→ 重启生效

---

## 六、下一步建议（按性价比）

1. **书源编辑 + 改完即测** —— 现在遇到不支持的规则只能干看着
3. **SQLite 替换 JSON** —— 换成 Room 后，体检历史、失败计数、离线章节才有地方放
4. **62 条源的 fixture 回归池** —— 现在只有 1 条真源在跑

---

## 七、与方案的关系

`方案.md` 是 v0.11，意见是"P0 先做 core + fixture 回归，33–59 人日"。
这次初版走的是**另一条路**：不移植原版 `AnalyzeRule`，而是按它的语义重写，
把 P0 的"shim 保真"问题整个绕开了 —— 代价是兼容性边界比原版窄（见 §4.1）。

`shim-fidelity/` 的三份实测（Base64 40/49 不一致、Cookie 分桶 0 失败、宽松 JSON 差集 5 条）
在这个初版里**只用到第三条**（`LenientJson` 的双路径）。
前两条的结论对"走 A"有意义，对"重写"不适用 —— 这是两条路线的分野，没对错，只是取舍不同。

---

## 八、版本修复记录

### v0.2 新增（对应你提的三条）

#### ① `@js` 支持 —— 已打通

`core/rule/JsRuntime.kt` + `core/pipeline/HttpJsBridge.kt`，嵌入 Rhino **1.7.15**。

选 1.7.15 而不是 1.9.x：1.9 编译到 Java 11+ 字节码且走 `java.lang.invoke` 路径，
Android 上不一定有；1.7.15 是 Java 8 字节码，稳。

**只绑你点名的 8 个 API**（62 条样本的真实调用全在这几个）：

| API | 调用次数 |
|---|---|
| `java.base64Encode` / `base64Decode` | 25 |
| `java.ajax` / `connect` | 14 |
| `java.get` | 14 |
| `java.encodeURI` | 6 |
| `java.getString` | 6 |
| `java.put` | 4 |
| `java.getElements` / `getElement` | 3 |
| `java.log` / `toast` | 10 |

`java.ajax` **没有后置** —— 一开始就接了，因为它只依赖已经有的 `HttpFetcher`。

三个实现要点：

- **超时靠丢弃线程**，不靠 interrupt。Rhino 在死循环里不响应 interrupt，
  所以是「独立线程池跑 + 超时后放弃」。被放弃的线程计数在 `aborted`，可监控。
- **编译缓存按实例存**，`CompiledScript` 复用。
- **`result` 变量要回 scope 里找**。裸写 `result = 'x'` 是挂到全局 scope 的，
  不是脚本返回值 —— 这是 Legado 的约定，一开始漏了，测试抓出来的。

**Base64 直接用 `android.util.Base64`**（`AndroidBase64Compat`）。
`shim-fidelity/` 里那 49 个用例的结论只在非 Android 环境才需要照 AOSP 移植；
跑在 App 里用真品，保真问题直接消失。

#### ② `nextTocUrl` / `nextContentUrl` 分页 —— 已实现

- 目录：最多 50 页，`visitedPages` 防环，某页 404 时**保留已抓到的章**而不是整体失败
- 正文：最多 20 页，`visited` 防环，下一页抓不到就**用手上的内容**返回

**实测效果**（SF 真实链路）：同一章正文从 **409 字 → 24374 字**。
真机上《重生为凤凰的我正在带孩子》第一章 **53165 字**。

这条确实是「能不能读完一本书」的分水岭 —— 谢谢你指出。

#### ③ 正则超时 —— 已实现，但**你举的例子不成立**

`core/rule/RegexGuard.kt`：独立线程池 + 800ms 上限 + 泄漏计数 + 熔断（12 次后停掉正则）。

**但实测发现 `(a+)+$` 在 JDK 17 上不慢**：

| pattern | 输入 | 耗时 |
|---|---|---|
| `(a+)+$` | 40×`a` + `!` | **2ms** |
| `(a+)+b` | 28–250×`a` | 0–3ms |
| `(a\|aa)+$` | 40×`a` + `!` | 1ms |
| `(\w+\s?)*$` | 40×`a` | 2ms |
| **`(.*a){30}`** | **100×`a`** | **15399ms** |

JDK 9+ 对「单字符重复的嵌套量词」做了度量化优化，教科书那批 ReDoS 例子已经打不动了。
真正会卡的是 `(.*a){30}` 这种 —— 我拿它当测试用例，**测试才不是假绿**。

这一条不影响功能，但影响你对风险的判断：如果按 `(a+)+$` 估风险，会以为正则超时很罕见。

---

### v0.3.0 修复的一个**真 bug**（`已实测`）

> 原属 §四，现已归入版本记录。

v0.2.0 的 JS 超时**从来没生效过**：

```kotlin
ctx.setInstructionObserverThreshold(10_000)   // 设了闹钟
// ← 但没有任何地方重写 observeInstructionCount，等于没接电话
```

实测（Rhino 1.7.15 / JDK 17）：

```
while(true){} 跑在独立线程 → 2 秒后 interrupt()
→ 线程状态仍是 RUNNABLE，脚本继续跑，永远不停
```

`future.cancel(true)` 只是把 Future 标记成已取消，脚本还在跑。
**后果**：`js-rule` 线程池只有 2 个线程，一个恶意/写错的 `while(true)` 源
跑两次就能把整个 `@js` 能力饿死。

修复见 `InterruptibleContextFactory.kt`：继承 `Context` 并**重写 `observeInstructionCount`**，
超出预算就抛 `EvaluatorException`。实测：

```
✅ 死循环被中断: EvaluatorException / script timeout
3 秒后线程状态: TERMINATED
```

新增 3 个测试锁死这个行为（`JsInterruptTest`），包括「打断后同实例仍可继续用」。

> ⚠️ **`RegexGuard` 做不到这一点，别指望。**
> `java.util.regex.Pattern` 没有可挂 hook 的指令计数器，
> 所以在 JVM 上正则超时**只能丢线程**，不能真中断。这是两条不同的路。

#### 更正：`Context.enter()` 会复用实例，预算必须显式刷新

v0.3.1 把预算放进 `ThreadLocal` 后，`makeContext()` 能读到正确值 ——
**但 `Context.enter()` 在同一个线程上会直接返回上次的 Context 实例，`makeContext()` 根本不会走。**

实测（1 线程池，先 20s 预算、再 800ms 预算）：

```
task2 拿到的 ctx 预算字段 = 30000   ← 应是 800
❌ 3s 未结束 —— 预算完全没生效
```

修复：`InterruptibleContext` 的 `budget` / `startedAt` 改成可刷新的 `var`，
`enterWithBudget()` 里**每次显式 `resetBudget()`**。

```
修复后：809ms 结束（预算 800ms）
```

> 完整分析（机制实测 + 7 种抓不到的写法 + 能抓住的写法）见 §八 · v0.3.2。

#### 关于 ReDoS 测试用例的选择

教科书那批例子在 **JDK 9+ 已被度量化优化**，实测：

| pattern | JDK 17 耗时 |
|---|---|
| `(a+)+$` | **1 ms** |
| `(a\|aa)+$` | 0 ms |
| `(.*a){30}` | **14 455 ms** |

按 `(a+)+$` 写测试会**误通过**，而且会让你误判「正则超时很罕见」。

### v0.3.1：JS 超时从「假超时」修成「真打断」

> 这条是评审提出的，**他说的对，而且比我以为的更严重**。

### 缺陷一：回调没接，闹钟不响（v0.2.0）

```kotlin
ctx.setInstructionObserverThreshold(10_000)   // 设了阈值
// ← 整个文件没有任何 override observeInstructionCount
```

后果实测：`while(true){}` → 2 秒后 `interrupt()` → **线程状态仍是 `RUNNABLE`**，
脚本继续跑。`future.cancel(true)` 只是把 Future 标成 `isCancelled=true`，
**脚本本身没停**。而 `js-rule` 池只有 2 个线程 —— 一个死循环源跑两次，
整个 `@js` 能力永久不可用。

修复（`InterruptibleContextFactory.kt`）：继承 `Context` 重写
`observeInstructionCount`，超预算抛 `EvaluatorException`。
实测死循环在 1002ms 被打断（预算 1000ms）。

### 缺陷二：预算用了全局变量，实例 timeoutMs 形同虚设（v0.3.0 引入）

v0.3.0 修好了回调，但把预算存在 `ContextFactory` 的
`@Volatile var defaultBudgetMs` 上 —— 而 `makeContext()` 根本拿不到
「这是哪个 `JsRuntime` 在跑」。

后果：`JsRuntime(timeoutMs = 600)` 的脚本实际按全局 30s 预算跑。
测试里能看出来 —— 600ms 的实例要等外层 `future.get(600ms)` 兜底，
而内层还在烧 CPU 30 秒。

修复：预算改 **ThreadLocal**，`enterWithBudget()` 在跑脚本的线程上写入，
`makeContext()` 从同一线程读。现在 600ms 预算实测 **601ms** 结束。

### 缺陷三：`cancelCurrent()` 是死代码（v0.3.0）

`Context.getCurrentContext()` 是 thread-local 的。外层超时在**调用方线程**
调 `cancelCurrent()`，拿到的不是跑脚本那个 Context，什么也没取消。

修复：`JsRuntime` 持有 context 引用（`AtomicReference`），外层超时时
调 `cancel(ctx)` 直接打断真正的那个。

### 新增的 5 条测试（都带 timeout，失败会挂而不是静默）

| 用例 | 断言 |
|---|---|
| 死循环必须在预算内被打断 | 耗时 700–5000ms 且 `lastError is JsTimeout` |
| 预算跟随实例而不是全局 | 600ms 实例必须 < 5s 结束（回归 v0.3.0 bug） |
| 并发时预算互不干扰 | 两个实例同时跑，各自预算生效 |
| 死循环线程最终真的终止 | 无 `RUNNABLE` 残留脚本线程 |
| 打断后 JsRuntime 仍可继续用 | 同一实例炸完还能跑正常脚本 |

### 顺带的性能修正

体检耗时从 **9.6s 回到 2.9s**。

原因：`@js` 之前是**无条件**给每个目录条目建 `JsRuntime` ——
SF 有 421 章，就白建 421 个 Rhino 引擎。现在先判断规则里是否真含
`@js`/`<js>`（`needsJs()`），不含就不建。

### `RegexGuard` 为什么不能同样处理

`java.util.regex.Pattern` 的匹配循环**没有**可挂 hook 的指令计数器
（Rhino 有 `observeInstructionCount`，它没有）。所以在 JVM 上正则超时
**只能丢弃线程，不能真正中断** —— 这两条是不同性质的事，注释里已写明。

---

### v0.3.2：写出能真正抓住「预算串台」的测试

评审说：「`预算跟随实例` 那条断言 `< 5000` 太松，而且我试了三种写法都抓不住这个 bug，
只能靠 `resetBudget` 那一行保证。」

**这话对，但结论可以更好 —— 我找到了能确定性抓住它的写法。**

### 先搞清楚一件事：exit 之后 Rhino 到底复不复用 context

实测（Rhino 1.7.15 / JDK 17）：

```
Context.enter() → Context.exit() → Context.enter()
  → makeContext() 被再调用，拿到**新实例**（id=1 → id=2）

Context.enter() → Context.enter()（不 exit）
  → makeContext() **不调用**，拿到**同一个实例**（id 相同）
```

所以：

- **正常路径**（`exitClean()` 走到 `Context.exit()`）预算**本来就不会串** ——
  这也是为什么注掉 `resetBudget` 后那些端到端测试依然全绿
- **风险路径**是**嵌套 enter / 异常路径没 exit**

之前所有测试都在验证正常路径，等于没测到。

### 能抓住它的测试长什么样

不猜、不靠计时，直接构造嵌套：

```kotlin
val a = enterWithBudget(30_000)
val b = enterWithBudget(800)      // ← 不 exit 就再 enter
assertEquals(800L, b.debugBudget) // ← 不刷新预算这里会拿到 30000
```

**回归注入验证**（把 `resetBudget` 注掉）：

```
❌ FAIL: 嵌套 enter 时预算必须刷新成本次的值
   不应有错误：[第2层预算=30000，应为 800, 第3层预算=30000，应为 900]
```

恢复修复后 3 条全绿，且端到端那条「嵌套后死循环在 **903ms** 结束」
说明预算真的作用到了指令回调，不只是字段被改对。

全量跑 51 个测试、注入回归后**只有这一条抓到** —— 其余 50 条全是正常路径，抓不住。

### 另外把一条测试的名不副实改掉了

原「预算跟随实例而不是全局」断言 `elapsed in 500..1500`。看起来收紧了，
但它**本质上还是计时**：`future.get(800ms)` 无论内层对不对都会在 800ms 返回。
改名成 `实例预算不被其他实例污染`，注释里写清「这条只能证明没有明显串台，
区分内层/外层要看 `死循环线程最终真的终止` 和 `BudgetIsolationTest`」。

### 顺手修掉的真实性能问题

真机体检 8.9 秒，而 curl 四个请求总共 1.1 秒 —— 差了 6 倍，不是网络问题。

加了请求计数后发现：**一次体检发了 23 次请求**。
原因是 `content` 阶段走完整的正文分页合并 —— 抽样只要判断"能不能读到"，
却把整章 20 页全抓完了。

修法：`content(chapter, maxPages)` 加参数，体检传 2，正常阅读用默认 20。

| | 请求数 | 耗时（JVM） | 真机 |
|---|---|---|---|
| 修前 | 23 | 3500ms | 8913ms |
| 修后 | **5** | **1514ms** | 4305ms |

正常阅读的正文长度不变，仍是 24374 字。

---

## v0.3.3：`@js` 在搜索阶段是坏的（用户问"还有什么问题"时查出来的）

这轮不是评审指出的，是用户直接问「目前还有什么问题吗」，我去逐条核对代码找出来的。

### 真 bug 一：搜索阶段的条目级 `@js` 完全失效

```kotlin
// 搜索阶段：每条结果 new 一个 AnalyzeRule，但没挂 JS 运行时
val per = AnalyzeRule().apply { bindSharedVars(sharedVars); setContent(el, url) }
val name = per.getString(rule.name ?: "@text")   // ← @js 规则得到 UnsupportedApi
if (name.isEmpty()) continue                     // ← 整条结果被跳过
```

**后果**：任何用 `@js` 写 `ruleSearch.name` 的书源，搜索结果**一条都出不来**。
实测确认：单测里 2 条结果，`hits=0`。

对比目录阶段是对的（那里单独写了 `attachJsForElement`），
说明这是"四个阶段写了四遍、漏了一处"的结构性问题，不是设计错误。

修复后 `hits=2`、`name=[书名A]`。加了 4 条测试，注入回归验证过能抓住：

```
注入回归（删掉 search 里的 attachJsForElement）:
FAILED  搜索阶段条目级 @js 能取到值
        两条都要出来（@js 失效会导致 name 为空被 continue 掉）expected:<2> but was:<0>
```

并且按需建引擎（`needJs` 判断），纯 CSS 的源不会白建 26 个 Rhino。

### 真 bug 二：连续失败计数只存了一条

```kotlin
// 每次只写当前这一个源
File(dir, "failures.json").writeText(gson.toJson(mapOf(result.sourceUrl to failures)))
```

**后果**：多源时**前面的全被覆盖**，「连续 3 次失败建议删除」永远只有最后一个源有效。
改成累加读全量 map，UI 上真的显示「连续失败 N 次，建议删除」了。
（这个功能 v0.1 就写在 README 里，实际上一直不工作。）

### 一次自我纠正：`currentContent = { null }` 不是 bug

我一开始以为 `attachJs()` 里写 `currentContent = { null }` 是缺陷，
加了参数 `content` 传进去 —— **然后注入回归验证，发现它不改变任何行为**。

原因：`JsRuntime` 取值顺序是 `content ?: bridge.currentContent()`，
而 `AnalyzeRule` 已经把 HTML 存进自己的 `element/document` 了，
`java.getString` 走的是那条路径。那个参数是冗余的。

已删掉，并在注释里写明「这里写 null 是对的」，避免后人再误判。

**这一条记下来是因为：我差点把一个多余改动说成修复。**

### 核对过、确认不是问题的

| 怀疑 | 实测结论 |
|---|---|
| `sharedVars` 用 `LinkedHashMap` 线程安全吗 | 每个 `SourceSession` 串行跑，且每次 new，不跨线程 |
| `AnalyzeRule.isRegex` 字段没人用 | 只是注释里提到的上游缺陷残留，无实际作用，可删但无害 |
| MOD 不能单独禁用 | 对 —— 方案 §8.5④ 要求"可单独禁用"，目前只能删目录 |
| 阅读进度存了但从不读 | 对 —— `loadProgress()` 只被自己调用，没有续读 UI |
| 搜索串行、源多了会慢 | 对 —— `for (src in enabled)` 串行，方案允许的上限是 4 并发（不同域名） |
| `nextTocUrl` 多候选 | 只取第一个，多候选（换行分隔）未实现 |

---

## v0.3.6：体检结果重启后全丢（严重）+ 出错规则看不见

评审提了一条很准的建议：**「最该补的不是书源编辑器，而是让用户看到失败原因」**。
顺着这条去查，挖出两个比编辑器严重得多的问题。

### 真 bug 一：一条带错误，全部体检结果丢失（严重）

`CheckResult.error` 是 sealed class `RuleError`。Gson 反序列化 `Map<String, CheckResult>`
时遇到抽象类型直接抛 `Abstract classes can't be instantiated`，
**整个 Map 变成 null**。

实测：

```
两条结果（一条 NO_CONTENT 带 error，一条 READABLE）
→ 读回来 ok=false，val=null
→ "可读"那条也跟着没了
```

**用户看到的现象**：体检完显示「抽样可读」的绿标，重启后全部变回「未验证」。
bookSources 越多、失败源越多，越必然发生。

修复：加 `RuleErrorSnapshot`（可序列化的扁平结构）+ `PersistedCheckResult` DTO。
注入回归验证——直接改回原写法，测试报的正是那句 `Abstract classes can't be instantiated`。

### 真 bug 二：正则写错是静默失败

```
##(unclosed##X   → 返回原文，lastError = null   ← 用户以为是"没匹配上"
##zzz##X         → 返回原文，lastError = null   ← 真的没匹配上
```

两种情况表现**完全一样**。而写错正则恰好是书源作者最常踩的坑。

修复：`RegexGuard` 编译失败时记下来，`AnalyzeRule` 转成 `RuleSyntaxError`。
现在能区分了。

### 真 bug 三：警告冲不掉，因为 lastError 每次调用都清空

即使前两个修好了，还有一个：`AnalyzeRule.getString` 开头会 `lastError = null`。
一个搜索阶段要跑 26 条 × 9 个字段，**最后一次成功就把前面所有错误痕迹冲没了**。

所以必须**在出错当下回调**：

```kotlin
var onRuleError: ((RuleError) -> Unit)? = null   // 出错立刻通知
```

`SourceSession` 收进 `warnings`，同类同阶段去重（421 章不该刷 421 条）。

### 顺带：把「该改哪里」显示出来

之前只显示 `humanMessage`（"规则语法错误"），用户不知道该改哪个字段。
现在：

| 显示 | 内容 |
|---|---|
| 状态徽章 | 「可读·有警告」（不再是纯绿标） |
| 报告 | `四步通（5 次请求）· ⚠ search: 规则语法错误` |
| 定位 | 「出错的规则 · 搜索规则（ruleSearch）」 |
| 规则串 | `(unclosed`（等宽盒子高亮，最多 6 行） |

截图：`docs/screenshots/rule-warning.png`

重启后全部保留（警告也进持久化了）。

### 关于「一次没抓住」的测试

警告回调那组测试，我第一次注入回归只改了 `newAnalyzer` 一处，
**测试照样全绿** —— 因为搜索走的是条目级那条路径。

三处全注入后才挂：

```
FAILED  搜索阶段出错但能跑通时要留警告
        必须留下警告，实际=[]
```

这是同一个教训的第三次复现：**注入点选错，等于没做注入回归**。
