# 更新记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 每个版本的详细说明见 [releases/](releases/)，或 GitHub Releases 页面。

## [Unreleased]

### 计划中
- Room 迁移（书源 / 体检 / 进度 / 书城快照）
- 下载前台服务（通知栏进度，切后台不中断）+ EPUB 导出
- 书源规则可视化编辑 + 改完即测
- 可选 WebView 过盾兜底
- 画师作品分页加载
- MOD 单独禁用

---

## [0.7.1] - 2026-11-26

**质量收口**：修掉 v0.7.0 的 6 个真实缺陷，小说榜首屏 10 秒 → 2.4 秒，零编译警告。

### 修复

- **榜单与搜索状态互相覆盖**：`pixivState.illusts` / `novels` / `loading` /
  `error` / `loadedOnce` 被 PixivScreen 和 SearchScreen 共用，
  在搜索页搜完插画切回 Pixiv，排行榜会被 48 条搜索结果覆盖。
  现在拆成 `ranking*` / `search*` 两套字段，`resetRanking()` 与
  `resetSearch()` 互不干扰
- **小说榜首屏等 10 秒**：原来「提 12 个 id → 逐条打 `/ajax/novel/<id>`
  （串行 900ms）→ 全部回来才渲染」。实测榜单页里内嵌了完整的
  `assign.display_a.rank_a` JSON（标题/作者/字数/收藏/标签/封面/系列全有），
  改为「1 个请求解析 `rank_a` 立即上屏 + 后台逐条补详情」，
  实测首屏 **2387 ms**
- **点 Tag 看起来没反应**：原来只调 `pixivSearch()`，但 Pixiv 页没有渲染
  搜索结果的地方。现在 `openPixivSearch()` 一步做三件事：
  切到搜索页「Pixiv插画」Tab + 写关键词 + 发起搜索。
  配套把 `SearchTab` 从 Composable 局部 state 提升到 ViewModel
- **画师卡片点击无响应**（`onClick = { }`）：现在进画师详情页
  （头像 / 昵称 / ID / 作品数 / 双列作品网格，点作品进大图浏览器）。
  数据走 `/ajax/user/<uid>/profile/all`（免登录，返回 ID 列表）
  + `/ajax/illust/<id>` 逐条取详情，因为 `/ajax/user/<uid>/illusts` 要登录。
  顺带修了 Compose 命中测试：卡片内层头像/昵称会截获点击，补 `clickable`
- **配了代理但图片不走代理**：`PixivApplication.newImageLoader()` 不读
  `Settings.proxyUrl`，导致 Pixiv 页文字能刷、图片全灰块。
  改用 `DynamicProxySelector`（每次请求现读设置，改完立即生效，无需重启），
  与 `PixivClient` 共用同一个代理解析函数
- **20 处编译警告**：`Icons.Filled.*` → `Icons.AutoMirrored.Filled.*`、
  多余 `?.` / `!!` / elvis、`Context()` 弃用（Rhino 1.7.15 唯一入口，
  用 `@Suppress` + 注释说明）

### 测试

**343 个用例**（v0.7.0 是 322，净增 21），8 个联网用例默认跳过，**零警告**。

- 新增 `V071RegressionTest`（18 条）：榜单/搜索状态隔离、
  代理解析 6 种写法、垃圾代理退直连、真实 `rank_a` 夹具字段级断言
- 真实夹具 `app/src/test/fixtures/novel_ranking_rank_a.html`
  （从 2026-11-26 实测响应原样切出前 4 条）
- 清理 `PixivTagWallDiagnosticTest`，核心断言并入 `PixivClientTest`

---

## [0.7.0] - 2026-11-26

**Pixiv 端口 · 阅读器闭环 · 搜索并发**：Pixiv 能看能读能存原图，搜索不再卡，翻页是真的翻页。

### 新增

- **Pixiv 端口（免登录可用）**（`core/pixiv/`）：
  - 插画 / 漫画榜：`ranking.php?mode=X&content=Y&format=json`
  - 小说榜：抓 HTML 里的 `/novel/show.php?id=` 提 ID，再逐条取详情
  - 搜索 / 相关 tag：`/ajax/search/top/<kw>`
  - 作品详情与小说正文：`/ajax/illust/<id>`、`/ajax/novel/<id>`
  - ⚠️ v0.6.0 计划的 `ajax/top/*` 实测要登录（403），**未使用**
  - 图床 `Referer` 注入挂在 Coil 全局 `ImageLoader` 上
  - 图片镜像可切换（官方直连 / 反代域名），API 镜像可在设置里填
  - R18 榜单未登录时明确提示「去设置填 Cookie」，不返回空列表
