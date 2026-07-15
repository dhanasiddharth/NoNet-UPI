package com.offlineupi.app.data

import android.content.Context
import org.json.JSONObject

/**
 * User-set metadata per payment target (UPI ID / phone): a display label and
 * a favourite flag that pins the payee to the quick-pay row. Plain prefs with
 * an in-memory mirror so list binds never re-parse JSON.
 */
object PayeeMeta {

    private var loaded = false
    private val labels = HashMap<String, String>()
    private val favs = HashSet<String>()

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences("payee_meta", Context.MODE_PRIVATE)

    private fun load(c: Context) {
        if (loaded) return
        runCatching {
            val o = JSONObject(prefs(c).getString("payees", "{}") ?: "{}")
            for (addr in o.keys()) {
                val e = o.getJSONObject(addr)
                e.optString("label").takeIf { it.isNotBlank() }?.let { labels[addr] = it }
                if (e.optBoolean("fav")) favs.add(addr)
            }
        }
        loaded = true
    }

    fun label(c: Context, address: String?): String? =
        address?.let { load(c); labels[it] }

    fun isFavourite(c: Context, address: String?): Boolean =
        address != null && run { load(c); address in favs }

    fun favourites(c: Context): Set<String> { load(c); return favs.toSet() }

    fun set(c: Context, address: String, label: String?, favourite: Boolean) {
        load(c)
        if (label.isNullOrBlank()) labels.remove(address) else labels[address] = label
        if (favourite) favs.add(address) else favs.remove(address)
        val o = JSONObject()
        for (addr in labels.keys + favs) {
            o.put(addr, JSONObject()
                .put("label", labels[addr] ?: "")
                .put("fav", addr in favs))
        }
        prefs(c).edit().putString("payees", o.toString()).apply()
    }
}
