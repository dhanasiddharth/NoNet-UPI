package com.offlineupi.app.portfolio

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

data class Instrument(
    val isin: String,
    val yahoo: String,
    val currency: String,   // "INR" | "USD" — display currency of the security
    val type: String,
    val name: String,
)

data class Trade(
    val key: String,        // stable id for upsert (broker trade id or synthetic)
    val isin: String,
    val day: Long,          // epoch day
    val side: String,       // "buy" | "sell"
    val qty: Double,
    val price: Double,      // native currency, per unit
    val fee: Double,
    val owner: String,
    val broker: String,
)

/**
 * Plain SQLite (no ORM): three tables and a metadata map. Price history is the
 * only sizeable table (~90k rows for 10y × 40 symbols) — well within SQLite's
 * comfort zone on-device.
 */
class PortfolioDb(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "portfolio.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE instrument (isin TEXT PRIMARY KEY, yahoo TEXT, currency TEXT, type TEXT, name TEXT)")
        db.execSQL("CREATE TABLE trade (key TEXT PRIMARY KEY, isin TEXT, day INTEGER, side TEXT, qty REAL, price REAL, fee REAL, owner TEXT, broker TEXT)")
        db.execSQL("CREATE TABLE price (symbol TEXT, day INTEGER, close REAL, PRIMARY KEY(symbol, day))")
        db.execSQL("CREATE INDEX idx_trade_isin ON trade(isin)")
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)")
        createAlertTables(db)
        createFundamentalsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) createAlertTables(db)
        if (old < 3) createFundamentalsTable(db)
    }

    private fun createFundamentalsTable(db: SQLiteDatabase) {
        // raw quoteSummary result per symbol; a 24h-ish cache, not a record
        db.execSQL("CREATE TABLE fundamentals (symbol TEXT PRIMARY KEY, json TEXT, fetched_at INTEGER)")
    }

    private fun createAlertTables(db: SQLiteDatabase) {
        // scope: "default" | "bucket:<Bucket.name>" | "isin:<isin>"; pct is ±%
        db.execSQL("CREATE TABLE alert_rule (scope TEXT PRIMARY KEY, pct REAL)")
        db.execSQL(
            "CREATE TABLE alert_log (day INTEGER, isin TEXT, name TEXT, pct REAL, " +
                "value REAL, currency TEXT, PRIMARY KEY(day, isin))"
        )
    }

    // ---- meta ----
    fun getMeta(k: String): String? = readableDatabase
        .rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k))
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun setMeta(k: String, v: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES(?,?)", arrayOf(k, v))
    }

    // ---- listing ----
    fun replaceListing(instruments: List<Instrument>, trades: List<Trade>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM instrument")
            db.execSQL("DELETE FROM trade")
            for (i in instruments) db.insert("instrument", null, ContentValues().apply {
                put("isin", i.isin); put("yahoo", i.yahoo); put("currency", i.currency)
                put("type", i.type); put("name", i.name)
            })
            for (t in trades) db.insertWithOnConflict("trade", null, ContentValues().apply {
                put("key", t.key); put("isin", t.isin); put("day", t.day); put("side", t.side)
                put("qty", t.qty); put("price", t.price); put("fee", t.fee)
                put("owner", t.owner); put("broker", t.broker)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun instruments(): List<Instrument> = readableDatabase
        .rawQuery("SELECT isin,yahoo,currency,type,name FROM instrument", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Instrument(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
                )
            }
        }

    fun trades(): List<Trade> = readableDatabase
        .rawQuery("SELECT key,isin,day,side,qty,price,fee,owner,broker FROM trade ORDER BY day", null).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    Trade(c.getString(0), c.getString(1), c.getLong(2), c.getString(3),
                        c.getDouble(4), c.getDouble(5), c.getDouble(6), c.getString(7), c.getString(8))
                )
            }
        }

    fun tradeCount(): Int = readableDatabase
        .rawQuery("SELECT COUNT(*) FROM trade", null).use { it.moveToFirst(); it.getInt(0) }

    // ---- prices ----
    fun upsertPrices(symbol: String, bars: List<Pair<Long, Double>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((day, close) in bars) db.insertWithOnConflict("price", null, ContentValues().apply {
                put("symbol", symbol); put("day", day); put("close", close)
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun priceSeries(symbol: String): List<Pair<Long, Double>> = readableDatabase
        .rawQuery("SELECT day, close FROM price WHERE symbol=? ORDER BY day", arrayOf(symbol)).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to c.getDouble(1)) }
        }

    fun lastPriceDay(symbol: String): Long? = readableDatabase
        .rawQuery("SELECT MAX(day) FROM price WHERE symbol=?", arrayOf(symbol)).use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }

    fun priceSymbols(): Set<String> = readableDatabase
        .rawQuery("SELECT DISTINCT symbol FROM price", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    // ---- fundamentals (cached quoteSummary JSON) ----
    fun fundamentals(symbol: String): Pair<String, Long>? = readableDatabase
        .rawQuery("SELECT json, fetched_at FROM fundamentals WHERE symbol=?", arrayOf(symbol)).use {
            if (it.moveToFirst()) it.getString(0) to it.getLong(1) else null
        }

    fun setFundamentals(symbol: String, json: String) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO fundamentals(symbol,json,fetched_at) VALUES(?,?,?)",
            arrayOf(symbol, json, System.currentTimeMillis())
        )
    }

    // ---- alert rules (threshold cascade: isin > bucket > default) ----
    fun alertRules(): Map<String, Double> = readableDatabase
        .rawQuery("SELECT scope, pct FROM alert_rule", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getDouble(1)) }
        }

    /** null pct removes the rule (falls back up the cascade). */
    fun setAlertRule(scope: String, pct: Double?) {
        if (pct == null) writableDatabase.execSQL("DELETE FROM alert_rule WHERE scope=?", arrayOf(scope))
        else writableDatabase.execSQL(
            "INSERT OR REPLACE INTO alert_rule(scope,pct) VALUES(?,?)", arrayOf(scope, pct)
        )
    }

    // ---- alert history ----
    data class AlertEntry(val day: Long, val isin: String, val name: String,
                          val pct: Double, val value: Double, val currency: String)

    fun logAlert(e: AlertEntry) {
        writableDatabase.insertWithOnConflict("alert_log", null, ContentValues().apply {
            put("day", e.day); put("isin", e.isin); put("name", e.name)
            put("pct", e.pct); put("value", e.value); put("currency", e.currency)
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun recentAlerts(limit: Int = 40): List<AlertEntry> = readableDatabase
        .rawQuery("SELECT day,isin,name,pct,value,currency FROM alert_log ORDER BY day DESC, pct DESC LIMIT ?",
            arrayOf(limit.toString())).use { c ->
            buildList {
                while (c.moveToNext()) add(AlertEntry(c.getLong(0), c.getString(1),
                    c.getString(2), c.getDouble(3), c.getDouble(4), c.getString(5)))
            }
        }

    companion object {
        fun epochDay(iso: String): Long = LocalDate.parse(iso).toEpochDay()
    }
}
