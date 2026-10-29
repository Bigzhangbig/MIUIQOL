package io.github.chsbuffer.miuihelper

import android.app.Application
import io.github.chsbuffer.miuihelper.prefs.FrameworkPreferences

class QolApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FrameworkPreferences.initialize(this)
    }
}
