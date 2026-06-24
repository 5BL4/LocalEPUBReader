# 📖 本地 EPUB 阅读器 (EpubReader) - OpenCode Agent Harness (v13.0)

## 1. 项目概述 (Project Overview)
本项目旨在开发一款企业级、高性能、高安全性的 Android 本地 EPUB 阅读器。支持 EPUB 2/3 标准，提供沉浸式阅读、动态排版、知识管理（高亮/笔记/书签/MD导出）、TTS 听书以及**智能自动翻页/滚动**功能。应用需具备**知识资产导出能力**，完美适配 Android 14+ 后台唤醒限制及 **Android 15+ (API 35) 的 Edge-to-Edge 全屏规范**，彻底解决 WebView 跨域渲染、Compose 重组与生命周期痛点、大文件 I/O 内存尖刺与手势冲突。具备防御恶意 EPUB 脚本、R8 混淆及**全局协程异常防护机制**，并在底层架构上**完美预留云端多端同步的扩展接口 (Offline-First 策略)** 与**严格的工程化基线**，确保未来架构的平滑演进与生产环境的绝对稳定。

## 2. 技术栈与架构约束 (Tech Stack & Architecture)
Agent 在生成代码时，**必须严格遵循 (MUST)** 以下规范：

*   **开发语言**：Kotlin (100%，禁用 Java)。
*   **Target SDK**：**必须 >= 34 (Android 14)**，并向下兼容至 API 24，同时**前瞻适配 API 35 (Android 15)**。
*   **UI 框架 (Compose 性能、规范与全屏红线)**：
    *   Jetpack Compose (原生 UI) + Readium `EpubNavigatorFragment` (核心阅读视图)。
    *   **性能约束**：高频更新状态**必须**使用 `derivedStateOf` 隔离。自定义数据类**必须**使用 `@Immutable` 或 `@Stable`。
    *   **Flow 安全收集**：ViewModel 暴露的 Flow，在 UI 层**必须**使用 `collectAsStateWithLifecycle()` 收集，防止后台电量泄漏。
    *   **Edge-to-Edge 与 WindowInsets 适配 (Android 15+ 关键)**：必须妥善处理 `WindowInsets`。在 `Scaffold` 或顶层布局中使用 `Modifier.windowInsetsPadding()` 或 `safeDrawingPadding()`。在阅读模式下，必须正确配置 `WindowCompat.setDecorFitsSystemWindows(window, false)` 并实现沉浸式状态栏/导航栏的隐藏逻辑，防止底部控制栏与系统手势导航条重叠。
    *   **SAF 唤起规范**：强制使用 `rememberLauncherForActivityResult`。
    *   **Navigation 类型安全**：强制使用 `Navigation 2.8+` 配合 `kotlinx.serialization`。
*   **架构模式**：MVVM + Clean Architecture + MVI (单向数据流)。
*   **底层引擎 (Readium v3 函数式范式)**：强制使用 v3.x 最新稳定版，使用 `fold`/`onFailure` 处理 `Try<T>`。
*   **媒体与后台播放**：强制使用 `androidx.media3` 体系。
*   **本地存储 (Room 与 DataStore 工程化基线)**：
    *   **Room Schema 导出**：`@Database` 必须开启 `exportSchema = true`。实体必须包含 `uuid` (主键), `isDeleted` (软删除) 等同步字段。
    *   **DataStore 类型安全 (Type-Safe Preferences)**：鼓励使用 `Proto DataStore`，或在 `Preferences DataStore` 上封装一层基于 Kotlin 密封类 (Sealed Class) 或强类型键值对的 Wrapper。**严禁**在业务逻辑中直接散落硬编码的 String Key (如 `"font_size"`)，保持与 Navigation 2.8+ 同等级别的工程化严谨度。
*   **网络/同步层**：Remote + Local 双数据源策略 (Offline-First)。
*   **异步与并发**：Kotlin Coroutines + Flow。
*   **依赖注入**：Hilt (Dagger)。
*   **测试框架**：JUnit 5, MockK, Turbine, Compose UI Test。

## 3. 核心功能模块详细设计 (Core Features Detailed Design)