- **`PixivNovelParser`**：`[newpage]` → 空行、`[chapter:]` 拆章、
  `[[rb:汉字>假名]]` → `汉字（假名）`、`[jump:]` 清除、`[pixivimage:]` → 占位
- **大图浏览器**：双指缩放、多图画册左右滑、作者/标签/收藏数、
  `MediaStore` 保存**原图**到相册
- **搜索页四子 Tab**：书源小说 / Pixiv插画·漫画 / Pixiv小说 / Pixiv画师
- **Tag 墙**：每个热门 tag 各取一张真实封面，点一下直接搜该 tag
- **书源回归台账**（`SourceLedgerTest`）：离线静态检查 + `-DsourceLedger.online=true` 联网四段报告
- **视觉改版**：Material 3 柔和浅紫体系（`#FAFAFC` 底 / `#EAE4FA` 选中胶囊 / `#6B5BD2` 主色）

### 修复

- **「保存原图」存的是缩略图**（449×960 / 682 KB）：
  原图 URL 不能保留 `_master1200`（那是 `img-master` 的转码标记），
  正解是 `img-original/.../152421696_p0.jpg` → 2805×6000 / 7.19 MB。
  另加「2xx + 大小 > 1KB + PNG/JPEG 文件头」三道校验，
  避免把 Pixiv 的 58 字节 404 JSON 存成 `.jpg`
- **Tag 墙 16 个格子全灰**：封面原本从 `ps.illusts` 推断，
  首次进入时该列表为空。改为每个 tag 搜一次、存进 `tagCovers`
- **`LazyVerticalGrid` 内 `aspectRatio(1f)` 算出 0 高度** → 改固定 `height(160.dp)`
- **搜索页切到「搜图片」再也切不回来**：
  v0.6.0 把模式胶囊放进了 `if (mode == IMAGE) { ...; return }` 分支内
- **搜索串行卡顿**：`for (src in enabled)` 全局 1000ms 间隔 →
  按域名分组并发（同域串行 + 800ms，跨域 4 并发），并跳过体检硬失败的源
- **R18 过滤失效**：搜索结果聚合前先过 `BookClassifier`，关开关时成人向进不了列表
- **图片镜像重写误伤**：`rewrite()` 会把任意 http 地址换成镜像域名
  （书源封面会被改到 `i.pixiv.re` 下必然 404）→ 加 `isPixivImage()` 前置判断

### 变更

- 阅读器：真左右翻页（`TextMeasurer` 精确分页）、点击左/中/右分区、
  底部快捷面板（字号/行距/纸色/翻页）、正文插图 `AsyncImage`、章内页码精准恢复
- 书城：跨源去重补全缺失封面/简介/字数、`altOrigins` 打通换源、
  30 分钟快照缓存
- 设置页 Pixiv 段：PHPSESSID / 代理 / 图片镜像 / API 镜像都可直接编辑
  （v0.6.0 的两个「配置」按钮是空 lambda）

### 测试

**322 个用例**（v0.6.0 是 209，净增 113），11 个联网用例默认跳过：

| 测试 | 数量 | 覆盖 |
|---|---|---|
| `PixivNovelParserTest` | 21 | 四类标记、拆章、边界 |
| `PixivClientTest` | 30 | 真实响应解析、`rating_count`、镜像重写、拦截器 |
| `PixivSaverTest` | 14 | 原图路径、图片头校验 |
| `SearchConcurrencyTest` | 8 | 同域串行 / 跨域并行 / 全局上限 |
| `Phase1EngineTest` | 24 | 分页、闸门、元数据合并、快照 |
| `SourceLedgerTest` | 6 | 书源回归台账 |
| `PixivOnlineTest` | 7 | 真联网哨兵 |
| `PixivTagWallDiagnosticTest` | 3 | Tag 墙数据链路 |

联网用法：
```
./gradlew testDebugUnitTest --tests "*PixivOnlineTest" -Dpixiv.online=true
./gradlew testDebugUnitTest --tests "*SourceLedgerTest" -DsourceLedger.online=true
```

