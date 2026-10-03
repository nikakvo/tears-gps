package io.github.jqssun.gpssetter.xposed

import android.app.Application
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Modern Xposed API (libxposed 102) entry point, declared in META-INF/xposed/java_init.list.
 *
 * - system_server ("System Framework" scope): hooks LocationManagerService.
 * - Any other scoped app: hooks android.location.Location / LocationManager in that process.
 * - com.google.android.gms.unstable (DroidGuard / Play Integrity) is never hooked, even when
 *   Google Play services is ticked in the scope.
 *
 * The module app itself is never hooked under the modern API; the UI detects activation
 * through the libxposed service instead (see ModuleBridge).
 */
class HookEntry : XposedModule() {

    private var inSystemServer = false
    private var appHooksInstalled = false

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        inSystemServer = true
        try {
            LocationHook.hookSystemServer(this, param.classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, LocationHook.TAG, "system_server hooks failed", t)
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // Other packages loaded into system_server must not get the app-level hooks.
        if (inSystemServer) return
        if (param.packageName in LocationHook.ignorePkg) return
        // Never touch DroidGuard / Play Integrity.
        if (currentProcessName(param.packageName) == GMS_UNSTABLE) return
        // Location/LocationManager are framework classes: hook them once per process,
        // even if several packages share it.
        if (appHooksInstalled) return
        appHooksInstalled = true
        try {
            LocationHook.hookApp(this, param.packageName)
        } catch (t: Throwable) {
            log(Log.ERROR, LocationHook.TAG, "app hooks failed in ${param.packageName}", t)
        }
    }

    private fun currentProcessName(fallback: String): String =
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() ?: fallback else fallback

    private companion object {
        const val GMS_UNSTABLE = "com.google.android.gms.unstable"
    }
}