### 3.1 书架与文件 I/O 管理 (Bookshelf & I/O)
*   **UI**：Compose `LazyVerticalGrid` 网格布局。
*   **SAF 导入流处理 (大文件、存储限额与脏数据防护)**：
    *   必须使用 `ContentResolver.openInputStream(uri)`。对于大型 EPUB，**必须**使用 NIO `FileChannel.transferFrom()` 或自定义 Buffer (8KB~16KB) 结合 `yield()` 进行流拷贝。
    *   **物理存储限额校验 (关键)**：在开始拷贝前，**必须**通过 `StatFs` 或 `File.usableSpace` 校验内部存储剩余空间。若空间不足（如剩余空间小于文件大小），必须直接阻断并 Toast 提示用户。
    *   **脏数据绝对清理**：若中途因异常断开或用户取消，`finally` 块**必须有绝对可靠的脏文件 `delete()` 清理机制**，防止产生损坏的半成品文件。
*   **进度 UI 性能**：导入进度条更新**必须**通过 `derivedStateOf` 隔离。
*   **元数据解析**：使用 Readium `Streamer` 提取封面、书名、作者存入 Room。

### 3.2 核心阅读器与 Compose 缝合 (Reader Core & Interop)
*   **Readium v3.x 初始化**：启动 `HttpServer` 规避 CORS。
*   **Compose 嵌套 Fragment 与生命周期解耦**：JS Bridge 注入与销毁**必须严格绑定在 Fragment 的视图生命周期 (`onCreateView` / `onDestroyView`)**。实例销毁时调用 `Publication.close()`。
*   **翻页与排版**：支持仿真/平移/滚动，外部字体，经典背景色。
*   **进度记忆**：基于 `Locator` 精准记录。

### 3.3 自动翻页与滚动系统 (Auto-Page & Auto-Scroll)
*   **左右翻页**：时间间隔 `goForward()`。
*   **上下滚动 (防拔河)**：JS 端 `requestAnimationFrame` 必须绑定 `touchstart`，触摸瞬间 `cancelAnimationFrame` 暂停。

### 3.4 辅助阅读、交互与知识管理 (Knowledge Management & Export)
*   **目录与搜索**：侧滑 TOC，全文查找。
*   **书签与选段**：右上角书签，长按 ActionMode。
*   **JS Bridge 安全与防混淆**：校验 `window.location.origin`；`@JavascriptInterface` 必须加 `@Keep` 防 R8 混淆。
*   **Markdown 导出**：按书籍/章节聚合，使用 `rememberLauncherForActivityResult` 唤起 ShareSheet 或 SAF。

### 3.5 听书模块、Media3 与后台唤醒 (TTS Audio & Media3)
*   **Media3 架构与 Notification Channel**：声明前台服务权限，启动前**必须**注册专属 Notification Channel。
*   **后台启动限制突破**：通过 `MediaController` 或 `PendingIntent` 合规唤醒。
*   **TTS 引擎状态机与冷启动管理 (关键)**：原生 TTS 初始化是异步过程。**必须**包含完整的状态机封装（`Initializing`, `Ready`, `Error`）。**严禁**在 `OnInitListener` 返回 `SUCCESS` (TTS 未就绪) 之前触发 `tts.speak()`。必须处理 `isLanguageAvailable` 检查，若返回 `LANG_MISSING_DATA`，必须将状态反馈给 UI 层，以弹窗提示用户下载语言包。
*   **音频焦点管理**：配置 `AudioAttributes` 并 `setHandleAudioFocus(true)`。
*   **播放控制与定时**：悬浮窗/底部控制条，定时停止，JS 句子高亮。

### 3.6 云端同步预留架构 (Sync Architecture)
*   **数据流策略 (Offline-First)**：Local 优先，后台增量同步。
*   **Last-Write-Wins**：对比 `updatedAt`，`isDeleted = 1` 优先级最高。

---

