package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.util.Log
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.service.ClashService
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.util.withProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DebugAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val store = ServiceStore(context)
                applySettings(store, intent)

                when (intent.action) {
                    ACTION_CONFIGURE -> Unit
                    ACTION_LOAD_PROFILE -> {
                        val url = intent.getStringExtra(EXTRA_URL)
                            ?: throw IllegalArgumentException("Missing profile URL")
                        val name = intent.getStringExtra(EXTRA_NAME) ?: "ADB test profile"
                        val uuid = withProfile {
                            create(Profile.Type.Url, name, url).also { id ->
                                patch(id, name, url, 0L, null)
                                commit(id)
                            }
                        }
                        withProfile {
                            val profile = queryByUUID(uuid)
                                ?: throw IllegalStateException("Imported profile was not found")
                            setActive(profile)
                        }
                        context.startForegroundServiceCompat(
                            Intent(context, ClashService::class.java)
                        )
                    }
                    else -> throw IllegalArgumentException("Unsupported debug action")
                }

                pending.setResultCode(Activity.RESULT_OK)
            } catch (e: Exception) {
                Log.e(TAG, "ADB automation failed (${e.javaClass.simpleName})")
                pending.setResultCode(Activity.RESULT_CANCELED)
            } finally {
                pending.finish()
            }
        }
    }

    private fun applySettings(store: ServiceStore, intent: Intent) {
        if (intent.hasExtra(EXTRA_ENABLED)) {
            store.secondHopEnabled = intent.getBooleanExtra(EXTRA_ENABLED, false)
        }
        if (intent.hasExtra(EXTRA_PACKAGES)) {
            store.secondHopPackages = intent.getStringExtra(EXTRA_PACKAGES)
                .orEmpty()
                .split(',', '\n', '\r')
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toSet()
        }
    }

    companion object {
        private const val TAG = "ClashMetaADB"
        private const val ACTION_CONFIGURE = "com.github.metacubex.clash.secondhop.DEBUG_CONFIGURE"
        private const val ACTION_LOAD_PROFILE = "com.github.metacubex.clash.secondhop.DEBUG_LOAD_PROFILE"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
    }
}
