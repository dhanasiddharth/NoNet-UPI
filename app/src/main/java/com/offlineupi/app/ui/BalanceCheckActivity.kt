package com.offlineupi.app.ui

import com.offlineupi.app.util.applySystemBarInsets

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offlineupi.app.R
import com.offlineupi.app.accessibility.UssdAccessibilityService
import com.offlineupi.app.data.AccountBalance
import com.offlineupi.app.data.AccountBalanceStore
import com.offlineupi.app.data.AccountStore
import com.offlineupi.app.data.PinStore
import com.offlineupi.app.data.SecureBalanceStore
import com.offlineupi.app.sms.SmsBroadcastReceiver
import com.offlineupi.app.databinding.ActivityBalanceCheckBinding
import com.offlineupi.app.util.formatIndianNumber

/**
 * Displays USSD balance check progress after *99# has been dialed.
 *
 * Listens for step completion broadcasts from UssdAccessibilityService
 * and updates each step's status indicator in real time.
 *
 * When the balance is received, it is stored securely using EncryptedSharedPreferences.
 *
 * SECURITY:
 * - No UPI PIN is displayed, suggested, or captured.
 * - Balance is stored encrypted on-device only.
 */
class BalanceCheckActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBalanceCheckBinding
    private lateinit var balanceStore: SecureBalanceStore
    private lateinit var accountStore: AccountStore
    private lateinit var accountBalanceStore: AccountBalanceStore

    private val callPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                dialUssd99()
            } else {
                Toast.makeText(this, getString(R.string.toast_dial_fallback), Toast.LENGTH_LONG).show()
                fallbackDialer()
            }
        }

    private val stepReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val step = intent.getIntExtra(UssdAccessibilityService.EXTRA_STEP, -1)
            val resultText = intent.getStringExtra(UssdAccessibilityService.EXTRA_RESULT_TEXT)
            val accountNumber = intent.getStringExtra(UssdAccessibilityService.EXTRA_ACCOUNT_NUMBER)
            val bankName = intent.getStringExtra(UssdAccessibilityService.EXTRA_BANK_NAME)
            if (accountNumber != null) {
                accountStore.saveAccountNumber(accountNumber)
                if (bankName != null) accountStore.saveBankName(bankName)
                showAccountNumber(accountNumber, bankName)
            }
            onStepCompleted(step, resultText)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBalanceCheckBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        balanceStore = SecureBalanceStore(this)
        accountStore = AccountStore(this)
        accountBalanceStore = AccountBalanceStore(this)

        setupSteps()

        binding.btnRetry.setOnClickListener { retryBalanceCheck() }
        binding.btnDone.setOnClickListener {
            UssdAccessibilityService.clearPending()
            finish()
        }

        startBalanceCheck()
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

    private fun startBalanceCheck() {
        // Collect PIN before proceeding (skips dialog if already stored this session)
        PinEntryDialog.ensurePin(this) {
            UssdAccessibilityService.setPendingBalanceCheck()

            if (!isAccessibilityServiceEnabled()) {
                promptEnableAccessibility()
            } else {
                proceedWithDial()
            }
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

    private fun promptEnableAccessibility() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.accessibility_prompt_title))
            .setMessage(getString(R.string.accessibility_prompt_message))
            .setPositiveButton(getString(R.string.accessibility_prompt_enable)) { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton(getString(R.string.accessibility_prompt_skip)) { _, _ ->
                proceedWithDial()
            }
            .setCancelable(false)
            .show()
    }

    private fun proceedWithDial() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            dialUssd99()
        } else {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun setupSteps() {
        binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle)

        binding.tvStep1.text = getString(R.string.balance_step1_pending)
        setStepPending(binding.tvStep1Status, "1")

        binding.tvStep2.text = getString(R.string.balance_step2_pending)
        setStepPending(binding.tvStep2Status, "2")

        binding.tvStep3.text = getString(R.string.balance_step3_pending)
        setStepPending(binding.tvStep3Status, "3")
    }

    private fun onStepCompleted(step: Int, resultText: String? = null) {
        when (step) {
            UssdAccessibilityService.STEP_BALANCE_WELCOME -> {
                setStepActive(binding.tvStep1Status)
            }
            UssdAccessibilityService.STEP_BALANCE_SELECTED -> {
                setStepDone(binding.tvStep1Status)
                binding.tvStep1.text = getString(R.string.balance_step1_done)
                setStepActive(binding.tvStep2Status)
                if (PinStore.hasPin()) {
                    binding.tvStep2.text = getString(R.string.balance_step2_autofill)
                    binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle_pin_autofill)
                } else {
                    binding.tvStep2.text = getString(R.string.balance_step2_ready)
                    binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle_pin)
                }
            }
            UssdAccessibilityService.STEP_BALANCE_PIN_PROMPT -> {
                if (PinStore.hasPin()) {
                    setStepDone(binding.tvStep2Status)
                    binding.tvStep2.text = getString(R.string.balance_step2_autofilled)
                    binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle_pin_autofill)
                    setStepActive(binding.tvStep3Status)
                } else {
                    setStepActive(binding.tvStep2Status)
                    binding.tvStep2.text = getString(R.string.balance_step2_ready)
                    binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle_pin)
                }
            }
            UssdAccessibilityService.STEP_BALANCE_RESULT -> {
                setStepDone(binding.tvStep2Status)
                binding.tvStep2.text = getString(R.string.balance_step2_done)
                setStepDone(binding.tvStep3Status)
                binding.tvStep3.text = getString(R.string.balance_step3_done)

                showBalanceResult(resultText)
                binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle_success) + " (via USSD)"
            }
            UssdAccessibilityService.STEP_BALANCE_FAILURE -> {
                setStepDone(binding.tvStep2Status)
                binding.tvStep2.text = getString(R.string.balance_step2_done)
                setStepDone(binding.tvStep3Status)

                showBalanceError(resultText)
                binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle_failure)
                binding.btnRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun showAccountNumber(accountNumber: String, bankName: String?) {
        val label = if (bankName != null) "$bankName - $accountNumber" else "A/c $accountNumber"
        binding.tvBalanceSubtitle.text = label
    }

    private fun showBalanceResult(rawText: String?) {
        binding.dividerResult.visibility = View.VISIBLE
        binding.layoutBalanceResult.visibility = View.VISIBLE
        binding.layoutBalanceError.visibility = View.GONE

        val balanceText = parseBalance(rawText)
        binding.tvBalanceAmount.text = balanceText ?: rawText ?: "N/A"

        // Show raw USSD text if different from parsed balance
        if (rawText != null && balanceText != null) {
            val cleaned = cleanUssdText(rawText)
            if (cleaned.isNotBlank() && cleaned != balanceText) {
                binding.tvBalanceRaw.text = cleaned
                binding.tvBalanceRaw.visibility = View.VISIBLE
            }
        }

        // Store balance securely
        val toStore = balanceText ?: rawText
        if (toStore != null) {
            balanceStore.saveBalance(toStore)

            // Also store in AccountBalanceStore for the overview page
            val accountNumber = accountStore.getAccountNumber()
            if (accountNumber != null) {
                val rawAmount = toStore.removePrefix("\u20B9").replace(",", "").trim()
                val bankName = accountStore.getBankName()
                val last4 = AccountBalanceStore.normalizeAccount(accountNumber)
                accountBalanceStore.updateBalances(listOf(
                    AccountBalance(
                        accountNumber = "XX$last4",
                        balance = rawAmount,
                        bankName = bankName,
                        timestamp = System.currentTimeMillis()
                    )
                ))
                sendBroadcast(
                    Intent(SmsBroadcastReceiver.ACTION_BALANCES_UPDATED)
                        .setPackage(packageName)
                )
            }
        }
    }

    private fun showBalanceError(rawText: String?) {
        binding.dividerResult.visibility = View.VISIBLE
        binding.layoutBalanceError.visibility = View.VISIBLE
        binding.layoutBalanceResult.visibility = View.GONE
        binding.tvStep3.text = getString(R.string.balance_step3_error)

        val cleaned = if (rawText != null) cleanUssdText(rawText) else null
        binding.tvErrorText.text = cleaned ?: getString(R.string.balance_error_generic)
    }

    /**
     * Attempts to extract a rupee amount from the USSD response text.
     * Looks for patterns like "Rs.1234.56", "INR 1,234.56", "Balance: 1234.56", etc.
     */
    private fun parseBalance(text: String?): String? {
        if (text == null) return null
        val lower = text.lowercase()

        // Pattern: Rs/INR followed by amount
        val amountPattern = Regex(
            """(?:rs\.?|inr|balance[:\s]*(?:rs\.?|inr)?)\s*([0-9,]+(?:\.[0-9]{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        val match = amountPattern.find(lower)
        if (match != null) {
            val amount = match.groupValues[1].replace(",", "")
            return "\u20B9 ${formatIndianNumber(amount)}"
        }

        // Fallback: find any number that looks like a balance (4+ digits or has decimal)
        val numberPattern = Regex("""([0-9,]+\.[0-9]{2})""")
        val numMatch = numberPattern.find(text)
        if (numMatch != null) {
            val amount = numMatch.groupValues[1].replace(",", "")
            return "\u20B9 ${formatIndianNumber(amount)}"
        }

        return null
    }

    private fun cleanUssdText(text: String): String {
        val buttonWords = setOf("ok", "cancel", "send", "reply")
        return text.lines()
            .filter { it.trim().lowercase() !in buttonWords }
            .joinToString("\n").trim()
    }

    private fun retryBalanceCheck() {
        UssdAccessibilityService.clearPending()
        UssdAccessibilityService.setPendingBalanceCheck()

        setupSteps()
        binding.btnRetry.visibility = View.GONE
        binding.dividerResult.visibility = View.GONE
        binding.layoutBalanceResult.visibility = View.GONE
        binding.layoutBalanceError.visibility = View.GONE
        binding.tvBalanceSubtitle.text = getString(R.string.balance_subtitle_retrying)

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

    private fun fallbackDialer() {
        val uri = Uri.parse("tel:*99" + Uri.encode("#"))
        startActivity(Intent(Intent.ACTION_DIAL, uri))
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
        indicator.text = "\u2713"
        indicator.setBackgroundResource(R.drawable.bg_step_done)
        indicator.setTextColor(getColor(R.color.white))
    }
}
