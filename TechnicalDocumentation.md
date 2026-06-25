# EpubReader — 技术文档 (Technical Documentation)

> **项目**: 本地 EPUB 阅读器 (EpubReader)
> **状态**: Phase 1 ✅ → Phase 2 ✅ → Phase 3 ✅ → Phase 4 ✅ → Phase 5 ✅ → Phase 6 ✅ 完成
> **日期**: 2026-06-25
> **下一步**: 等待用户指令后开始 Phase 7 (测试驱动 + 同步)

本文档合并了 Phase 1 (基础架构与同步数据模型)、Phase 2 (书架 UI 与路由)、Phase 3 (Readium v3.x 引擎集成)、Phase 4 (JS Bridge 与交互系统)、Phase 5 (知识管理与导出)、Phase 6 (Media3 TTS 听书) 的全部进度文档。

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

# Phase 4 — JS Bridge 与交互系统

> **状态**: ✅ 完成 (构建通过, 60 项测试全部通过, Oracle 计划审查 ACCEPT_WITH_CHANGES, Oracle 结果审查 ACCEPT_WITH_CHANGES, 所有 must-fix 已修复)

## 4.1 关键架构决策 (Oracle 审查通过)

### D1: JS Bridge 工厂模式 + 资源级作用域
使用 `EpubNavigatorFragment.Configuration { registerJavascriptInterface("AndroidNativeApi") { link -> ... } }` 工厂模式。工厂按资源 (spine item) 调用, 仅对 HTML 资源 (`link.mediaType?.isHtml == true`) 返回 bridge 实例, 其他资源返回 null。这是 NEVER #8 (origin 校验) 的纵深防御层 — 即使恶意 EPUB 注入 JS, 也无法在非 HTML 资源中访问 bridge。