---

## [0.6.0] - 2026-11-26

**下载缓存 · 跨源切换 · 搜索双模式**：能离线和换源了，这才算能长期用。

### 新增

- **离线缓存**（`DownloadEngine`）：
  - 按域名并发闸门（默认 2）—— 同域名几百章无节制并发会被判成 CC 攻击
  - **断点续传**：已缓存章节跳过，不重下
  - **失败重试**：失败章节收集后重试一轮（瞬时 500/超时很常见）
  - 存储 `<sha1(bookUrl)前16位>/0001.txt`，第一行标题、正文从第二行
  - **缓存优先读取**：命中就零网络请求，这是离线阅读的全部实现，不需要单独开关
  - **导出 TXT**：只导已缓存章节，落 `Android/data/com.ayanami.reader/files/exports/`
- **跨源切换**：详情页显示「其他书源也有这本」胶囊。
  换源后按标题匹配章节，匹配不上按进度比例估算（老源 400 章读到 200 章 →
  新源 200 章的第 100 章）
- **搜索页双模式**：搜小说 / 搜图片，小说支持 全部/书名/作者 字段筛选
- **阅读器接上设置**：字号、行距（= 字号 × 倍数）、三套「纸」配色
  （日间近白 / 护眼米黄 / 夜间深灰，都是专门调过的，不复用 App 主题色）

### 修复

- **下载进度永远停在 4/50**：`Task.completed` 用 `AtomicInteger` + `@Volatile`，
  数据对但 Compose 观察不到 —— UI 只在首次重组读到值，看起来像卡死。
  改成 `mutableStateOf<Int>`。
- **下载第 4 章后彻底卡住**：`HttpFetcher` 里 `client.newBuilder().build()`
  **会克隆新的连接池和 Dispatcher 线程池**，每章 1~N 次请求 → 50 章上百个连接池，
  很快耗尽 fd。改用共享 client 的 `newCall()`，连接池调到 8。
- **状态栏重叠**：Activity 开了 `enableEdgeToEdge`，但各页面用**固定 dp** 当顶部间距，
  在状态栏高度不匹配的机型上标题被压住（实测 MOD 标题与时钟重叠）。
  全 App 改成读 `WindowInsets.statusBars`。
- **版本号写死 `v0.5.0`**：改成读 `BuildConfig.VERSION_NAME`，并开启 `buildFeatures.buildConfig`

### 测试

- **209 个用例全绿**（v0.5.0 是 185，新增 24）
- 新增 `DownloadEngineTest`（16）、`ReaderProgressTest`（8）
- **注入回归验证**：删掉 `filterNot { isCached(...) }` 后，
  两条断点续传测试立刻挂掉，证明不是「写个测试凑数」

### 验证

真机实测：下载 50 章完成 → 重启后缓存仍在（50/421）→ 已缓存章节秒开 →
导出 3.2 MB TXT（书名/作者/简介 + 60 章正文）→ 护眼主题米黄底生效

---

## [0.5.0] - 2026-11-26

**5 Tab · 书城聚合 · 设置中心**：书源修通之后，第一次有了可以逛的首页。

### 新增

- **底部 5 Tab 导航**：书架 / 书城 / Pixiv / 搜索 / 设置。
  实现上是一个 NavHost + 条件显示底栏（不是每 Tab 一个 NavHost）——
  详情/阅读/书源管理都是从 Tab 推出去的页面，多 NavHost 会让返回栈炸开。
  当前 Tab 用 `rememberSaveable` 存，从详情页返回回到原 Tab。
- **书城聚合页**：只聚合通过四段校验（`READABLE`）的源 —— 未通过体检的源
  塞进书城，用户点进去就是空的，体验比没有还差。
  - 四层筛选：主分类 / R18 细分（仅开关打开时）/ 内容分级 / 书源胶囊
  - 排序：综合默认 / 近期更新 / 字数倒序 / 字数正序
  - 字数解析吃野格式：`1234567`、`120万`、`300万字`、`12千字`、`1亿`、
    `连载中`（非数字 → -1 沉底）
  - **渐进式加载**：首屏每源拉第 1 页 → 滚到倒数第 6 个才拉下一页 →
    按域名做并发闸门（同域名最多 2 并发）→ 单源最多 6 页
  - 跨源去重按「书名+作者」，保留首次出现的那条
