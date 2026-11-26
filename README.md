# YueduReader

一个兼容 [Legado（阅读）](https://github.com/gedoor/legado) 书源规则的 Android 阅读器。

> 规则引擎是按 Legado 的**语义自己实现的**，不依赖原版源码。

---

## 它是什么

导入 Legado 格式的 `.json` 书源，搜索、加书架、读正文。支持声明式 MOD 换肤和扩展。

| | |
|---|---|
| 语言 / UI | Kotlin + Jetpack Compose |
| 规则支持 | CSS 选择器 / JSON path / XPath / `@js` (Rhino) |
| 目标平台 | Android 8.0+（minSdk 26） |
| 当前版本 | **v0.6.0** |

## 功能

### 规则引擎

| 语法 | 状态 |
|---|---|
| CSS 选择器（jsoup） | ✅ |
| **Legado 默认选择器**：`class.` / `id.` / `tag.` / `text.` / `children` | ✅ |
| **索引后缀**：`.0` / `.-1` / `!0` / `[1,3]` / `1:3` / `!0:2` | ✅ |
| **`@` 链式逐级下钻**（末段取值符才提取字符串） | ✅ |
| `@Json:` / `$.` / `$[` 自动判 JSON | ✅ |
| `@XPath:` | ✅ |
| `&&` / `\|\|` 组合 | ✅ |
| `##正则##替换###` | ✅（含超时保护） |
| `@put` / `@get` 变量 | ✅ |
| `{{}}` 插值 | ✅ |
| `@js:` 脚本 | ✅（Rhino 1.7.15，含死循环中断） |
| 后缀脚本 `规则主体<js>脚本</js>` | ✅（前段结果注入 `result`） |
| 严格 → 宽松 JSON 双路径 | ✅ |
| `nextTocUrl` / `nextContentUrl` 分页 | ✅（50 页上限 + 防环） |
| 相对 `searchUrl` / `exploreUrl` / `tocUrl` 拼基地址 | ✅ |
| POST body 里的 `{{key}}` / `{{page}}` 替换 | ✅ |
| 请求参数按书源声明的 `charset` 编码（GBK 站） | ✅ |

### 其他

- **书源导入**：数组 / 单对象 / 分组对象三种形态，按 `bookSourceUrl` 去重，坏条目单独列出
- **书源体检**：搜索 → 详情 → 目录 → 正文**四段全部校验**，任一段不过就不算可读。
  结果在界面上摊成四个小药丸，一眼看出哪段挂了
- **假正文识别**：登录墙 / 付费章节 / 转码中 / 人机验证 → 明确报「需登录或人机验证」，
  而不是假装「未取到正文」
- **错误定位**：能指出**具体哪个字段、哪条规则**出错（如 `搜索规则（ruleSearch）` + `(unclosed`）
- **封面**：Coil 加载 + 无封面时按书名稳定取色的初字占位图
- **智能分类**：本地关键词加权投票，书名(5) + 简介(2) + 书源 kind(2)，
  输出 10 个一级分类 + 属性标签 + R18 细分（R18 判定宁可错杀）
- **MOD**：声明式（`theme` / `sources` / `categories`），**零代码执行**

### 界面

底部 5 Tab：**书架 / 书城 / Pixiv / 搜索 / 设置**

- **书城**：只聚合通过四段校验的书源。四层筛选（主分类 / R18 细分 / 内容分级 / 书源胶囊）
  + 4 种排序 + 渐进式加载（滚到底才拉下一页，按域名限流，单源最多 6 页）
- **书架**：入口卡片 + 最近阅读（真实进度，点进去跳回上次那一章）
- **设置**：R18 总开关、书源与 MOD 管理、阅读器（字号/行距/三套纸色/翻页）、Pixiv

### 离线缓存

- 按域名并发闸门（默认 2）+ **断点续传** + **失败重试**
- 缓存优先读取：命中就零网络请求
- 导出 TXT 到 `Android/data/com.ayanami.reader/files/exports/`

### 跨源切换

详情页可直接换到别的源，章节按标题匹配，匹配不上按进度比例对齐。

### R18 安全边界

开关关闭时成人内容**根本不会进结果集**，不是靠 UI 不显示。三层过滤：

1. 加载时（`BookstoreEngine.fetchOne`）：成人书直接丢弃
2. 筛选时（`BookstoreState.visible`）：再挡一次，防「设置异步加载后才关开关」
3. 判定时（`BookClassifier`）：书源分组/kind 带 R18 标记 → 整源强制标成人

设计原则是**宁可错杀** —— 漏一本的代价是「用户没开 R18 也看到了」，
误判的代价只是少显示一本。

## 安装

从 [Releases](https://github.com/YOUR_NAME/YueduReader/releases) 下载最新 APK。

> release 包用 debug key 签名。如已有其他签名版本，需先卸载。

## 使用

1. 打开 App → 底部「书源」→ 导入 `.json` 文件或粘贴 JSON
2. 点「测」跑一次体检，确认能用
3. 底部「搜索」→ 输入关键词 → 点结果进详情 → 选章节阅读

> 建议先跑体检：`书源` 页点「全部重测」。四段全通的源才会显示绿色，
> 被登录墙挡住的会标「需登录或人机验证」—— 这类源需要先在浏览器登录导 Cookie。

仓库自带一个示例书源：`sources/sfacg-lightnovel.json`（SF轻小说）。

## MOD

`mods/` 里有两个示例，打包好的在 `dist/*.mod.zip`。

```
mods/warm-theme/
  mod.json          { id, name, version, type, entry }
  theme.json        颜色 / 字体 / 圆角 → CSS 变量
mods/light-novel-sources/
  mod.json
  sources/*.json    Legado 格式书源包
```

安装：把 `.mod.zip` 放进设备的 `Android/data/com.ayanami.reader/files/mods/`，重启 App。

**MOD 不支持执行代码** —— 这是刻意的，见 [SECURITY.md](SECURITY.md)。

## 从源码构建

```bash
cd android
./gradlew testDebugUnitTest      # 81 个测试（含 8 条真实联网端到端）
./gradlew assembleRelease        # 产出 APK
```

需要 JDK 17 + Android SDK（`compileSdk 36`）。`local.properties` 里的 `sdk.dir` 要自己建，或用 `ANDROID_HOME`。

## 项目结构

```
android/                       可编译的 Android 工程
  app/src/main/java/com/ayanami/reader/
    core/rule/                 规则引擎（切分 / 求值 / JS 运行时 / 正则保护）
    core/net/                  HTTP（编码猜测 / 302 跟随 / 每域名间隔）
    core/pipeline/             搜索 → 详情 → 目录 → 正文
    mod/                       声明式 MOD 加载
    ui/                        Compose 界面
  app/src/test/                81 个单元测试
shim-fidelity/                 保真度测试（android.util.Base64 / Cookie 分桶）
sources/                       示例书源
mods/                          示例 MOD
releases/                     各版本发布说明
docs/screenshots/              截图
```

## 已知限制

- 不支持 `webView` / `webJs`（需要过 Cloudflare 的源不可用）
- 不支持 `Packages.*`（`@js` 直接访问 Java 类；安全策略，约损失 1/60 源）
- MOD 不能单独禁用（只能删目录）
- 书架 UI 是占位，阅读进度只写不读
- 持久化用 JSON 文件，不是 SQLite

完整清单见 [docs/LIMITATIONS.md](docs/LIMITATIONS.md)。

## 文档

- [CHANGELOG.md](CHANGELOG.md) — 版本更新记录
- [SECURITY.md](SECURITY.md) — 安全边界与设计取舍
- [docs/LIMITATIONS.md](docs/LIMITATIONS.md) — 完整限制清单
- [docs/WORKLOG.md](docs/WORKLOG.md) — 开发过程记录（含踩过的坑）

## 致谢

规则语义参考 [Legado](https://github.com/gedoor/legado)（GPLv3）。本项目是独立实现，未复制其源码。

## 许可

[MIT](LICENSE)

---

> 本项目仅供个人学习与自用。不内置任何内容，不带书源。使用者需自行确保所访问内容的合法性。
