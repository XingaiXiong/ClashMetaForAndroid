package com.github.kr328.clash

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.util.Log
import com.github.kr328.clash.common.compat.startForegroundServiceCompat
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.remote.Remote
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
            var stage = "initialize"
            try {
                val store = ServiceStore(context)

                when (intent.action) {
                    ACTION_CONFIGURE -> {
                        stage = "configure settings"
                        applySettings(store, intent)
                        context.sendBroadcast(
                            Intent(Intents.ACTION_OVERRIDE_CHANGED).setPackage(context.packageName)
                        )
                    }
                    ACTION_LOAD_PROFILE -> {
                        // Remote is normally bound by the visible UI. Bind explicitly so ADB
                        // automation also works when no Activity has been opened.
                        stage = "bind profile service"
                        Remote.service.bind()
                        try {
                            stage = "import profile"
                            applySettings(store, intent)
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
                        } finally {
                            Remote.service.unbind()
                        }
                        stage = "start clash service"
                        context.startForegroundServiceCompat(
                            Intent(context, ClashService::class.java)
                        )
                    }
                    else -> throw IllegalArgumentException("Unsupported debug action")
                }

                pending.setResultCode(Activity.RESULT_OK)
            } catch (e: Exception) {
                Log.e(TAG, "ADB automation failed during $stage (${e.javaClass.simpleName}): ${e.message.orEmpty().take(160)}")
                pending.setResultCode(Activity.RESULT_CANCELED)
            } finally {
                pending.finish()
            }
        }
    }

    private fun applySettings(store: ServiceStore, intent: Intent) {
        val server = if (intent.hasExtra(EXTRA_SERVER)) {
            intent.getStringExtra(EXTRA_SERVER).orEmpty().trim()
        } else {
            store.secondHopServer
        }
        val port = if (intent.hasExtra(EXTRA_PORT)) {
            intent.getIntExtra(EXTRA_PORT, 0)
        } else {
            store.secondHopPort
        }
        val username = if (intent.hasExtra(EXTRA_USERNAME)) {
            intent.getStringExtra(EXTRA_USERNAME).orEmpty()
        } else {
            store.secondHopUsername
        }
        val password = if (intent.hasExtra(EXTRA_PASSWORD)) {
            intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
        } else {
            store.secondHopPassword
        }
        val enabled = intent.getBooleanExtra(EXTRA_ENABLED, store.secondHopEnabled)
        require(port in 0..65535) { "Second-hop port must be between 1 and 65535" }
        if (enabled) {
            require(server.isNotBlank() && port in 1..65535) { "Second-hop server and port are required" }
            require(username.isNotEmpty() && password.isNotEmpty()) { "Second-hop credentials are required" }
        }

        store.secondHopServer = server
        store.secondHopPort = port
        store.secondHopUsername = username
        store.secondHopPassword = password
        store.secondHopEnabled = enabled

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
        private val ACTION_CONFIGURE = "${BuildConfig.APPLICATION_ID}.DEBUG_CONFIGURE"
        private val ACTION_LOAD_PROFILE = "${BuildConfig.APPLICATION_ID}.DEBUG_LOAD_PROFILE"
        private const val EXTRA_ENABLED = "enabled"
        private const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_USERNAME = "username"
        private const val EXTRA_PASSWORD = "password"
        private const val EXTRA_URL = "url"
        private const val EXTRA_NAME = "name"
    }
}
