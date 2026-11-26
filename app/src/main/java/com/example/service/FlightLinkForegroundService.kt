package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

/**
 * Keeps drone heartbeat and UDP telemetry sockets alive across brief screen transitions
 * using a properly typed connectedDevice foreground service on Android 8.0+ (API 26-36).
 */
class FlightLinkForegroundService : Service() {

  companion object {
    private const val CHANNEL_ID = "autopilot_flight_link"
    private const val NOTIFICATION_ID = 4101
    private const val EXTRA_LABEL = "extra_protocol_label"

    fun start(context: Context, protocolLabel: String) {
      val intent =
        Intent(context, FlightLinkForegroundService::class.java).putExtra(EXTRA_LABEL, protocolLabel)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, FlightLinkForegroundService::class.java))
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    ensureChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val label = intent?.getStringExtra(EXTRA_LABEL) ?: "Drone Link"
    val notification = buildNotification(label)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
    return START_NOT_STICKY
  }

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val mgr = getSystemService(NotificationManager::class.java)
      val channel =
        NotificationChannel(
          CHANNEL_ID,
          "Active Drone Flight Link",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Maintains native UDP/RTSP link while flight console is active."
        }
      mgr.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(protocolLabel: String): Notification {
    val openIntent =
      PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )
    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("AUTOPILOT Flight Link Active")
      .setContentText("Protocol: $protocolLabel")
      .setOngoing(true)
      .setContentIntent(openIntent)
      .build()
  }
}
