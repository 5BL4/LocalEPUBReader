# EpubReader — Phase 1 进度文档 (Phase 1 Progress Report)

> **项目**: 本地 EPUB 阅读器 (EpubReader)  
> **Phase**: 1 — 基础架构与同步数据模型  
> **状态**: ✅ 完成 (Oracle 审查通过, ACCEPT_WITH_CHANGES, 无阻塞项)  
> **日期**: 2026-06-25  
> **下一步**: 等待用户指令后开始 Phase 2 (书架 UI 与路由)

---

## 1. 已完成的交付物

### 1.1 Gradle 工程脚手架
| 文件 | 说明 |
|------|------|
| `settings.gradle.kts` | pluginManagement + dependencyResolutionManagement, 项目名 EpubReader, include :app |
| `gradle/libs.versions.toml` | 版本目录 — 所有依赖版本锁定 (见 §2) |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle 9.4.1 (已缓存) |
| `build.gradle.kts` | 根构建文件, 插件 `apply false` |
| `app/build.gradle.kts` | 应用模块: compileSdk=37, targetSdk=35, minSdk=24, jvmTarget=17, Compose, KSP, Hilt, 序列化, 核心库脱糖 |
| `gradle.properties` | JVM 参数, AndroidX, nonTransitiveRClass |
| `local.properties` | SDK 路径 (gitignored) |
| `app/proguard-rules.pro` | R8 规则: @JavascriptInterface @Keep (预置, Phase 4 用) |

