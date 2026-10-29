package com.github.kr328.clash.service

import android.content.Context
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.store.ServiceStore
import org.json.JSONArray
import org.json.JSONObject

internal fun Context.applySecondHopConfig() {
    val store = ServiceStore(this)
    val settings = JSONObject()
        .put("enabled", store.secondHopEnabled)
        .put("server", BuildConfig.SECOND_HOP_SERVER)
        .put("port", BuildConfig.SECOND_HOP_PORT)
        .put("username", BuildConfig.SECOND_HOP_USERNAME)
        .put("password", BuildConfig.SECOND_HOP_PASSWORD)
        .put("apps", JSONArray(store.secondHopPackages.sorted()))

    Clash.setSecondHopConfig(settings.toString())
}
