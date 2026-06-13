package com.friday.node.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class DiscoveryManager(
    private val context: Context,
    private val onHubAddressFound: (wsUrl: String) -> Unit
) {
    private val TAG = "FRIDAY_Discovery"
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_friday-hub._tcp"
    private var isDiscovering = false

    // Replace this string with your active ngrok URL prefix
    // NOTE: Convert 'https://' into the secure websocket identifier 'wss://'
    private val REMOTE_FALLBACK_URL = "wss://stubbly-impotent-escargot.ngrok-free.dev/ws/android"

    companion object {
        private var resolvedHubIp: String? = null

        fun getResolvedHubIp(): String? = resolvedHubIp
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("FRIDAY_NSD", "Discovery start failed: Error code $errorCode")
            synchronized(this@DiscoveryManager) {
                isDiscovering = false
            }
            try {
                nsdManager.stopServiceDiscovery(this)
            } catch (e: Exception) {}
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("FRIDAY_NSD", "Discovery stop failed: Error code $errorCode")
            synchronized(this@DiscoveryManager) {
                isDiscovering = false
            }
            try {
                nsdManager.stopServiceDiscovery(this)
            } catch (e: Exception) {}
        }

        override fun onDiscoveryStarted(regType: String) {
            Log.d("FRIDAY_NSD", "Service discovery started")
            synchronized(this@DiscoveryManager) {
                isDiscovering = true
            }
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d("FRIDAY_NSD", "Discovery stopped: $serviceType")
            synchronized(this@DiscoveryManager) {
                isDiscovering = false
            }
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.d("FRIDAY_NSD", "Service found: ${serviceInfo.serviceName}, type: ${serviceInfo.serviceType}")
            if (serviceInfo.serviceType.contains("_friday-hub")) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.e("FRIDAY_NSD", "Resolve failed: Error code $errorCode")
                    }

                    override fun onServiceResolved(resolvedServiceInfo: NsdServiceInfo) {
                        Log.d("FRIDAY_NSD", "Resolve Succeeded. IP: ${resolvedServiceInfo.host.hostAddress}")
                        val ip = resolvedServiceInfo.host.hostAddress ?: return
                        val port = resolvedServiceInfo.port
                        resolvedHubIp = ip
                        onHubAddressFound("ws://$ip:$port/ws/android")
                    }
                })
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.e("FRIDAY_NSD", "Service lost: ${serviceInfo.serviceName}")
            if (serviceInfo.host?.hostAddress == resolvedHubIp) {
                resolvedHubIp = null
            }
        }
    }

    fun startSearching() {
        if (isUsingCellularOrRemote()) {
            Log.i(TAG, "Cross-Network routing detected. Routing traffic through public edge relay.")
            onHubAddressFound(REMOTE_FALLBACK_URL)
            return
        }

        Log.i(TAG, "Local Wi-Fi detected. Initializing standard mDNS local autodetection.")
        synchronized(this) {
            if (!isDiscovering) {
                isDiscovering = true
                try {
                    nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                } catch (e: Exception) {
                    Log.e("FRIDAY_NSD", "Failed to start service discovery: ${e.message}")
                    isDiscovering = false
                }
            }
        }
    }

    fun stopSearching() {
        synchronized(this) {
            if (isDiscovering) {
                try {
                    nsdManager.stopServiceDiscovery(discoveryListener)
                } catch (e: Exception) {
                    Log.e("FRIDAY_NSD", "Failed to stop service discovery: ${e.message}")
                } finally {
                    isDiscovering = false
                }
            }
        }
    }

    private fun isUsingCellularOrRemote(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return true
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return true
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
