package com.myfactory.forge

import android.app.Application
import com.myfactory.forge.di.AppContainer

class ForgeApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Apply the saved tier override before any screen reads capabilities.
        container.applyTierOverride(container.settings.current.tierOverride)
    }
}
