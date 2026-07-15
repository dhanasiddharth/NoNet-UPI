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
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.offlineupi.app.R
import com.offlineupi.app.portfolio.PortfolioAnalytics
import com.offlineupi.app.portfolio.PortfolioDb
import com.offlineupi.app.portfolio.PortfolioSync
import com.offlineupi.app.ui.MainActivity
import java.util.Calendar
import java.util.TimeZone
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

        // Two daily refreshes, pinned to IST and user-configurable. Defaults:
        // ~2pm (mid-session, catches the day's move) and ~4am (overnight US/prev
        // close settled). Stored as HH:mm in plain prefs — not sensitive.
        private const val WORK_A = "portfolio_price_sync_a"
        private const val WORK_B = "portfolio_price_sync_b"
        private const val WORK_NOW = "portfolio_price_sync_now"
        private val DEFAULTS = listOf(14 to 0, 4 to 0)
        private val IST: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")

        private fun prefs(c: Context) =
            c.getSharedPreferences("price_sync", Context.MODE_PRIVATE)

        /** The two configured [hour, minute] IST slots (falls back to defaults). */
        fun configuredTimes(c: Context): List<Pair<Int, Int>> {
            val p = prefs(c)
            return listOf(
                p.getInt("h0", DEFAULTS[0].first) to p.getInt("m0", DEFAULTS[0].second),
                p.getInt("h1", DEFAULTS[1].first) to p.getInt("m1", DEFAULTS[1].second),
            )
        }

        /** Persist new slots and re-anchor the periodic work to them. */
        fun setTimes(c: Context, a: Pair<Int, Int>, b: Pair<Int, Int>) {
            prefs(c).edit()
                .putInt("h0", a.first).putInt("m0", a.second)
                .putInt("h1", b.first).putInt("m1", b.second)
                .apply()
            reschedule(c)
        }

        /** Idempotent: keeps existing timers so app opens don't reset the clock. */
        fun schedule(context: Context) = enqueueDaily(context, ExistingPeriodicWorkPolicy.KEEP)

        /** Replaces the timers — used when the configured times change.
         *  UPDATE would keep the original period anchor and fire at the old
         *  wall-clock time; re-anchoring needs a full re-enqueue. */
        fun reschedule(context: Context) =
            enqueueDaily(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)

        private fun enqueueDaily(context: Context, policy: ExistingPeriodicWorkPolicy) {
            // drop the legacy single-slot work if it's still around
            WorkManager.getInstance(context).cancelUniqueWork("portfolio_price_sync")
            val times = configuredTimes(context)
            scheduleAt(context, WORK_A, times[0].first, times[0].second, policy)
            scheduleAt(context, WORK_B, times[1].first, times[1].second, policy)
        }

        private fun scheduleAt(
            context: Context, name: String, hour: Int, minute: Int,
            policy: ExistingPeriodicWorkPolicy,
        ) {
            val now = Calendar.getInstance(IST)
            val target = Calendar.getInstance(IST).apply {
                set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
            }
            val req = PeriodicWorkRequestBuilder<PriceSyncWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(target.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .setConstraints(networkOnly())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(name, policy, req)
        }

        /** Fire a one-off refresh when the app opens and prices are stale (> [maxAgeMs]). */
        fun syncIfStale(context: Context, maxAgeMs: Long = 3_600_000L) {
            val db = PortfolioDb(context)
            if (db.tradeCount() == 0) return
            val last = db.getMeta("priceSyncedAt")?.toLongOrNull() ?: 0L
            if (System.currentTimeMillis() - last < maxAgeMs) return
            val req = OneTimeWorkRequestBuilder<PriceSyncWorker>()
                .setConstraints(networkOnly())
                .build()
            // KEEP: if a refresh is already queued/running, don't pile another on
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, req)
        }

        private fun networkOnly() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED).build()

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
