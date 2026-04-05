package com.offlineupi.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.offlineupi.app.R
import com.offlineupi.app.databinding.ActivityUssdInstructionBinding

/**
 * Displays step-by-step USSD instructions for completing the UPI payment
 * after the system dialer has been launched with a NUUP USSD code.
 *
 * Because the USSD code already encodes VPA, amount, and remarks
 * (*99*1*3*VPA*AMOUNT*1#), the user only needs to enter their UPI PIN.
 *
 * SECURITY:
 * - No UPI PIN is displayed, suggested, or captured.
 * - The UPI ID shown here is only for user reference; it is not stored.
 * - This screen makes NO network calls and has NO USSD automation.
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

        setupInstructions(payeeAddress, amount)

        binding.btnDone.setOnClickListener {
            finishAffinity()
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }
    }

    private fun setupInstructions(payeeAddress: String, amount: String?) {
        // Step 1: USSD code auto-sent
        binding.tvStep1.text = getString(R.string.ussd_step1)

        // Step 2: VPA was encoded in the USSD code
        binding.tvStep2.text = getString(R.string.ussd_step2, payeeAddress)

        // Hide clipboard badge — no longer copying to clipboard
        binding.tvClipboardBadge.visibility = android.view.View.GONE

        // Step 3: Amount
        binding.tvStep3.text = if (!amount.isNullOrBlank()) {
            getString(R.string.ussd_step3_with_amount, amount)
        } else {
            getString(R.string.ussd_step3_no_amount)
        }

        // Step 4: UPI PIN
        binding.tvStep4.text = getString(R.string.ussd_step4)

        // Disclaimer & security note
        binding.tvDisclaimer.text = getString(R.string.ussd_sms_disclaimer)
        binding.tvSecurityNote.text = getString(R.string.ussd_security_note)
    }
}