### 1.2 Manifest 与 Application
- `AndroidManifest.xml`: 声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限 (NEVER #10), Application = `.EpubReaderApplication`
- `EpubReaderApplication.kt`: `@HiltAndroidApp` — 仅此一项, 不注册全局异常处理器 (S8)

### 1.3 核心包 (`core/`)
| 文件 | 职责 |
|------|------|
| `Result.kt` | 函数式结果类型 `Success<T>`/`Error` — Repository 返回类型 (非 UiState) |
| `Syncable.kt` | 同步契约接口 + `SyncCursor`/`SyncPage`/`PushAck` |
| `DispatchersProvider.kt` | 可注入的 Dispatcher 提供者 (IO/Default/Main/MainImmediate) — 可测试 |
| `ErrorChannel.kt` | `@Singleton` 全局错误通道 (`SharedFlow<AppError>`) |
| `AppCoroutineExceptionHandler.kt` | `@Singleton` 协程异常兜底处理器 (日志 + 发射到 ErrorChannel) |
| `README.md` | 约定文档: Result vs UiState, Readium Try→Result 桥接规则, 异常策略 |

### 1.4 Room 数据层 (`data/local/`)
**5 个实体** (均实现 `Syncable`, `uuid: String` 主键, `isDeleted` 软删除, `createdAt`/`updatedAt`/`syncedAt`/`userId` 同步字段):

| 实体 | 表名 | 业务字段 | 外键 |
|------|------|----------|------|
| `BookEntity` | books | title, author, coverPath, filePath, fileSize, format | — |
| `ReadingProgressEntity` | reading_progress | bookUuid, locator(String), progress(Double) | →books (NO_ACTION) |
| `BookmarkEntity` | bookmarks | bookUuid, locator, label | →books (NO_ACTION) |
| `HighlightEntity` | highlights | bookUuid, locator, text, color | →books (NO_ACTION) |
| `NoteEntity` | notes | bookUuid, highlightUuid, locator, content | →books + →highlights (NO_ACTION) |

- **索引**: 每个实体在 `updatedAt`/`syncedAt`/`isDeleted` 上建索引; 子实体额外在 `bookUuid` (NoteEntity 在 `highlightUuid`) 上建索引
- **外键**: 全部 `onDelete = NO_ACTION` (无 CASCADE — NEVER #7)
- **5 个 DAO**: `upsert`, `softDelete` (UPDATE isDeleted=1), `observeActive`, `observeById`, `getById`, `getAllActive`, `getDirty`, `getUpdatedSince`, `markSynced`; 子 DAO 额外有 `observeByBook`
- `Converters.kt`: `List<String>` ↔ JSON (无 Locator 类型转换器 — Readium 留到 Phase 3)
- `AppDatabase.kt`: `@Database(version=1, exportSchema=true)`, 5 个实体, 5 个 DAO 访问器
- **Schema 已导出**: `app/schemas/com.epubreader.app.data.local.AppDatabase/1.json` (18.9KB, 已纳入 git — 迁移基线)

### 1.5 远程数据源 (`data/remote/`)
- `RemoteDataSource.kt`: 同步传输接口 (仅 `pullSince`/`push`, 不含 LWW 合并逻辑 — 合并留给 Phase 7 SyncManager)
- `NoopRemoteDataSource.kt`: `@Singleton` 空实现 (Phase 1 本地优先, 无后端)

### 1.6 DataStore 强类型封装 (`data/prefs/`)
- `AppPreferences.kt`: `@Immutable` 快照数据类 — fontSize, fontFamily, lineSpacing, theme, backgroundColor, autoPageIntervalMs, autoScrollSpeed, ttsRate, ttsPitch, ttsEngine (**无 locator/progress** — 阅读进度在 Room)
- `ThemeMode.kt`: 枚举 (LIGHT/DARK/SEPIA/SYSTEM)
- `PreferenceKeys.kt`: `internal object` — 所有 `Preferences.Key<T>` 集中于此 (业务逻辑不可见原始 String Key — NEVER #29)
- `PreferencesRepository.kt` / `PreferencesRepositoryImpl.kt`: 强类型接口 + 实现, `Flow<AppPreferences>` + 10 个类型化 suspend setter

### 1.7 仓库层 (`domain/repository/` + `data/repo/`)
- **5 个接口** (在 `domain.repository`): `BookRepository`, `ReadingProgressRepository`, `BookmarkRepository`, `HighlightRepository`, `NoteRepository` — 返回 `Flow<T>` (观察) 或 `Result<T>` (一次性操作)
- **5 个实现** (在 `data.repo`): `@Singleton @Inject constructor(dao, dispatchers)` — 一次性操作用 `withContext(dispatchers.io) { Result.runCatchingAsync { ... } }` 包裹 (NEVER #26)

### 1.8 Hilt DI (`di/`)
- `DatabaseModule.kt`: 提供 `AppDatabase` (Singleton) + 5 个 DAO (仅用于仓库注入 — NEVER #2)
- `DataStoreModule.kt`: 提供 `DataStore<Preferences>` (Singleton)
- `AppBindingsModule.kt`: 8 个 `@Binds` 绑定 (DispatchersProvider, RemoteDataSource, PreferencesRepository, 5 个仓库接口)
- `ErrorChannel` 和 `AppCoroutineExceptionHandler` 通过 `@Inject constructor` 由 Hilt 自动构造

### 1.9 测试
| 测试 | 类型 | 状态 | 验证规则 |
|------|------|------|----------|
| `PreferencesRepositoryTest` (3 个) | 单元测试 (JUnit 5) | ✅ 通过 | NEVER #29 (类型化 Key, 无原始 String) |
| `CoroutineExceptionGuardTest` (2 个) | 单元测试 (JUnit 5 + MockK) | ✅ 通过 | NEVER #26 (DAO 异常 → Result.Error) |
| `BookDaoSoftDeleteTest` (2 个) | 插桩测试 (AndroidJUnit4) | ⏸ 延迟 (无模拟器) | NEVER #7+#17 (软删除, uuid 主键) |

---

## 2. 锁定的依赖版本 (后续 Phase 共用)

| 依赖 | 版本 | 备注 |
|------|------|------|
| AGP | 9.2.1 | Gradle 9.4.1, JDK 21 |
| Kotlin | 2.3.20 | 与 Readium 3.3.0 编译时版本匹配; KSP 2.3.9 可用 |
| KSP | 2.3.9 | 独立于 Kotlin 版本 (KSP2) |
| Compose BOM | 2026.06.00 | 编译器通过 `org.jetbrains.kotlin.plugin.compose` (无 composeOptions) |
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
| JUnit 5 BOM | 5.14.4 | + junit-platform-launcher |
| MockK | 1.14.11 | |
| Turbine | 1.2.1 | |

**关键约束**: 不要升级到 Kotlin 2.4.0 (无 KSP 2.4.x, 与 Readium 未验证)。不要使用 alpha/RC 版本 (DataStore 1.3.x, Navigation 2.10.x, Media3 1.11.x, hilt-navigation-compose 1.4.x)。

---

## 3. 包结构 (Clean Architecture)

```
com.epubreader.app
├── EpubReaderApplication.kt          (@HiltAndroidApp)
├── core/                              核心工具与契约
│   ├── Result.kt                      函数式结果类型
│   ├── Syncable.kt                    同步契约 + 游标/页/确认
│   ├── DispatchersProvider.kt         可注入 Dispatcher
│   ├── ErrorChannel.kt                全局错误通道
│   ├── AppCoroutineExceptionHandler.kt  协程异常兜底
│   └── README.md                      约定文档
├── data/
│   ├── local/                         Room 数据层
│   │   ├── AppDatabase.kt
│   │   ├── entity/                    5 个实体
│   │   ├── dao/                       5 个 DAO
│   │   └── converter/Converters.kt
│   ├── remote/                        远程数据源 (传输层)
│   ├── prefs/                         DataStore 强类型封装
│   └── repo/                          仓库实现
├── domain/
│   └── repository/                    仓库接口 (ViewModel 注入此层)
└── di/                                Hilt 模块
    ├── DatabaseModule.kt
    ├── DataStoreModule.kt
    └── AppBindingsModule.kt
```

**Phase 2+ 预留**: `ui/` (Compose 界面), `feature/` (功能模块), `navigation/` (路由定义)

---

## 4. 关键架构约定 (后续 Phase 必须遵守)

### 4.1 数据模型约定
- **主键**: 所有用户数据表使用 `uuid: String` (NEVER #17 — 禁止自增 Int)
- **软删除**: `UPDATE ... SET isDeleted = 1, updatedAt = :now` (NEVER #7 — 禁止物理 DELETE)
- **查询过滤**: 所有活跃查询加 `WHERE isDeleted = 0`
- **同步字段**: 每个实体包含 `uuid`, `isDeleted`, `createdAt`, `updatedAt`, `syncedAt`, `userId`
- **外键**: `onDelete = NO_ACTION` (软删除级联在仓库逻辑中处理, 非 DB 层)
- **Locator**: 当前存储为原始 `String` (JSON); Phase 3 引入 Readium `Locator` 类型转换器

### 4.2 仓库层约定
- **接口在 `domain.repository`**, **实现在 `data.repo`** — ViewModel 注入接口, 不注入 DAO (NEVER #2)
- **一次性操作**: `withContext(dispatchers.io) { Result.runCatchingAsync { dao.xxx() } }` — 异常转为 `Result.Error` (NEVER #26)
- **观察操作**: 直接返回 DAO 的 `Flow` (Room 处理 Flow 错误)
- **Result vs UiState**: `Result<T>` 是仓库返回类型 (Success/Error); `UiState` (Loading/Success/Error) 是 Phase 2 表现层状态 — 仓库不返回 UiState

### 4.3 DataStore 约定
- **强类型封装**: 业务逻辑通过 `PreferencesRepository` 接口访问, 不直接接触 `Preferences.Key` (NEVER #29)
- **Key 集中管理**: `PreferenceKeys` 是 `internal object`, 仅 `data.prefs` 包内可见
- **阅读进度不在 DataStore**: 在 Room `ReadingProgressEntity` 中 (支持多端同步)

### 4.4 协程异常约定
- **局部 try-catch 是主防线**: 仓库层 `Result.runCatchingAsync` 包裹所有高风险操作
- **`AppCoroutineExceptionHandler` 是兜底**: 通过 `CoroutineModule`(已合并入 `AppBindingsModule`) 注入, 用于 `viewModelScope.launch(handler)` 的未捕获异常
- **不在 Application 注册全局处理器**: `EpubReaderApplication` 仅 `@HiltAndroidApp`

### 4.5 Readium v3 桥接约定 (Phase 3 用)
- `Try<T>` 用 `fold`/`onFailure` 处理, **禁止 try-catch 包裹** (NEVER #14)
- 桥接模式: `tryResult.fold(onSuccess = { Result.Success(it) }, onFailure = { Result.Error(it) })` — 注意这是 Readium 的 `Try.fold` (转换型), 非 `core/Result.fold` (消费型, 返回 Unit)
- `HttpServer` 内置于 navigator 模块, `HttpServer().serve(publication)` 提供 localhost http:// URL (避免 file:// + CORS)

### 4.6 同步预留 (Phase 7 用)
- **Offline-First**: Local 优先, 后台增量同步
- **Last-Write-Wins**: 对比 `updatedAt`, `isDeleted = 1` 优先级最高
- **传输层**: `RemoteDataSource` 仅传输 (`pullSince`/`push`), LWW 合并在 `SyncManager` (Phase 7)
- **脏数据查询**: `getDirty()` (syncedAt IS NULL OR syncedAt < updatedAt), `getUpdatedSince(since)` (updatedAt > since)

---

## 5. 构建配置要点 (后续 Phase 需知)

- **compileSdk = 37, targetSdk = 35**: 2026 年的库要求 compileSdk 36/37; targetSdk=35 满足 harness "前瞻适配 API 35"; 两者可不同
- **核心库脱糖已启用**: `isCoreLibraryDesugaringEnabled = true` + `desugar_jdk_libs:2.1.5` (Readium 需要 java.time 在 minSdk 24)
- **AGP 9 内置 Kotlin**: 不应用 `org.jetbrains.kotlin.android` 插件 (AGP 9.0 内置); `kotlin-compose` 和 `kotlin-serialization` 仍显式应用
- **Compose 编译器**: 通过 `org.jetbrains.kotlin.plugin.compose` 插件, **不设** `composeOptions.kotlinCompilerExtensionVersion`
- **KSP 参数**: `room.schemaLocation=$projectDir/schemas`, `room.generateKotlin=true`
- **JUnit 5**: `useJUnitPlatform()` + `junit-platform-launcher` 在 testRuntimeOnly; 测试用 `org.junit.jupiter.api` 包

---

## 6. Oracle 审查结论

### 计划审查 (ora-1): ACCEPT_WITH_CHANGES
- 必修项 M1 (阅读进度移至 Room) ✅ 已修复
- 必修项 M2 (无 CASCADE 外键) ✅ 已修复
- 建议项 S1-S9 全部采纳

### 结果审查 (ora-2): ACCEPT_WITH_CHANGES — 无阻塞项
- **无必修项**: Phase 2 可立即开始, 零返工
- 建议项 (非阻塞, 可在 Phase 2/7 处理):
  - SF-1: DataStore 轮询测试绕过了 setter (建议 Phase 2 补一个 setter 轮询 + setTtsEngine(null) 移除测试)
  - SF-2: 仓库接口直接暴露 Room 实体类型 (务实折中; Phase 7 同步时可能需要领域模型映射层)
  - SF-3: core/README.md fold 命名冲突 ✅ 已修复 (澄清 Try.fold vs Result.fold)
  - SF-4: CoroutineModule 合并入 AppBindingsModule (组织偏差, 功能等价)

### Phase 2 就绪评估: ✅ 立即可开始
| Phase 2 需求 | 状态 |
|-------------|------|
| BookRepository | ✅ 接口+实现+DI 绑定 |
| PreferencesRepository | ✅ 接口+实现+DI 绑定 |
| Hilt DI | ✅ 8 个绑定已生效 |
| Navigation 2.8+ 类型安全路由 | ✅ 依赖已锁定 (路由定义在 Phase 2) |
| Edge-to-Edge | ✅ activity-compose 1.13.0 (enableEdgeToEdge) + window 1.5.1 |
| Compose BOM | ✅ 2026.06.00 已锁定 |
| collectAsStateWithLifecycle | ✅ lifecycle-runtime-compose 2.9.4 已锁定 |
| 协程异常兜底 | ✅ AppCoroutineExceptionHandler + ErrorChannel 可注入 |

---

## 7. 后续 Phase 衔接要点

### Phase 2 (书架 UI 与路由) — 立即可开始
- 新增: `MainActivity` (enableEdgeToEdge), Compose 书架 (`LazyVerticalGrid`), `@HiltViewModel`, `@Serializable` 路由, NavHost
- **注意**: VM 消费 `BookRepository.observeBooks(): Flow<List<BookEntity>>` 时, 必须用 `collectAsStateWithLifecycle()` (NEVER #21); 列表需包装为 `PersistentList` 放入 `@Immutable` UiState (架构师笔记)
- **注意**: SAF 导入用 `rememberLauncherForActivityResult` (NEVER #15); 大文件用 NIO + yield() (NEVER #24); 拷贝前校验 `File.usableSpace` (NEVER #27); finally 清理脏数据
- **建议**: 补充 SF-1 的 setter 轮询测试

### Phase 3 (Readium v3 引擎集成)
- `Streamer.open()` 返回 `Try<Publication>` — 用 `fold` 处理 (NEVER #14)
- `HttpServer().serve(publication)` — localhost http:// 规避 CORS (NEVER #5 禁 file://)
- Fragment 缝合: JS Bridge 绑定在 `onCreateView`/`onDestroyView` (NEVER #6, #20)
- 引入 Readium `Locator` 类型转换器 (替换当前的 raw String)
- `Publication.close()` 在 `onDestroy`

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

## 8. 验证命令参考

```powershell
# 编译 + 生成 Room Schema
.\gradlew.bat :app:assembleDebug --no-daemon

# 运行单元测试
.\gradlew.bat :app:testDebugUnitTest --no-daemon

# 运行插桩测试 (需连接模拟器/设备)
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon

# 配置检查
.\gradlew.bat :app:help --no-daemon
```

**环境**: JDK 21 (`C:\Users\MECHREVO\jdks\jdk21\jdk-21.0.2`), Android SDK (`C:\Users\MECHREVO\AppData\Local\Android\Sdk`), 平台 35/36.1/37, Build-tools 34-37, Gradle 9.4.1 (wrapper)
