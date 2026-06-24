package com.epubreader.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application shell.
 *
 * Only hosts @HiltAndroidApp (S8). The global CoroutineExceptionHandler is NOT
 * registered here at the JVM/Application level; it is provided via CoroutineModule
 * and injected per viewModelScope.launch. Local try-catch in repositories is the
 * primary exception guard (NEVER #26); the handler is the backstop.
 */
@HiltAndroidApp
class EpubReaderApplication : Application()
