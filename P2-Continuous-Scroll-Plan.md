# P2 连续滚动方案 (Continuous Scroll Plan)

> **项目**: 本地 EPUB 阅读器 (EpubReader)
> **Phase**: P2 — 真连续滚动 (True Continuous Vertical Scrolling)
> **状态**: 📋 方案待审阅 (Plan Pending Review)
> **日期**: 2026-07-01
> **基线**: Readium kotlin-toolkit v3.3.0
> **决策来源**: 代码侦察 (@explorer) + 网络研究 (@librarian) + 议会讨论 (@council, 3 模型一致) + 架构审查 (@oracle, conditional GO)

---

## 1. 背景与目标

### 1.1 目标
实现**真连续滚动**：所有章节在一个垂直滚动容器中无缝衔接，用户持续向下滑动即可跨章节阅读，而非 ViewPager 逐章翻页。

### 1.2 之前方案 B 失败的原因
用户曾尝试把 `EpubNavigatorFragment.kt` 源码复制到 `app/src/main/java/org/` 下。失败因为：这是 **Kotlin 文件**，从 app 模块引用 readium-navigator 模块的 `internal` 类，Kotlin 编译器读元数据后拒绝访问。

### 1.3 关键发现
根因不在"能否访问类"，而在**复制方向错误**。正确做法是**把 readium 整体作为本地源码模块引入，在其内部修改**——internal 类因同模块而天然可访问。

---

## 2. 技术现状

### 2.1 构建配置
| 项 | 值 | 来源 |
|---|---|---|
| 模块结构 | 单模块，仅 `include(":app")` | `settings.gradle.kts:24` |
| Readium 依赖 | `readium-shared`/`streamer`/`navigator` 3.3.0 (Maven Central AAR) | `app/build.gradle.kts:112-115` |
| 版本声明 | `readium = "3.3.0"` | `gradle/libs.versions.toml:41` |
| `org/` 目录 | git-ignored，标注 "Readium source snapshot (clonedeps for local inspection)"，**不参与编译** | `.gitignore:44` |

### 2.2 类可见性（关键矛盾点，已核对）
| 类 | GitHub 源码 | AAR 字节码 (javap) | 实际效果 |
|---|---|---|---|
| `R2BasicWebView` | `internal open class` | `public class` | app 模块 Kotlin 代码**不可**引用（元数据强制 internal） |
| `R2PagerAdapter` | `internal class` | `public final class` | 同上 |
| `R2EpubPageFragment` | `internal class` | `public final class` | 同上 |
| `R2ViewPager` | `internal` | `public` | 同上 |
| `NavigatorFragment` | `public abstract class` | `public abstract class` | 可引用 |
| `EpubNavigatorFragment` | `public class` + `internal constructor` | 同 | 只能通过 `EpubNavigatorFactory.createFactory()` 实例化 |
| `EpubNavigatorViewModel` | `internal class` | internal | 不可引用 |

**核对结论**：Kotlin `internal` 编译到 JVM 字节码后变为 `public`，但 Kotlin 元数据 (`.kotlin_module`) 仍标记为 `internal`。从 app 模块的 **Kotlin** 代码引用 → 编译器拒绝；从 **Java** 代码引用 → 可访问（但不推荐，无法继承/覆写 internal 方法）。维护者在 issue #675 明确拒绝改为 public。

### 2.3 `org/` 快照中已有的连续滚动脚手架
`org/readium/r2/navigator/epub/EpubNavigatorFragment.kt`（develop 分支预发布版，比 3.3.0 新）已包含连续滚动分支点：

| 位置 | 内容 |
|---|---|
| `:222` | `Configuration.continuousScroll` 字段 |
| `:367-373` | `continuousRecyclerView`/`continuousAdapter`/`continuousLocatorMapper`/`isContinuousScrollEnabled` |
| `:449-496` | `isContinuousScrollEnabled=true` 时创建 `RecyclerView`+`LinearLayoutManager`+`ContinuousResourceAdapter`+`ContinuousLocatorMapper`（替代 ViewPager） |
| `:652` | `invalidateResourcePager()` 启用连续滚动时直接返回 |
| `:659-664` | `onSettingsChange()` 启用连续滚动时跳过 ViewPager 更新 |
| `:1104-1114` | `goToPreviousResource()` 启用连续滚动时用 `recyclerView.scrollToPosition()` |

