package io.github.chsbuffer.miuihelper.model

import android.content.SharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.miuihelper.prefs.HookPreferences

abstract class Hook {
    companion object {
        val xPrefs: SharedPreferences
            get() = HookPreferences.preferences
    }

    protected lateinit var lpparam: LoadPackageParam
    protected lateinit var classLoader: ClassLoader

    fun init(lpparam: LoadPackageParam) {
        this.lpparam = lpparam
        this.classLoader = lpparam.classLoader

        init()
    }

    open fun init() {

    }
}
