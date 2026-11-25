# 更新记录

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

> 每个版本的详细说明见 [releases/](releases/)，或 GitHub Releases 页面。

## [Unreleased]

### 计划中
- 书源规则编辑 + 改完即测（现在改书源必须重新导入整个 JSON）
- 搜索并发（方案允许 4 并发，现在是串行）
- MOD 单独禁用

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
