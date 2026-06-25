package com.epubreader.app.core

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed wrapper for string resource access, keeping [Context] out of ViewModels (NEVER #2).
 * Same pattern as [com.epubreader.app.data.prefs.PreferencesRepository] hiding DataStore keys.
 */
interface StringProvider {
    fun get(@StringRes resId: Int): String
    fun get(@StringRes resId: Int, vararg args: Any): String
}

@Singleton
class AndroidStringProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : StringProvider {
    override fun get(resId: Int): String = context.getString(resId)
    override fun get(resId: Int, vararg args: Any): String = context.getString(resId, *args)
}
