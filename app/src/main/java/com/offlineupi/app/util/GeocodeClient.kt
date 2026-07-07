package com.offlineupi.app.util

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reverse-geocodes a coordinate into candidate places via the tokenless Apps
 * Script proxy (returns Google Geocoding JSON). Only lat/lng leave the device —
 * never any payment detail. Runs off the main thread.
 */
object GeocodeClient {

    private const val ENDPOINT =
        "https://script.google.com/macros/s/AKfycbwQ9jZrxNr_HBgIKV6xz1SCrSxO0uGFeLHEXG3_B2KjA7clfl1AkEEU3L-SO_3WPiCj/exec"

    data class Place(
        val label: String,        // short, distinguishing name/address
        val area: String,         // locality + state context
        val formatted: String,    // full formatted_address
        val placeId: String?,
        val isBusiness: Boolean,  // establishment / point_of_interest
    )

    /** Ranked, de-duplicated candidates (businesses first). Empty on any failure. */
    fun reverseGeocode(lat: Double, lng: Double): List<Place> {
        val body = httpGet("$ENDPOINT?lat=$lat&lng=$lng")
        val json = JSONObject(body)
        if (json.optString("status") != "OK") return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()

        val byAddress = LinkedHashMap<String, Place>()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val formatted = r.optString("formatted_address").takeIf { it.isNotBlank() } ?: continue
            if (byAddress.containsKey(formatted)) continue
            val types = r.optJSONArray("types")
            // skip broad geographic entries — they don't identify a spot to pay at
            if (types.hasAny("locality", "political", "postal_code",
                    "administrative_area_level_1", "administrative_area_level_2", "country") &&
                !types.hasAny("establishment", "point_of_interest", "premise", "street_address", "route")
            ) continue
            val components = r.optJSONArray("address_components")
            byAddress[formatted] = Place(
                label = shortLabel(formatted, components),
                area = areaLabel(components),
                formatted = formatted,
                placeId = r.optString("place_id").takeIf { it.isNotBlank() },
                isBusiness = types.hasAny("establishment", "point_of_interest"),
            )
        }
        return byAddress.values.sortedByDescending { it.isBusiness }.take(8)
    }

    // ---- helpers ----

    private fun JSONArray?.hasAny(vararg wanted: String): Boolean {
        if (this == null) return false
        for (i in 0 until length()) if (optString(i) in wanted) return true
        return false
    }

    private fun component(components: JSONArray?, type: String): String? {
        if (components == null) return null
        for (i in 0 until components.length()) {
            val c = components.optJSONObject(i) ?: continue
            val types = c.optJSONArray("types") ?: continue
            for (j in 0 until types.length()) if (types.optString(j) == type)
                return c.optString("long_name").takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun shortLabel(formatted: String, components: JSONArray?): String {
        val premise = component(components, "premise") ?: component(components, "route")
        val subl = component(components, "sublocality_level_2")
            ?: component(components, "sublocality_level_3")
        val parts = listOfNotNull(premise, subl).distinct()
        return if (parts.isNotEmpty()) parts.joinToString(", ")
        else formatted.substringBefore(",").trim().ifBlank { formatted }
    }

    private fun areaLabel(components: JSONArray?): String {
        val locality = component(components, "locality")
            ?: component(components, "sublocality_level_1")
        val state = component(components, "administrative_area_level_1")
        return listOfNotNull(locality, state).joinToString(", ")
    }

    private fun httpGet(url: String): String {
        var current = url
        repeat(5) {
            val conn = URL(current).openConnection() as HttpURLConnection
            // Apps Script 302-redirects to googleusercontent; follow manually
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Finance/2.0")
            val code = conn.responseCode
            if (code in 300..399) {
                current = conn.getHeaderField("Location") ?: error("redirect without location")
                conn.disconnect()
                return@repeat
            }
            require(code in 200..299) { "HTTP $code" }
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        }
        error("too many redirects")
    }
}
