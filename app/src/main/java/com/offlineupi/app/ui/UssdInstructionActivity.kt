package com.offlineupi.app.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.offlineupi.app.R
import com.offlineupi.app.accessibility.UssdAccessibilityService
import com.offlineupi.app.databinding.ActivityUssdInstructionBinding

/**
 * Displays USSD payment status after *99# has been dialed.
 *
 * When the accessibility service is enabled, steps 1-5 are auto-filled
 * and the user only needs to enter their UPI PIN.
 * When disabled, shows manual step-by-step instructions.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUssdInstructionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val payeeAddress = intent.getStringExtra(EXTRA_PAYEE_ADDRESS) ?: ""
        val amount = intent.getStringExtra(EXTRA_AMOUNT)

        val autoFilling = UssdAccessibilityService.hasPending()
        setupInstructions(payeeAddress, amount, autoFilling)

        binding.btnDone.setOnClickListener {
            UssdAccessibilityService.clearPending()
            finishAffinity()
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }
    }

    private fun setupInstructions(payeeAddress: String, amount: String?, autoFilling: Boolean) {
        if (autoFilling) {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle_autofill)
            binding.tvStep1.text = getString(R.string.ussd_step1_autofill)
            binding.tvStep2.text = getString(R.string.ussd_step2_autofill, payeeAddress)
            binding.tvClipboardBadge.visibility = View.GONE
            binding.tvStep3.text = if (!amount.isNullOrBlank()) {
                getString(R.string.ussd_step3_autofill, amount)
            } else {
                getString(R.string.ussd_step3_no_amount)
            }
            binding.tvStep4.text = getString(R.string.ussd_step4)
        } else {
            binding.tvUssdSubtitle.text = getString(R.string.ussd_subtitle)
            binding.tvStep1.text = getString(R.string.ussd_step1)
            binding.tvStep2.text = getString(R.string.ussd_step2, payeeAddress)
            binding.tvClipboardBadge.visibility = View.VISIBLE
            binding.tvClipboardBadge.text = getString(R.string.ussd_clipboard_copied_badge)
            binding.tvStep3.text = if (!amount.isNullOrBlank()) {
                getString(R.string.ussd_step3_with_amount, amount)
            } else {
                getString(R.string.ussd_step3_no_amount)
            }
            binding.tvStep4.text = getString(R.string.ussd_step4)
        }

        binding.tvDisclaimer.text = getString(R.string.ussd_sms_disclaimer)
        binding.tvSecurityNote.text = getString(R.string.ussd_security_note)
    }
}
