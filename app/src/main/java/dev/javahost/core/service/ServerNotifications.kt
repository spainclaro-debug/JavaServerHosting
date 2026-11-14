package dev.javahost.core.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.javahost.R
import dev.javahost.core.model.RunState
import dev.javahost.core.model.ServerRuntimeStatus
import java.util.Locale

/**
 * Builds every notification JavaHost posts, and owns the channel setup.
 *
 * Channel design: the ongoing server status lives on a LOW importance channel
 * (visible, updatable, never noisy); alerts and download progress get their own
 * channels so users can mute one without muting the other.
 *
 * All notify/cancel calls are defensive: a device that denies
 * POST_NOTIFICATIONS (Android 13+) or an OEM NotificationManager that throws
 * must never crash a foreground service that is hosting a server.
 */
class ServerNotifications(context: Context) {

    private val appContext: Context = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    /** Creates the channels; idempotent, cheap, safe to call on every onCreate. */
    fun ensureChannels() {
        val system = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val statusChannel = NotificationChannel(
            CHANNEL_STATUS,
            appContext.getString(R.string.notification_channel_server),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.notification_channel_server_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val alertsChannel = NotificationChannel(
            CHANNEL_ALERTS,
            appContext.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = appContext.getString(R.string.notification_channel_alerts_desc)
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val downloadsChannel = NotificationChannel(
            CHANNEL_DOWNLOADS,
            appContext.getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = appContext.getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        runCatching {
            system.createNotificationChannels(listOf(statusChannel, alertsChannel, downloadsChannel))
        }.onFailure { Log.w(TAG, "Could not create notification channels", it) }
    }

    /** True when notifications can actually be shown to the user. */
    fun areNotificationsEnabled(): Boolean =
        runCatching { notificationManager.areNotificationsEnabled() }.getOrDefault(false)

    /* ------------------------------------------------------------------ */
    /* Builders                                                           */
    /* ------------------------------------------------------------------ */

    /** Ongoing status notification: name, state, uptime, players, heap, free RAM. */
    fun buildStatus(
        status: ServerRuntimeStatus,
        instanceName: String?,
        isMinecraft: Boolean,
        fallbackDeviceAvailMb: Long,
        fallbackDeviceTotalMb: Long,
        contentIntent: PendingIntent?,
        stopIntent: PendingIntent?,
        restartIntent: PendingIntent?,
        forceStopIntent: PendingIntent? = null
    ): Notification {
        val title = instanceName?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.app_name)
        val lines = ArrayList<String>(4)
        lines.add(stateLabel(status.state))
        if (status.state == RunState.STARTING || status.state == RunState.RUNNING ||
            status.state == RunState.STOPPING
        ) {
            lines.add("up ${formatUptime(status.uptimeMs)}")
        }
        if (isMinecraft) {
            if (status.playersOnline > 0 || status.playersMax > 0) {
                lines.add("${status.playersOnline}/${status.playersMax} players")
            }
        } else {
            status.serverPort?.let { lines.add("port $it") }
            status.connectionsOnline?.let { lines.add("$it connections") }
        }
        val heapUsed = status.heapUsedMb
        val heapMax = status.heapMaxMb
        when {
            heapUsed != null && heapMax != null && heapMax > 0 ->
                lines.add("heap ${heapUsed}/${heapMax} MB")
            heapUsed != null -> lines.add("heap ${heapUsed} MB")
        }
        val availMb = status.deviceAvailMb.takeIf { it > 0 } ?: fallbackDeviceAvailMb
        val totalMb = status.deviceTotalMb.takeIf { it > 0 } ?: fallbackDeviceTotalMb
        when {
            availMb > 0 && totalMb > 0 -> lines.add("${formatRamMb(availMb)} free of ${formatRamMb(totalMb)}")
            availMb > 0 -> lines.add("${formatRamMb(availMb)} free")
        }
        val text = lines.joinToString(" · ")

        return baseStatusBuilder(title, text, contentIntent, stopIntent, restartIntent, forceStopIntent)
            .build()
    }

    /** First notification posted before the controller starts (5-second rule). */
    fun buildStarting(
        instanceName: String?,
        contentIntent: PendingIntent?,
        stopIntent: PendingIntent?
    ): Notification {
        val title = instanceName?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.app_name)
        return baseStatusBuilder(
            title = title,
            text = "Starting server…",
            contentIntent = contentIntent,
            stopIntent = stopIntent,
            restartIntent = null
        ).build()
    }

    /** Crash / unexpected-exit / rejected-start alert with an optional Restart action. */
    fun buildAlert(
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        restartIntent: PendingIntent?
    ): Notification {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (restartIntent != null) {
            builder.addAction(0, appContext.getString(R.string.notification_restart), restartIntent)
        }
        return builder.build()
    }

    /**
     * Ongoing notification shown while the service waits to auto-restart a
     * crashed server. Deliberately keeps the same notification id as the normal
     * status so the foreground notification never disappears mid-recovery.
     */
    fun buildAutoRestart(
        instanceName: String?,
        attempt: Int,
        maxAttempts: Int,
        contentIntent: PendingIntent?,
        stopIntent: PendingIntent?
    ): Notification {
        val title = instanceName?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.app_name)
        return baseStatusBuilder(
            title = title,
            text = "Restarting automatically (attempt $attempt/$maxAttempts)…",
            contentIntent = contentIntent,
            stopIntent = stopIntent,
            restartIntent = null
        ).build()
    }

