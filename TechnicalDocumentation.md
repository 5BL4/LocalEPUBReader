# EpubReader — 技术文档 (Technical Documentation)

> **项目**: 本地 EPUB 阅读器 (EpubReader)
> **状态**: Phase 1 ✅ → Phase 2 ✅ → Phase 3 ✅ 完成
> **日期**: 2026-06-25
> **下一步**: 等待用户指令后开始 Phase 4 (JS Bridge 与交互系统)

本文档合并了 Phase 1 (基础架构与同步数据模型)、Phase 2 (书架 UI 与路由)、Phase 3 (Readium v3.x 引擎集成) 的全部进度文档。

---

# Phase 1 — 基础架构与同步数据模型

> **状态**: ✅ 完成 (Oracle 审查通过, ACCEPT_WITH_CHANGES, 无阻塞项)

## 1.1 已完成的交付物

### Gradle 工程脚手架
| 文件 | 说明 |
|------|------|
| `settings.gradle.kts` | pluginManagement + dependencyResolutionManagement, 项目名 EpubReader, include :app |
| `gradle/libs.versions.toml` | 版本目录 — 所有依赖版本锁定 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 (已缓存) |
| `build.gradle.kts` | 根构建文件, 插件 `apply false` |
| `app/build.gradle.kts` | 应用模块: compileSdk=37, targetSdk=35, minSdk=24, jvmTarget=17, Compose, KSP, Hilt, 序列化, 核心库脱糖 |
| `gradle.properties` | JVM 参数, AndroidX, nonTransitiveRClass |
| `local.properties` | SDK 路径 (gitignored) |
| `app/proguard-rules.pro` | R8 规则: @JavascriptInterface @Keep (预置, Phase 4 用) |

