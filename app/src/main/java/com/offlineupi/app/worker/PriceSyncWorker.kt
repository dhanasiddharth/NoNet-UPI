package com.offlineupi.app.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.offlineupi.app.R
import com.offlineupi.app.portfolio.PortfolioAnalytics
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.portfolio.PortfolioSync
import com.offlineupi.app.ui.MainActivity
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Daily price refresh + movement check. Alerts are event-driven only: a
 * notification fires when a holding's latest close moved beyond the
 * threshold — no scheduled digests.
 */
class PriceSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val db = PortfolioDb(applicationContext)
        if (db.tradeCount() == 0) return Result.success()
        return try {
            PortfolioSync(applicationContext, db).syncPrices()
            checkMovements(applicationContext, db)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val CHANNEL = "movement_alerts"
        const val DEFAULT_THRESHOLD = 4.0

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<PriceSyncWorker>(24, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "portfolio_price_sync", ExistingPeriodicWorkPolicy.KEEP, req
            )
        }

        /** isin rule > bucket rule > default rule > built-in ±4%. */
        fun resolvedThreshold(rules: Map<String, Double>, h: PortfolioAnalytics.Holding): Double =
            rules["isin:${h.instrument.isin}"]
                ?: rules["bucket:${h.bucket.name}"]
                ?: rules["default"]
                ?: DEFAULT_THRESHOLD

        /** Notifies once per holding per close-day; re-runs are deduped via meta. */
        fun checkMovements(context: Context, db: PortfolioDb) {
            val rules = db.alertRules()
            // compute() collects movers down to the loosest rule; filter per holding after
            val floor = (rules.values + DEFAULT_THRESHOLD).min().coerceAtLeast(0.5)
            val snap = PortfolioAnalytics.compute(db, floor) ?: return
            val todays = snap.movers.filter { it.day == snap.asOfDay &&
                abs(it.pct) >= resolvedThreshold(rules, it.holding) }
            if (todays.isEmpty()) return
            val lastNotified = db.getMeta("alertedDay")?.toLongOrNull() ?: 0L
            if (snap.asOfDay <= lastNotified) return
            db.setMeta("alertedDay", snap.asOfDay.toString())
            for (m in todays) db.logAlert(PortfolioDb.AlertEntry(
                m.day, m.holding.instrument.isin, m.holding.instrument.name,
                m.pct, m.holding.value, m.holding.instrument.currency
            ))

            ensureChannel(context)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val nm = NotificationManagerCompat.from(context)
            todays.take(3).forEachIndexed { i, m ->
                val sym = if (m.holding.instrument.currency == "USD") "$" else "₹"
                val arrow = if (m.pct > 0) "▲" else "▼"
                val impact = m.holding.value * m.pct / 100
                val intent = PendingIntent.getActivity(
                    context, 200 + i,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notif = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_rupee)
                    .setContentTitle("${m.holding.instrument.name} $arrow ${"%.1f".format(abs(m.pct))}%")
                    .setContentText(
                        "Your $sym${"%,.0f".format(m.holding.value)} position moved " +
                            "${if (impact >= 0) "+" else "−"}$sym${"%,.0f".format(abs(impact))}"
                    )
                    .setContentIntent(intent)
                    .setAutoCancel(true)
                    .build()
                nm.notify(300 + i, notif)
            }
        }

        private fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Movement alerts", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "Significant daily moves in your holdings" }
            )
        }
    }
}
