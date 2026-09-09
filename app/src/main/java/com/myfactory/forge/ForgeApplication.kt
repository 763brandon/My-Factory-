package com.myfactory.forge

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.myfactory.forge.di.AppContainer

class ForgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Apply the saved tier override before any screen reads capabilities.
        container.applyTierOverride(container.settings.current.tierOverride)

        // Re-apply the chosen language at process start. Doing it here rather
        // than relying on appcompat's optional auto-store service keeps the
        // single source of truth in our own settings.
        container.settings.current.languageTag?.let { tag ->
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }
}