**⚠️ 但 `ContinuousResourceAdapter` 和 `ContinuousLocatorMapper` 在整个代码库中找不到定义**（仅被引用，未实现）。develop 脚手架是半成品——`go(locator)` 也只滚到章节 position 无 item 内 offset。

### 2.4 当前 P0 状态
- `ReaderHostFragment.kt:231-272`：通过 `EpubNavigatorFactory` → `fragmentFactory.instantiate()` 创建 `EpubNavigatorFragment`，存于 `reader_container`
- `AppPreferences.scroll` (Boolean) → `EpubPreferences(scroll=)` → `submitPreferences()`
- `scroll=true`：WebView 用 `column-count:auto`（单章内垂直滚动），但 ViewPager 仍在章节间**水平滑动**
- `scroll=false`：CSS columns 合成分页
- 已有自动章节推进：`ReaderJsScripts.kt:85-111` SCROLL_LISTENER JS → `onScrollNearBottom()` → `ReaderViewModel.kt:1808-1839` `requestNextChapter()`（仅 scroll=true 时，有方向感知+防突发）
- **这不是真连续滚动**——章节间仍有 ViewPager 翻页的视觉断裂

### 2.5 Readium 官方态度
- issue #563：维护者明确"不支持连续滚动，目前无计划"
- discussion #668：develop 分支有"新 Web 导航器"（`readium/navigators/web/`），alpha 阶段
- Swift 工具包有活跃连续滚动 PR (#766)
- Readium CSS 本身支持滚动布局（`--RS__disablePagination`），问题在 Kotlin 工具包的 ViewPager 按资源分段架构

### 2.6 日志模块
- `AppLogger` (`app/.../core/log/AppLogger.kt:32`)，`object` 单例
- API：`d/i/w/e/v(tag, msg[, throwable])`、`clear()`、`exportText(): String`、`entries: StateFlow<List<LogEntry>>`
- 同时输出到 logcat + 2000 条内存环形缓冲区
- **设备 logcat 加密**，运行时日志必须走 `AppLogger`

---

## 3. 议会讨论总结 (@council, 3 模型一致)

### 3.1 核心共识（3/3 一致）
1. **复合构建是唯一工程正当路径**——从根上解决 Kotlin `internal` 访问问题
2. **v3.3.0 + 移植脚手架**（非 develop）——develop 省不了多少工作，还带来 API 迁移风险
3. **多 WebView 每章独立**——RecyclerView.Adapter 每 item 一章
4. **可快速回退到 AAR**（但 Oracle 后续指出此点需修正，见 §4.2）

### 3.2 分歧点
| 议题 | 多数意见 | 少数意见 |
|---|---|---|
| fork 粒度 | alpha/gamma: 完整 includeBuild | beta: 轻量单模块 vendored |
| ViewHolder 内部 | beta: 直接 WebView | gamma: Fragment 包装 (R2EpubPageFragment) |
| Feature flag | alpha/gamma: 三态枚举 | beta: 两布尔 |
| SCROLL_LISTENER | alpha/gamma: continuous 模式禁用 | beta: 保留降级为兜底 |
| 工期 | gamma: 9-12 天 | beta: 4-6 周 |

### 3.3 议会推荐路径
v3.3.0 tag 基线 + 复合构建 + 在 fork 的 readium-navigator 内部实现连续滚动 + 参考 develop 分支结构但不追踪。优先尝试 beta 的轻量单模块 Vendored，依赖解析困难再升级完整 includeBuild。

---

## 4. Oracle 审查总结 (@oracle, conditional GO)

### 4.1 Oracle 核心判定
- **GO：连续滚动方向值得做，复合构建路径可行**
- **NO-GO：按"轻量单模块 vendored + 直接 WebView + 构建开关回退"这个组合去承诺交付**
- **fork 粒度倾向：完整 includeBuild 上游仓库 > 轻量单模块 vendored**（与用户确认一致）

### 4.2 Oracle 指出的 7 个关键盲点（必须纳入实施）

| # | 盲点 | 说明 | 修正措施 |
|---|---|---|---|
| 1 | **JS 注入是按 WebView 维度** | `evaluateJavascript` 只打到当前可见 WebView；`registerJavascriptInterface` 只暴露接口，不会自动给每个新 WebView 注入 readium 脚本 | 注入路径改为每个 item WebView `onPageFinished` 后触发 |
| 2 | **位置更新无节流** | continuous 分支 `notifyCurrentLocation()` 直接 `_currentLocator.value = locator`，fling 时疯狂触发 StateFlow/Room/UI | 加 debounce/throttle |
| 3 | **设置变更无重建策略** | continuous 分支 `onSettingsChange()` 只改背景色；字号/边距/主题变更改变 chapter height 和 locator 映射 | 清空高度缓存并重建 |
| 4 | **goForward/goBackward 语义** | 当前做成跳章节，连续滚动应是滚一个 viewport | 调整语义 |
| 5 | **R8/consumer rules** | `@JavascriptInterface`/FragmentFactory/Parcelable locator 的 keep 规则需审计 | release 包验证 |
| 6 | **assets 是硬约束** | `WebViewServer` 读 `application.assets.open("readium/error.xhtml")`；纯拷源码不带 assets 会 404 | 确保完整 library module（`src/main/assets` 并入 app） |
| 7 | **回退开关不可靠** | app 引用 `continuousScroll` 后切回 AAR 编译失败（符号不存在）；BuildConfig 不解决符号缺失 | 始终用 fork，"回退"= git revert 连续滚动改动 |

### 4.3 Oracle 对 GO/NO-GO 关卡的修正
- P2 作为大关卡合理，但**作为第一次风险分界点太晚**
- 建议把"直接 WebView 能否工作"前移到 **P0 末尾做廉价 spike**
- spike 内容：3-item RecyclerView + 真 WebView，验证 load 后高度上报、recycled WebView destroy、onPageFinished 后脚本对每个 item 执行
- **若 spike 失败 → 直接切 Fragment 包装方案，不打补丁**

---

## 5. 最终方案

### 5.1 核心路径
以 **v3.3.0 tag** 为基线，用 **Gradle 复合构建（includeBuild + dependencySubstitution）** 把 readium AAR 替换为本地源码模块（**完整 includeBuild 整仓库**，用户已确认）。在 fork 的 navigator 模块**内部**直接修改 `EpubNavigatorFragment.kt` 及相关类——internal 类因同模块而天然可访问。参考 develop 分支的分支结构设计但不追踪 develop。

### 5.2 关键设计决策

| 决策 | 选择 | 理由 |
|---|---|---|
| 基线 | v3.3.0 tag | 稳定，app 集成代码零改动；develop 省不了多少工作还带 API 迁移风险 |
| fork 粒度 | 完整 includeBuild 整仓库 | Oracle 推荐+用户确认；更稳，避免单模块依赖解析风险 |
| 连续容器架构 | 多 WebView 每章独立 | 议会 3/3 一致；RecyclerView.Adapter 每 item 一章 |
| ViewHolder 内部 | **MVP 先直接 WebView**，问题严重切 Fragment | Oracle/council 共识；spike 验证后决定 |
| 预加载窗口 | ±1（共 3 个 WebView 常驻） | 平衡内存与流畅度 |
| Feature flag | 三态枚举 `ScrollMode(PAGINATED/SCROLLED_PER_CHAPTER/CONTINUOUS)` | 2/3 议会+Oracle 同意；语义清晰 |
| continuousScroll 归属 | `EpubNavigatorFragment.Configuration`（非 `EpubPreferences`） | 控制 Android 容器，非 Readium CSS 内部分页 |
| SCROLL_LISTENER | continuous 模式不注入 | RecyclerView 自然跨章；保留 `requestNextChapter()` 作 preloading 失败 fallback |
| Locator 精度 | 先粗粒度近似（`itemTop/chapterHeight → progression`） | 足够进度/书签/TTS 大致恢复；后续引入 DOM 精确定位 |

**Feature flag 映射**：
```
PAGINATED          → EpubPreferences.scroll=false, Configuration.continuousScroll=false
SCROLLED_PER_CHAPTER → scroll=true, continuousScroll=false
CONTINUOUS         → scroll=true, continuousScroll=true
```

### 5.3 回退策略（Oracle 修正后）
- **始终用 fork**，不保留 AAR 回退开关
- fork 基于 v3.3.0 tag，**无连续滚动改动时等价于 v3.3.0 AAR**
- "回退" = `git revert` 连续滚动相关 commits，fork 回到纯净 v3.3.0 状态
- **不保留任何 AAR 切换开关**，从 P0 起即承诺 fork；"回退"始终指 git revert 连续滚动改动
- app 层 UI 开关在非 continuous 构建时隐藏（通过 BuildConfig flag 控制 UI 可见性，非编译切换）

### 5.4 维护策略
1. 只 fork 必要模块的**改动**集中在 `epub/continuous/` 子包，`EpubNavigatorFragment` 只加分支不大改旧路径
2. 保持 public API 兼容，让 app 基本不感知 fork
3. 建立上游 diff 文档，记录改了哪些文件/符号
4. 上游 v4.0 若原生支持连续滚动，评估废弃 fork 迁移到官方

---

## 6. 分阶段实施计划（Oracle 调整，压缩为 6 阶段）

### P0 — 复合构建骨架（2-3 天 + spike）
**目标**：fork/build 可编译，不改行为。

| 步骤 | 内容 | 验收 |
|---|---|---|
| P0.1 | `git clone https://github.com/readium/kotlin-toolkit.git` 到 `modules/readium`，checkout v3.3.0 tag | 仓库就位 |
| P0.2 | `settings.gradle.kts` 加 `includeBuild("modules/readium")` + `dependencySubstitution`（替换 shared/streamer/navigator 三个依赖） | Gradle sync 通过 |
| P0.3 | 确认 `readium/` assets（readium-reflowable.js、ReadiumCSS、error.xhtml）在 library module 形态下被 app 访问 | WebView 加载不 404 |
| P0.4 | 确认 R8/consumer rules、test 任务都过 | release 构建通过 |
| P0.5 | **廉价 spike**：3-item RecyclerView + 真 WebView（不碰 Readium），验证 load 后高度上报、recycled WebView destroy、onPageFinished 脚本对每 item 执行、**recycle 后 JS bridge 不串台**、**连续创建/销毁 20 次无泄漏** | spike 通过 → 直接 WebView 方案 GO；失败 → 切 Fragment 方案 |

**P0 验收标准**：复合构建可编译通过；`readium/` assets 运行时可访问（WebView 加载不 404）；R8/测试任务不阻塞；多 WebView spike 通过。完整功能等价验证留待 P1 之后。

### P1 — 配置开关（3-4 天）
**目标**：`continuousScroll` 字段+分支点，默认 false，旧路径完全不变。

| 步骤 | 内容 |
|---|---|
| P1.1 | 在 fork 的 `EpubNavigatorFragment.Configuration` 加 `continuousScroll: Boolean = false` |
| P1.2 | 移植 develop 的分支点：`onCreateView` RecyclerView 创建路径、`invalidateResourcePager` 跳过、`onSettingsChange` 跳过、`goToPreviousResource` 路径 |
| P1.3 | `continuousScroll=false` 时所有分支走旧 ViewPager 路径（行为不变） |
| P1.4 | `continuousScroll=true` 时 RecyclerView 创建但空载（无 Adapter） |
| P1.5 | app 层 `buildConfiguration()` 加 `continuousScroll = ...`（通过 ScrollMode 枚举映射） |
| P1.6 | app 层加 `ScrollMode` 枚举 + `AppPreferences` 持久化 + `PreferencesMapper` 映射 |

**P1 验收标准**：`continuousScroll=false` 行为与 P0 完全一致；`=true` 时 RecyclerView 创建不崩溃。

### P2 — 最小连续滚动 vertical slice（5-7 天，**GO/NO-GO 关卡**）
**目标**：1 WebView item RecyclerView 路径，验证 scroll/load/inject/destroy。

| 步骤 | 内容 |
|---|---|
| P2.1 | 实现 `ContinuousResourceAdapter`（`epub/continuous/` 子包）：RecyclerView.Adapter，每 item 一个 WebView，fixed height = RecyclerView height |
| P2.2 | WebView 配置复用 R2BasicWebView 的初始化逻辑（URL 解析、WebView 配置、JS bridge 注册） |
| P2.3 | `onBindViewHolder`：加载章节资源，`onPageFinished` 后注入 JS（SELECTION_LISTENER/CENTER_TAP_LISTENER/buildReaderCss） |
| P2.4 | `onViewRecycled`：destroy WebView |
| P2.5 | 预加载窗口 ±1（共 3 个 WebView 常驻） |
| P2.6 | `ContinuousLocatorMapper` 骨架：scroll→Locator 粗粒度映射（itemTop/chapterHeight → progression） |
| P2.7 | `notifyCurrentLocation()` 加 throttle（debounce 200ms） |

**P2 GO/NO-GO 判据**：
- ✅ GO：50+ 章混合 EPUB（HTML+图片+footnote）不白屏不丢章；反复滚动/旋转/后台/process death 后 WebView 数不增长；JS 注入覆盖每章；`go(locator)` 恢复正确章节；`currentLocator` 频率受控
- ❌ NO-GO（切 Fragment 方案）：WebView recycle 后偶发空白/高度为 0；脚本注入不稳定；高度测量持续漂移；低端设备 OOM

### P3 — Locator/restore/progress（3-4 天）
- `ContinuousLocatorMapper` 完整实现：readingOrder[index]→高度缓存、Locator↔(position, intraProgress) 双向映射
- 进度条实时更新
- 书签读取/保存正确
- `go(locator)` 精确恢复到正确章节+粗略位置

### P4 — Selection/highlight/TTS/search（3-4 天）
- 多 WebView JS 注入路由：`evaluateJavascript` 按 href/position 路由到正确 WebView
- selection/center-tap/CSS 每章正常
- TTS：锁定 dominant visible WebView 提取句子，章节间过渡由 RecyclerView 滚动自动触发
- `onSettingsChange()` 完整重建：字号/边距/主题变更时清空高度缓存并重建
- `goForward/goBackward` 语义改为滚一个 viewport
- continuous 模式不注入 SCROLL_LISTENER，保留 `requestNextChapter()` 作 fallback

### P5 — 边界+回归（4-5 天）
- 固定布局 (fixed-layout) EPUB fallback（回退到 ViewPager）
- 内存策略：按设备 RAM 动态调整预加载窗口
- TOC/搜索导航适配
- 低端设备 + 大 EPUB + process death + rotation + RTL 回归
- R8 release 包全功能验证

**总估时：21-31 工作日（约 4-6 周）**

---

## 7. 风险评估

| 风险 | 优先级 | 缓解 |
|---|---|---|
| WebView 在 RecyclerView 中 layout/measure 异步行为 | 🔴 最高 | P0 spike 提前验证；fixed height + 延迟 notifyItemChanged 重新测量 |
| 多 WebView 内存压力（低端 OOM） | 🔴 高 | 按设备 RAM 动态预加载窗口；onViewRecycled destroy |
| Locator 精度不足（书签/TTS 恢复不准） | 🟡 中 | 先粗粒度近似，后续引入 DOM 精确定位 |
| evaluateJavascript 打到错误 WebView | 🟡 中 | 按 href/position 路由；P4 专项处理 |
| KMP 构建复杂度（完整 includeBuild） | 🟡 中 | P0 验证；主路径为完整 includeBuild，仅在 P0 构建确实无法通过时才作为最后手段评估单模块 vendored（非平行备选方案） |
| 上游 v4.0 原生支持连续滚动导致 fork 废弃 | 🟢 低 | 持续关注上游；改动局部化便于迁移 |
| R8 release 包 keep 规则缺失 | 🟡 中 | P0/P5 release 包验证 |

---

## 8. 立即行动项（P0 第一步）

1. **克隆仓库**：`git clone https://github.com/readium/kotlin-toolkit.git modules/readium`，checkout v3.3.0 tag
2. **配置复合构建**：`settings.gradle.kts` 加 `includeBuild` + `dependencySubstitution`
3. **验证等价性**：app 编译通过，功能与 AAR 等价
4. **验证 assets**：`readium/` 脚本和 CSS 在 library module 形态下被 app 访问
5. **廉价 spike**：3-item RecyclerView + 真 WebView，验证多 WebView load/destroy/注入
6. **若 spike 失败**：直接切 Fragment 包装方案，不打补丁

---

## 9. 决策记录

| 决策 | 选择 | 决策者 | 日期 |
|---|---|---|---|
| 核心路径 | 复合构建 + fork 内部修改 | 议会 3/3 + Oracle | 2026-07-01 |
| 基线 | v3.3.0 tag | 议会 3/3 + Oracle | 2026-07-01 |
| fork 粒度 | 完整 includeBuild 整仓库 | Oracle 推荐 + 用户确认 | 2026-07-01 |
| 回退策略 | 始终用 fork，git revert 回退 | Oracle + 用户待确认 | 2026-07-01 |
| Feature flag | 三态枚举 ScrollMode | 议会 2/3 + Oracle | 2026-07-01 |
| 连续容器 | 先直接 WebView，问题切 Fragment | 议会 + Oracle | 2026-07-01 |
| GO/NO-GO 关卡 | P2（+P0 spike 前移） | Oracle | 2026-07-01 |