### Manifest 与 Application
- `AndroidManifest.xml`: 声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限 (NEVER #10), Application = `.EpubReaderApplication`
- `EpubReaderApplication.kt`: `@HiltAndroidApp` — 仅此一项, 不注册全局异常处理器 (S8)

### 核心包 (`core/`)
| 文件 | 职责 |
|------|------|
| `Result.kt` | 函数式结果类型 `Success<T>`/`Error` — Repository 返回类型 (非 UiState)。`runCatching`/`runCatchingAsync` re-throw `CancellationException` (Phase 2 M1 修复) |
| `Syncable.kt` | 同步契约接口 + `SyncCursor`/`SyncPage`/`PushAck` |
| `DispatchersProvider.kt` | 可注入的 Dispatcher 提供者 (IO/Default/Main/MainImmediate) — 可测试 |
| `ErrorChannel.kt` | `@Singleton` 全局错误通道 (`SharedFlow<AppError>`) |
| `AppCoroutineExceptionHandler.kt` | `@Singleton` 协程异常兜底处理器 (日志 + 发射到 ErrorChannel) |
| `StringProvider.kt` | 本地化字符串接口 (Phase 2 M5 — VM 不注入 Context) |
| `readium/LocatorMapper.kt` | Locator↔String 转换工具 (Phase 3 — 非 Room @TypeConverter, 保持数据层解耦) |
| `README.md` | 约定文档: Result vs UiState, Readium Try→Result 桥接规则, 异常策略 |

### Room 数据层 (`data/local/`)
**5 个实体** (均实现 `Syncable`, `uuid: String` 主键, `isDeleted` 软删除, `createdAt`/`updatedAt`/`syncedAt`/`userId` 同步字段):

| 实体 | 表名 | 业务字段 | 外键 |
|------|------|----------|------|
| `BookEntity` | books | title, author, coverPath, filePath, fileSize, format | — |
| `ReadingProgressEntity` | reading_progress | bookUuid, locator(String), progress(Double) | →books (NO_ACTION) |
| `BookmarkEntity` | bookmarks | bookUuid, locator, label | →books (NO_ACTION) |
| `HighlightEntity` | highlights | bookUuid, locator, text, color | →books (NO_ACTION) |
| `NoteEntity` | notes | bookUuid, highlightUuid, locator, content | →books + →highlights (NO_ACTION) |

- **索引**: 每个实体在 `updatedAt`/`syncedAt`/`isDeleted` 上建索引; 子实体额外在 `bookUuid` 上建索引
- **外键**: 全部 `onDelete = NO_ACTION` (无 CASCADE — NEVER #7)
- **5 个 DAO**: `upsert`, `softDelete`, `observeActive`, `observeById`, `getById`, `getAllActive`, `getDirty`, `getUpdatedSince`, `markSynced`
- `Converters.kt`: `List<String>` ↔ JSON (Locator 保持 raw String, 通过 LocatorMapper 在 reader 层转换 — Phase 3 D2)
- `AppDatabase.kt`: `@Database(version=1, exportSchema=true)`, 5 个实体, 5 个 DAO 访问器
- **Schema 已导出**: `app/schemas/.../1.json` (已纳入 git — 迁移基线)

### 远程数据源 (`data/remote/`)
- `RemoteDataSource.kt`: 同步传输接口 (仅 `pullSince`/`push`, 不含 LWW 合并逻辑 — 合并留给 Phase 7 SyncManager)
- `NoopRemoteDataSource.kt`: `@Singleton` 空实现 (Phase 1 本地优先, 无后端)

### DataStore 强类型封装 (`data/prefs/`)
- `AppPreferences.kt`: `@Immutable` 快照数据类 — fontSize, fontFamily, lineSpacing, theme, backgroundColor, autoPageIntervalMs, autoScrollSpeed, ttsRate, ttsPitch, ttsEngine (**无 locator/progress** — 阅读进度在 Room)
- `ThemeMode.kt`: 枚举 (LIGHT/DARK/SEPIA/SYSTEM)
- `PreferenceKeys.kt`: `internal object` — 所有 `Preferences.Key<T>` 集中于此 (业务逻辑不可见原始 String Key — NEVER #29)
- `PreferencesRepository.kt` / `PreferencesRepositoryImpl.kt`: 强类型接口 + 实现, `Flow<AppPreferences>` + 10 个类型化 suspend setter

### 仓库层 (`domain/repository/` + `data/repo/`)
- **5 个接口** (在 `domain.repository`): `BookRepository`, `ReadingProgressRepository`, `BookmarkRepository`, `HighlightRepository`, `NoteRepository` — 返回 `Flow<T>` (观察) 或 `Result<T>` (一次性操作)
- **5 个实现** (在 `data.repo`): `@Singleton @Inject constructor(dao, dispatchers)` — 一次性操作用 `withContext(dispatchers.io) { Result.runCatchingAsync { ... } }` 包裹 (NEVER #26)

### Hilt DI (`di/`)
- `DatabaseModule.kt`: 提供 `AppDatabase` (Singleton) + 5 个 DAO (仅用于仓库注入 — NEVER #2)
- `DataStoreModule.kt`: 提供 `DataStore<Preferences>` (Singleton)
- `AppBindingsModule.kt`: 9 个 `@Binds` 绑定 (DispatchersProvider, StringProvider, RemoteDataSource, PreferencesRepository, 5 个仓库接口)
- `ReadiumModule.kt` [Phase 3]: 提供 DefaultHttpClient, AssetRetriever, PublicationOpener
- `ImportModule.kt` [Phase 2/3]: BookImporter + MetadataParser 绑定 (Phase 3 改绑 ReadiumMetadataParser)

---

# Phase 2 — 书架 UI 与路由

> **状态**: ✅ 完成 (构建通过, 17 项测试全部通过, Oracle 结果审查 ACCEPT_WITH_CHANGES, 无阻塞项)

## 2.1 已完成的交付物

### 核心修复 (M1 — 激活的 Phase 1 潜在缺陷)
- `core/Result.kt`: `runCatching`/`runCatchingAsync` 现在 **re-throw `CancellationException`** (catch CancellationException BEFORE Throwable)。修复了结构化并发缺陷 — 长时间可取消 I/O 经过此路径时, 取消操作会被错误地转为 `Result.Error`。新增 `getOrThrow()` 扩展函数。

### 新增依赖
| 依赖 | 版本 | 用途 |
|------|------|------|
| `kotlinx-collections-immutable` | 0.3.7 | `PersistentList` 用于 `@Immutable` UiState |
| `compose-material-icons-extended` | BOM 管理 | `Icons.Default.Add` / `Icons.AutoMirrored.Filled.ArrowBack` |

### 导航层 (`navigation/`)
| 文件 | 说明 |
|------|------|
| `Routes.kt` | `@Serializable object BookshelfRoute`, `@Serializable data class ReaderRoute(val bookUuid: String)` — 类型安全路由 (NEVER #18) |
| `EpubReaderNavHost.kt` | `NavHost` + `composable<BookshelfRoute>` + `composable<ReaderRoute>`; 路由参数提取用 `backStackEntry.toRoute<ReaderRoute>()` (NEVER #22)。Phase 3 将 ReaderPlaceholderScreen 替换为 ReaderScreen |

### 主题层 (`ui/theme/`)
| 文件 | 说明 |
|------|------|
| `Color.kt` | 三套 Material3 配色: `LightColorScheme` (primary #1F6FEB), `DarkColorScheme` (primary #7B9CFF), `SepiaColorScheme` (暖纸色 surface #F4ECD8) |
| `Type.kt` | 默认 Material3 Typography |
| `Theme.kt` | `EpubReaderTheme(themeMode)` — 处理全部 4 个 ThemeMode: SYSTEM 用 `isSystemInDarkTheme()`, LIGHT/DARK/SEPIA 各自配色 (S8) |

### Activity 与根组合函数
| 文件 | 说明 |
|------|------|
| `MainActivity.kt` | `@AndroidEntryPoint`, `enableEdgeToEdge()`, 注入 `PreferencesRepository` + `ErrorChannel`, `setContent { EpubReaderApp(...) }` |
| `ui/EpubReaderApp.kt` | 根 Composable: `collectAsStateWithLifecycle` 收集 prefs (NEVER #21) + ErrorChannel (S2 Snackbar); 包裹 `EpubReaderTheme(prefs.theme) { Scaffold { EpubReaderNavHost() } }` |

### 导入管线 (`data/bookimport/`)
| 文件 | 职责 |
|------|------|
| `BookMetadata.kt` | 数据类 (title, author?, coverPath?) |
| `MetadataParser.kt` | 接口 — Phase 3 用 Readium Streamer 替换 |
| `FilenameMetadataParser.kt` | 桩实现: 从文件名提取标题 (Phase 3 保留, 不再绑定) |
| `ReadiumMetadataParser.kt` [Phase 3] | Readium Streamer 实现: PublicationOpener 打开 EPUB, 提取 title/author/cover, 保存封面到 filesDir/covers/ |
| `InsufficientStorageException.kt` | IOException 子类, 携带 requiredBytes/availableBytes |
| `BookImporter.kt` | 接口 — `importBook(uri, onProgress)`; onProgress 在 IO 线程回调 |
| `EpubBookImporter.kt` | 完整实现 (NIO + 存储校验 + 脏数据清理) |
| `di/ImportModule.kt` | `@Binds` 绑定 BookImporter→EpubBookImporter, MetadataParser→ReadiumMetadataParser (Phase 3 改绑) |

### 书架 UI (`ui/bookshelf/`)
| 文件 | 职责 |
|------|------|
| `BookshelfUiState.kt` | `@Immutable data class(isLoading, books: PersistentList<BookEntity>, error: String?)` |
| `ImportState.kt` | 密封接口: Idle / Importing / Success / Error(message) |
| `BookshelfViewModel.kt` | `@HiltViewModel` — 注入 BookRepository, BookImporter, StringProvider, ErrorChannel (NEVER #2)。双 StateFlow 隔离 (NEVER #12)。Phase 3 新增 reparseMetadata(uuid) |
| `components/BookCard.kt` | 卡片: Coil AsyncImage 封面 或 首字母占位符 (S9); combinedClickable 点击/长按 |
| `BookshelfScreen.kt` | 主屏幕: LazyVerticalGrid + SAF 导入 + 进度隔离。Phase 3 新增长按上下文菜单 (删除 + 重新提取元数据) |

## 2.2 关键实现细节

### EpubBookImporter (Oracle M2/M3/M4 + NEVER #24/#27)
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

### BookshelfViewModel (NEVER #2/#12/#21 + S1/S3/S4 + M5/M6)
- **注入接口**: `BookRepository`, `BookImporter`, `StringProvider`, `ErrorChannel` (NEVER #2 — 不注入 DAO/Context)
- **双 StateFlow 隔离** (NEVER #12): `importState` (低频) + `importProgress` (高频)
- **取消机制** (S1): `importJob: Job?`, `cancelImport()` 取消 Job + 重置状态
- **异常映射** (S4 + M5): `mapImportError()` 通过 `StringProvider` 获取本地化字符串
- **删除错误路由** (M6): `softDeleteBook` 失败时通过 `errorChannel.tryEmit()` 路由
- **协程异常兜底**: `viewModelScope.launch(exceptionHandler.handler)` (NEVER #26)

---

# Phase 3 — Readium v3.x 引擎集成

> **状态**: ✅ 完成 (构建通过, 38 项测试全部通过, Oracle 计划审查 ACCEPT_WITH_CHANGES, Oracle 结果审查 ACCEPT_WITH_CHANGES, 所有 must-fix 已修复)

## 3.1 关键架构决策 (Oracle 审查通过)

### D1: 不实现 HttpServer (v3.x 偏离 harness)
Harness §3.2/§4.5 假设 v2.x 的 `HttpServer().serve(publication)`。**Readium v3.3.0 彻底移除了 HttpServer**, `EpubNavigatorFactory` 使用内部 `WebViewServer` 拦截 `shouldInterceptRequest()`。无需本地 HTTP 服务器, 无需 INTERNET 权限, 无 CORS 问题。**NEVER #5 (禁 file://) 仍然满足** — WebViewServer 通过拦截提供资源, 从不使用 file:// URL。Harness §6.5 (无 CORS 报错) 也满足。文档记录此偏离。

### D2: Locator 转换工具 (非 Room @TypeConverter)
保持所有实体的 `locator: String` 字段不变 (无 DB schema 变更, 无迁移)。创建 `LocatorMapper` 工具 (`core/readium/`), 提供 `String.toLocator(): Locator?` 和 `Locator.toJsonString(): String` 扩展函数。理由: (a) Clean Architecture §2 禁止数据层耦合 Readium 类型; (b) Phase 7 同步传输 JSON 字符串; (c) schema 保持 v1 — 无迁移风险。Harness "引入 Readium Locator 类型转换器" 解释为转换工具而非 Room @TypeConverter 注解。

### D3: Publication.close() 在 ReaderViewModel.onCleared()
Harness §7 说 "onDestroy 清理 Publication"。在 Compose+ViewModel 架构中, `ViewModel.onCleared()` 是正确的等价物 — 在 NavBackStackEntry 弹出时调用 (阅读会话永久结束)。`onCleared()` **在配置变更 (旋转) 时存活** — Publication 不会关闭重开, 这实际上比 Fragment.onDestroy 更好 (避免重新打开大型 EPUB)。WebView 清理委托给 `EpubNavigatorFragment.onDestroyView()` (Readium 内部)。

### D4: AndroidViewBinding + FragmentContainerView
使用 XML 布局 `FragmentContainerView` + `android:name` + Compose `AndroidViewBinding`。FragmentManager 通过 `android:name` 实例化 `ReaderHostFragment` — **无 Compose 工厂中的 `Fragment()` 构造调用** (NEVER #6)。JS Bridge (Phase 4) 绑定在 `onCreateView`/`onDestroyView` (NEVER #20)。

### D5: Readium 组件通过 Hilt 提供
`ReadiumModule` 提供 `DefaultHttpClient`, `AssetRetriever(@ApplicationContext)`, `PublicationOpener` 作为 `@Singleton`。`PublicationOpener` 无状态, 线程安全。`AssetRetriever` 需要 Context — `@ApplicationContext` 确保应用上下文 (无泄漏)。

## 3.2 已完成的交付物

### Phase 3A: Readium 数据基础设施
| 文件 | 说明 |
|------|------|
| `core/readium/LocatorMapper.kt` [NEW] | `String.toLocator(): Locator?` (Locator.fromJSON) + `Locator.toJsonString(): String` (toJSON().toString())。KDoc 解释 D2 决策 + Phase 7 canonical JSON 注记 |
| `di/ReadiumModule.kt` [NEW] | @Module 提供 DefaultHttpClient, AssetRetriever(@ApplicationContext), PublicationOpener(DefaultPublicationParser, pdfFactory=null) |
| `data/bookimport/ReadiumMetadataParser.kt` [NEW] | @Singleton 实现 MetadataParser。PublicationOpener 打开 EPUB, 提取 title/author/cover (publication.cover()), 保存封面到 filesDir/covers/。Try.getOrElse 函数式处理 (NEVER #14), runCatchingAsync 包裹 (NEVER #26), finally 关闭 Publication |
| `di/ImportModule.kt` [MODIFY] | 改绑 MetadataParser: FilenameMetadataParser → ReadiumMetadataParser |
| `proguard-rules.pro` [MODIFY] | 新增 Readium keep 规则: shared/streamer/navigator 包 + @Keep |
| `domain/repository/BookRepository.kt` [MODIFY] | 新增 `reparseMetadata(uuid): Result<BookEntity>` (S5) |
| `data/repo/BookRepositoryImpl.kt` [MODIFY] | 注入 MetadataParser, 实现 reparseMetadata |
| `ui/bookshelf/BookshelfViewModel.kt` [MODIFY] | 新增 reparseMetadata(uuid) action |
| `ui/bookshelf/BookshelfScreen.kt` [MODIFY] | 长按上下文菜单: 删除 + 重新提取元数据 (S6) |

### Phase 3B: 阅读器引擎
| 文件 | 说明 |
|------|------|
| `ui/reader/ReaderUiState.kt` [NEW] | @Immutable data class: isLoading, bookTitle, navigatorFactoryReady, error (**无 currentLocator** — M2 修复: 高频状态隔离) |
| `ui/reader/ReaderViewModel.kt` [NEW] | @HiltViewModel。注入 SavedStateHandle + 仓库 + Readium 组件 + StringProvider + ErrorChannel + AppCoroutineExceptionHandler。**M2 (NEVER #22)**: `bookUuid = savedStateHandle.toRoute<ReaderRoute>().bookUuid`。**M3**: init 加载保存的 locator (readingProgressRepository.getProgress → toLocator)。**D3**: Publication.close() 在 onCleared()。**M2 (NEVER #12)**: currentLocator 独立 StateFlow, 不在 UiState。Try.fold/getOrElse (NEVER #14)。viewModelScope.launch(handler) (NEVER #26)。防抖保存进度 (1s delay) |
| `ui/reader/ReaderHostFragment.kt` [NEW] | @AndroidEntryPoint Fragment, 实现 EpubNavigatorFragment.Listener。**M4**: bind() 仅存储 VM 引用; startCollectors() 在 onViewCreated() 调用 (M1 修复: 不在 bind() 访问 viewLifecycleOwner)。**M5**: DefaultReaderFragmentFactory 兜底 (进程死亡恢复)。**S1**: setupNavigator 提交初始 prefs (避免 CSS 闪烁)。**S2**: servedAssets "fonts/.*" (JS Bridge 基础设施, Phase 4 注册接口)。onDestroyView 重置 navigatorAdded + 取消 collector |
| `ui/reader/ReaderScreen.kt` [NEW] | 替换 ReaderPlaceholderScreen。hiltViewModel() + collectAsStateWithLifecycle (NEVER #21)。**M5 (NEVER #6)**: AndroidViewBinding + XML FragmentContainerView android:name (FragmentManager 实例化 Fragment)。**S5**: Edge-to-Edge 沉浸式 (WindowCompat + WindowInsetsControllerCompat)。Scaffold + TopAppBar + 加载/错误状态 |
| `ui/reader/PreferencesMapper.kt` [NEW] | `AppPreferences.toEpubPreferences(): EpubPreferences` — fontSize (/16.0 clamp), fontFamily (→FontFamily), lineSpacing, backgroundColor (→Color), theme (→Theme), textColor=null (M3 修复: 避免 Color(0) 透明), scroll=true |
| `res/layout/fragment_reader_host_container.xml` [NEW] | FragmentContainerView android:name=ReaderHostFragment (Compose 宿主容器) |
| `res/layout/fragment_reader_host.xml` [NEW] | FrameLayout id=reader_container (ReaderHostFragment 自身布局, 承载子 EpubNavigatorFragment) |
| `navigation/EpubReaderNavHost.kt` [MODIFY] | ReaderPlaceholderScreen → ReaderScreen |
| `ui/reader/ReaderPlaceholderScreen.kt` [DELETE] | 已替换 |
| `assets/fonts/README.txt` [NEW] | 字体文件占位 (S4 基础设施就绪, .ttf 待捆绑) |

### Phase 3D: 测试 (18 项新增, 38 项总计)
| 测试文件 | 测试数 | 验证点 |
|----------|--------|--------|
| `LocatorMapperTest` | 5 | String↔Locator 往返, null/blank/无效 JSON 返回 null |
| `ReadiumMetadataParserTest` | 5 | title/author 提取, Try→Result 映射 (NEVER #14), Publication 关闭, 封面失败不阻断 |
| `ReaderViewModelTest` | 4 | UiState 默认值/不可变性, 依赖注入装配 (注: toRoute 需 Bundle, JVM 测试限制, 简化为装配验证) |
| `BookRepositoryImplReparseTest` | 4 | reparseMetadata 更新元数据, 保留旧封面, 书籍未找到返回 Error, 解析失败返回 Error |

## 3.3 Readium v3.3.0 API 修正 (编译时发现)

研究文档中的 API 引用与实际 v3.3.0 有多处偏差, 编译时已全部修正:

| 文档 API | 实际 v3.3.0 API |
|----------|----------------|
| `org.readium.r2.streamer.opener.PublicationOpener` | `org.readium.r2.streamer.PublicationOpener` |
| `org.readium.r2.streamer.retriever.AssetRetriever` | `org.readium.r2.shared.util.asset.AssetRetriever` |
| `org.readium.r2.navigator.epub.FontFamily/Theme/Color` | `org.readium.r2.navigator.preferences.FontFamily/Theme/Color` |
| `Theme.DEFAULT` | 仅 LIGHT/DARK/SEPIA (用 LIGHT 兜底) |
| `Color(R,G,B,A)` 4 参数 | `Color(Int)` 单参数 (packed ARGB) |
| `PublicationOpener.open(url, allowUserInteraction)` | `open(asset, password, allowUserInteraction, builder, warningLogger)` — 需 Asset 非 Url |
| `AbsoluteUrl.fromFile(File)` | 不存在 — 用 `assetRetriever.retrieve(file = File)` |
| `EpubNavigatorFragment.Listener.onNavigatorEvent/onNavigatorError` | 仅 `onExternalLinkActivated(url: AbsoluteUrl)` |
| `publication.images` | 无 — 用 `publication.cover()` 扩展函数 |
| `DefaultPublicationParser(Context, HttpClient, AssetRetriever)` 3 参数 | 5 参数, 需显式 `pdfFactory = null` |

## 3.4 Oracle 审查结论

### 计划审查 (ora-1): ACCEPT_WITH_CHANGES
- 决策 D1-D5 全部裁定 CORRECT
- 5 个必修项 (M1-M5): LocatorMapper 包位置, bookUuid via toRoute, 进度加载, Fragment↔VM 通信契约, 进程死亡恢复
- 11 个建议项 (S1-S11)
- 全部在实现前修复

### 结果审查 (ora-2): ACCEPT_WITH_CHANGES — 所有必修项已修复
- **M1 (崩溃)**: ReaderHostFragment.bind() 在 onCreateView 前访问 viewLifecycleOwner → 移到 startCollectors() 在 onViewCreated 调用 ✅
- **M2 (NEVER #12)**: currentLocator 在 UiState 触发全屏重组 → 隔离到独立 StateFlow ✅
- **M3 (透明文字)**: textColor=Color(0) → textColor=null ✅
- **M4 (测试缺失)**: 4 个测试文件, 18 项测试 ✅
- **M5 (NEVER #6)**: AndroidView 中 new Fragment → AndroidViewBinding + XML android:name ✅

---

# 锁定的依赖版本 (后续 Phase 共用)

| 依赖 | 版本 | 备注 |
|------|------|------|
| AGP | 9.2.1 | Gradle 9.4.1, JDK 21 |
| Kotlin | 2.3.20 | 与 Readium 3.3.0 编译时版本匹配; KSP 2.3.9 可用 |
| KSP | 2.3.9 | 独立于 Kotlin 版本 (KSP2) |
| Compose BOM | 2026.06.00 | 编译器通过 `org.jetbrains.kotlin.plugin.compose` |
| Hilt | 2.59.2 | KSP (非 kapt); hilt-navigation-compose 1.3.0 |
| Room | 2.8.4 | KSP, exportSchema=true, generateKotlin=true |
| Navigation Compose | 2.9.8 | 类型安全路由 + kotlinx.serialization |
| kotlinx-serialization-json | 1.11.0 | 插件随 Kotlin 捆绑 |
| Media3 | 1.10.1 | Phase 6 用 |
| DataStore Preferences | 1.2.1 | 仅 Preferences (Proto 延迟) |
| Readium v3 | 3.3.0 | shared/streamer/navigator — Phase 3 集成 |
| Coil | 2.7.0 | Phase 2 封面加载 |
| flexmark | 0.64.8 | Phase 5 HTML→MD |
| activity-compose | 1.13.0 | enableEdgeToEdge |
| core-ktx | 1.19.0 | |
| desugar_jdk_libs | 2.1.5 | Readium 需要核心库脱糖 |
| kotlinx-collections-immutable | 0.3.7 | PersistentList for @Immutable UiState |
| compose-ui-viewbinding | BOM 管理 | Phase 3 AndroidViewBinding |
| JUnit 5 BOM | 5.14.4 | + junit-platform-launcher |
| MockK | 1.14.11 | |
| Turbine | 1.2.1 | |

**关键约束**: 不要升级到 Kotlin 2.4.0 (无 KSP 2.4.x, 与 Readium 未验证)。不要使用 alpha/RC 版本。

---

# 包结构 (Clean Architecture)

```
com.epubreader.app
├── EpubReaderApplication.kt          (@HiltAndroidApp)
├── MainActivity.kt                   (@AndroidEntryPoint, enableEdgeToEdge)
├── core/                             核心工具与契约
│   ├── Result.kt                     函数式结果类型 (+ CancellationException re-throw, getOrThrow)
│   ├── Syncable.kt                   同步契约 + 游标/页/确认
│   ├── DispatchersProvider.kt        可注入 Dispatcher
│   ├── ErrorChannel.kt               全局错误通道
│   ├── AppCoroutineExceptionHandler.kt  协程异常兜底
│   ├── StringProvider.kt             本地化字符串接口 (Phase 2 M5)
│   ├── readium/
│   │   └── LocatorMapper.kt          Locator↔String 转换 (Phase 3 D2)
│   └── README.md                     约定文档
├── data/
│   ├── local/                        Room 数据层
│   │   ├── AppDatabase.kt            (version=1, exportSchema=true)
│   │   ├── entity/                   5 个实体 (locator: String)
│   │   ├── dao/                      5 个 DAO
│   │   └── converter/Converters.kt   List<String>↔JSON
│   ├── remote/                       远程数据源 (传输层)
│   ├── prefs/                        DataStore 强类型封装
│   ├── repo/                         仓库实现 (+ reparseMetadata Phase 3)
│   └── bookimport/                   导入管线
│       ├── BookMetadata.kt
│       ├── MetadataParser.kt         (接口)
│       ├── FilenameMetadataParser.kt (桩实现, Phase 3 不再绑定)
│       ├── ReadiumMetadataParser.kt  [Phase 3] Readium Streamer 实现
│       ├── InsufficientStorageException.kt
│       ├── BookImporter.kt           (接口)
│       └── EpubBookImporter.kt       (NIO + 存储校验 + 脏数据清理)
├── domain/
│   └── repository/                   仓库接口 (+ reparseMetadata Phase 3)
├── di/                               Hilt 模块
│   ├── DatabaseModule.kt
│   ├── DataStoreModule.kt
│   ├── AppBindingsModule.kt          (9 个 @Binds)
│   ├── ImportModule.kt               (BookImporter + MetadataParser→ReadiumMetadataParser)
│   └── ReadiumModule.kt              [Phase 3] DefaultHttpClient/AssetRetriever/PublicationOpener
├── navigation/                       路由定义
│   ├── Routes.kt                     (BookshelfRoute, ReaderRoute)
│   └── EpubReaderNavHost.kt          (ReaderScreen 替换占位)
└── ui/                               Compose 界面
    ├── EpubReaderApp.kt              (根 Composable)
    ├── theme/
    │   ├── Color.kt
    │   ├── Type.kt
    │   └── Theme.kt
    ├── bookshelf/
    │   ├── BookshelfUiState.kt       (@Immutable + PersistentList)
    │   ├── ImportState.kt            (密封接口)
    │   ├── BookshelfViewModel.kt     (+ reparseMetadata Phase 3)
    │   ├── BookshelfScreen.kt        (LazyVerticalGrid + SAF + 进度隔离 + 上下文菜单)
    │   └── components/
    │       └── BookCard.kt           (Coil + 占位符)
    └── reader/                       [Phase 3]
        ├── ReaderUiState.kt          (@Immutable, 无 currentLocator)
        ├── ReaderViewModel.kt        (@HiltViewModel, toRoute, 进度记忆, onCleared close)
        ├── ReaderHostFragment.kt     (@AndroidEntryPoint, EpubNavigatorFragment.Listener)
        ├── ReaderScreen.kt           (AndroidViewBinding + Edge-to-Edge 沉浸式)
        └── PreferencesMapper.kt      (AppPreferences→EpubPreferences)
```

---

# 关键架构约定 (后续 Phase 必须遵守)

## 数据模型约定
- **主键**: 所有用户数据表使用 `uuid: String` (NEVER #17 — 禁止自增 Int)
- **软删除**: `UPDATE ... SET isDeleted = 1, updatedAt = :now` (NEVER #7 — 禁止物理 DELETE)
- **查询过滤**: 所有活跃查询加 `WHERE isDeleted = 0`
- **同步字段**: 每个实体包含 `uuid`, `isDeleted`, `createdAt`, `updatedAt`, `syncedAt`, `userId`
- **外键**: `onDelete = NO_ACTION` (软删除级联在仓库逻辑中处理, 非 DB 层)
- **Locator**: 存储为原始 `String` (JSON); 通过 `LocatorMapper` 在 reader 层转换 (Phase 3 D2 — 非 Room @TypeConverter, 保持数据层解耦)

## 仓库层约定
- **接口在 `domain.repository`**, **实现在 `data.repo`** — ViewModel 注入接口, 不注入 DAO (NEVER #2)
- **一次性操作**: `withContext(dispatchers.io) { Result.runCatchingAsync { dao.xxx() } }` — 异常转为 `Result.Error` (NEVER #26)
- **观察操作**: 直接返回 DAO 的 `Flow` (Room 处理 Flow 错误)
- **Result vs UiState**: `Result<T>` 是仓库返回类型; `UiState` 是表现层状态 — 仓库不返回 UiState

## DataStore 约定
- **强类型封装**: 业务逻辑通过 `PreferencesRepository` 接口访问 (NEVER #29)
- **Key 集中管理**: `PreferenceKeys` 是 `internal object`
- **阅读进度不在 DataStore**: 在 Room `ReadingProgressEntity` 中 (支持多端同步)

## 协程异常约定
- **局部 try-catch 是主防线**: 仓库层 `Result.runCatchingAsync` 包裹所有高风险操作
- **`AppCoroutineExceptionHandler` 是兜底**: 通过 `viewModelScope.launch(handler)` 使用
- **CancellationException 必须 re-throw**: `Result.runCatchingAsync` 在 catch Throwable 前 catch CancellationException 并 re-throw (Phase 2 M1)

## Readium v3 桥接约定 (Phase 3)
- **HttpServer 已移除**: v3.3.0 使用内部 WebViewServer, 无需本地 HTTP 服务器 (D1 偏离 harness)
- **Try<T> 用 `fold`/`getOrElse`/`onFailure` 处理**, **禁止 try-catch 包裹** (NEVER #14)
- **Publication.close() 在 `ReaderViewModel.onCleared()`** (D3 — Compose+VM 等价于 Fragment.onDestroy)
- **Locator 通过 LocatorMapper 转换**, 不在数据层引入 Readium 类型 (D2)
- **Fragment 嵌入用 AndroidViewBinding + XML android:name** (D4 — NEVER #6)
- **高频状态 (currentLocator) 隔离到独立 StateFlow** (M2 — NEVER #12)

## 同步预留 (Phase 7 用)
- **Offline-First**: Local 优先, 后台增量同步
- **Last-Write-Wins**: 对比 `updatedAt`, `isDeleted = 1` 优先级最高
- **传输层**: `RemoteDataSource` 仅传输, LWW 合并在 `SyncManager` (Phase 7)

---

# NEVER 规则合规性 (Phase 1-3)

| # | 规则 | 合规 | 验证方式 |
|---|------|------|----------|
| 2 | VM 注入接口非 DAO | ✅ | ReaderVM 注入 BookRepository + ReadingProgressRepository + Readium 组件 |
| 5 | 禁 file:// | ✅ | v3.x WebViewServer 拦截, 无 file:// (D1) |
| 6 | 禁 AndroidView 中 new Fragment | ✅ | AndroidViewBinding + XML android:name (M5 修复) |
| 12 | 高频状态 derivedStateOf/隔离 | ✅ | currentLocator 独立 StateFlow (M2 修复); importProgress derivedStateOf |
| 14 | Try 函数式处理 | ✅ | getOrElse/fold, 无 try-catch 包裹 Try |
| 15 | SAF 用 rememberLauncherForActivityResult | ✅ | OpenDocument() |
| 18 | 类型安全路由 | ✅ | @Serializable + composable<Route> |
| 20 | WebView/JS Bridge 在 onDestroyView | ✅ | ReaderHostFragment.onDestroyView 取消 collector; EpubNavigatorFragment 内部清理 |
| 21 | collectAsStateWithLifecycle | ✅ | 全部使用 |
| 22 | toRoute<T>() 提取参数 | ✅ | ReaderViewModel savedStateHandle.toRoute<ReaderRoute>() |
| 23 | @Keep on @JavascriptInterface | ✅ | ProGuard 规则预置 (Phase 4 注册接口) |
| 24 | NIO + yield() 大文件拷贝 | ✅ | EpubBookImporter |
| 26 | try-catch / CoroutineExceptionHandler | ✅ | runCatchingAsync + launch(handler) |
| 27 | usableSpace 校验 + finally 清理 | ✅ | EpubBookImporter |
| 29 | DataStore 强类型 Key | ✅ | PreferenceKeys internal object |

---

# 测试覆盖 (38 项)

| 测试文件 | 测试数 | Phase | 验证点 |
|----------|--------|-------|--------|
| `PreferencesRepositoryTest` | 3 | 1 | NEVER #29 (类型化 Key) |
| `CoroutineExceptionGuardTest` | 2 | 1 | NEVER #26 (DAO 异常 → Result.Error) |
| `PreferencesRepositorySetterTest` | 4 | 2 | SF-1: setter 轮询 |
| `EpubBookImporterTest` | 4 | 2 | M2/M3/M4 + NEVER #27 |
| `BookshelfViewModelTest` | 4 | 2 | uiState 发射, 导入状态转换, 取消重置 |
| `LocatorMapperTest` | 5 | 3 | String↔Locator 往返, null/无效 JSON |
| `ReadiumMetadataParserTest` | 5 | 3 | NEVER #14, 封面提取, Publication 关闭 |
| `ReaderViewModelTest` | 4 | 3 | UiState/依赖装配 (Bundle JVM 限制) |
| `BookRepositoryImplReparseTest` | 4 | 3 | reparseMetadata 流程 |
| **合计** | **38** | | **全部通过** |

---

# 后续 Phase 衔接要点

### Phase 4 (JS Bridge 与交互) — 立即可开始
- `@JavascriptInterface` + `@Keep` 防 R8 (NEVER #23) — ProGuard 规则已预置
- 校验 `window.location.origin` (NEVER #8)
- 自动滚动 JS 绑定 `touchstart` → `cancelAnimationFrame` (NEVER #9)
- 在 `EpubNavigatorFragment.Configuration` 中 `registerJavascriptInterface("AndroidNativeApi") { link -> ... }` (基础设施已就绪)
- 侧滑目录 (TOC), 全文搜索, 选词

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
- Locator JSON canonical 排序 (S10 — 同步确定性)

---

# 验证命令参考

```powershell
# 编译 + 生成 APK
.\gradlew.bat :app:assembleDebug --no-daemon

# 运行单元测试 (38 项全部通过)
.\gradlew.bat :app:testDebugUnitTest --no-daemon

# 运行插桩测试 (需连接模拟器/设备)
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

**环境**: JDK 21 (`C:\Users\MECHREVO\jdks\jdk21\jdk-21.0.2`), Android SDK (`C:\Users\MECHREVO\AppData\Local\Android\Sdk`), 平台 35/36.1/37, Build-tools 34-37, Gradle 9.4.1 (wrapper), Compose BOM 2026.06.00, Kotlin 2.3.20, KSP 2.3.9, Readium 3.3.0