## 4. ⛔ 绝对禁止清单 (NEVER DO THIS - Agent 红线约束)
1.  **NEVER** 使用 `Thread`, `Handler`, `AsyncTask` 或 `RxJava`。
2.  **NEVER** 在 ViewModel 中直接注入 Room `Dao` 或持有 `Context`/`View`。
3.  **NEVER** 使用 `MediaSessionCompat`。
4.  **NEVER** 直接读取 SAF `DocumentFile` URI 进行 ZIP 解压。
5.  **NEVER** 在 WebView 中使用 `file://` 协议。
6.  **NEVER** 在 Compose `AndroidView` 中直接 `new` Fragment。
7.  **NEVER** 对 Room 用户数据进行物理删除 (`DELETE FROM`)。
8.  **NEVER** 在 JS Bridge 中不校验 `window.location.origin`。
9.  **NEVER** 在自动滚动的 JS 代码中忽略 `touchstart` 事件。
10. **NEVER** 遗漏 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限。
11. **NEVER** 忽略 Media3 的 `AudioAttributes` 和音频焦点管理。
12. **NEVER** 将高频更新状态直接传入未隔离的 Compose 顶层节点。
13. **NEVER** 混用 Readium v2.x 和 v3.x API。
14. **NEVER** 使用 `try-catch` 包裹 Readium v3 的 `Try<T>` API (应使用函数式 API)。
15. **NEVER** 在 Compose 中使用 `startActivityForResult`。
16. **NEVER** 在后台直接调用 `startForegroundService()`。
17. **NEVER** 使用自增 `Int` 作为用户数据表的主键。**必须**使用 `uuid: String`。
18. **NEVER** 使用基于 String 拼接的传统 Navigation 路由传参。
19. **NEVER** 在 Room `@Database` 注解中设置 `exportSchema = false`。
20. **NEVER** 在 Fragment 的 `onDestroy` 中销毁 WebView 或移除 JS Bridge。**必须**在 `onDestroyView` 中执行。
21. **NEVER** 在 Compose 中使用 `collectAsState()` 收集 ViewModel 的 Flow。**必须**使用 `collectAsStateWithLifecycle()`。
22. **NEVER** 在 Hilt ViewModel 中使用 `savedStateHandle.get<String>()` 提取路由参数。**必须**使用 `savedStateHandle.toRoute<T>()`。
23. **NEVER** 让带有 `@JavascriptInterface` 的类或方法被 R8 混淆。**必须**添加 `@Keep`。
24. **NEVER** 使用默认的 `InputStream.copyTo()` 拷贝几百 MB 的大文件。**必须**使用 NIO 或自定义 Buffer 并结合 `yield()`。
25. **NEVER** 在未注册 Notification Channel 的情况下启动 Media3 前台服务。
26. **NEVER** 在不使用 `try-catch` 或 `CoroutineExceptionHandler` 的情况下，于 `viewModelScope` 中裸奔执行高风险的 I/O、网络或数据库操作。**必须**确保底层未捕获异常被转化为安全的 UI 状态（如 `Result.Error`）通知用户，防止主线程崩溃退出。
27. **NEVER** 在拷贝文件前不校验 `File.usableSpace`。**必须**阻断空间不足的操作并清理 `finally` 脏数据。
28. **NEVER** 在 TTS `OnInitListener` 回调 `SUCCESS` 之前调用 `tts.speak()`。**必须**维护 TTS 状态机。
29. **NEVER** 在 DataStore 中直接散落硬编码的 String Key。**必须**使用强类型 Wrapper 或 Proto DataStore。

---

## 5. 开发阶段划分 (Agent Tasks Breakdown)
*   **Phase 1: 基础架构与同步数据模型** (配置 Hilt/Room/Schema 导出，创建 Entities，定义 `RemoteDataSource`，**封装 DataStore 强类型 Wrapper**)。
*   **Phase 2: 书架 UI 与路由** (Compose 书架, `rememberLauncherForActivityResult`, 进度隔离, **Navigation 2.8+ 强类型路由**, **Edge-to-Edge 基础配置**)。
*   **Phase 3: Readium v3.x 引擎集成** (Local Server, Fragment 缝合, 函数式错误处理, 外部字体, JS Bridge 视图生命周期与 `@Keep`)。
*   **Phase 4: JS Bridge 与交互系统** (侧滑目录, 搜索, 选词, 自动滚动防拔河, 安全源校验)。
*   **Phase 5: 知识管理与导出** (笔记列表, HTML 转 MD 聚合, ShareSheet/SAF 导出)。
*   **Phase 6: Media3 TTS 听书** (合规唤醒, Notification Channel, Audio Focus, **TTS 状态机与语言包校验**, 定时关闭, 句子高亮, **大文件 I/O NIO 与存储限额校验**)。
*   **Phase 7: 测试驱动 (TDD)** (Repository 双数据源测试, 协程异常边界测试, Turbine, Compose UI E2E)。

