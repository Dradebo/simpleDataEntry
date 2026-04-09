package com.ash.simpledataentry.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.*
import javax.inject.Inject
import javax.inject.Singleton

data class NetworkState(
    val isConnected: Boolean = false,
    val hasInternet: Boolean = false,
    val isMetered: Boolean = false,
    val networkType: NetworkType = NetworkType.NONE,
    val connectionQuality: ConnectionQuality = ConnectionQuality.UNKNOWN,
    val estimatedBandwidthKbps: Long? = null,
    val latencyMs: Long? = null,
    val isStable: Boolean = false,
    val signalStrength: Int? = null // 0-100 percentage
)

enum class NetworkType {
    NONE, WIFI, CELLULAR, ETHERNET, BLUETOOTH, VPN
}

enum class ConnectionQuality {
    EXCELLENT, // > 10 Mbps, < 50ms latency
    GOOD,      // > 1 Mbps, < 200ms latency
    FAIR,      // > 256 Kbps, < 500ms latency
    POOR,      // < 256 Kbps or > 500ms latency
    UNKNOWN
}

@Singleton
class NetworkStateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val connectivityManager = ContextCompat.getSystemService(context, ConnectivityManager::class.java)
    private val telephonyManager = ContextCompat.getSystemService(context, TelephonyManager::class.java)
    private val measurementScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _networkState = MutableStateFlow(getCurrentNetworkState())
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    // Store recent measurements for stability assessment
    private val recentLatencies = mutableListOf<Long>()
    private val recentBandwidths = mutableListOf<Long>()
    private var lastQualityMeasurement = 0L
    
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _networkState.value = getCurrentNetworkState()
        }
        
        override fun onLost(network: Network) {
            _networkState.value = getCurrentNetworkState()
        }
        
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _networkState.value = getCurrentNetworkState()
        }
    }
    
    init {
        registerNetworkCallback()
    }
    
    private fun registerNetworkCallback() {
        try {
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            
            connectivityManager?.registerNetworkCallback(networkRequest, networkCallback)
        } catch (e: Exception) {
            // Handle registration failure gracefully
        }
    }
    
    private fun getCurrentNetworkState(): NetworkState {
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it) }
        
        val isConnected = activeNetwork != null && capabilities != null
        val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                         capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val isMetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false
        
        val networkType = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> NetworkType.WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> NetworkType.CELLULAR
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> NetworkType.ETHERNET
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> NetworkType.BLUETOOTH
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> NetworkType.VPN
            else -> NetworkType.NONE
        }
        
        // Get enhanced network state with quality metrics
        val (quality, bandwidth, latency, signalStrength, isStable) = getNetworkQualityMetrics(capabilities, networkType)

        return NetworkState(
            isConnected = isConnected,
            hasInternet = hasInternet,
            isMetered = isMetered,
            networkType = networkType,
            connectionQuality = quality,
            estimatedBandwidthKbps = bandwidth,
            latencyMs = latency,
            isStable = isStable,
            signalStrength = signalStrength
        )
    }
    
    fun isOnline(): Boolean = networkState.value.hasInternet
    
    fun isWifiConnected(): Boolean = networkState.value.networkType == NetworkType.WIFI
    
    fun unregisterCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Handle unregister failure gracefully
        }
    }

    /**
     * Get comprehensive network quality metrics
     */
    private fun getNetworkQualityMetrics(
        capabilities: NetworkCapabilities?,
        networkType: NetworkType
    ): QualityMetrics {
        if (capabilities == null) {
            return QualityMetrics(ConnectionQuality.UNKNOWN, null, null, null, false)
        }

        // Use cached measurements if recent (within 30 seconds)
        val now = System.currentTimeMillis()
        if (now - lastQualityMeasurement < 30_000 && recentLatencies.isNotEmpty()) {
            val avgLatency = recentLatencies.average().toLong()
            val avgBandwidth = recentBandwidths.average().toLong()
            val signalStrength = getSignalStrength(networkType)
            val isStable = assessConnectionStability()
            val quality = calculateConnectionQuality(avgBandwidth, avgLatency)

            return QualityMetrics(quality, avgBandwidth, avgLatency, signalStrength, isStable)
        }

        // Get signal strength immediately (doesn't require network calls)
        val signalStrength = getSignalStrength(networkType)

        // Return basic quality assessment based on network type and signal strength
        val basicQuality = getBasicQualityEstimate(networkType, signalStrength)

        // Trigger async quality measurement for future use
        triggerAsyncQualityMeasurement()

        return QualityMetrics(basicQuality, null, null, signalStrength, false)
    }

    /**
     * Trigger asynchronous quality measurement
     */
    private fun triggerAsyncQualityMeasurement() {
        measurementScope.launch {
            try {
                val latency = measureLatency()
                val bandwidth = estimateBandwidth()

                // Update cached measurements
                synchronized(recentLatencies) {
                    recentLatencies.add(latency)
                    if (recentLatencies.size > 5) recentLatencies.removeAt(0)
                }

                synchronized(recentBandwidths) {
                    recentBandwidths.add(bandwidth)
                    if (recentBandwidths.size > 5) recentBandwidths.removeAt(0)
                }

                lastQualityMeasurement = System.currentTimeMillis()

                // Update state with new measurements
                _networkState.value = getCurrentNetworkState()
            } catch (e: Exception) {
                // Measurement failed, continue with cached or estimated values
            }
        }
    }

    /**
     * Measure network latency using ping-like approach
     */
    private suspend fun measureLatency(): Long = withContext(Dispatchers.IO) {
        try {
            val startTime = System.currentTimeMillis()
            val socket = Socket()

            withTimeout(5000) {
                socket.connect(InetSocketAddress("8.8.8.8", 53), 3000)
                socket.close()
            }

            System.currentTimeMillis() - startTime
        } catch (e: Exception) {
            1000L // Default to 1 second if measurement fails
        }
    }

    /**
     * Estimate bandwidth using small data transfer
     */
    private suspend fun estimateBandwidth(): Long = withContext(Dispatchers.IO) {
        try {
            val testUrl = URL("https://httpbin.org/bytes/1024") // 1KB test
            val startTime = System.currentTimeMillis()

            withTimeout(10000) {
                val connection = testUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val inputStream = connection.inputStream
                val buffer = ByteArray(1024)
                var totalBytes = 0

                while (inputStream.read(buffer) != -1) {
                    totalBytes += buffer.size
                }

                inputStream.close()
                connection.disconnect()

                val duration = System.currentTimeMillis() - startTime
                if (duration > 0) {
                    // Convert to Kbps: (bytes * 8) / (duration_ms / 1000) / 1000
                    (totalBytes * 8L) / duration
                } else {
                    1000L // Default to 1 Mbps
                }
            }
        } catch (e: Exception) {
            1000L // Default to 1 Mbps if measurement fails
        }
    }

    /**
     * Get signal strength based on network type
     */
    private fun getSignalStrength(networkType: NetworkType): Int? {
        return when (networkType) {
            NetworkType.WIFI -> {
                // For WiFi, signal strength can be estimated but requires location permission
                // For now, return null and implement later if needed
                null
            }
            NetworkType.CELLULAR -> {
                try {
                    // This requires READ_PHONE_STATE permission
                    // For now, return null and implement later if needed
                    null
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
    }

    /**
     * Get basic quality estimate based on network type and signal strength
     */
    private fun getBasicQualityEstimate(networkType: NetworkType, signalStrength: Int?): ConnectionQuality {
        return when (networkType) {
            NetworkType.WIFI -> ConnectionQuality.GOOD
            NetworkType.ETHERNET -> ConnectionQuality.EXCELLENT
            NetworkType.CELLULAR -> {
                when (signalStrength) {
                    in 75..100 -> ConnectionQuality.GOOD
                    in 50..74 -> ConnectionQuality.FAIR
                    in 25..49 -> ConnectionQuality.POOR
                    else -> ConnectionQuality.FAIR
                }
            }
            NetworkType.VPN -> ConnectionQuality.FAIR
            NetworkType.BLUETOOTH -> ConnectionQuality.POOR
            NetworkType.NONE -> ConnectionQuality.UNKNOWN
        }
    }

    /**
     * Calculate connection quality based on bandwidth and latency
     */
    private fun calculateConnectionQuality(bandwidthKbps: Long, latencyMs: Long): ConnectionQuality {
        return when {
            bandwidthKbps > 10_000 && latencyMs < 50 -> ConnectionQuality.EXCELLENT
            bandwidthKbps > 1_000 && latencyMs < 200 -> ConnectionQuality.GOOD
            bandwidthKbps > 256 && latencyMs < 500 -> ConnectionQuality.FAIR
            else -> ConnectionQuality.POOR
        }
    }

    /**
     * Assess connection stability based on recent measurements
     */
    private fun assessConnectionStability(): Boolean {
        if (recentLatencies.size < 3) return false

        val latencyVariance = recentLatencies.map { it.toDouble() }.let { latencies ->
            val mean = latencies.average()
            latencies.map { (it - mean) * (it - mean) }.average()
        }

        // Consider stable if latency variance is low (< 100ms variance)
        return latencyVariance < 10000 // 100ms squared
    }

    /**
     * Force a fresh quality measurement
     */
    suspend fun measureNetworkQuality(): NetworkState {
        triggerAsyncQualityMeasurement()
        delay(2000) // Wait for measurement to complete
        return networkState.value
    }

    fun cleanup() {
        measurementScope.cancel()
    }
}

/**
 * Data class to hold quality measurement results
 */
private data class QualityMetrics(
    val quality: ConnectionQuality,
    val bandwidthKbps: Long?,
    val latencyMs: Long?,
    val signalStrength: Int?,
    val isStable: Boolean
)