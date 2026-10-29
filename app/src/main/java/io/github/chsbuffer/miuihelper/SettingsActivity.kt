@file:Suppress("DEPRECATION")

package io.github.chsbuffer.miuihelper

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.preference.Preference.OnPreferenceChangeListener
import android.preference.Preference.OnPreferenceClickListener
import android.preference.PreferenceFragment
import android.preference.SwitchPreference
import io.github.chsbuffer.miuihelper.prefs.FrameworkPreferences
import io.github.chsbuffer.miuihelper.prefs.HookPreferences


class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fragmentManager.beginTransaction().replace(android.R.id.content, SettingsFragment())
            .commit()
    }

    class SettingsFragment : PreferenceFragment(), OnPreferenceChangeListener,
        OnPreferenceClickListener {

        private val statusListener: (FrameworkPreferences.Status) -> Unit = { status ->
            val summary = when (status) {
                FrameworkPreferences.Status.DISCONNECTED -> R.string.framework_disconnected
                FrameworkPreferences.Status.SYNCING -> R.string.framework_syncing
                FrameworkPreferences.Status.SYNCED -> R.string.framework_synced
                FrameworkPreferences.Status.SYNC_FAILED -> R.string.framework_sync_failed
                FrameworkPreferences.Status.LOCAL_ERROR -> R.string.framework_local_error
            }
            findPreference("framework_status")?.setSummary(summary)
        }

        @Deprecated("Deprecated in Java")
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            preferenceManager.sharedPreferencesMode = MODE_PRIVATE
            preferenceManager.sharedPreferencesName = HookPreferences.GROUP
            addPreferencesFromResource(R.xml.header_preferences)
            addPreferencesFromResource(R.xml.root_preferences)

            findPreference("framework_status").onPreferenceClickListener = this
            findPreference("behavior_record_enhance").onPreferenceChangeListener = this
            findPreference("kill_system_ui").onPreferenceClickListener = this
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                removePreference("original_default_open_setting")
            }
            if (miui.os.Build.IS_INTERNATIONAL_BUILD) {
                removePreference("restore_google_icon")
                removePreference("notification_settings_no_white_list")
                removePreference("restore_wallet_tile")
            }
        }

        override fun onResume() {
            super.onResume()
            FrameworkPreferences.addListener(statusListener)
            FrameworkPreferences.refresh()
        }

        override fun onPause() {
            FrameworkPreferences.removeListener(statusListener)
            super.onPause()
        }

        private fun removePreference(key: String) {
            val pref = findPreference(key)
            val category = pref.parent!!
            category.removePreference(pref)

            if (category.preferenceCount == 0) {
                category.parent!!.removePreference(category)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean =
            when (preference.key) {
                "behavior_record_enhance" -> {
                    (findPreference("behavior_record_system_app_whitelist") as SwitchPreference).isChecked =
                        newValue as Boolean
                    true
                }
                else -> true
            }

        @Deprecated("Deprecated in Java")
        override fun onPreferenceClick(preference: Preference) = when (preference.key) {
            "framework_status" -> {
                FrameworkPreferences.refresh()
                true
            }
            "kill_system_ui" -> {
                AlertDialog.Builder(context).setMessage(R.string.kill_system_ui_confirm)
                    .setPositiveButton(R.string.confirm) { _, _ ->
                        Runtime.getRuntime().exec("su -c killall com.android.systemui")
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> }
                    .show()
                true
            }
            else -> false
        }
    }
}
