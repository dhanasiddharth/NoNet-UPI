package com.offlineupi.app.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.offlineupi.app.R
import com.offlineupi.app.databinding.FragmentMoreBinding
import com.offlineupi.app.util.ThemePref

/** "More" tab — links to transactions and recipients, plus app settings. */
class MoreFragment : Fragment() {

    private var _binding: FragmentMoreBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMoreBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.rowTransactions.setOnClickListener {
            startActivity(Intent(requireContext(), TransactionHistoryActivity::class.java))
        }
        binding.rowRecipients.setOnClickListener {
            startActivity(Intent(requireContext(), TransactionHistoryActivity::class.java))
        }
        binding.rowPriceUpdates.setOnClickListener {
            startActivity(Intent(requireContext(), PriceSyncSettingsActivity::class.java))
        }
        renderAppearance()
    }

    /** System / Light / Dark segmented control. Choosing one applies it live —
     *  AppCompat recreates the activity, which re-renders the selection. */
    private fun renderAppearance() {
        val seg = binding.segAppearance
        seg.removeAllViews()
        val current = ThemePref.get(requireContext())
        val opts = listOf(
            ThemePref.SYSTEM to "System", ThemePref.LIGHT to "Light", ThemePref.DARK to "Dark"
        )
        for ((mode, label) in opts) {
            val selected = mode == current
            val tv = TextView(requireContext()).apply {
                text = label
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, dp(11), 0, dp(11))
                setBackgroundResource(
                    if (selected) R.drawable.bg_pill_selected else R.drawable.bg_input_pill
                )
                setTextColor(requireContext().getColor(
                    if (selected) R.color.accent_emerald_light else R.color.text_secondary
                ))
                setOnClickListener {
                    if (mode == current) return@setOnClickListener
                    ThemePref.set(requireContext(), mode)
                    // the resolved night mode may not change (e.g. System→Light
                    // while already light) — no recreate happens then, so
                    // re-render the selection instead of relying on it
                    renderAppearance()
                }
            }
            seg.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (mode != opts.last().first) marginEnd = dp(8) })
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