- **书架「最近阅读」**：从 `Repository.loadProgress()` 反序列化，
  显示「刚刚 / 3 小时前 / 11-26」，点进去跳回上次读到的章节。
  `saveProgress` 新增 `bookName` 参数 —— 以前只存 URL，书架根本认不出是哪本书
- **设置中心**：4 个分区（内容安全 / 书源与 MOD / 阅读器 / Pixiv）。
  以前书源/MOD 管理散落在书架入口卡片上，而 R18 / 字号 / 阅读主题**根本没有入口**。
  - 阅读器：字号 13–30 sp、行距 1.20–2.40 倍、日间/护眼/夜间、滚动/翻页、自动恢复进度
  - Pixiv：PHPSESSID / Refresh Token / 代理
- **Pixiv 页骨架**：明确标注「v0.7.0 交付」并列出要做的事，
  而不是放假封面骗点击

### 安全

- **R18 三层硬过滤**（不只是隐藏入口）：
  1. 加载时（`BookstoreEngine.fetchOne`）：`!adultOn && isAdult` → 不进结果集
  2. 筛选时（`BookstoreState.visible`）：再挡一次，防「设置异步加载后才关开关」
  3. 判定时（`BookClassifier`）：书源分组/kind 带 R18 标记 → **整源强制标成人**，
     哪怕书名简介完全干净
- `SettingsVisibilityTest` 把真值表 12 种组合（开关 × 分级 × 是否成人）逐条锁死

### 修复

- **真实书目分类错判**（用真机书城数据校准）：
  | 书 | 之前 | 修正后 |
  |---|---|---|
  | Sword Art Online刀剑神域 | 玄幻奇幻 | 游戏竞技 |
  | 我转生就超神 | 轻小说 | 游戏竞技 |
  | 圣女怎么可能是男孩子 | 其他 | 轻小说 |

  修法是**补词库**而非加 `if`：游戏竞技补 `虚拟实境` / `vr游戏` / `全息游戏` /
  `死亡游戏` / `登出` / `刷怪` / `刀剑神域` / `sao` / `通关` 等 20+ 词；
  轻小说补 `物语` / `恋爱物语` / `同居` / `教皇` / `圣女` / `棋手` / `日常`
- **Compose 不解析 markdown**：设置页与书城页的说明文字写了 `**硬过滤**`、
  `**通过四段校验**`，星号原样显示出来了。已去掉

### 测试

- **185 个用例全绿**（v0.4.0 是 157，新增 28）
- 新增 `BookstoreTest`（14）、`SettingsVisibilityTest`（7）、`ClassifierRealCasesTest`（7）

### 验证

真机 `reader_test`（1080×2400）实测：5 Tab 可切换；书城聚合 **39 本 / 2 个可读源**；
R18 关闭时 39 本不含成人书，打开后多出 6 项细分 +「成人向」分级，
《妖刀姬》等成人书才出现。

---

## [0.4.0] - 2026-11-26

**规则引擎重写**：把 Legado 兼容性从「能导入书源」修到「书源真的能搜到内容」。
这一版没有新页面，全部用在底层 —— 不修这些，加再多 UI 也是空壳。

### 修复（四个阻塞级缺陷，都会静默返回空）

- **Legado 默认选择器语法完全没实现**：`class.` / `id.` / `tag.` / `text.` / `children`
  以前整串直接丢给 `jsoup.select()`，`class.odd.0` 会去「找 `<class>` 标签」→ 永远空。
  43 条实测书源里 26 条的 `bookList` 都是这种写法。
  现在由 `RuleSteps` 解析成「访问器 + 参数 + 索引过滤器」。
  索引全支持：`.0`、`.-1`（倒数）、`!0`（排除）、`[1,3]` / `1:3`（闭区间）、`!0:2`。
  `class.x` 按 Legado 语义走子串包含（`[class*="x"]`），能命中 `class="book-item"`。
- **`@` 链式下钻语义反了**：以前 `findAttrSeparator()` 只取**第一个** `@`，
  `class.odd.0@tag.a@href` 变成「读属性名为 `tag.a@href` 的东西」→ 永远空。
  现在按 `@` 切成 N 段逐级过滤，只有末段是取值符时才提取字符串。
  切分会躲开 `##正则##`（邮箱正则里有 `@`）、`{{变量}}`、引号和括号内部。
  顺带补齐 `规则主体<js>脚本</js>` 后缀脚本（前段结果注入 `result`）。
