package com.kafisir.wetalks.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Watches the phone's connection (Wi-Fi / mobile / roaming / metered / Android Data Saver) and keeps the latest answer in
 * Compose state, so a chat that is open redraws by itself when the person walks out of Wi-Fi range.
 * The decision rules themselves live in [MediaPolicy].
 */
object MediaNet {
    var state by mutableStateOf(MediaPolicy.NetState(MediaPolicy.Net.WIFI)); private set
    private var started = false
    private lateinit var app: Context

    fun start(c: Context) {
        if (started) return
        app = c.applicationContext; started = true
        refresh()
        try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { refresh() }
                override fun onLost(network: Network) { refresh() }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refresh() }
            })
        } catch (_: Exception) { /* no permission or no service: refresh() on demand still works */ }
    }

    /** Reads the connection now. Safe to call from any thread. */
    fun refresh(): MediaPolicy.NetState {
        val s = read()
        if (s != state) state = s
        return s
    }

    private fun read(): MediaPolicy.NetState = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val saver = cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        if (caps == null) MediaPolicy.NetState(MediaPolicy.Net.OFFLINE, false, saver)
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            MediaPolicy.NetState(MediaPolicy.Net.MOBILE, true, saver)          // a VPN that does not say what it runs over: assume the cautious case
        else MediaPolicy.classify(
            wifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            notRoaming = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            systemDataSaver = saver)
    } catch (_: Exception) { MediaPolicy.NetState(MediaPolicy.Net.MOBILE, true) }      // when unsure, behave as if the connection costs money

    /** Called before an explicit tap spends data: tells the person when they are roaming. */
    fun warnIfRoaming() { if (MediaPolicy.warnOnTap(refresh())) com.kafisir.wetalks.ui.Nav.say("You are roaming, downloading may cost extra") }
}
