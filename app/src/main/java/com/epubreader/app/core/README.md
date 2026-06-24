## Core Package Conventions

- `Result<T>` is the core functional result type returned by repositories.
- `UiState` (Loading/Success/Error) is a Phase 2 presentation-layer concern and MUST NOT be returned from repositories.
- Readium v3 `Try<T>` (arrives Phase 3) is bridged to `Result` using Readium's **`Try.fold`** (transforming — returns the branch result): `tryResult.fold(onSuccess = { Result.Success(it) }, onFailure = { Result.Error(it) })`. Note: `core/Result.kt` also defines a `Result.fold` but it returns `Unit` (side-effecting consumer) — do NOT confuse the two. NEVER try-catch around `Try<T>` (NEVER #14).
- High-risk I/O/network/db in viewModelScope MUST be wrapped: local try-catch in repos → `Result.Error`; `AppCoroutineExceptionHandler` is the backstop only (NEVER #26).
