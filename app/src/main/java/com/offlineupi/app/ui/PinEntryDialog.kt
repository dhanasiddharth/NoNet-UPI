package com.offlineupi.app.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Window
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.offlineupi.app.R
import com.offlineupi.app.data.PinStore

/**
 * Full-screen-style dialog with a number pad for UPI PIN entry.
 * Stores the PIN in volatile PinStore (process memory only).
 */
class PinEntryDialog(
    context: Context,
    private val onPinEntered: () -> Unit
) : Dialog(context, R.style.Theme_OfflineUPI) {

    private val maxPinLength = 6
    private val minPinLength = 4
    private var pinBuffer = StringBuilder()
    private lateinit var tvPinDots: TextView
    private lateinit var btnSubmit: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_pin_entry, null)
        setContentView(view)
        setCancelable(true)

        tvPinDots = view.findViewById(R.id.tvPinDots)
        btnSubmit = view.findViewById(R.id.btnSubmitPin)

        val numberButtons = listOf(
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        )
        for (id in numberButtons) {
            view.findViewById<MaterialButton>(id).setOnClickListener { btn ->
                val digit = (btn as MaterialButton).text.toString()
                if (pinBuffer.length < maxPinLength) {
                    pinBuffer.append(digit)
                    updateDisplay()
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.btnBackspace).setOnClickListener {
            if (pinBuffer.isNotEmpty()) {
                pinBuffer.deleteCharAt(pinBuffer.length - 1)
                updateDisplay()
            }
        }

        view.findViewById<MaterialButton>(R.id.btnClear).setOnClickListener {
            pinBuffer.clear()
            updateDisplay()
        }

        btnSubmit.setOnClickListener {
            if (pinBuffer.length >= minPinLength) {
                PinStore.setPin(pinBuffer.toString())
                dismiss()
                onPinEntered()
            }
        }

        updateDisplay()
    }

    private fun updateDisplay() {
        val filled = "\u25CF ".repeat(pinBuffer.length)
        val empty = "\u25CB ".repeat(maxPinLength - pinBuffer.length)
        tvPinDots.text = (filled + empty).trim()
        btnSubmit.isEnabled = pinBuffer.length >= minPinLength
    }

    companion object {
        /**
         * Shows the PIN dialog if no PIN is stored, otherwise calls onReady immediately.
         */
        fun ensurePin(context: Context, onReady: () -> Unit) {
            if (PinStore.hasPin()) {
                onReady()
            } else {
                PinEntryDialog(context, onReady).show()
            }
        }
    }
}
