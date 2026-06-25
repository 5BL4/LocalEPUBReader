# EpubReader — Phase 2 进度文档 (Phase 2 Progress Report)

> **项目**: 本地 EPUB 阅读器 (EpubReader)  
> **Phase**: 2 — 书架 UI 与路由  
> **状态**: ✅ 完成 (构建通过, 17 项测试全部通过, 无阻塞项)  
> **日期**: 2026-06-25  
> **下一步**: 等待用户指令后开始 Phase 3 (Readium v3.x 引擎集成)

---

## 1. 已完成的交付物

### 1.1 核心修复 (M1 — 激活的 Phase 1 潜在缺陷)
| 文件 | 说明 |
|------|------|
| `core/Result.kt` | `runCatching`/`runCatchingAsync` 现在 **re-throw `CancellationException`** (catch CancellationException BEFORE Throwable)。修复了 Phase 1 的结构化并发缺陷 — 长时间可取消 I/O 经过此路径时, 取消操作会被错误地转为 `Result.Error`。新增 `getOrThrow()` 扩展函数。 |

### 1.2 新增依赖
| 依赖 | 版本 | 用途 |
|------|------|------|
| `kotlinx-collections-immutable` | 0.3.7 | `PersistentList` 用于 `@Immutable` UiState (架构师笔记: "List 包装为 PersistentList") |
| `compose-material-icons-extended` | BOM 管理 | `Icons.Default.Add` / `Icons.AutoMirrored.Filled.ArrowBack` |

