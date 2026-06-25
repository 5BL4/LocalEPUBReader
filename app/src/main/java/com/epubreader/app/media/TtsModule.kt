package com.epubreader.app.media

import com.epubreader.app.core.tts.TtsController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module binding [TtsController] → [TtsControllerImpl].
 *
 * [TtsBus] is @Singleton with @Inject constructor — no binding needed.
 * [AndroidTtsEngine] and [TtsPlayer] are constructed directly by
 * [TtsPlaybackService] (not Hilt-injected) because they are service-scoped
 * and Context-bound.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TtsModule {

    @Binds
    @Singleton
    abstract fun bindTtsController(impl: TtsControllerImpl): TtsController
}
