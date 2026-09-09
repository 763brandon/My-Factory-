package com.myfactory.forge.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.myfactory.forge.MainActivity
import com.myfactory.forge.R

/**
 * Keeps a long agent turn or a build alive when the screen goes off.
 *
 * Without this, Android freezes the process partway through a multi-minute
 * turn and the user comes back to a half-finished conversation. The
 * notification is not optional and not dismissible while work is in flight,
 * which is the correct trade: the user can always see that the app is doing
 * something on their behalf.
 */
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.getStringExtra(EXTRA_MESSAGE)
            ?: getString(R.string.chat_thinking)
        startForeground(NOTIFICATION_ID, buildNotification(message))
        return START_NOT_STICKY
    }

    private fun buildNotification(message: String): Notification {
        ensureChannel()
        val contentIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or
                android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                // LOW: visible in the shade, but never makes a sound while
                // someone is working.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        private const val CHANNEL_ID = "forge_agent"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_MESSAGE = "message"

        fun start(context: Context, message: String) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .putExtra(EXTRA_MESSAGE, message)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching {
                context.stopService(Intent(context, AgentForegroundService::class.java))
            }
        }
    }
}