### D2: BridgeCallbackHolder 防 Fragment 泄漏
JS Bridge 工厂捕获 `BridgeCallbackHolder` (小型对象), 而非 Fragment。Fragment 在 `setupNavigatorObserver()` 设置 callback, 在 `onDestroyView()` 清除 (NEVER #20)。`@Volatile var callback` 保证 WebView 线程可见性。BridgeCallback 实现仅委托给 ViewModel 方法 (线程安全的 StateFlow 更新), 不访问 Fragment 视图。

### D3: 混合选词模式 (Push + Pull)
- **Push (JS → Native)**: `selectionchange` 事件经 300ms 防抖后, JS 调用 `AndroidNativeApi.onSelectionChanged(origin, text)` 通知原生选词文本变化 (响应式 UX)
- **Pull (Native → JS)**: 用户点击高亮/书签按钮时, VM 发出 `RequestCurrentSelection` 命令, Fragment 调用 `navigator.currentSelection()` (suspend) 获取完整 Locator

### D4: 内置 SearchService (@ExperimentalReadiumApi)
使用 Readium v3.3.0 内置 `Publication.search(query): SearchIterator?` (标记 `@ExperimentalReadiumApi` + `@Search`)。`SearchIterator.next()` 返回 `Try<LocatorCollection?, SearchError>`, 用 `getOrElse` 函数式处理 (NEVER #14)。搜索结果作为 `Decoration.Style.Underline` 装饰应用, 导航到结果时用 `navigator.go(locator)`。

### D5: 自动滚动状态驱动 (非命令)
自动滚动通过 `uiState.isAutoScrollActive` StateFlow 驱动, Fragment 观察状态变化注入 START/STOP JS。不使用命令通道 — 避免双路径竞态 (Oracle M2)。JS 端 `touchstart` → `cancelAnimationFrame` + 通知原生 `onAutoScrollStopped()` (NEVER #9), 原生更新状态为 false, 图标自动切换。

### D6: highlightDecorations StateFlow (非命令)
高亮装饰通过 `highlightDecorations: StateFlow<List<Decoration>>` 直接由 Fragment 收集, 而非通过 SharedFlow 命令。原因: SharedFlow `replay=0` 会丢失 VM init 期间 (navigator 尚未就绪) 发射的初始装饰。StateFlow 始终保留最新值, Fragment 在 `setupNavigatorObserver()` 开始收集时立即获得当前装饰。

### D7: JS 脚本资源切换重注入
`evaluateJavascript` 仅作用于当前资源的 WebView。导航到新章节时创建新 WebView, 注入的脚本丢失。Fragment 观察 `currentLocator.href` 变化 (`distinctUntilChanged`), 在 href 改变时重新注入 `SELECTION_LISTENER` 和 (如果激活) `AUTO_SCROLL_START` (Oracle M6)。

### D8: 进程死亡恢复 (usedDefaultFactory 标志)
进程死亡后, `onCreate()` 中 VM 尚未加载, 使用 `DefaultReaderFragmentFactory` (无 Configuration) 创建 navigator。VM 加载后 `setupNavigator()` 检测 `usedDefaultFactory` 标志, 移除未配置的 fragment 并用正确 Configuration 重建 (Oracle M3)。

## 4.2 已完成的交付物

### Phase 4 新增文件 (6)

| 文件 | 说明 |
|------|------|
| `ui/reader/AndroidNativeApi.kt` [NEW] | `@Keep` JS Bridge 类 (NEVER #23)。`@JavascriptInterface` 方法接收 `origin` 参数, `isAllowedOrigin()` 校验 against `ALLOWED_ORIGINS = {"https://readium_package", "https://readium_assets"}` (NEVER #8)。`BridgeCallbackHolder` (`@Volatile callback`) 防 Fragment 泄漏。`BridgeCallback` 接口: `onAutoScrollStopped()`, `onSelectionChanged(text)` |
| `ui/reader/ReaderJsScripts.kt` [NEW] | JS 脚本常量。`AUTO_SCROLL_START`: `requestAnimationFrame` 循环 + `touchstart`/`touchmove` → `cancelAnimationFrame` (NEVER #9) + 通知原生 + 文档底部检测 (S-I)。`AUTO_SCROLL_STOP`: 调用 stop 函数。`SELECTION_LISTENER`: 300ms 防抖 `selectionchange` → 通知原生。所有脚本幂等 (检查 `window.__epub*` 标志), 传递 `window.location.origin` |
| `ui/reader/ReaderCommand.kt` [NEW] | Fragment 命令密封接口: `NavigateToLocator`, `NavigateToLink`, `RequestCurrentSelection`, `ApplyDecorations`, `ClearSelection`。无 `EvaluateJavascript` (Oracle M2 — 状态驱动避免泄漏 JS 实现细节) |
| `ui/reader/TocDrawer.kt` [NEW] | TOC 侧滑抽屉。`@Immutable TocItem(title, level)`。`TocDrawer` Composable: `ModalDrawerSheet` + `LazyColumn` + `NavigationDrawerItem`, 按 level 缩进 |
| `ui/reader/SearchPanel.kt` [NEW] | 搜索面板。`@Immutable SearchState(query, isSearching, results: PersistentList, currentIndex, error)` + `SearchResult(text, before, after)`。`SearchPanel` Composable: `ModalBottomSheet` + `OutlinedTextField` + 结果列表 |
| `ui/reader/SelectionToolbar.kt` [NEW] | 选词工具栏。`@Immutable SelectionState(isActive, text)`。`SelectionToolbar` Composable: 高亮/书签/复制/关闭按钮 |

### Phase 4 修改文件 (5)

| 文件 | 变更 |
|------|------|
| `ui/reader/ReaderUiState.kt` [MODIFY] | 新增字段: `toc: PersistentList<TocItem>`, `isTocDrawerOpen`, `isSearchPanelOpen`, `isAutoScrollActive` |
| `ui/reader/ReaderViewModel.kt` [MODIFY] | 注入 `BookmarkRepository`, `HighlightRepository`。新增 StateFlow: `searchState`, `selectionState`, `bookmarks`, `bookmarkedHrefs` (S-E 预计算), `highlightDecorations` (D6), `commands` (M1 缓冲 SharedFlow)。新增方法: TOC 加载/导航, 搜索 (SearchService + 防抖 S4 + 立即更新 query S-A), 选词 (pendingSelectionAction M4), 书签切换, 自动滚动状态, 高亮装饰。`@OptIn(ExperimentalReadiumApi, Search)` |
| `ui/reader/ReaderHostFragment.kt` [MODIFY] | `buildConfiguration()` 工厂模式注册 JS Bridge (D1)。实现 `BridgeCallback` (D2)。`usedDefaultFactory` 标志 (M3/D8)。JS 重注入 (M6/D7)。命令收集器在 `setupNavigatorObserver` (M7)。自动滚动状态驱动 (M2/D5)。所有 suspend 调用 try-catch + CancellationException re-throw (M5/MF1)。`onExternalLinkActivated` 打开浏览器 |
| `ui/reader/ReaderScreen.kt` [MODIFY] | `ModalNavigationDrawer` TOC 抽屉。TopAppBar 操作: TOC/搜索/书签/自动滚动图标。`SearchPanel` 覆盖层。`SelectionToolbar` 底部栏。`derivedStateOf` 计算 `isBookmarked` (S1, 使用 `bookmarkedHrefs` S-E)。全部 `collectAsStateWithLifecycle` (NEVER #21) |
| `res/values/strings.xml` [MODIFY] | 新增 18 个字符串 (TOC, 搜索, 书签, 自动滚动, 选词) |

### Phase 4 测试 (25 项新增, 60 项总计)

| 测试文件 | 测试数 | 验证点 |
|----------|--------|--------|
| `AndroidNativeApiTest` | 6 | NEVER #8: 允许 origin 调用回调, 阻止 origin 不调用, null callback 不崩溃, isAllowedOrigin 校验 |
| `ReaderJsScriptsTest` | 10 | NEVER #9: requestAnimationFrame, touchstart, cancelAnimationFrame, onAutoScrollStopped, origin 传递, 幂等性, passive 监听器 |
| `ReaderUiStatePhase4Test` | 9 | UiState 默认值/copy, TocItem, SearchState, SelectionState, SearchResult, ReaderCommand |

## 4.3 Readium v3.3.0 API 修正 (编译时验证)

| 文档 API | 实际 v3.3.0 API |
|----------|----------------|
| `registerJavascriptInterface(name, listener: (String) -> Unit)` | `registerJavascriptInterface(name: String, factory: JavascriptInterfaceFactory)` where `typealias JavascriptInterfaceFactory = (resource: Link) -> Any?` |
| `SelectionListener` / `onSelection` 回调 | 不存在 — 用 `SelectableNavigator.currentSelection(): Selection?` (suspend) |
| `navigator.currentBookmarkValue` | 不存在 — 用 `navigator.currentLocator.value` |
| 搜索需自定义 JS | 内置 `SearchService` (`@ExperimentalReadiumApi` + `@Search`), `publication.search(query): SearchIterator?` |
| `SearchIterator.next()` 返回 `Try<LocatorCollection?, SearchError>` | `LocatorCollection.locators: List<Locator>` (在 `org.readium.r2.shared.publication` 包) |

## 4.4 Oracle 审查结论

### 计划审查 (ora-1): ACCEPT_WITH_CHANGES
- 7 个必修项 (M1-M7): SharedFlow 缓冲, 自动滚动状态驱动, 进程死亡恢复, pendingSelectionAction, try-catch, JS 重注入, 命令收集器位置
- 10 个建议项 (S1-S10)
- 全部在实现前修复

### 结果审查 (ora-2): ACCEPT_WITH_CHANGES — 所有必修项已修复
- **MF1 (NEVER #26)**: 4 个 try-catch 块吞掉 CancellationException → 全部添加 `catch (e: CancellationException) { throw e }` ✅
- **S-A**: query 在防抖前立即更新 (避免文本框回退) ✅
- **S-C**: toggleSearchPanel 关闭时清除搜索装饰 ✅
- **S-E**: 预计算 bookmarkedHrefs Set (避免每次滚动 JSON 解析) ✅
- **S-I**: 自动滚动文档底部检测 (节省 CPU/电池) ✅

---

# Phase 5 — 知识管理与导出

> **状态**: ✅ 完成 (构建通过, 90 项测试全部通过, Oracle 计划审查 ACCEPT_WITH_CHANGES, Oracle 结果审查 ACCEPT_WITH_CHANGES, 所有 must-fix 已修复)

## 5.1 关键架构决策 (Oracle 审查通过)

### D1: KnowledgePanel 作为 ModalBottomSheet (非独立屏幕/路由)
- 按书籍作用域 (所有标注按 bookUuid 关联)
- 复用现有 SearchPanel 模式 (ModalBottomSheet)
- 无需新导航路由
- TopAppBar 新增 "标注" 图标切换面板
- 面板包含 3 类内容 (高亮/笔记/书签) 在单个 LazyColumn 中, 顶部有导出按钮
- 理由: 知识管理与阅读上下文紧密关联; 数据已在 ReaderVM 中按书加载; 避免导航复杂度

### D2: MarkdownExporter — 接口在 `core/export/`, 实现在 `core/export/`, Hilt @Binds 绑定
- 接口: `MarkdownExporter` + `exportToMarkdown(request: ExportRequest): String`
- 实现: `MarkdownExporterImpl @Singleton @Inject constructor()` — 包装 `FlexmarkHtmlConverter` (lazy + `@Synchronized`, flexmark 非线程安全)
- **Oracle M1 修复**: 使用纯数据类 (ExportHighlight/ExportNote/ExportBookmark/ExportTocItem/ExportRequest), **不依赖 Room 实体** — 保持 `core/` → `data/` 的 Clean Architecture 不变量 (Phase 1-4 维护的约束)
- VM 将 Room 实体映射为纯导出模型后调用导出器
- 所有文本字段 (highlight.text, note.content) 经 `FlexmarkHtmlConverter.convert()` 处理 — 满足 harness "使用 flexmark-java, 不写正则"
- 输出: 结构化 MD, 按章节层级分组 (# 书名 → ## 章节 → ### 高亮/笔记/书签)

### D3: 标注按章节分组 (Locator href + TOC + 片段归一化)
- **Oracle M2 修复**: `ExportTocItem` 携带 `href` 字段 (VM 从 `tocLinks` 填充, Phase 4 的 `TocItem` 无 href)
- 归一化: 匹配前剥离 `#` 片段和 `?` 查询 (`substringBefore('#').substringBefore('?').trimEnd('/')`)
- 高亮/笔记的 locator href 常带片段 (`ch1.xhtml#section-3`), TOC href 通常为裸路径 (`ch1.xhtml`) — 归一化后匹配
- 未匹配的 locator 归入 "Unsorted" 章节 (合并所有未匹配 href + null locator 标注)
- 章节顺序遵循 TOC 顺序; 章节内标注按 createdAt 排序
- 仅渲染包含标注的章节 (无标注的 TOC 章节不输出)

### D4: 导出流程 — VM 生成 MD 字符串 (IO), UI 处理 SAF/ShareSheet
- ReaderVM: `prepareExport()` — 收集当前标注 + 书名 + TOC, 调用 `markdownExporter.exportToMarkdown()`, 在 `dispatchers.default` 上运行
- VM 暴露 `exportState: StateFlow<ExportState>` (密封: Idle/Preparing/Ready(content, suggestedFileName)/Error(message)) — **Oracle S1: ExportState 在 UI 层 (KnowledgeUiState.kt), 非 core/export**
- UI: `rememberLauncherForActivityResult(CreateDocument("text/markdown"))` — Uri 回调时通过 `contentResolver.openOutputStream` 写入, 在 IO 调度器上执行
- UI: ShareSheet 通过 `Intent.ACTION_SEND` + `EXTRA_TEXT` + `createChooser` → `context.startActivity` (无需 FileProvider)
- **VM 从不接触 Context/Uri** — 干净分离 (NEVER #2)
- **Oracle M3 修复**: SAF 写入包裹 try-catch + CancellationException re-throw (NEVER #26 精神)
- 建议文件名: `{bookTitle}-annotations.md` (Oracle S5: 文件名净化 `replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)`)

### D5: 笔记观察 + 创建 (Oracle M4 修复)
- ReaderVM: 注入 `NoteRepository` (新依赖) + `MarkdownExporter`
- 新增 `notes: StateFlow<List<NoteEntity>>` 从 `noteRepository.observeNotes(bookUuid)` (stateIn WhileSubscribed 5000)
- 新增 `knowledgeState: StateFlow<KnowledgeState>` — combine 高亮/笔记/书签三个 Flow, 聚合为统一 KnowledgeItem 列表
- **Oracle M4 修复**: 笔记编辑器使用 `noteEditorState: StateFlow<NoteEditorState>` (UI 层 StateFlow), **非 Fragment 绑定的 `commands` SharedFlow** — `commands` 仅驱动 Fragment/navigator 动作, 不能显示 Compose 对话框
- `requestNote()` → 存储 pending action → `onSelectionRetrieved()` 处理 Note 分支: 存储 locator+text 到 `pendingNoteLocator/pendingNoteText`, 设置 `noteEditorState = Editing(selectedText)`, **不清除选区** (延迟到对话框保存/取消)
- `createNote(content)` → **Oracle S2: 先创建 HighlightEntity, 成功后创建 NoteEntity(highlightUuid)** — 若高亮失败则不创建笔记 (FK 约束)
- `cancelNoteEditor()` → 清除状态 + 清除选区
- NoteEditorDialog: Compose AlertDialog, 显示选中文本 (只读) + OutlinedTextField 输入笔记内容 + 保存/取消

### D6: 一次性查询 (getByBook) 用于导出
- 新增 `@Query("SELECT * FROM ... WHERE bookUuid = :bookUuid AND isDeleted = 0") suspend fun getByBook(bookUuid): List<T>` 到 3 个 DAO
- 新增 `suspend fun getByBook(bookUuid): Result<List<T>>` 到 3 个仓库接口 + 实现
- 导出使用一次性查询 (非 StateFlow.value) — StateFlow.value 在 WhileSubscribed(5000) 下可能过期; 一次性查询保证新鲜数据

### D7: SAF CreateDocument 为主, ShareSheet ACTION_SEND 为辅
- 主: `CreateDocument("text/markdown")` — 用户选择保存位置, 应用写入 MD 内容
- 辅: `Intent.ACTION_SEND` + `EXTRA_TEXT` (纯文本分享) — 无需 FileProvider
- Phase 5 不实现 FileProvider (避免 manifest 复杂度; 纯文本分享满足 harness "ShareSheet 或 SAF")
- 均使用 `rememberLauncherForActivityResult` (NEVER #15)

## 5.2 已完成的交付物

### Phase 5 新增文件 (7)

| 文件 | 说明 |
|------|------|
| `core/export/MarkdownExporter.kt` [NEW] | 接口 + 5 个纯导出数据类 (ExportHighlight/ExportNote/ExportBookmark/ExportTocItem/ExportRequest) — Oracle M1: 不依赖 Room 实体 |
| `core/export/MarkdownExporterImpl.kt` [NEW] | @Singleton 实现。FlexmarkHtmlConverter (lazy + @Synchronized)。章节分组 + 片段归一化 (Oracle M2)。blockquote 逐行前缀 (Oracle S7)。extractHref() 从 Locator JSON 提取 href (正则, 非 HTML→MD — harness "不写正则" 仅限 HTML→MD) |
| `ui/reader/KnowledgeUiState.kt` [NEW] | @Immutable KnowledgeItem/KnowledgeState + ExportState 密封接口 (Idle/Preparing/Ready/Error) + NoteEditorState 密封接口 (Hidden/Editing) + KnowledgeItemType 枚举 — Oracle S1: ExportState 在 UI 层 |
| `ui/reader/KnowledgePanel.kt` [NEW] | ModalBottomSheet。计数头部 (高亮/笔记/书签数, Oracle S14)。两个导出按钮: "Save to file" + "Share" (Oracle S13)。LazyColumn 标注列表, 每项有类型图标/文本/章节标题/删除按钮 |
| `ui/reader/NoteEditorDialog.kt` [NEW] | AlertDialog。显示选中文本 (只读) + OutlinedTextField 笔记输入 + 保存 (非空时启用) /取消 |
| `core/export/MarkdownExporterTest.kt` [NEW] | 15 项测试: HTML→MD 转换 (标题/加粗/斜体/特殊字符), 章节分组, 片段归一化, Unsorted 章节, 多行 blockquote, 空输入, 笔记带/不带高亮 |
| `ui/reader/KnowledgeUiStateTest.kt` [NEW] | 15 项测试: KnowledgeState/KnowledgeItem 默认值与字段, ExportState 四态, NoteEditorState 两态, ReaderUiState Phase 5 字段 |

### Phase 5 修改文件 (12)

| 文件 | 变更 |
|------|------|
| `data/local/dao/NoteDao.kt` [MODIFY] | 新增 `getByBook(bookUuid): List<NoteEntity>` (D6) |
| `data/local/dao/HighlightDao.kt` [MODIFY] | 新增 `getByBook(bookUuid): List<HighlightEntity>` (D6) |
| `data/local/dao/BookmarkDao.kt` [MODIFY] | 新增 `getByBook(bookUuid): List<BookmarkEntity>` (D6) |
| `domain/repository/NoteRepository.kt` [MODIFY] | 新增 `getByBook(bookUuid): Result<List<NoteEntity>>` (D6) |
| `domain/repository/HighlightRepository.kt` [MODIFY] | 新增 `getByBook(bookUuid): Result<List<HighlightEntity>>` (D6) |
| `domain/repository/BookmarkRepository.kt` [MODIFY] | 新增 `getByBook(bookUuid): Result<List<BookmarkEntity>>` (D6) |
| `data/repo/NoteRepositoryImpl.kt` [MODIFY] | 实现 `getByBook` (withContext IO + runCatchingAsync, NEVER #26) |
| `data/repo/HighlightRepositoryImpl.kt` [MODIFY] | 实现 `getByBook` |
| `data/repo/BookmarkRepositoryImpl.kt` [MODIFY] | 实现 `getByBook` |
| `di/AppBindingsModule.kt` [MODIFY] | 新增 `bindMarkdownExporter` @Binds 绑定 |
| `ui/reader/ReaderUiState.kt` [MODIFY] | 新增 `isKnowledgePanelOpen: Boolean = false` |
| `ui/reader/ReaderViewModel.kt` [MODIFY] | 注入 NoteRepository + MarkdownExporter。新增 StateFlow: notes, knowledgeState (combine 三 Flow), exportState, noteEditorState (Oracle M4)。新增方法: requestNote, createNote (Oracle S2 顺序创建), cancelNoteEditor, toggleKnowledgePanel (Oracle S3 面板互斥), closeKnowledgePanel, deleteKnowledgeItem, prepareExport (D6 一次性查询), resetExportState, findChapterTitle (Oracle M2 归一化), sanitizeFileName (Oracle S5) |
| `ui/reader/ReaderScreen.kt` [MODIFY] | TopAppBar 新增 Knowledge 图标 (Icons.AutoMirrored.Filled.Notes)。KnowledgePanel 覆盖层。NoteEditorDialog。SAF launcher (CreateDocument + try-catch Oracle M3)。ShareSheet (ACTION_SEND)。LaunchedEffect 响应 exportState。全部 collectAsStateWithLifecycle (Oracle S10) |
| `ui/reader/SelectionToolbar.kt` [MODIFY] | 新增 Note 按钮 (Icons.Default.Note) + onNote 参数, 位于 Highlight 和 Bookmark 之间 |
| `res/values/strings.xml` [MODIFY] | 新增 22 个字符串 (knowledge/export/note editor/selection note/feedback) |

## 5.3 导出文档格式

```markdown
# {书名}

> Exported: {yyyy-MM-dd HH:mm}

## {章节 1 标题}

### Highlights

> {高亮文本 (HTML→MD 转换, 逐行 blockquote 前缀)}

### Notes

- {笔记内容} *(on: "{关联高亮文本}")*

### Bookmarks

- {书签标签}

## {章节 2 标题}
...

## Unsorted

### Highlights
> {未匹配章节的高亮}

### Notes
- {null locator 的独立笔记}
```

## 5.4 Oracle 审查结论

### 计划审查 (ora-1): ACCEPT_WITH_CHANGES
- 4 个必修项 (M1-M4): core 层级回归, 章节分组损坏, SAF 写入崩溃, 笔记对话框信号路由错误
- 14 个建议项 (S1-S14): ExportState 位置, 顺序创建, 面板互斥, TopAppBar 溢出, 文件名净化, ShareSheet 大小限制, blockquote 逐行, 测试覆盖, flexmark 纯文本测试, collectAsStateWithLifecycle, 独立笔记, KnowledgePanel insets, 导出按钮清晰度, 计数显示
- 全部在实现前修复

### 结果审查 (ora-2): ACCEPT_WITH_CHANGES — 所有必修项已修复
- **M1 (层级回归)**: core/export 使用纯数据类, 不依赖 Room 实体 ✅
- **M2 (章节分组)**: ExportTocItem 携带 href + 片段归一化 (strip #) ✅
- **M3 (SAF 崩溃)**: try-catch + CancellationException re-throw ✅
- **M4 (笔记对话框)**: noteEditorState StateFlow (UI 层), 非 commands SharedFlow ✅
- **MF1 (面板互斥 bug)**: toggleKnowledgePanel 中 clearSearch 未调用 (update 后读取状态) → 捕获 searchWasOpen 在 update 前 ✅
- **S1 (对话框卡死)**: createNote 失败时 pending 字段已清空导致 Save 无响应 → 移入成功路径清理 ✅

---

# Phase 6 — Media3 TTS 听书

> **状态**: ✅ 完成 (构建通过, 116 项测试全部通过, Oracle 计划审查 ACCEPT_WITH_CHANGES, Oracle 结果审查 ACCEPT_WITH_CHANGES, 所有 must-fix 已修复)

## 6.1 关键架构决策 (Oracle + Council + 架构师审查通过)

### D1: TtsPlayer extends SimpleBasePlayer (章节级 MediaItem)
- 自定义 Player 包装 TextToSpeech, @OptIn(UnstableApi)
- **Council M9**: 1 个 MediaItem = 1 个章节 (非句子), 避免通知栏重建风暴 (200 句 = 200 次重建)
- 句子进度通过 TtsBus.currentSentenceIndex 内部追踪, 不经过 MediaItem 转换
- handleSetPlayWhenReady→speak/pause, handleStop→stop, handleSetMediaItems→读取 TtsBus.sentences.value (M1 单路径), handleSeek→句子索引映射, handleRelease→tts.shutdown()
- 手动 AudioManager 音频焦点 (USAGE_MEDIA + CONTENT_TYPE_SPEECH, 短暂丢失暂停而非 duck)

### D2: TtsPlaybackService extends MediaSessionService
- @AndroidEntryPoint (Hilt)
- **Oracle M5**: super.onCreate() (Hilt 注入) → createNotificationChannel (NEVER #25) → MediaSession + DefaultMediaNotificationProvider
- onGetSession 返回 session; onTaskRemoved 非播放时 stopSelf
- **Oracle S7**: STATE_ENDED + 无 controller → stopSelf (防止通知残留)
- **Oracle S9**: onCreate 重置 TtsBus (清除进程死亡残留状态)
- Manifest: foregroundServiceType="mediaPlayback" + intent-filter

### D3: AndroidTtsEngine — TTS 状态机 + 看门狗 + 线程安全
- 状态机: Uninitialized → Initializing → Ready / LanguageMissing(locale) / Error(reason)
- **NEVER #28**: speak() 仅在 Ready 状态执行, 否则静默丢弃
- **Council M13**: 2 秒看门狗定时器 — speak() 后启动, onStart 取消, 超时 → Error + shutdown + 重新初始化
- **Council M11**: UtteranceProgressListener 回调通过 Dispatchers.Main.immediate 切换到主线程
- **Council S11**: 长句分块 (>800 字符按词边界分割)
- isLanguageAvailable → LANG_MISSING_DATA → LanguageMissing 状态 → UI 提示安装
- 接口提取 (Oracle S2): TtsEngine 接口在 core/tts/, AndroidTtsEngine 实现在 media/, 可测试

### D4: TtsBus @Singleton — 共享状态 (精简版)
- **Oracle S1**: 仅持有 MediaController 无法承载的状态 (无 playbackState — 从 Player.Listener 派生)
- 3 个 StateFlow: sentences (按需数据源, 非驱动), engineState, currentSentenceIndex
- **Council M14**: generationId — 每次 JS 提取生成 UUID, 防止跨章节高亮竞态
- clear() 方法: 切书/停止时调用, 防止内存泄漏

### D5: TtsController 接口 + TtsControllerImpl — UI 与服务桥梁
- 接口在 core/tts/ (纯 Kotlin), 实现在 media/ (@Singleton, 注入 @ApplicationContext — NEVER #2)
- 管理 MediaController 生命周期 (buildAsync, release)
- **Oracle S8**: 未连接契约 — play() 排队, 连接后重放; 其他命令丢弃+日志
- **Oracle S4**: 连接时从 prefs 初始化 speed/pitch
- **架构师 M3 修正**: disconnect() 仅释放 MediaController, 不停止播放 — 服务通过前台通知继续后台播放

### D6: JS 句子提取 + Locator 特征值 (架构师指南)
- JS 提取特征值 (href + progression), Kotlin 组装完整 Locator (via publication.locatorFromLink)
- **Council M10**: TtsSentence 含 Locator — 支持分页模式翻页 + 进度保存
- 高亮流程: currentSentenceIndex 变化 → navigator.go(locator) 翻页 → HIGHLIGHT_SENTENCE JS
- CJK 分句支持 (。！？); DOM Range 存储在 window.__epubTtsRanges

### D7: 睡眠定时器 (ReaderVM 协程)
- 预设: 5/10/15/30 分钟 + "本章结束"
- 协程实现 (viewModelScope + delay), 到期 pauseTts()
- **架构师 M3**: 因 onCleared 不停止 TTS, 定时器在 ReaderVM 中正确 (仅在阅读器打开时需要)

### D8: 后台播放生命周期 (架构师 M3 修正)
- **onCleared() 仅 disconnect(), 不 stop()** — 服务通过前台通知继续后台播放
- stop() 仅在: 用户显式停止 / 切换书籍 / 到达书末 时调用
- 重连: 用户返回阅读器 → ReaderVM init connect() → MediaController 重建

## 6.2 已完成的交付物

### Phase 6 新增文件 — core/tts/ (纯 Kotlin, 6 个)
| 文件 | 说明 |
|------|------|
| `TtsEngineState.kt` | 密封接口: Uninitialized/Initializing/Ready/LanguageMissing(locale)/Error(reason) |
| `TtsPlaybackState.kt` | 密封接口: Idle/Playing/Paused/Ended (从 Player.Listener 派生, 非 TtsBus) |
| `TtsSentence.kt` | 数据类: id, text, locator: Locator? (M10 — 支持翻页+进度保存) |
| `TtsBus.kt` | @Singleton: sentences/engineState/currentSentenceIndex/generationId StateFlow + clear() |
| `TtsController.kt` | 接口: StateFlows + connect/play/pause/stop/setSpeed/setPitch/seekToSentence/disconnect |
| `TtsEngine.kt` | 接口: state StateFlow + initialize/speak/stop/setSpeechRate/setPitch/shutdown/setCallbacks + Callbacks + WATCHDOG_TIMEOUT_MS=2000 + MAX_UTTERANCE_LENGTH=800 |

### Phase 6 新增文件 — media/ (Android/Media3, 5 个)
| 文件 | 说明 |
|------|------|
| `AndroidTtsEngine.kt` | TtsEngine 实现. TextToSpeech 包装. 状态机 (NEVER #28). 看门狗 (M13). 线程安全 (M11, Dispatchers.Main.immediate). 长句分块 (S11). createInstallLanguageDataIntent() |
| `TtsPlayer.kt` | SimpleBasePlayer (@OptIn UnstableApi). 章节级 MediaItem (M9). 手动 AudioManager 音频焦点 (NEVER #11). TtsBus.sentences 按需读取 (M1). handleSetPlaybackParameters→setSpeechRate/setPitch (M3). 引擎状态观察 (M2 — Ready 时自动恢复播放). TtsEngine.Callbacks: onStart/onDone(下一句或 STATE_ENDED)/onError |
| `TtsPlaybackService.kt` | MediaSessionService (@AndroidEntryPoint). super.onCreate()→channel (M5)→MediaSession. STATE_ENDED+无 controller→stopSelf (S7). onCreate 重置 TtsBus (S9) |
| `TtsControllerImpl.kt` | TtsController 实现 (@Singleton). @ApplicationContext (NEVER #2). MediaController buildAsync. 未连接排队 (S8). Player.Listener 派生 playbackState (S1). setPlaybackParameters 触发 handleSetPlaybackParameters (M3). disconnect 不停止播放 (M3) |
| `TtsModule.kt` | Hilt @Binds TtsController→TtsControllerImpl |

### Phase 6 新增文件 — ui/reader/ (4 个)
| 文件 | 说明 |
|------|------|
| `TtsUiState.kt` | @Immutable TtsPanelState (isPanelOpen, isSettingsLocked, totalSentences, currentSentence, speed, pitch) + SleepTimerState 密封接口 (Inactive/Active/EndOfChapter) |
| `TtsControlPanel.kt` | ModalBottomSheet: play/pause/stop, seek backward/forward, speed slider (0.5-3.0), pitch slider (0.5-2.0), sleep timer 预设 (5/10/15/30m + end of chapter + cancel) |
| `LanguagePackDialog.kt` | AlertDialog: LanguageMissing (Install→ACTION_INSTALL_TTS_DATA) + Error 变体 |
| `TtsJsScripts.kt` | EXTRACT_SENTENCES (文本节点遍历, CJK 分句, DOM Range 存储, JSON {id,text,href,progression}, 幂等), highlightSentence(index) (高亮 span + scrollIntoView), CLEAR_TTS_HIGHLIGHT |

### Phase 6 修改文件 (10)
| 文件 | 变更 |
|------|------|
| `AndroidManifest.xml` | +FOREGROUND_SERVICE, +POST_NOTIFICATIONS, +TtsPlaybackService (foregroundServiceType="mediaPlayback" + intent-filter) |
| `EpubReaderApplication.kt` | onCreate 创建 TTS Notification Channel (belt-and-suspenders, NEVER #25) |
| `AndroidNativeApi.kt` | +onSentencesExtracted @JavascriptInterface (origin 校验 NEVER #8) + BridgeCallback.onSentencesExtracted |
| `ReaderCommand.kt` | +ExtractSentences, +ClearTtsHighlight (无 HighlightSentence — 状态驱动 M2) |
| `ReaderViewModel.kt` | +注入 TtsController, +TTS StateFlows (ttsPanelState/sleepTimerState/ttsEngineState/ttsPlaybackState/ttsCurrentSentenceIndex/ttsGenerationId), +init connect (S4) + observe, +startTts/pauseTts/stopTts/seekTts/setTtsSpeed/setTtsPitch/onSentencesExtracted (Dispatchers.Default S5)/parseSentences (CancellationException re-throw S4)/buildLocator (locatorFromLink)/handleChapterEnd (M8)/nextReadingOrderLink/onChapterChanged (M7 isTtsNavigating)/saveTtsProgress (S1 真实 Locator)/sleep timer, +onCleared disconnect only (M3) |
| `ReaderHostFragment.kt` | +BridgeCallback.onSentencesExtracted, +handleCommand ExtractSentences/ClearTtsHighlight, +currentSentenceIndex 收集器 (状态驱动 M2) → highlightSentence JS, +href 变化 → onChapterChanged (M7) |
| `ReaderScreen.kt` | +TTS 状态收集, +VolumeUp 图标 (TopAppBar), +TtsControlPanel 覆盖层, +LanguagePackDialog 覆盖层, +LaunchedEffect Toast (M12 设置锁定提示) |
| `proguard-rules.pro` | +TtsPlayer/TtsPlaybackService/AndroidTtsEngine/TtsControllerImpl/core.tts keep 规则 (S10) |
| `res/values/strings.xml` | +20 TTS 字符串 (notification channel, panel, controls, sleep timer, language missing, error) |

### Phase 6 测试 (20 项新增, 116 项总计)
| 测试文件 | 测试数 | 验证点 |
|----------|--------|--------|
| `TtsEngineStateTest` | 7 | 状态转换, NEVER #28 契约 (Ready 前 speak 被拒绝), 看门狗/分块常量 |
| `TtsJsScriptsTest` | 10 | EXTRACT_SENTENCES (onSentencesExtracted 调用, origin 传递, 幂等, CJK, JSON 字段), highlightSentence (高亮类, index 参数, scrollIntoView), CLEAR_TTS_HIGHLIGHT |
| `AndroidNativeApiTest` (扩展) | +3 | onSentencesExtracted origin 校验 (NEVER #8), 阻止 origin, null callback 安全 |

## 6.3 Oracle + Council 审查结论

### 计划审查 (ora-1): ACCEPT_WITH_CHANGES
- 8 个必修项 (M1-M8): 单控制路径, 状态驱动高亮, onCleared 生命周期, Channel 顺序, 测试覆盖, 手动导航, 下一章链接
- 12 个建议项 (S1-S12): 精简 TtsBus, TtsEngine 接口, API 26 守卫, prefs 初始化, JSON 解析线程, Readium Decorations, 服务自停, 未连接契约, TtsBus 重置, ProGuard, 长句分块, CJK

### Council 风险预设: 3 个致命反模式 + 4 架构转向
- **Top Risk #1**: 句子作为 MediaItem → 通知栏灾难 → 改为章节级 (M9)
- **Top Risk #4/#8**: 缺少 Locator 映射 → 分页 UX 灾难 + 进度丢失 → TtsSentence 含 Locator (M10)
- **Top Risk #7**: UtteranceProgressListener 线程不安全 → Dispatchers.Main.immediate (M11)
- **Top Risk #6**: DOM Range 失效 → TTS 期间锁定 UserSettings (M12)
- **Top Risk #9**: TTS 引擎僵尸化 → 2 秒看门狗 (M13)
- 架构转向: Generation ID 防竞态 (M14), 状态单一真相源 (S1)

### 架构师最终审查: 批准 + M3 强制修正
- **M3 修正**: onCleared() 仅 disconnect() 不 stop() — 保留后台播放 (核心卖点)
- **Locator 落地指南**: JS 传特征值 (href + progression), Kotlin 用 locatorFromLink 组装
- 细节打磨: Seek 映射, generationId 清理, UserSettings 锁定 UX (alpha 0.5 + Toast)

### 结果审查 (ora-2): ACCEPT_WITH_CHANGES — 所有必修项已修复
- **M1 (致命)**: TtsEngine.initialize() 从未调用 → startPlayback() 中调用 initialize() ✅
- **M2 (致命)**: 无 resume-when-ready 机制 → TtsPlayer init 观察引擎状态, Ready 时自动恢复 ✅
- **M3 (高)**: speed/pitch 死控件 → handleSetPlaybackParameters + setPlaybackParameters ✅
- **M4 (中)**: UserSettings 锁未执行 → LaunchedEffect Toast 提示 ✅
- **S1**: saveTtsProgress 死代码 + 硬编码 0.5 → 使用 currentSentences 真实 Locator ✅
- **S4**: parseSentences 吞 CancellationException → try/catch re-throw ✅

## 6.4 关键流程

### 播放流程:
1. 用户点 TTS 图标 → isTtsPanelOpen=true
2. 用户点 Play → startTts() → 锁定 UserSettings (M12) → 发出 ExtractSentences command
3. Fragment 执行 EXTRACT_SENTENCES JS → JS 返回 JSON → AndroidNativeApi.onSentencesExtracted(origin, json)
4. Fragment → ReaderVM.onSentencesExtracted(json) → Dispatchers.Default 解析 (S5) → List<TtsSentence(id,text,locator)> + generationId (M14)
5. ReaderVM → ttsController.play(sentences) → TtsControllerImpl 写 TtsBus.sentences + generationId → MediaController.setMediaItem(章节级 M9) + play
6. TtsPlayer.handleSetMediaItems → 读取 TtsBus.sentences.value (M1 单路径) → handleSetPlayWhenReady → startPlayback()
7. 引擎未 Ready → ttsEngine.initialize() (M1) → Ready 后自动恢复 (M2) → TtsEngine.speak(sentence_0) + 看门狗 (M13)
8. UtteranceProgressListener.onStart (Dispatchers.Main.immediate M11) → TtsBus.currentSentenceIndex=0 + 取消看门狗
9. ReaderVM 观察 currentSentenceIndex → Fragment 收集 (M2 状态驱动) → HIGHLIGHT_SENTENCE JS

### 章节过渡:
1. 最后一句 onDone → TtsPlayer STATE_ENDED (章节级 M9)
2. ReaderVM 观察 STATE_ENDED → nextReadingOrderLink(currentHref) (M8)
3. 有下一章 → isTtsNavigating=true → navigator.go(nextLink) → href 变化 → onChapterChanged → ExtractSentences → 继续
4. 无下一章 → 停止 TTS + 解锁 UserSettings + UI 显示"已到书末"

### 手动导航中途打断 (M7):
1. 用户点 TOC 跳转 → href 变化 → onChapterChanged 检测 isTtsNavigating=false 且 TTS 在播放
2. ttsController.stop() + ClearTtsHighlight + 重新提取新章节句子

### 后台播放 (架构师 M3):
1. 用户离开阅读器 → ReaderVM.onCleared() → ttsController.disconnect() (仅释放 MediaController)
2. 服务通过前台通知继续后台播放
3. 用户返回 → ReaderVM init connect() → MediaController 重建 → 恢复 UI 控制

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
│   ├── tts/                          [Phase 6] TTS 核心契约 (纯 Kotlin)
│   │   ├── TtsEngineState.kt         密封接口: Uninitialized/Initializing/Ready/LanguageMissing/Error
│   │   ├── TtsPlaybackState.kt       密封接口: Idle/Playing/Paused/Ended
│   │   ├── TtsSentence.kt            数据类: id, text, locator (M10)
│   │   ├── TtsBus.kt                 @Singleton 共享状态 (sentences/engineState/currentSentenceIndex/generationId)
│   │   ├── TtsController.kt          接口: StateFlows + connect/play/pause/stop/seek/disconnect
│   │   └── TtsEngine.kt              接口: state + initialize/speak/stop/shutdown + Callbacks + 常量
│   ├── export/                       [Phase 5] Markdown 导出
│   │   ├── MarkdownExporter.kt       接口 + 纯导出数据类 (不依赖 Room 实体)
│   │   └── MarkdownExporterImpl.kt   flexmark HTML→MD 实现 + 章节分组
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
├── media/                            [Phase 6] Media3 TTS 实现
│   ├── AndroidTtsEngine.kt           TtsEngine 实现 (状态机+看门狗+线程安全+分块)
│   ├── TtsPlayer.kt                  SimpleBasePlayer (章节级 MediaItem+单路径+音频焦点)
│   ├── TtsPlaybackService.kt         MediaSessionService (Channel+Hilt+自停)
│   ├── TtsControllerImpl.kt          TtsController 实现 (MediaController+排队契约)
│   └── TtsModule.kt                  Hilt @Binds TtsController→TtsControllerImpl
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
    └── reader/                       [Phase 3/4/5]
        ├── ReaderUiState.kt          (@Immutable, +toc/isTocDrawerOpen/isSearchPanelOpen/isAutoScrollActive Phase 4, +isKnowledgePanelOpen Phase 5)
        ├── ReaderViewModel.kt        (@HiltViewModel, toRoute, 进度记忆, onCleared close, +搜索/选词/书签/自动滚动 Phase 4, +笔记/知识面板/导出 Phase 5)
        ├── ReaderHostFragment.kt     (@AndroidEntryPoint, EpubNavigatorFragment.Listener, +JS Bridge/命令/自动滚动 Phase 4)
        ├── ReaderScreen.kt           (AndroidViewBinding + Edge-to-Edge 沉浸式, +TOC抽屉/搜索/选词工具栏 Phase 4, +知识面板/笔记编辑器/SAF导出 Phase 5)
        ├── PreferencesMapper.kt      (AppPreferences→EpubPreferences)
        ├── AndroidNativeApi.kt       [Phase 4] @Keep JS Bridge + origin 校验 + BridgeCallbackHolder
        ├── ReaderJsScripts.kt        [Phase 4] 自动滚动/选词 JS 脚本 (touchstart→cancelAnimationFrame)
        ├── ReaderCommand.kt          [Phase 4] Fragment 命令密封接口
        ├── TocDrawer.kt              [Phase 4] TOC 侧滑抽屉 + TocItem
        ├── SearchPanel.kt            [Phase 4] 搜索面板 + SearchState/SearchResult
        ├── SelectionToolbar.kt       [Phase 4/5] 选词工具栏 + SelectionState (+Note 按钮 Phase 5)
        ├── KnowledgeUiState.kt       [Phase 5] KnowledgeItem/KnowledgeState + ExportState + NoteEditorState
        ├── KnowledgePanel.kt         [Phase 5] 知识面板 ModalBottomSheet (高亮/笔记/书签列表 + 导出按钮)
        ├── NoteEditorDialog.kt       [Phase 5] 笔记编辑器 AlertDialog
        ├── TtsUiState.kt             [Phase 6] TtsPanelState + SleepTimerState
        ├── TtsControlPanel.kt        [Phase 6] TTS 控制 ModalBottomSheet (播放/速度/音调/睡眠定时器)
        ├── LanguagePackDialog.kt     [Phase 6] 语言包缺失/错误对话框
        └── TtsJsScripts.kt           [Phase 6] 句子提取+高亮 JS (CJK 分句, DOM Range, Locator 特征值)
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

## Readium v3 桥接约定 (Phase 3-4)
- **HttpServer 已移除**: v3.3.0 使用内部 WebViewServer, 无需本地 HTTP 服务器 (D1 偏离 harness)
- **Try<T> 用 `fold`/`getOrElse`/`onFailure` 处理**, **禁止 try-catch 包裹** (NEVER #14)
- **Publication.close() 在 `ReaderViewModel.onCleared()`** (D3 — Compose+VM 等价于 Fragment.onDestroy)
- **Locator 通过 LocatorMapper 转换**, 不在数据层引入 Readium 类型 (D2)
- **Fragment 嵌入用 AndroidViewBinding + XML android:name** (D4 — NEVER #6)
- **高频状态 (currentLocator) 隔离到独立 StateFlow** (M2 — NEVER #12)
- **JS Bridge 工厂模式注册** (Phase 4 D1): `registerJavascriptInterface(name) { link -> ... }`, 仅 HTML 资源
- **JS Bridge origin 校验** (Phase 4 D1/NEVER #8): `window.location.origin` against `{"https://readium_package", "https://readium_assets"}`
- **JS Bridge 清理在 onDestroyView** (Phase 4 D2/NEVER #20): `bridgeCallbackHolder.callback = null`
- **自动滚动状态驱动** (Phase 4 D5): `uiState.isAutoScrollActive` StateFlow, 非 command
- **高亮装饰 StateFlow** (Phase 4 D6): `highlightDecorations` 直接收集, 非 command (避免 replay=0 丢失)
- **JS 脚本资源切换重注入** (Phase 4 D7): 观察 `currentLocator.href` 变化重注入
- **SearchService @ExperimentalReadiumApi** (Phase 4 D4): `@OptIn(ExperimentalReadiumApi, Search)`

## 知识管理与导出约定 (Phase 5)
- **core/export 不依赖 Room 实体** (Phase 5 D1/Oracle M1): 使用纯数据类 (ExportHighlight/ExportNote/ExportBookmark/ExportTocItem/ExportRequest), VM 映射实体→纯模型
- **HTML→MD 用 flexmark-java** (Phase 5 D2): `FlexmarkHtmlConverter` (lazy + @Synchronized), 禁正则 (harness §7)
- **章节分组 + 片段归一化** (Phase 5 D3/Oracle M2): `ExportTocItem.href` + `substringBefore('#')` 匹配
- **导出流程分离** (Phase 5 D4): VM 生成 MD 字符串 (无 Context/Uri), UI 处理 SAF/ShareSheet (NEVER #2)
- **SAF 写入 try-catch** (Phase 5 D4/Oracle M3): CancellationException re-throw (NEVER #26 精神)
- **笔记编辑器 UI StateFlow** (Phase 5 D5/Oracle M4): `noteEditorState` StateFlow, 非 Fragment `commands` SharedFlow
- **笔记创建顺序依赖** (Phase 5 D5/Oracle S2): 先 HighlightEntity 后 NoteEntity(highlightUuid), 高亮失败不创建笔记
- **一次性查询导出** (Phase 5 D6): `getByBook` suspend 查询, 非 StateFlow.value (保证新鲜数据)
- **面板互斥** (Phase 5 D5/Oracle S3): 开启 KnowledgePanel 关闭 SearchPanel, 反之亦然

## Media3 TTS 约定 (Phase 6)
- **SimpleBasePlayer 章节级 MediaItem** (Phase 6 D1/Council M9): 1 MediaItem = 1 章节, 句子进度通过 TtsBus 内部追踪 (避免通知栏重建风暴)
- **TtsBus 单路径数据源** (Phase 6 D4/Oracle M1): TtsPlayer 读取 TtsBus.sentences.value on demand, 不收集 StateFlow (避免双路径竞态)
- **句子高亮状态驱动** (Phase 6 D6/Oracle M2): currentSentenceIndex 是 StateFlow, 非 ReaderCommand (避免淹没 SharedFlow 缓冲区)
- **TtsSentence 含 Locator** (Phase 6 D6/Council M10): 支持分页模式翻页 + 进度保存; JS 传特征值, Kotlin 用 locatorFromLink 组装
- **TTS 引擎状态机** (Phase 6 D3/NEVER #28): speak() 仅在 Ready 执行; 2 秒看门狗 (M13); UtteranceProgressListener 回调切 Dispatchers.Main.immediate (M11)
- **后台播放生命周期** (Phase 6 D8/架构师 M3): onCleared() 仅 disconnect() 不 stop() — 服务通过前台通知继续; stop() 仅在显式停止/切书/书末
- **generationId 防竞态** (Phase 6 D4/Council M14): 每次 JS 提取生成 UUID, 高亮命令校验 generationId 防跨章节崩溃
- **UserSettings 锁定** (Phase 6 D6/Council M12): TTS 期间锁定排版设置 (防 DOM Range 失效), UI alpha 0.5 + Toast
- **Notification Channel 顺序** (Phase 6 D2/Oracle M5): super.onCreate() (Hilt 注入) → createChannel → MediaSession
- **playbackState 单一真相源** (Phase 6 D4/Oracle S1): 从 Player.Listener 派生, 不存 TtsBus (避免双源竞态)
- **未连接排队契约** (Phase 6 D5/Oracle S8): play() 排队, 连接后重放; 其他命令丢弃+日志
- **文件名净化** (Phase 5 D4/Oracle S5): `replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)`
- **blockquote 逐行前缀** (Phase 5 D2/Oracle S7): 多行高亮文本每行加 `> ` 前缀

## 同步预留 (Phase 7 用)
- **Offline-First**: Local 优先, 后台增量同步
- **Last-Write-Wins**: 对比 `updatedAt`, `isDeleted = 1` 优先级最高
- **传输层**: `RemoteDataSource` 仅传输, LWW 合并在 `SyncManager` (Phase 7)

---

# NEVER 规则合规性 (Phase 1-5)

| # | 规则 | 合规 | 验证方式 |
|---|------|------|----------|
| 2 | VM 注入接口非 DAO | ✅ | ReaderVM 注入 BookRepository + ReadingProgressRepository + BookmarkRepository + HighlightRepository + NoteRepository + MarkdownExporter + Readium 组件 (Phase 5 新增 NoteRepository + MarkdownExporter) |
| 5 | 禁 file:// | ✅ | v3.x WebViewServer 拦截, 无 file:// (D1) |
| 6 | 禁 AndroidView 中 new Fragment | ✅ | AndroidViewBinding + XML android:name (M5 修复) |
| 8 | JS Bridge origin 校验 | ✅ | AndroidNativeApi.isAllowedOrigin + 工厂资源级作用域 (Phase 4 D1) |
| 9 | 自动滚动 touchstart→cancelAnimationFrame | ✅ | ReaderJsScripts.AUTO_SCROLL_START (Phase 4) |
| 12 | 高频状态 derivedStateOf/隔离 | ✅ | currentLocator/searchState/selectionState/highlightDecorations 独立 StateFlow; isBookmarked derivedStateOf (Phase 4); knowledgeState/exportState/noteEditorState 独立 StateFlow (Phase 5) |
| 14 | Try 函数式处理 | ✅ | getOrElse/fold, 无 try-catch 包裹 Try; SearchIterator.next().getOrElse (Phase 4) |
| 15 | SAF 用 rememberLauncherForActivityResult | ✅ | OpenDocument() (Phase 2); CreateDocument("text/markdown") (Phase 5) |
| 18 | 类型安全路由 | ✅ | @Serializable + composable<Route> |
| 20 | WebView/JS Bridge 在 onDestroyView | ✅ | bridgeCallbackHolder.callback = null in onDestroyView (Phase 4) |
| 21 | collectAsStateWithLifecycle | ✅ | 全部使用 (含 Phase 4 新增 searchState/selectionState/bookmarkedHrefs; Phase 5 新增 knowledgeState/exportState/noteEditorState) |
| 22 | toRoute<T>() 提取参数 | ✅ | ReaderViewModel savedStateHandle.toRoute<ReaderRoute>() |
| 23 | @Keep on @JavascriptInterface | ✅ | AndroidNativeApi @Keep + ProGuard 规则 (Phase 4) |
| 24 | NIO + yield() 大文件拷贝 | ✅ | EpubBookImporter |
| 26 | try-catch / CoroutineExceptionHandler | ✅ | runCatchingAsync + launch(handler) + 命令处理 try-catch + CancellationException re-throw (Phase 4 MF1); SAF 写入 try-catch (Phase 5 Oracle M3); parseSentences try-catch + CancellationException re-throw (Phase 6 Oracle S4) |
| 27 | usableSpace 校验 + finally 清理 | ✅ | EpubBookImporter |
| 28 | TTS OnInit SUCCESS 前禁 speak | ✅ | AndroidTtsEngine 状态机: speak() 仅在 Ready 执行 (Phase 6 D3/NEVER #28) |
| 29 | DataStore 强类型 Key | ✅ | PreferenceKeys internal object |

---

# 测试覆盖 (116 项)

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
| `AndroidNativeApiTest` | 9 | 4/6 | NEVER #8 (origin 校验), null callback 安全, +onSentencesExtracted (Phase 6) |
| `ReaderJsScriptsTest` | 10 | 4 | NEVER #9 (touchstart→cancelAnimationFrame), origin 传递, 幂等性 |
| `ReaderUiStatePhase4Test` | 9 | 4 | UiState/TocItem/SearchState/SelectionState/ReaderCommand |
| `MarkdownExporterTest` | 15 | 5 | HTML→MD 转换 (flexmark), 章节分组, 片段归一化, Unsorted, 多行 blockquote |
| `KnowledgeUiStateTest` | 15 | 5 | KnowledgeState/KnowledgeItem, ExportState 四态, NoteEditorState 两态, ReaderUiState Phase 5 字段 |
| `TtsEngineStateTest` | 7 | 6 | 状态转换, NEVER #28 契约, 看门狗/分块常量 |
| `TtsJsScriptsTest` | 10 | 6 | EXTRACT_SENTENCES (CJK, JSON, 幂等), highlightSentence, CLEAR_TTS_HIGHLIGHT |
| **合计** | **116** | | **全部通过** |

---

# 后续 Phase 衔接要点

### Phase 4 (JS Bridge 与交互) — ✅ 完成
- `@JavascriptInterface` + `@Keep` 防 R8 (NEVER #23) — AndroidNativeApi 已实现
- 校验 `window.location.origin` (NEVER #8) — isAllowedOrigin + 工厂资源级作用域
- 自动滚动 JS 绑定 `touchstart` → `cancelAnimationFrame` (NEVER #9) — ReaderJsScripts.AUTO_SCROLL_START
- `registerJavascriptInterface("AndroidNativeApi")` 工厂模式 — buildConfiguration()
- 侧滑目录 (TOC), 全文搜索 (SearchService), 选词, 书签, 自动滚动 — 全部实现

### Phase 5 (知识管理与导出) — ✅ 完成
- flexmark-java HTML→MD (不用正则) — MarkdownExporterImpl (FlexmarkHtmlConverter)
- `rememberLauncherForActivityResult` 唤起 ShareSheet/SAF — CreateDocument + ACTION_SEND
- 笔记列表 (KnowledgePanel ModalBottomSheet) + 笔记编辑器 (NoteEditorDialog)
- 按书籍/章节聚合导出 — 章节分组 + 片段归一化
- core/export 纯数据类 (不依赖 Room 实体) — Oracle M1
- 笔记创建: 先高亮后笔记 (FK 顺序依赖) — Oracle S2

### Phase 6 (Media3 TTS 听书) — ✅ 完成
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `FOREGROUND_SERVICE` + `POST_NOTIFICATIONS` 权限已声明 (NEVER #10)
- Notification Channel 在 super.onCreate() 后创建 (NEVER #25, Oracle M5)
- TTS 状态机 (Uninitialized/Initializing/Ready/LanguageMissing/Error) — speak() 仅在 Ready (NEVER #28)
- 手动 AudioManager 音频焦点 (USAGE_MEDIA + CONTENT_TYPE_SPEECH, NEVER #11)
- MediaController 触发服务启动, 合规 (NEVER #16)
- SimpleBasePlayer 章节级 MediaItem (Council M9 — 避免通知栏重建风暴)
- TtsEngine 看门狗 (Council M13) + 线程安全 (Council M11) + 长句分块 (Council S11)
- TtsSentence 含 Locator (Council M10 — 分页翻页 + 进度保存)
- generationId 防跨章节高亮竞态 (Council M14)
- onCleared() 仅 disconnect 不 stop — 后台播放继续 (架构师 M3 修正)
- JS 句子提取 + CJK 分句 + DOM Range 高亮
- 睡眠定时器 (5/10/15/30m + 本章结束)

### Phase 7 (测试驱动 + 同步)
- Repository 双数据源测试, 协程异常边界, Turbine, Compose UI E2E
- SyncManager: LWW 合并 (对比 updatedAt, isDeleted=1 优先)
- 可能需要领域模型映射层 (SF-2)
- Locator JSON canonical 排序 (S10 — 同步确定性)
- TTS 集成测试 (需 Robolectric 或设备测试 — SimpleBasePlayer Looper 依赖)

---

# 验证命令参考

```powershell
# 编译 + 生成 APK
.\gradlew.bat :app:assembleDebug --no-daemon

# 运行单元测试 (116 项全部通过)
.\gradlew.bat :app:testDebugUnitTest --no-daemon

# 运行插桩测试 (需连接模拟器/设备)
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon
```

**环境**: JDK 21 (`C:\Users\MECHREVO\jdks\jdk21\jdk-21.0.2`), Android SDK (`C:\Users\MECHREVO\AppData\Local\Android\Sdk`), 平台 35/36.1/37, Build-tools 34-37, Gradle 9.4.1 (wrapper), Compose BOM 2026.06.00, Kotlin 2.3.20, KSP 2.3.9, Readium 3.3.0
