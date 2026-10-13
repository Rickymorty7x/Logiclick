package com.logiclick.app.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.logiclick.app.MainActivity
import com.logiclick.app.data.ConfigStore

class MirrorBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED || !ConfigStore(context).mirrorAutoResume) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen-share resume", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val resume = PendingIntent.getActivity(
            context,
            203,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_RESUME_MIRROR, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Resume Logiclick screen sharing")
                .setContentText("Android requires one tap to approve screen capture after restart")
                .setContentIntent(resume)
                .setAutoCancel(true)
                .build()
        )
    }

    companion object {
        private const val CHANNEL_ID = "logiclick_mirror_resume"
        private const val NOTIFICATION_ID = 1203
    }
}