    /** Alert-channel announcement of a pending automatic restart (visible to the user). */
    fun postAutoRestartAlert(
        instanceName: String?,
        attempt: Int,
        maxAttempts: Int,
        contentIntent: PendingIntent?
    ) {
        val title = instanceName?.takeIf { it.isNotBlank() }?.let { "$it crashed" }
            ?: "Server crashed"
        postCrashAlert(
            title = title,
            text = "Restarting automatically (attempt $attempt/$maxAttempts)…",
            contentIntent = contentIntent,
            restartIntent = null
        )
    }

    private fun baseStatusBuilder(
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        stopIntent: PendingIntent?,
        restartIntent: PendingIntent?,
        forceStopIntent: PendingIntent? = null
    ): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (stopIntent != null) {
            builder.addAction(0, appContext.getString(R.string.notification_stop), stopIntent)
        }
        if (restartIntent != null) {
            builder.addAction(0, appContext.getString(R.string.notification_restart), restartIntent)
        }
        if (forceStopIntent != null) {
            builder.addAction(0, appContext.getString(R.string.notification_force_stop), forceStopIntent)
        }
        return builder
    }

    /* ------------------------------------------------------------------ */
    /* Posting                                                            */
    /* ------------------------------------------------------------------ */

    fun postStatus(notification: Notification) {
        safeNotify(ID_STATUS, notification)
    }

    fun postCrashAlert(
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        restartIntent: PendingIntent?
    ) {
        safeNotify(ID_ALERT, buildAlert(title, text, contentIntent, restartIntent))
    }

    fun postStartRejected(
        message: String,
        instanceName: String?,
        contentIntent: PendingIntent?,
        restartIntent: PendingIntent?
    ) {
        val title = instanceName?.takeIf { it.isNotBlank() }?.let { "Could not start $it" }
            ?: "Could not start server"
        postCrashAlert(title, message, contentIntent, restartIntent)
    }

    fun cancelStatus() {
        runCatching { notificationManager.cancel(ID_STATUS) }
            .onFailure { Log.w(TAG, "Could not cancel status notification", it) }
    }

    fun cancelAlert() {
        runCatching { notificationManager.cancel(ID_ALERT) }
            .onFailure { Log.w(TAG, "Could not cancel alert notification", it) }
    }

    private fun safeNotify(id: Int, notification: Notification) {
        runCatching { notificationManager.notify(id, notification) }
            .onFailure { Log.w(TAG, "Could not post notification $id", it) }
    }

    companion object {
        const val TAG = "ServerNotifications"

        const val CHANNEL_STATUS = "server_status"
        const val CHANNEL_ALERTS = "server_alerts"
        const val CHANNEL_DOWNLOADS = "downloads"

        /** The single ongoing notification owned by the foreground service. */
        const val ID_STATUS = 1001

        /** Last crash / start-rejection alert (replaced, never stacked). */
        const val ID_ALERT = 1002

        /** Base id for download progress notifications (unused by the service). */
        const val ID_DOWNLOAD_BASE = 2000

        fun stateLabel(state: RunState): String = when (state) {
            RunState.STOPPED -> "Stopped"
            RunState.STARTING -> "Starting…"
            RunState.RUNNING -> "Running"
            RunState.STOPPING -> "Stopping…"
            RunState.CRASHED -> "Crashed"
        }

        /** Compact uptime: `42s`, `7m 03s`, `2h 14m`. */
        fun formatUptime(uptimeMs: Long): String {
            val totalSeconds = uptimeMs.coerceAtLeast(0L) / 1000L
            return when {
                totalSeconds < 60L -> "${totalSeconds}s"
                totalSeconds < 3600L ->
                    "${totalSeconds / 60L}m ${(totalSeconds % 60L).toString().padStart(2, '0')}s"
                else ->
                    "${totalSeconds / 3600L}h ${((totalSeconds % 3600L) / 60L).toString().padStart(2, '0')}m"
            }
        }

        /** 512 -> `512 MB`, 2048 -> `2.0 GB`. */
        fun formatRamMb(mb: Long): String =
            if (mb >= 1024L) {
                String.format(Locale.US, "%.1f GB", mb / 1024.0)
            } else {
                "$mb MB"
            }
    }
}
