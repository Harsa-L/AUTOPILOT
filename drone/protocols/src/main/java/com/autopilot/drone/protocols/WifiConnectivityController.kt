package com.autopilot.drone.protocols

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.provider.Settings
import com.autopilot.core.common.AutopilotLog
import com.autopilot.core.common.SubsystemTag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class WifiLinkSnapshot(
  val isWifiConnected: Boolean,
  val ssid: String?,
  val rssiDbm: Int?,
  val linkSpeedMbps: Int?,
  val boundNetworkHandle: Long?,
  val apiStrategy: String,
  val statusMessage: String,
)

/**
 * Android 8.0+ (API 26-36) Wi-Fi connectivity abstraction.
 * Uses WifiNetworkSpecifier on API 29+ to route UDP/RTSP traffic directly to drone APs
 * without internet capability, and falls back cleanly to active Wi-Fi Network inspection on API 26-28.
 */
class WifiConnectivityController(private val context: Context) {

  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
  private val wifiManager =
    context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

  private var activeCallback: ConnectivityManager.NetworkCallback? = null

  private val _linkState =
    MutableStateFlow(
      WifiLinkSnapshot(
        isWifiConnected = false,
        ssid = null,
        rssiDbm = null,
        linkSpeedMbps = null,
        boundNetworkHandle = null,
        apiStrategy =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "API ${Build.VERSION.SDK_INT}: WifiNetworkSpecifier"
          } else {
            "API ${Build.VERSION.SDK_INT}: Legacy WifiManager + ConnectivityManager"
          },
        statusMessage = "Idle — inspect active Wi-Fi or bind a drone AP.",
      )
    )
  val linkState: StateFlow<WifiLinkSnapshot> = _linkState.asStateFlow()

  @Suppress("DEPRECATION")
  @SuppressLint("MissingPermission")
  fun refreshCurrentWifiState(): WifiLinkSnapshot {
    val activeWifiNetwork = findActiveWifiNetwork()
    val info = runCatching { wifiManager.connectionInfo }.getOrNull()
    val rawSsid = info?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    val rssi = info?.rssi?.takeIf { it in -120..-1 }
    val speed = info?.linkSpeed?.takeIf { it > 0 }
    val snapshot =
      WifiLinkSnapshot(
        isWifiConnected = activeWifiNetwork != null,
        ssid = rawSsid,
        rssiDbm = rssi,
        linkSpeedMbps = speed,
        boundNetworkHandle = activeWifiNetwork?.networkHandle,
        apiStrategy =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "API ${Build.VERSION.SDK_INT}: WifiNetworkSpecifier + Network.networkHandle"
          } else {
            "API ${Build.VERSION.SDK_INT}: Active Wi-Fi NetworkHandle"
          },
        statusMessage =
          if (activeWifiNetwork != null) {
            "Wi-Fi interface active${rawSsid?.let { " ($it)" } ?: ""}"
          } else {
            "No active Wi-Fi link detected. Connect to drone AP or use Isolated Test Harness."
          },
      )
    _linkState.value = snapshot
    return snapshot
  }

  fun requestDroneApBinding(ssidPrefix: String, onResult: (Long?) -> Unit) {
    requestDroneApBinding(listOf(ssidPrefix), preferredSsid = null, onResult = onResult)
  }

  fun requestDroneApBinding(
    ssidPrefixes: List<String>,
    preferredSsid: String? = null,
    onResult: (Long?) -> Unit,
  ) {
    val cleanPrefixes = ssidPrefixes.map { it.trim() }.filter { it.isNotEmpty() }
    val current = refreshCurrentWifiState()
    val currentSsid = current.ssid
    val alreadyMatchingCurrent =
      current.boundNetworkHandle != null &&
        currentSsid != null &&
        ((preferredSsid != null && currentSsid.equals(preferredSsid, ignoreCase = true)) ||
          cleanPrefixes.any { prefix -> currentSsid.startsWith(prefix, ignoreCase = true) })

    if (alreadyMatchingCurrent) {
      findActiveWifiNetwork()?.let { net ->
        runCatching { connectivityManager.bindProcessToNetwork(net) }
      }
      _linkState.value =
        _linkState.value.copy(
          statusMessage = "Using already-connected drone AP ($currentSsid)"
        )
      AutopilotLog.info(
        SubsystemTag.CONNECTIVITY,
        "Bound active Wi-Fi handle=${current.boundNetworkHandle} ($currentSsid)",
      )
      onResult(current.boundNetworkHandle)
      return
    }

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      AutopilotLog.info(
        SubsystemTag.CONNECTIVITY,
        "API < 29: using active Wi-Fi handle=${current.boundNetworkHandle}",
      )
      onResult(current.boundNetworkHandle)
      return
    }

    val targetPattern =
      preferredSsid?.takeIf { it.isNotBlank() }
        ?: cleanPrefixes.firstOrNull()
        ?: ""
    if (targetPattern.isEmpty()) {
      onResult(current.boundNetworkHandle)
      return
    }

    releaseBinding()
    val specifierBuilder = WifiNetworkSpecifier.Builder()
    if (preferredSsid != null && preferredSsid.isNotBlank()) {
      specifierBuilder.setSsid(preferredSsid)
    } else {
      specifierBuilder.setSsidPattern(PatternMatcher(targetPattern, PatternMatcher.PATTERN_PREFIX))
    }

    val request =
      NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .setNetworkSpecifier(specifierBuilder.build())
        .build()

    val displayHint = preferredSsid ?: "$targetPattern*"
    val callback =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          val handle = network.networkHandle
          connectivityManager.bindProcessToNetwork(network)
          _linkState.value =
            _linkState.value.copy(
              isWifiConnected = true,
              boundNetworkHandle = handle,
              statusMessage = "Bound process & native sockets to drone AP ($displayHint)",
            )
          AutopilotLog.info(SubsystemTag.CONNECTIVITY, "Bound network handle=$handle ($displayHint)")
          onResult(handle)
        }

        override fun onUnavailable() {
          _linkState.value =
            _linkState.value.copy(
              isWifiConnected = false,
              boundNetworkHandle = null,
              statusMessage = "Drone AP ($displayHint) unavailable or request declined.",
            )
          AutopilotLog.warn(SubsystemTag.CONNECTIVITY, "WifiNetworkSpecifier unavailable for $displayHint")
          onResult(null)
        }

        override fun onLost(network: Network) {
          connectivityManager.bindProcessToNetwork(null)
          _linkState.value =
            _linkState.value.copy(
              isWifiConnected = false,
              boundNetworkHandle = null,
              statusMessage = "Drone Wi-Fi AP link lost.",
            )
          AutopilotLog.warn(SubsystemTag.CONNECTIVITY, "Drone Wi-Fi network lost.")
        }
      }

    activeCallback = callback
    connectivityManager.requestNetwork(request, callback)
  }

  fun releaseBinding() {
    activeCallback?.let { cb ->
      runCatching { connectivityManager.unregisterNetworkCallback(cb) }
    }
    activeCallback = null
    runCatching { connectivityManager.bindProcessToNetwork(null) }
  }

  fun createOpenWifiSettingsIntent(): Intent =
    Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

  @Suppress("DEPRECATION")
  private fun findActiveWifiNetwork(): Network? {
    val active = connectivityManager.activeNetwork
    val activeCaps = active?.let { connectivityManager.getNetworkCapabilities(it) }
    if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
      return active
    }
    return connectivityManager.allNetworks.firstOrNull { net ->
      connectivityManager.getNetworkCapabilities(net)
        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
  }
}
