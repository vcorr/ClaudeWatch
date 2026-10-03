package com.vcorr.claudewatch

import android.app.Activity
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.widget.TextView
import java.io.IOException
import java.net.Inet4Address

/**
 * Setting the API key from the phone: serves the PIN-protected key page ([KeySetupServer]) on the
 * local Wi-Fi, keeps the watch's Wi-Fi up for it, and shows the address and PIN on [screen].
 * [onKeySaved] runs on the main thread once a key is saved. Call from the main thread.
 */
class KeySetupFlow(
    private val activity: Activity,
    private val screen: TextView,
    private val onKeySaved: () -> Unit,
) {
    private var server: KeySetupServer? = null
    private var port = 0
    private var address: String? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    /** A line shown above the instructions, such as why setup opened; it outlasts a pause. */
    var notice: String? = null
        private set

    /** Starts serving the page, unless it already is. */
    fun start(notice: String?) {
        if (server != null) return
        val newServer = KeySetupServer(
            activity,
            onKeySaved = {
                activity.runOnUiThread {
                    this.notice = null
                    stop()
                    onKeySaved()
                }
            },
            onLockedOut = {
                activity.runOnUiThread {
                    stop()
                    screen.text = "Too many wrong PINs.\n\nClose and reopen ClaudeWatch for a new PIN."
                }
            },
        )
        port = try {
            newServer.start()
        } catch (e: IOException) {
            screen.text = "Couldn't start key setup: ${e.message}"
            return
        }
        server = newServer
        this.notice = notice
        requestWifi()
        render()
    }

    fun stop() {
        server?.stop()
        server = null
        address = null
        wifiCallback?.let { activity.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wifiCallback = null
    }

    private fun render() {
        val server = server ?: return
        val notice = notice?.let { "$it\n\n" }.orEmpty()
        val ip = address
        screen.text = if (ip == null) {
            "${notice}Set your API key\n\nWaiting for Wi-Fi…\nTurn on the watch's Wi-Fi, on the same network as your phone."
        } else {
            "${notice}Set your API key\n\nOn your phone, open\nhttp://$ip:$port\n\nPIN  ${server.pin}\n\nPhone and watch on the same Wi-Fi."
        }
    }

    /** Wear OS may keep Wi-Fi off while Bluetooth is connected; asking for it brings it up. */
    private fun requestWifi() {
        val cm = activity.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses
                    .map { it.address }
                    .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                    ?.hostAddress
                show(ip)
            }

            override fun onLost(network: Network) = show(null)

            // A change already on its way when setup stopped mustn't bring back a stale address.
            private fun show(ip: String?) = activity.runOnUiThread {
                if (wifiCallback !== this) return@runOnUiThread
                address = ip
                render()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        cm.requestNetwork(request, callback)
        wifiCallback = callback
    }
}
