package io.github.chsbuffer.miuihelper

import android.util.Log
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import io.github.chsbuffer.miuihelper.prefs.HookPreferences
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.concurrent.CopyOnWriteArraySet

/** API101 lifecycle and preference transport; the feature hooks retain API82 compatibility. */
class ModernXposedInit : XposedModule() {
    private lateinit var process: ModuleLoadedParam

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        process = param
    }

    override fun onPackageReady(param: PackageReadyParam) {
        try {
            // A freshly installed module must publish its settings before enabling feature defaults.
            if (!HookPreferences.attach(getRemotePreferences(HookPreferences.GROUP))) {
                log(Log.WARN, TAG, "Settings are not ready; open the module settings and restart ${param.packageName}")
                return
            }
            val legacy = newLoadPackageParam().apply {
                packageName = param.packageName
                processName = process.processName
                classLoader = param.classLoader
                appInfo = param.applicationInfo
                isFirstApplication = param.isFirstPackage
            }
            MainHook().handleLoadPackage(legacy)
        } catch (error: Throwable) {
            log(Log.ERROR, TAG, "Cannot initialize hooks for ${param.packageName}", error)
        }
    }

    private fun newLoadPackageParam(): LoadPackageParam {
        // API82's published stub omits the callback-set constructor used by current frameworks.
        for (constructor in LoadPackageParam::class.java.declaredConstructors) {
            constructor.isAccessible = true
            if (constructor.parameterCount == 0) {
                return constructor.newInstance() as LoadPackageParam
            }
            if (constructor.parameterCount == 1) {
                val parameter = constructor.parameterTypes[0]
                val callbacks = if (parameter.isAssignableFrom(CopyOnWriteArraySet::class.java)) {
                    CopyOnWriteArraySet<Any>()
                } else if (parameter.simpleName == "CopyOnWriteSortedSet") {
                    parameter.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                } else {
                    continue
                }
                return constructor.newInstance(callbacks) as LoadPackageParam
            }
        }
        throw IllegalStateException("Unsupported legacy LoadPackageParam constructor")
    }

    private companion object {
        const val TAG = "MIUI QOL"
    }
}
