package lt.stillhome

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import lt.stillhome.data.AppDb
import lt.stillhome.data.Store
import lt.stillhome.service.GuardService

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        app = this
        Store.init(this)
        db = AppDb.get(this)
        createChannels()
        GuardService.start(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_GUARD, "Status (lock screen)", NotificationManager.IMPORTANCE_LOW).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, "Session warnings", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_PING, "Allowed notifications", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_DIGEST, "Digest", NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); setShowBadge(true)
        })
    }

    companion object {
        lateinit var app: App; private set
        lateinit var db: AppDb; private set
        const val CH_GUARD = "guard"
        const val CH_ALERT = "alert"
        const val CH_PING = "ping"
        const val CH_DIGEST = "digest"
    }
}
