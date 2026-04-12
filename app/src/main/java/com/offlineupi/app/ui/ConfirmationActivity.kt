package com.offlineupi.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offlineupi.app.R
import com.offlineupi.app.accessibility.UssdAccessibilityService
import com.offlineupi.app.databinding.ActivityConfirmationBinding
import com.offlineupi.app.model.UpiPaymentData
import com.offlineupi.app.util.formatIndianNumber
import com.offlineupi.app.viewmodel.ConfirmationViewModel

/**
 * Confirmation screen showing parsed UPI payment details.
 * When the user taps "Pay via USSD (*99#)":
 *  A. Copies UPI ID to clipboard (auto-clears after 60s).
 *  B. Requests CALL_PHONE permission if not already granted.
 *  C. Auto-dials *99# using Intent.ACTION_CALL (falls back to ACTION_DIAL if denied).
 *  D. Navigates to UssdInstructionActivity with step-by-step guide.
 *
 * SECURITY:
 * - No UPI PIN is captured here.
 * - Payment data is NOT persisted to disk or sent over network.
 * - UPI ID is NOT logged.
 */
class ConfirmationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAYEE_ADDRESS = "extra_payee_address"
        const val EXTRA_PAYEE_NAME = "extra_payee_name"
        const val EXTRA_AMOUNT = "extra_amount"
    }

    private lateinit var binding: ActivityConfirmationBinding
    private val viewModel: ConfirmationViewModel by viewModels()

    // Holds the USSD string while waiting for permission result
    private var pendingPayeeAddress: String? = null
    private var pendingAmount: String? = null

    /** Launcher for CALL_PHONE runtime permission. */
    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val address = pendingPayeeAddress ?: return@registerForActivityResult
            val amount = pendingAmount
            navigateToUssdInstructions(address, amount)
            if (granted) {
                dialUssd99()
            } else {
                Toast.makeText(this, getString(R.string.toast_dial_fallback), Toast.LENGTH_LONG).show()
                fallbackDialer()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConfirmationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val payeeAddress = intent.getStringExtra(EXTRA_PAYEE_ADDRESS)
        val payeeName   = intent.getStringExtra(EXTRA_PAYEE_NAME)
        val amount      = intent.getStringExtra(EXTRA_AMOUNT)

        if (payeeAddress.isNullOrBlank()) { finish(); return }

        val data = UpiPaymentData(
            payeeAddress = payeeAddress,
            payeeName    = payeeName ?: "Unknown Payee",
            amount       = amount
        )
        viewModel.setPaymentData(data)
        setupUI(data)
        observeViewModel()
    }

    private fun setupUI(data: UpiPaymentData) {
        binding.tvPayeeName.text = data.payeeName
        binding.tvUpiId.text    = data.payeeAddress

        if (data.hasAmount) {
            binding.tvAmount.text      = getString(R.string.currency_format, formatIndianNumber(data.amount!!))
            binding.tvAmount.isVisible = true
            binding.tilAmountInput.isVisible = false
        } else {
            binding.tvAmount.isVisible = false
            binding.tilAmountInput.isVisible = true
            binding.etAmount.requestFocus()
            binding.etAmount.postDelayed({
                val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(binding.etAmount, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 200)
            binding.etAmount.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = viewModel.clearAmountError()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.btnPayUssd.setOnClickListener { handlePayment(data) }
    }

    private fun observeViewModel() {
        viewModel.amountError.observe(this) { error ->
            binding.tilAmountInput.error = error
        }
    }

    private fun handlePayment(data: UpiPaymentData) {
        val finalAmount: String? = if (data.hasAmount) {
            data.amount
        } else {
            viewModel.validateAndGetAmount(binding.etAmount.text.toString()) ?: return
        }

        val remarks = binding.etRemarks.text.toString().trim().ifBlank { null }

        // Collect PIN before proceeding (skips dialog if already stored this session)
        PinEntryDialog.ensurePin(this) {
            startPaymentFlow(data.payeeAddress, finalAmount, remarks)
        }
    }

    private fun startPaymentFlow(payeeAddress: String, amount: String?, remarks: String? = null) {
        // Copy UPI ID to clipboard as fallback
        copyToClipboard(payeeAddress)

        // Store pending values for the permission callback
        pendingPayeeAddress = payeeAddress
        pendingAmount       = amount

        // Set pending payment for auto-fill accessibility service
        UssdAccessibilityService.setPendingPayment(payeeAddress, amount, remarks)

        if (!isAccessibilityServiceEnabled()) {
            promptEnableAccessibility(payeeAddress, amount)
        } else {
            proceedWithDial(payeeAddress, amount)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${UssdAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun promptEnableAccessibility(payeeAddress: String, amount: String?) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.accessibility_prompt_title))
            .setMessage(getString(R.string.accessibility_prompt_message))
            .setPositiveButton(getString(R.string.accessibility_prompt_enable)) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(getString(R.string.accessibility_prompt_skip)) { _, _ ->
                proceedWithDial(payeeAddress, amount)
            }
            .setCancelable(false)
            .show()
    }

    private fun proceedWithDial(payeeAddress: String, amount: String?) {
        // Check / request CALL_PHONE and dial *99#
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            navigateToUssdInstructions(payeeAddress, amount)
            dialUssd99()
        } else {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    /**
     * Copies UPI ID to clipboard and schedules auto-clear after 60 seconds for privacy.
     */
    private fun copyToClipboard(vpa: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("UPI ID", vpa))
        Toast.makeText(this, getString(R.string.toast_upi_copied), Toast.LENGTH_SHORT).show()
        // Auto-clear clipboard after 60 seconds for privacy
        Handler(Looper.getMainLooper()).postDelayed({
            clipboard.clearPrimaryClip()
        }, 60_000)
    }

    /**
     * Dials *99# — the NUUP USSD entry point. The user then navigates
     * the interactive USSD menu (Send Money → UPI ID → paste VPA → enter amount).
     */
    private fun dialUssd99() {
        val uri = Uri.parse("tel:*99" + Uri.encode("#"))
        try {
            startActivity(Intent(Intent.ACTION_CALL, uri))
        } catch (e: Exception) {
            fallbackDialer()
        }
    }

    /**
     * Fallback: opens the dialer pre-filled with *99# when CALL_PHONE is denied.
     */
    private fun fallbackDialer() {
        val uri = Uri.parse("tel:*99" + Uri.encode("#"))
        startActivity(Intent(Intent.ACTION_DIAL, uri))
    }

    private fun navigateToUssdInstructions(payeeAddress: String, amount: String?) {
        startActivity(Intent(this, UssdInstructionActivity::class.java).apply {
            putExtra(UssdInstructionActivity.EXTRA_PAYEE_ADDRESS, payeeAddress)
            putExtra(UssdInstructionActivity.EXTRA_AMOUNT, amount)
            putExtra(UssdInstructionActivity.EXTRA_REMARKS, UssdAccessibilityService.pendingRemarks)
        })
    }
}