### 1.3 导航层 (`navigation/`)
| 文件 | 说明 |
|------|------|
| `Routes.kt` | `@Serializable object BookshelfRoute`, `@Serializable data class ReaderRoute(val bookUuid: String)` — 类型安全路由 (NEVER #18) |
| `EpubReaderNavHost.kt` | `NavHost` + `composable<BookshelfRoute>` + `composable<ReaderRoute>`; 路由参数提取用 `backStackEntry.toRoute<ReaderRoute>()` (NEVER #22) |

### 1.4 主题层 (`ui/theme/`)
| 文件 | 说明 |
|------|------|
| `Color.kt` | 三套 Material3 配色: `LightColorScheme` (primary #1F6FEB), `DarkColorScheme` (primary #7B9CFF), `SepiaColorScheme` (暖纸色 surface #F4ECD8) |
| `Type.kt` | 默认 Material3 Typography |
| `Theme.kt` | `EpubReaderTheme(themeMode)` — 处理全部 4 个 ThemeMode: SYSTEM 用 `isSystemInDarkTheme()`, LIGHT/DARK/SEPIA 各自配色 (S8) |

### 1.5 Activity 与根组合函数
| 文件 | 说明 |
|------|------|
| `MainActivity.kt` | `@AndroidEntryPoint`, `enableEdgeToEdge()`, 注入 `PreferencesRepository` + `ErrorChannel`, `setContent { EpubReaderApp(...) }` |
| `ui/EpubReaderApp.kt` | 根 Composable: `collectAsStateWithLifecycle` 收集 prefs (NEVER #21) + ErrorChannel (S2 Snackbar); 包裹 `EpubReaderTheme(prefs.theme) { Scaffold { EpubReaderNavHost() } }` |

### 1.6 导入管线 (`data/bookimport/`)
> **注**: 原计划用 `data.import` 包名, 但 KSP 在 Java 层拒绝 `import` 关键字 (即使 Kotlin 反引号转义), 已重命名为 `data.bookimport`。

| 文件 | 职责 |
|------|------|
| `BookMetadata.kt` | 数据类 (title, author?, coverPath?) |
| `MetadataParser.kt` | 接口 — Phase 3 用 Readium Streamer 替换 |
| `FilenameMetadataParser.kt` | 桩实现: 从文件名提取标题 (替换 _/- 为空格, 首字母大写) |
| `InsufficientStorageException.kt` | IOException 子类, 携带 requiredBytes/availableBytes |
| `BookImporter.kt` | 接口 — `importBook(uri, onProgress)`; onProgress 在 IO 线程回调 (S7 注释) |
| `EpubBookImporter.kt` | 完整实现 (见 §2 关键实现) |
| `di/ImportModule.kt` | `@Binds` 绑定 BookImporter→EpubBookImporter, MetadataParser→FilenameMetadataParser |

### 1.7 书架 UI (`ui/bookshelf/`)
| 文件 | 职责 |
|------|------|
| `BookshelfUiState.kt` | `@Immutable data class(isLoading, books: PersistentList<BookEntity>, error: String?)` |
| `ImportState.kt` | 密封接口: Idle / Importing / Success / Error(message) |
| `BookshelfViewModel.kt` | `@HiltViewModel` (见 §2 关键实现) |
| `components/BookCard.kt` | 卡片: Coil AsyncImage 封面 或 首字母占位符 (S9); combinedClickable 点击/长按 |
| `BookshelfScreen.kt` | 主屏幕 (见 §2 关键实现) |

### 1.8 阅读器占位 (`ui/reader/`)
| 文件 | 职责 |
|------|------|
| `ReaderPlaceholderScreen.kt` | Phase 3 占位: 返回箭头 + 书籍 UUID 显示 |

### 1.9 资源
| 文件 | 说明 |
|------|------|
| `AndroidManifest.xml` | 新增 `MainActivity` (LAUNCHER intent-filter, exported=true) |
| `res/values/strings.xml` | 新增 10 条书架字符串 (title, empty, import, importing, success, error, cancel, delete, storage error, reader placeholder) |

### 1.10 测试
| 测试文件 | 测试数 | 类型 | 状态 | 验证规则 |
|----------|--------|------|------|----------|
| `PreferencesRepositorySetterTest` | 4 | 单元测试 (JUnit 5) | ✅ 通过 | SF-1: setter 轮询 (非直接 DataStore edit), ttsEngine 持久化, 默认 null |
| `EpubBookImporterTest` | 4 | 单元测试 (JUnit 5 + MockK) | ✅ 通过 | M2/M3/M4 + NEVER #27: 存储不足阻断, 成功导入, 拷贝失败清理, 元数据失败清理 |
| `BookshelfViewModelTest` | 4 | 单元测试 (JUnit 5 + Turbine) | ✅ 通过 | uiState 发射, 导入状态转换 (Success/Error), 取消重置 |
| `PreferencesRepositoryTest` (Phase 1) | 3 | 单元测试 | ✅ 通过 | NEVER #29 |
| `CoroutineExceptionGuardTest` (Phase 1) | 2 | 单元测试 | ✅ 通过 | NEVER #26 |
| **合计** | **17** | | **全部通过** | |

---

## 2. 关键实现细节

### 2.1 EpubBookImporter (Oracle M2/M3/M4 + NEVER #24/#27)
```
importBook(uri, onProgress):
  1. queryUriInfo(uri) → size(可空), displayName
  2. filesDir/books/ mkdirs
  3. 存储校验 (NEVER #27): if (size != null && size > 0 && dir.usableSpace < size) → Error(InsufficientStorageException)
  4. UUID 提前生成 (M3): targetFile = filesDir/books/${uuid}.epub
  5. NIO 拷贝 (NEVER #24): ByteBuffer(16KB) + FileChannel.write + yield()
  6. metadataParser.parse(path).getOrThrow()
  7. BookEntity(uuid, ..., fileSize=actualSize) + bookRepository.addBook().getOrThrow()
  8. success = true; return Success(book)
  catch (CancellationException) { throw e }  ← M2: 必须在 Exception 之前
  catch (Exception) { return Error(e) }
  finally { if (!success) targetFile?.delete() }  ← NEVER #27: 脏数据清理
```

### 2.2 BookshelfViewModel (NEVER #2/#12/#21 + S1/S3/S4)
- **注入接口**: `BookRepository`, `BookImporter` (NEVER #2 — 不注入 DAO)
- **无 SavedStateHandle** (S3 — BookshelfRoute 无参数, YAGNI)
- **双 StateFlow 隔离** (NEVER #12):
  - `importState: StateFlow<ImportState>` — 低频 (Idle→Importing→Success/Error, ~4 次/导入)
  - `importProgress: StateFlow<Float>` — 高频 (0f..1f, 数千次/导入)
- **取消机制** (S1): `importJob: Job?`, `cancelImport()` 取消 Job + 重置状态
- **异常映射** (S4): `mapImportError()` — InsufficientStorageException → R.string.error_insufficient_storage
- **协程异常兜底**: `viewModelScope.launch(exceptionHandler.handler)` (NEVER #26)

### 2.3 BookshelfScreen (NEVER #12/#15/#21 + S1/S9/S10)
- **SAF 导入** (NEVER #15): `rememberLauncherForActivityResult(OpenDocument())` + `arrayOf("application/epub+zip")`
- **进度隔离** (NEVER #12): `derivedStateOf { (importProgress * 100).toInt() / 100f }` — 阈值到 1%, ~100 次重组/100MB 文件
- **全部 collectAsStateWithLifecycle** (NEVER #21)
- **封面占位符** (S9): coverPath 为 null 时显示首字母
- **Edge-to-Edge** (S10): LazyVerticalGrid contentPadding 来自 Scaffold innerPadding
- **导入对话框**: Cancel 按钮 → `viewModel.cancelImport()` (S1)
- **删除确认**: 长按 → AlertDialog → `softDeleteBook(uuid)`

---

## 3. Oracle 计划审查项状态

### 必修项 (M#) — 全部完成
| # | 项目 | 状态 |
|---|------|------|
| M1 | Result.kt re-throw CancellationException | ✅ 已修复 (catch CancellationException BEFORE Throwable) |
| M2 | Importer catch 顺序 | ✅ catch(CancellationException) 在 catch(Exception) 之前 |
| M3 | UUID 在拷贝前生成 | ✅ targetFile = filesDir/books/${uuid}.epub |
| M4 | null OpenableColumns.SIZE 处理 | ✅ 跳过 usableSpace 校验, 用 File.length() |

### 建议项 (S#)
| # | 项目 | 状态 |
|---|------|------|
| S1 | 取消机制 (Job + Cancel 按钮) | ✅ 已实现 |
| S2 | ErrorChannel 消费 (Snackbar) | ✅ EpubReaderApp 中 collectAsStateWithLifecycle + Snackbar |
| S3 | 移除 BookshelfViewModel 的 SavedStateHandle | ✅ 已移除 (YAGNI) |
| S4 | 异常映射为本地化字符串 | ✅ mapImportError() + @ApplicationContext |
| S5 | Phase 3 元数据重解析流 | ⏸ 延迟 — MetadataParser 接口已预留 |
| S6 | 重复导入检测 | ⏸ 延迟 — TODO |
| S7 | onProgress 线程注释 | ✅ BookImporter.kt KDoc 注明 IO 线程 |
| S8 | Theme SYSTEM 用 isSystemInDarkTheme | ✅ 全部 4 个 ThemeMode 处理 |
| S9 | BookCard 封面占位符 | ✅ 首字母占位 |
| S10 | Grid contentPadding (Edge-to-Edge) | ✅ 来自 Scaffold innerPadding |
| S11 | 取消清理测试 | ✅ 拷贝失败 + 元数据失败测试验证清理 |

---

## 4. NEVER 规则合规性

| # | 规则 | 合规 | 验证方式 |
|---|------|------|----------|
| 2 | VM 注入接口非 DAO | ✅ | BookshelfViewModel 注入 BookRepository + BookImporter |
| 5 | 禁 file:// | ✅ | 无 file:// 引用 (Phase 3 HttpServer) |
| 12 | 高频状态 derivedStateOf 隔离 | ✅ | importProgress 独立 StateFlow + derivedStateOf 阈值 |
| 15 | SAF 用 rememberLauncherForActivityResult | ✅ | OpenDocument() |
| 18 | 类型安全路由 | ✅ | @Serializable + composable<Route> |
| 21 | collectAsStateWithLifecycle | ✅ | grep 确认无 collectAsState() |
| 22 | toRoute<T>() 提取参数 | ✅ | backStackEntry.toRoute<ReaderRoute>() |
| 24 | NIO + yield() 大文件拷贝 | ✅ | ByteBuffer + FileChannel.write + yield() |
| 26 | try-catch / CoroutineExceptionHandler | ✅ | Importer try-catch-finally; VM launch(handler) |
| 27 | usableSpace 校验 + finally 清理 | ✅ | 存储校验 + finally { delete() } |
| 29 | DataStore 强类型 Key | ✅ | Phase 1 已完成, Phase 2 未引入新 Key |

---

## 5. 包结构更新

```
com.epubreader.app
├── EpubReaderApplication.kt          (@HiltAndroidApp)
├── MainActivity.kt                   (@AndroidEntryPoint, enableEdgeToEdge) [NEW]
├── core/                             核心工具与契约
│   ├── Result.kt                     + getOrThrow(), CancellationException re-throw [MODIFIED]
│   └── ... (Phase 1 不变)
├── data/
│   ├── local/                        Room 数据层 (Phase 1 不变)
│   ├── remote/                       远程数据源 (Phase 1 不变)
│   ├── prefs/                        DataStore 强类型封装 (Phase 1 不变)
│   ├── repo/                         仓库实现 (Phase 1 不变)
│   └── bookimport/                   导入管线 [NEW]
│       ├── BookMetadata.kt
│       ├── MetadataParser.kt         (接口 — Phase 3 替换为 Readium Streamer)
│       ├── FilenameMetadataParser.kt (桩实现)
│       ├── InsufficientStorageException.kt
│       ├── BookImporter.kt           (接口)
│       └── EpubBookImporter.kt       (NIO + 存储校验 + 脏数据清理)
├── domain/
│   └── repository/                   仓库接口 (Phase 1 不变)
├── di/                               Hilt 模块
│   ├── DatabaseModule.kt             (Phase 1)
│   ├── DataStoreModule.kt            (Phase 1)
│   ├── AppBindingsModule.kt          (Phase 1)
│   └── ImportModule.kt               [NEW] BookImporter + MetadataParser 绑定
├── navigation/                       路由定义 [NEW]
│   ├── Routes.kt
│   └── EpubReaderNavHost.kt
└── ui/                               Compose 界面 [NEW]
    ├── EpubReaderApp.kt              (根 Composable)
    ├── theme/
    │   ├── Color.kt
    │   ├── Type.kt
    │   └── Theme.kt
    ├── bookshelf/
    │   ├── BookshelfUiState.kt       (@Immutable + PersistentList)
    │   ├── ImportState.kt            (密封接口)
    │   ├── BookshelfViewModel.kt     (@HiltViewModel)
    │   ├── BookshelfScreen.kt        (LazyVerticalGrid + SAF + 进度隔离)
    │   └── components/
    │       └── BookCard.kt           (Coil + 占位符)
    └── reader/
        └── ReaderPlaceholderScreen.kt (Phase 3 占位)
```

---

## 6. 构建配置变更

- **新增依赖**: `kotlinx-collections-immutable:0.3.7`, `compose-material-icons-extended` (BOM 管理)
- **compileSdk = 37, targetSdk = 35**: 不变
- **包名重命名**: `data.import` → `data.bookimport` (KSP 在 Java 层拒绝 `import` 关键字, 即使 Kotlin 反引号转义)

---

## 7. 测试覆盖

### 新增测试 (12 项)
| 测试 | 验证点 |
|------|--------|
| `setter round-trip fontSize` | SF-1: 通过 setter API 写入, Flow 读回 |
| `setter round-trip theme` | SF-1: theme setter 轮询 |
| `setTtsEngine value persists` | SF-1: ttsEngine 持久化 |
| `fresh repo defaults to null ttsEngine` | SF-1: 默认 null |
| `insufficient storage returns Error` | M4 + NEVER #27: 存储不足阻断, 无文件残留 |
| `successful import copies file and saves book` | 完整导入流程: 拷贝 + 元数据 + 保存 |
| `copy failure cleans up partial file` | NEVER #27: 拷贝失败 → finally 清理 |
| `metadata parse failure cleans up copied file` | NEVER #27: 元数据失败 → finally 清理 |
| `uiState emits books from repository` | observeBooks → UiState 映射 |
| `importBook transitions to Success` | Turbine: Idle → Importing → Success |
| `importBook transitions to Error on failure` | Turbine: Idle → Importing → Error |
| `cancelImport resets to Idle` | S1: 取消重置 |

### 测试技术适配
| 问题 | 解决方案 |
|------|----------|
| `MatrixCursor.addRow()` 单元测试不可用 | MockK mock Cursor 接口, 0 索引列位置 |
| `Uri.parse()` 单元测试抛异常 | `mockk<Uri>(relaxed = true)` |
| Windows DataStore 多次 edit 文件重命名问题 | 拆分为单次 edit 测试 |
| `withContext(UnconfinedTestDispatcher)` 死锁 | `runTest(UnconfinedTestDispatcher())` + `Dispatchers.Unconfined` |
| `viewModelScope` 用 `Dispatchers.Main` | `@BeforeEach setMain` + `@AfterEach resetMain` |

---

## 8. Phase 3 就绪评估

| Phase 3 需求 | 状态 |
|-------------|------|
| ReaderRoute(bookUuid) 类型安全路由 | ✅ 已定义, toRoute<ReaderRoute>() 已用 |
| ReaderPlaceholderScreen | ✅ 占位, Phase 3 替换 |
| BookRepository.getBook(uuid) | ✅ Phase 1 已提供 |
| PreferencesRepository (字体/主题/背景色) | ✅ Phase 1 已提供 |
| Result.fold / getOrThrow | ✅ 可用 (M1 已修复 CancellationException) |
| AppCoroutineExceptionHandler | ✅ 可注入 |
| ErrorChannel | ✅ EpubReaderApp 已消费 (Snackbar) |
| Readium v3 依赖 | ✅ Phase 1 已锁定 (shared/streamer/navigator 3.3.0) |
| HttpServer (规避 CORS) | ⏸ Phase 3 实现 |
| MetadataParser → Readium Streamer | ⏸ Phase 3 替换 FilenameMetadataParser |
| Locator 类型转换器 | ⏸ Phase 3 引入 (当前 raw String) |

---

## 9. 后续 Phase 衔接要点

### Phase 3 (Readium v3.x 引擎集成) — 立即可开始
- `Streamer.open()` 返回 `Try<Publication>` — 用 `fold` 处理 (NEVER #14)
- `HttpServer().serve(publication)` — localhost http:// 规避 CORS (NEVER #5 禁 file://)
- Fragment 缝合: JS Bridge 绑定在 `onCreateView`/`onDestroyView` (NEVER #6, #20)
- 引入 Readium `Locator` 类型转换器 (替换当前的 raw String)
- `Publication.close()` 在 `onDestroy`
- **替换 `FilenameMetadataParser`** 为 `ReadiumMetadataParser` (Streamer.open 提取封面/书名/作者)
- **补充 S5**: 为 Phase 2 导入的书籍添加元数据重解析命令 (更新 BookEntity 的 coverPath/author)

### Phase 4 (JS Bridge 与交互)
- `@JavascriptInterface` + `@Keep` 防 R8 (NEVER #23)
- 校验 `window.location.origin` (NEVER #8)
- 自动滚动 JS 绑定 `touchstart` → `cancelAnimationFrame` (NEVER #9)

### Phase 5 (知识管理与导出)
- flexmark-java HTML→MD (不用正则)
- `rememberLauncherForActivityResult` 唤起 ShareSheet/SAF

### Phase 6 (Media3 TTS)
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限已声明 (NEVER #10)
- 启动前台服务前注册 Notification Channel (NEVER #25)
- TTS 状态机 (Initializing/Ready/Error) — `OnInitListener` SUCCESS 前不调 `speak()` (NEVER #28)
- AudioAttributes + setHandleAudioFocus(true) (NEVER #11)
- 前台启动, 后台 MediaSession 维持 (NEVER #16)

### Phase 7 (测试驱动 + 同步)
- Repository 双数据源测试, 协程异常边界, Turbine, Compose UI E2E
- SyncManager: LWW 合并 (对比 updatedAt, isDeleted=1 优先)
- 可能需要领域模型映射层 (SF-2)

---

## 10. 验证命令参考

```powershell
# 编译 + 生成 APK
.\gradlew.bat :app:assembleDebug --no-daemon

# 运行单元测试 (17 项全部通过)
.\gradlew.bat :app:testDebugUnitTest --no-daemon

# 运行插桩测试 (需连接模拟器/设备)
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

**环境**: JDK 21, Android SDK, Gradle 9.4.1 (wrapper), Compose BOM 2026.06.00, Kotlin 2.3.20, KSP 2.3.9
