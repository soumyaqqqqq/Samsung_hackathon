package com.friday.node.utils

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class DiscoveryManager(
    context: Context,
    private val onHubFound: (ipAddress: String, port: Int) -> Unit
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val SERVICE_TYPE = "_friday-hub._tcp"
    private var isDiscovering = false

    companion object {
        private var resolvedHubIp: String? = null

        fun getResolvedHubIp(): String? = resolvedHubIp
    }

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("FRIDAY_NSD", "Discovery start failed: Error code $errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e("FRIDAY_NSD", "Discovery stop failed: Error code $errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onDiscoveryStarted(regType: String) {
            Log.d("FRIDAY_NSD", "Service discovery started")
            isDiscovering = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.d("FRIDAY_NSD", "Discovery stopped: $serviceType")
            isDiscovering = false
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
                        onHubFound(ip, port)
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
        if (!isDiscovering) {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }
    }

    fun stopSearching() {
        if (isDiscovering) {
            nsdManager.stopServiceDiscovery(discoveryListener)
        }
    }
}