- **相对地址没拼基地址**：书源写 `/s.php` 时直接 `new URL("/s.php")` → host 为 null
  → `URL 无法解析`。现在 `searchUrl` / `exploreUrl` / `tocUrl` / 章节地址 / 下一页
  全部相对 `bookSourceUrl` 解析。顺带修好「详情页取到 `/list/1.html` 却不拼接」
  导致目录恒空的问题。
- **POST body 里的 `{{key}}` 从未被替换 + 字符集编码缺失**：body 里的占位符原样发出去；
  声明 `charset: gbk` 的源也只拿它解**响应**，请求参数一律 UTF-8 百分号编码，
  GBK 站收到就是乱码 → 0 条结果。现在 `UrlTemplate` 统一做
  变量替换 → 按声明字符集编码 → 拼基地址；空格编成 `+`，JSON body 不编码。

### 变更

- **网络层换 OkHttp**：`HttpURLConnection` 不跨协议跳 redirect（http→https 会卡住）、
  无连接池复用、无法干净注入 Referer。⚠️ 踩坑记录：手动设 `Accept-Encoding`
  会让 OkHttp 停止透明 gzip 解压，拿到压缩字节转 String 全是乱码
  → 目录/正文全部 `SelectorEmpty`（实测 SF 源 421 章变 0 章）。
- **书源体检从「HTTP 200 就算过」改成四段校验**：搜索（书名+地址非空）→
  详情（书名+目录地址非空）→ 目录（≥1 章标题+绝对地址）→
  正文（>50 字 + 中文占比 ≥15% + 未命中假正文）。
- **假正文识别（14 条特征）**：登录墙页面往往上千字，「够长就算过」挡不住。
  分两类：登录墙（报「需登录或人机验证」）与非登录墙（报「未取到正文」）。
  ⚠️ 必须先判假正文再判长度 —— 「请先登录后阅读」只有 8 字，
  先判长度会被归成「正文过短」，用户永远不知道需要登录。
  长正文只在开头 60 字找特征（真正文里「请先登录」作为台词很常见）。

### 新增

- **封面渲染**：引入 `coil-compose`，搜索结果与详情页真实加载 `coverUrl`。
  拿不到封面时用「书名首字 + 稳定渐变色块」占位（不是灰方块），
  颜色按书名 FNV-1a 哈希取色板，同一本书三处颜色一致。
- **自适应 App 图标**：`background` / `foreground` / `monochrome` 三层，
  Android 13+ 主题图标可用。⚠️ 第一版白书页实机缩到 48dp 后压在米色底上糊成一片，
  已改深墨色 `#252C3B`。
- **`BookClassifier` 智能分类引擎**（为书城准备）：纯函数、零网络、纳秒级。
  书名（权重 5）+ 简介（2×命中数）+ 书源 kind（2，不可信只作修正）加权投票，
  输出 10 个一级分类 + 属性标签 + R18 细分。
  **R18 判定宁可错杀**：命中词库或书源分组带 R18 标记 → 强制成人；
  置信度用绝对强度而非投票占比（占比在单票时恒为 1.0，等于瞎猜）。
- **四段校验结果在界面上摊开**：四个小药丸并排（搜索/详情/目录/正文），
  通过绿底、失败橙底，下面给出失败原因。

### 测试

- **157 个用例全绿**（v0.3.7 是 81 个，新增 76 个）
- 新增 `LegadoSelectorTest`（29）、`RequestPipelineTest`（15，起真实 HTTP 服务器）、
  `StrictCheckTest`（10）、`BookClassifierTest`（22）

### 验证

- 真机（`reader_test` 模拟器）：搜索 `magic` → 29 本书、来自 2 个源，封面与徽标正常
- 真实书源（SF 轻小说，走真实网络）：四段全通 891ms，目录 421 章，
  正文抽样 24374 字

---

## [0.3.7] - 2026-11-25

### 修复
- **旧格式体检记录导致崩溃**：`RuleErrorSnapshot.kind` 声明成非空 `String`，但 Gson 会绕过 Kotlin 空检查塞 `null`。旧文件没有该字段 → `when(kind)` 调 `hashCode()` → NPE。升级用户必踩。

