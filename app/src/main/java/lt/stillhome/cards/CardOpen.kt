package lt.stillhome.cards

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import lt.stillhome.App
import lt.stillhome.R
import lt.stillhome.data.CardRow
import lt.stillhome.data.Store
import lt.stillhome.ui.BarcodeActivity

/**
 * One place that decides how a store card opens: Google Wallet (Wallet keeps the card, we just jump to it)
 * or our own full-screen barcode. Google Wallet has no API to read cards, so "Wallet" is a per-card choice
 * kept in Store as cardWallet.<id> (no database change needed).
 */
object CardOpen {
    const val WALLET = "com.google.android.apps.walletnfcrel"
    private const val CH_CARD = "card"
    private const val NOTIF_ID = 7101

    fun walletInstalled(ctx: Context) = ctx.packageManager.getLaunchIntentForPackage(WALLET) != null
    fun usesWallet(id: Long) = Store.bool("cardWallet.$id", false)
    fun setWallet(id: Long, on: Boolean) = Store.set("cardWallet.$id", if (on) "1" else null)

    /** Intent that shows this card: Wallet if chosen (and installed), else our barcode screen. */
    fun intent(ctx: Context, id: Long): Intent {
        val wallet = if (usesWallet(id)) ctx.packageManager.getLaunchIntentForPackage(WALLET) else null
        val noBarcode = App.db.dao().card(id)?.let { it.number.isBlank() && it.appPkg.isEmpty() } ?: false
        val i = wallet
            // Wallet-only card but Wallet is gone: send to its Play Store page instead of an empty barcode screen.
            ?: if (usesWallet(id) && noBarcode) Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$WALLET"))
            else Intent(ctx, BarcodeActivity::class.java).putExtra("card", id)
        return i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun open(ctx: Context, id: Long) = ctx.startActivity(intent(ctx, id))

    /** "You're at Maxima" heads-up with a tap-to-open card. Posted once per store visit. */
    fun notifyArrival(ctx: Context, card: CardRow, storeName: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_CARD, "Store cards", NotificationManager.IMPORTANCE_HIGH).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC; setShowBadge(false)
        })
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, intent(ctx, card.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val where = if (usesWallet(card.id) && walletInstalled(ctx)) "Google Wallet" else "your barcode"
        val n = Notification.Builder(ctx, CH_CARD).setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("You're at ${storeName.ifBlank { card.brand }}")
            .setContentText("Tap for your ${card.name} card ($where)")
            .setContentIntent(pi).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(null, "Open card", pi).build())
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun clearArrival(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)
}
