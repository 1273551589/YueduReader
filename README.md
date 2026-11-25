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
| 当前版本 | **v0.3.7** |

## 功能

### 规则引擎

| 语法 | 状态 |
|---|---|
| CSS 选择器（jsoup） | ✅ |
| `@Json:` / `$.` / `$[` 自动判 JSON | ✅ |
| `@XPath:` | ✅ |
| `&&` / `\|\|` 组合 | ✅ |
| `##正则##替换###` | ✅（含超时保护） |
| `@put` / `@get` 变量 | ✅ |
| `{{}}` 插值 | ✅ |
| `@js:` 脚本 | ✅（Rhino 1.7.15，含死循环中断） |
| 严格 → 宽松 JSON 双路径 | ✅ |
| `nextTocUrl` / `nextContentUrl` 分页 | ✅（50 页上限 + 防环） |

### 其他

- **书源导入**：数组 / 单对象 / 分组对象三种形态，按 `bookSourceUrl` 去重，坏条目单独列出
- **书源体检**：搜索 → 详情 → 目录 → 正文四步串行，给出状态 + 总耗时 + 卡在哪一步
- **错误定位**：能指出**具体哪个字段、哪条规则**出错（如 `搜索规则（ruleSearch）` + `(unclosed`）
- **MOD**：声明式（`theme` / `sources` / `categories`），**零代码执行**

## 安装

从 [Releases](https://github.com/YOUR_NAME/YueduReader/releases) 下载最新 APK。

> release 包用 debug key 签名。如已有其他签名版本，需先卸载。

## 使用

1. 打开 App → 底部「书源」→ 导入 `.json` 文件或粘贴 JSON
2. 点「测」跑一次体检，确认能用
3. 底部「搜索」→ 输入关键词 → 点结果进详情 → 选章节阅读

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