## 6. 评估标准与测试用例 (Test Harness / Evaluation)
1.  **数据模型与 Schema 审查**：Room Entity 主键为 `uuid`，`exportSchema = true`。
2.  **存储限额与脏数据测试 (关键)**：模拟设备存储空间不足，验证导入大文件时是否提前阻断提示；模拟拷贝中途抛出异常，验证内部存储中**绝对没有**残留的半成品脏文件。
3.  **I/O 与取消**：100MB+ EPUB 导入无 ANR 且无内存尖刺；中途取消瞬间停止。
4.  **Edge-to-Edge UI 验收 (Android 15+)**：在 API 35 模拟器上，验证阅读器的底部控制栏、设置弹窗 (BottomSheet) 是否完美避开了系统手势导航条，没有发生 UI 重叠。
5.  **跨域与多媒体**：复杂 EPUB 3 完美渲染，无 CORS 报错。
6.  **Compose 性能与生命周期**：Recomposition 次数极低；退到后台时 Flow 收集自动暂停。
7.  **TTS 语言包缺失测试**：禁用系统 TTS 语言包，点击听书，验证应用不崩溃，且正确弹出“缺少语言包，请前往系统设置下载”的提示。
8.  **音频焦点打断**：TTS 播放时拨打语音，验证自动暂停/Duck。
9.  **后台启动合规**：锁屏状态下触发 TTS，不抛出 `BackgroundServiceStartNotAllowedException`。
10. **协程异常边界测试**：模拟 Room 数据库损坏或 I/O 读写权限被拒，验证 ViewModel 是否捕获异常并转化为 `UiState.Error`，**App 绝不崩溃**。
11. **Readium 错误分支**：包含对 `Try.Failure` 的明确 UI 提示。
12. **路由类型安全**：使用 `toRoute<T>()` 提取参数。
13. **Release 包 JS 交互**：开启 R8 混淆，JS 调用原生 API 依然有效。
14. **导出质量**：MD 文件排版整洁，包含章节层级。
15. **状态与内存**：`onDestroyView` 销毁 WebView；`onDestroy` 调用 `tts.shutdown()` 和 `Publication.close()`。
16. **安全验收**：恶意 EPUB 调用 `AndroidNativeApi` 被拦截。

## 7. 架构师的避坑指南 (Architect's Notes - 必读)
*   **Edge-to-Edge 的 `safeDrawingPadding` 陷阱**：在 Compose 中，如果使用了 `Scaffold`，它默认会消耗 WindowInsets。如果内部的 BottomSheet 需要自己处理 Insets，必须确保外层没有过度消费，或者使用 `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)` 精确控制，否则在 Android 15 上底部按钮会被手势条遮挡。
*   **TTS 冷启动的“抢跑”崩溃**：很多开发者在 ViewModel 初始化时立刻调用 `tts.speak()`，此时 `OnInitListener` 还没回调，导致静默失败。务必通过 `StateFlow<TtsState>` 暴露状态，控制逻辑必须等待 `TtsState.Ready` 才能下发播放指令。
*   **协程异常的“静默吞噬”与“全局崩溃”**：在 `viewModelScope.launch` 中，如果子协程抛出异常且没有被 `try-catch` 捕获，默认行为是取消整个 `viewModelScope` 甚至导致 App 崩溃。对于文件拷贝等高危操作，务必在局部使用 `try-catch` 并更新 UI 状态，而不是依赖全局 Handler 掩盖问题。
*   **物理存储的“假满”状态**：使用 `File.usableSpace` 时，注意它返回的是当前应用可用的空间（受限于配额）。对于极端情况，拷贝循环中的 `IOException` 依然是最后的防线，`finally` 块中的 `file.delete()` 必须确保执行。
*   **Navigation 2.8+ 参数提取陷阱**：使用 `savedStateHandle.toRoute<ReaderRoute>()` 安全反序列化。
*   **JS Bridge R8 混淆灾难**：务必在 `@JavascriptInterface` 类上加 `@Keep`。
*   **大文件 I/O 与协程取消**：使用 `FileChannel` 拷贝时，循环中必须调用 `yield()` 响应取消。
*   **Media3 Notification Channel 遗漏**：启动前台服务前务必创建 Channel。
*   **Fragment 视图与实例生命周期分离**：`onDestroyView` 清理 WebView，`onDestroy` 清理 Publication。
*   **Room Schema 迁移基线**：对比 JSON 差异手写 SQL 迁移。
*   **HTML 转 Markdown**：使用 `flexmark-java`，不写正则。
*   **软删除与“幽灵笔记”**：`isDeleted = 1` 同步给服务器。
*   **Fragment 状态与连续打开 OOM**：退回书架时确保 `Streamer` 和 `Publication` 都 `close()`。
*   **BackgroundServiceStartNotAllowedException**：前台启动，后台 `MediaSession` 维持。
*   **Readium v3 `Try<T>`**：使用 `when` 或 `fold` 处理。
*   **Audio Focus Transient Loss**：遇微信语音暂停，结束视业务恢复。
*   **Compose @Stable 欺骗性**：`List` 包装为 `PersistentList`。
*   **Readium CSS 注入闪烁**：预先注入 CSS 变量。
*   **软删除查询**：加 `WHERE isDeleted = 0`。
*   **定时器生命周期**：`onPause` 暂停自动翻页。