### 新增
- 旧格式兼容测试 4 条（此前只测「新格式写 + 新格式读」，没测「旧格式读」）

### 清理
- `saveCheckResult` 重复读取 `loadCheckResults()`（还留了个没用的变量）
- `shim-fidelity` 的第三方 jar 改为 `fetch-libs.sh` 现拉，不进 git

## [0.3.6] - 2026-11-25

### 修复
- **体检结果重启后全部丢失**：`error` 是 sealed class，Gson 无法还原 → 整个 Map 变 `null`，连可读的源也一起没了
- **正则写错是静默失败**：`##(unclosed##X` 和 `##zzz##X` 表现完全一样
- **警告被冲掉**：`lastError` 每次调用开头清空，搜索跑 26 条 × 9 字段后最后一次成功就抹掉所有痕迹。改成出错当下回调

### 新增
- 体检报告能定位到**具体字段 + 具体规则串**（「出错的规则 · 搜索规则（ruleSearch）」+ `(unclosed`）
- 徽章区分「可读」和「可读·有警告」

### 已知问题
- ⚠️ 本版读取旧格式文件会崩，0.3.7 修复

## [0.3.4] - 2026-11-25

### 新增
- 连续失败计数测试 6 条，注入回归验证可抓住「整表覆盖」
- 把合并逻辑抽成纯函数 `nextFailureCounts()`（`Repository` 依赖 Android `Context`，不便直接测）

### 清理
- `loadFailureCounts()` 被读两次 → 只读一次

## [0.3.3] - 2026-11-25

### 修复
- **搜索阶段的 `@js` 完全失效**：条目级 `AnalyzeRule` 没挂 JS，任何用 `@js` 写 `ruleSearch.name` 的源搜索结果一条都出不来
- **连续失败计数只存一条**：`failures.json` 每次整表覆盖，「连续 3 次失败建议删除」一直不工作

## [0.3.2] - 2026-11-25

### 修复
- **预算串台**：`Context.enter()` 在同线程复用上次的 Context 实例，`makeContext()` 不会被再调用 → 线程池复用线程时预算仍是旧的
- 新增能用确定性断言抓住它的测试（不靠计时，直接构造嵌套 `enter`）

### 性能
- 体检请求数 **23 → 5**（此前为判断「能不能读」把整章 20 页正文全抓完）

## [0.3.1] - 2026-11-25

### 修复
- 预算改用 `ThreadLocal` 按执行传递（此前在 `ContextFactory` 全局变量上，实例的 `timeoutMs` 形同虚设）
- `cancelCurrent()` 是死代码（`Context.getCurrentContext()` 是 thread-local，从调用方线程拿不到）

### 性能
- 体检 9.6s → 2.9s（`@js` 曾对每个目录条目无条件建 Rhino 引擎，421 章白建 421 个）

## [0.3.0] - 2026-11-25

### 修复
- **JS 超时从来没生效过**：只调了 `setInstructionObserverThreshold` 但没重写 `observeInstructionCount`。`while(true){}` 会让 2 线程的 `js-rule` 池永久耗尽

### 已知问题
- ⚠️ 本版把预算放到全局变量，实例 `timeoutMs` 失效，0.3.1 修复

## [0.2.0] - 2026-11-25

### 新增
- **`@js` 支持**：嵌入 Rhino 1.7.15，绑定 62 条样本源里真实用到的 8 个 API
- **分页**：`nextTocUrl` / `nextContentUrl`（50 页上限 + 防环）。同一章正文 409 字 → 24374 字
- **正则超时保护**：超时丢弃线程 + 熔断

### 已知问题
- ⚠️ JS 超时是假的，0.3.0 修复

## [0.1.0] - 2026-11-25

### 新增
- 首个可用版本：规则引擎（CSS / JSON path / XPath / `&&` `||` / `##正则##` / `@put` `@get` / `{{}}`）
- 四阶段抓取流水线，**阶段级实例隔离**
- 书源导入（三种形态 / 去重 / 坏条目单列）
- 书源体检（状态 + 耗时 + 卡在哪一步）
- 声明式 MOD（theme / sources / categories）

### 已知限制
- `@js` 不支持（覆盖约 24% 的源）
- 分页未实现
