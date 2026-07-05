package com.offlineupi.app.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.ContactsContract
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory

/**
 * Device-contacts lookup: name + photo by phone number, with an in-memory
 * cache so list scrolling never re-queries the provider. All calls no-op
 * safely when READ_CONTACTS hasn't been granted.
 */
object ContactsHelper {

    data class Entry(val name: String?, val photoUri: String?)

    private val cache = HashMap<String, Entry?>()

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Clears the cache (e.g. after the permission is newly granted). */
    fun invalidate() = cache.clear()

    fun lookupPhone(context: Context, number: String?): Entry? {
        if (number.isNullOrBlank() || number.contains('@')) return null
        if (!hasPermission(context)) return null
        return cache.getOrPut(number) {
            runCatching {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
                )
                context.contentResolver.query(
                    uri,
                    arrayOf(
                        ContactsContract.PhoneLookup.DISPLAY_NAME,
                        ContactsContract.PhoneLookup.PHOTO_URI
                    ),
                    null, null, null
                )?.use { c ->
                    if (c.moveToFirst()) Entry(c.getString(0), c.getString(1)) else null
                }
            }.getOrNull()
        }
    }

    private fun loadCircularPhoto(context: Context, photoUri: String, sizePx: Int): Drawable? =
        runCatching {
            context.contentResolver.openInputStream(Uri.parse(photoUri))?.use { ins ->
                val bmp = BitmapFactory.decodeStream(ins) ?: return null
                val scaled = Bitmap.createScaledBitmap(bmp, sizePx, sizePx, true)
                RoundedBitmapDrawableFactory.create(context.resources, scaled)
                    .apply { isCircular = true }
            }
        }.getOrNull()

    /**
     * If [address] is a phone number with a contact photo, replaces the
     * initials avatar with the circular photo. Returns the contact name
     * (or null) so callers can prefer it over stored names.
     */
    fun applyPhotoToAvatar(avatar: TextView, address: String?): String? {
        val ctx = avatar.context
        val entry = lookupPhone(ctx, address) ?: return null
        entry.photoUri?.let { uri ->
            val size = if (avatar.width > 0) avatar.width
            else avatar.layoutParams?.width?.takeIf { it > 0 }
                ?: (42 * ctx.resources.displayMetrics.density).toInt()
            loadCircularPhoto(ctx, uri, size)?.let { photo ->
                avatar.background = photo
                avatar.text = ""
            }
        }
        return entry.name
    }
}
