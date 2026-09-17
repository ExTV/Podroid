/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.data.repository

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanguageManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    companion object {
        const val LANGUAGE_AUTO = "auto"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"

        private const val LANG_CACHE_FILE = "podroid_lang_cache"

        /**
         * Synchronously reads the saved language preference from the cache file.
         * Called from [MainActivity.attachBaseContext] before Hilt injection is available.
         */
        fun getSavedLanguage(ctx: Context): String {
            return try {
                val file = File(ctx.filesDir, LANG_CACHE_FILE)
                // Clamp to a known value so a partially-written or stale cache
                // can't feed an unrecognized locale into attachBaseContext; an
                // unknown value means "follow the system" exactly like the flow.
                val raw = if (file.exists()) file.readText().trim() else LANGUAGE_AUTO
                if (raw == LANGUAGE_ZH || raw == LANGUAGE_EN) raw else LANGUAGE_AUTO
            } catch (_: Exception) {
                LANGUAGE_AUTO
            }
        }

        /**
         * Persists the language preference to the cache file so it's available
         * synchronously on the next process start.
         */
        fun persistLanguage(ctx: Context, language: String) {
            try {
                File(ctx.filesDir, LANG_CACHE_FILE).writeText(language)
            } catch (e: Exception) {
                // Log rather than swallow: a failed write leaves the next cold
                // start showing the old language while DataStore disagrees.
                android.util.Log.w("LanguageManager", "failed to persist language cache", e)
            }
        }

        /**
         * Wraps [base] in a [Context] whose resources are configured for [language].
         * Returns [base] unchanged when [language] is [LANGUAGE_AUTO].
         */
        fun wrapContextForLocale(base: Context, language: String): Context {
            if (language == LANGUAGE_AUTO) return base

            val locale = when (language) {
                LANGUAGE_ZH -> java.util.Locale("zh", "CN")
                else -> java.util.Locale.ENGLISH
            }

            val config = Configuration(base.resources.configuration)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocale(locale)
                config.setLocales(android.os.LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }

            return base.createConfigurationContext(config)
        }
    }

    private fun getSystemLanguage(): String {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        return when {
            locale?.language == "zh" -> LANGUAGE_ZH
            else -> LANGUAGE_EN
        }
    }

    val language: Flow<String> = settingsRepository.language.map { prefLanguage ->
        when (prefLanguage) {
            LANGUAGE_AUTO -> getSystemLanguage()
            LANGUAGE_ZH, LANGUAGE_EN -> prefLanguage
            else -> getSystemLanguage()
        }
    }

    /**
     * Persists the selected language to both DataStore and the synchronous
     * cache file. The caller is responsible for triggering Activity recreation
     * so [attachBaseContext] picks up the new locale.
     */
    suspend fun setLanguage(language: String) {
        settingsRepository.setLanguage(language)
        persistLanguage(context, language)
    }

}
