package com.offlineupi.app.ui

import android.content.Context
import android.text.InputType
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offlineupi.app.data.PayeeMeta

/**
 * Label + favourite editor for a payment target. The label overrides the
 * payee's display name everywhere; favourite pins them to the quick-pay row.
 */
object PayeeEdit {

    fun show(ctx: Context, address: String, suggestedName: String?, onSaved: () -> Unit) {
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            hint = suggestedName ?: "e.g. Milk vendor"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(PayeeMeta.label(ctx, address) ?: "")
            setSelection(text.length)
        }
        val fav = CheckBox(ctx).apply {
            text = "Favourite — keep on the pay screen"
            isChecked = PayeeMeta.isFavourite(ctx, address)
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
            addView(fav, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(address)
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                PayeeMeta.set(ctx, address, input.text.toString().trim(), fav.isChecked)
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
