package com.offlineupi.app.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.offlineupi.app.R
import com.offlineupi.app.accessibility.UssdAccessibilityService
import com.offlineupi.app.databinding.ActivityUssdInstructionBinding

/**
 * Displays USSD payment progress after *99# has been dialed.
 *
 * Listens for step completion broadcasts from UssdAccessibilityService
 * and updates each step's status indicator in real time.
 *
 * SECURITY:
 * - No UPI PIN is displayed, suggested, or captured.
 * - The UPI ID shown here is only for user reference; it is not stored.
 */
class UssdInstructionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYEE_ADDRESS = "extra_payee_address"
        const val EXTRA_AMOUNT = "extra_amount"
    }

    private lateinit var binding: ActivityUssdInstructionBinding
    private var payeeAddress = ""
    private var amount: String? = null
    private var autoFilling = false

    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val step = intent.getIntExtra(UssdAccessibilityService.EXTRA_STEP, -1)
            val resultText = intent.getStringExtra(UssdAccessibilityService.EXTRA_RESULT_TEXT)
            onStepCompleted(step, resultText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUssdInstructionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        payeeAddress = intent.getStringExtra(EXTRA_PAYEE_ADDRESS) ?: ""
        amount = intent.getStringExtra(EXTRA_AMOUNT)
        autoFilling = UssdAccessibilityService.hasPending()

        setupSteps()

        binding.btnRetry.setOnClickListener { retryTransaction() }

        binding.btnDone.setOnClickListener {
            UssdAccessibilityService.clearPending()
            finishAffinity()
            startActivity(Intent(this, MainActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(UssdAccessibilityService.ACTION_STEP_COMPLETED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stepReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stepReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stepReceiver)
    }

    private fun setupSteps() {
        if (autoFilling) {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_autofill)
        } else {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle)
        }

        // Step 1: Send Money
        binding.tvStep1.text = getString(R.string.ussd_step1_pending)
        setStepPending(binding.tvStep1Status, "1")

        // Step 2: Select UPI ID
        binding.tvStep2.text = getString(R.string.ussd_step2_pending)
        setStepPending(binding.tvStep2Status, "2")

        // Step 3: Enter VPA
        if (autoFilling) {
            binding.tvStep3.text = getString(R.string.ussd_step3_pending, payeeAddress)
        } else {
            binding.tvStep3.text = getString(R.string.ussd_step3_manual, payeeAddress)
        }
        setStepPending(binding.tvStep3Status, "3")

        // Step 4: Enter amount
        binding.tvStep4.text = if (!amount.isNullOrBlank()) {
            getString(R.string.ussd_step4_pending, amount)
        } else {
            getString(R.string.ussd_step4_no_amount)
        }
        setStepPending(binding.tvStep4Status, "4")

        // Step 5: UPI PIN (always manual)
        binding.tvStep5.text = getString(R.string.ussd_step5_pending)
        setStepPending(binding.tvStep5Status, "5")

        // Clipboard badge
        binding.tvClipboardBadge.visibility = if (!autoFilling) View.VISIBLE else View.GONE
        binding.tvClipboardBadge.text = getString(R.string.ussd_clipboard_copied_badge)

        binding.tvDisclaimer.text = getString(R.string.ussd_sms_disclaimer)
        binding.tvSecurityNote.text = getString(R.string.ussd_security_note)
    }

    private fun onStepCompleted(step: Int, resultText: String? = null) {
        when (step) {
            UssdAccessibilityService.STEP_WELCOME -> {
                setStepActive(binding.tvStep1Status)
            }
            UssdAccessibilityService.STEP_SEND_MONEY -> {
                setStepDone(binding.tvStep1Status)
                binding.tvStep1.text = getString(R.string.ussd_step1_done)
                setStepActive(binding.tvStep2Status)
            }
            UssdAccessibilityService.STEP_UPI_ID_SELECTED -> {
                setStepDone(binding.tvStep2Status)
                binding.tvStep2.text = getString(R.string.ussd_step2_done)
                setStepActive(binding.tvStep3Status)
            }
            UssdAccessibilityService.STEP_VPA_ENTERED -> {
                setStepDone(binding.tvStep3Status)
                binding.tvStep3.text = getString(R.string.ussd_step3_done, payeeAddress)
                setStepActive(binding.tvStep4Status)
            }
            UssdAccessibilityService.STEP_AMOUNT_ENTERED -> {
                setStepDone(binding.tvStep4Status)
                binding.tvStep4.text = getString(R.string.ussd_step4_done, amount ?: "")
                setStepActive(binding.tvStep5Status)
                binding.tvStep5.text = getString(R.string.ussd_step5_ready)
            }
            UssdAccessibilityService.STEP_REMARKS_SKIPPED -> {
                setStepActive(binding.tvStep5Status)
                binding.tvStep5.text = getString(R.string.ussd_step5_ready)
                binding.tvUssdSubtitle.text = "Auto-fill complete. Enter your UPI PIN to finish."
            }
            UssdAccessibilityService.STEP_PIN_PROMPT -> {
                setStepActive(binding.tvStep5Status)
                binding.tvStep5.text = getString(R.string.ussd_step5_ready)
                binding.tvUssdSubtitle.text = "Enter your UPI PIN to complete the payment."
            }
            UssdAccessibilityService.STEP_RESULT_SUCCESS -> {
                setStepDone(binding.tvStep5Status)
                binding.tvStep5.text = "UPI PIN entered"
                showResult(true, resultText)
                showRetryButton()
            }
            UssdAccessibilityService.STEP_RESULT_FAILURE -> {
                setStepDone(binding.tvStep5Status)
                binding.tvStep5.text = "UPI PIN entered"
                showResult(false, resultText)
                showRetryButton()
            }
        }
    }

    private fun retryTransaction() {
        // Reset accessibility service state and re-set pending payment
        UssdAccessibilityService.clearPending()
        UssdAccessibilityService.setPendingPayment(payeeAddress, amount)
        autoFilling = true

        // Reset UI
        setupSteps()
        binding.btnRetry.visibility = View.GONE
        binding.dividerResult.visibility = View.GONE
        binding.layoutResult.visibility = View.GONE
        binding.tvResultDetails.visibility = View.GONE
        binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_retrying)

        // Re-dial *99#
        dialUssd99()
    }

    private fun dialUssd99() {
        val uri = Uri.parse("tel:*99" + Uri.encode("#"))
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                startActivity(Intent(Intent.ACTION_CALL, uri))
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_DIAL, uri))
            }
        } else {
            Toast.makeText(this, getString(R.string.toast_dial_fallback), Toast.LENGTH_LONG).show()
            startActivity(Intent(Intent.ACTION_DIAL, uri))
        }
    }

    private fun showRetryButton() {
        binding.btnRetry.visibility = View.VISIBLE
    }

    private fun showResult(success: Boolean, rawText: String?) {
        binding.dividerResult.visibility = View.VISIBLE
        binding.layoutResult.visibility = View.VISIBLE

        if (success) {
            binding.tvResultStatus.text = "\u2713"
            binding.tvResultStatus.setBackgroundResource(R.drawable.bg_step_done)
            binding.tvResultStatus.setTextColor(getColor(R.color.white))
            binding.tvResult.text = getString(R.string.ussd_result_success)
            binding.tvResult.setTextColor(getColor(R.color.accent_green))
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_success)
        } else {
            binding.tvResultStatus.text = "!"
            binding.tvResultStatus.setBackgroundResource(R.drawable.bg_step_active)
            binding.tvResultStatus.setTextColor(getColor(R.color.white))
            binding.tvResult.text = getString(R.string.ussd_result_failure)
            binding.tvResult.setTextColor(getColor(R.color.accent_amber))
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_failure)
        }

        // Show the raw USSD response text for reference
        if (!rawText.isNullOrBlank()) {
            // Clean up: remove button text like "OK" from the raw text
            val cleaned = rawText.lines()
                .filter { it.trim().lowercase() != "ok" && it.trim().lowercase() != "cancel" }
                .joinToString("\n").trim()
            if (cleaned.isNotBlank()) {
                binding.tvResultDetails.text = cleaned
                binding.tvResultDetails.visibility = View.VISIBLE
            }
        }
    }

    private fun setStepPending(indicator: TextView, number: String) {
        indicator.text = number
        indicator.setBackgroundResource(R.drawable.bg_step_pending)
        indicator.setTextColor(getColor(R.color.text_secondary))
    }

    private fun setStepActive(indicator: TextView) {
        indicator.text = "..."
        indicator.setBackgroundResource(R.drawable.bg_step_active)
        indicator.setTextColor(getColor(R.color.white))
    }

    private fun setStepDone(indicator: TextView) {
        indicator.text = "\u2713" // checkmark
        indicator.setBackgroundResource(R.drawable.bg_step_done)
        indicator.setTextColor(getColor(R.color.white))
    }
}
