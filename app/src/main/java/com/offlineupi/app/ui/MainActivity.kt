package com.offlineupi.app.ui

import com.offlineupi.app.util.applySystemBarInsets

import android.Manifest
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.offlineupi.app.R
import com.offlineupi.app.data.Transaction
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.data.storedName
import com.offlineupi.app.databinding.ActivityMainBinding
import com.offlineupi.app.util.ContactsHelper
import com.offlineupi.app.util.formatMobileForDisplay
import com.offlineupi.app.worker.DailyBalanceCheckWorker
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Hosts the four tabs in a ViewPager2 so they are swipeable; the bottom
 * nav stays fixed and only tab content moves.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var currentTab = R.id.nav_pay

    private val tabOrder = listOf(R.id.nav_pay, R.id.nav_portfolio, R.id.nav_money, R.id.nav_more)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        DailyBalanceCheckWorker.schedule(this)
        TransactionStore(this).deduplicateByRrn()

        // Contact names + photos across the app; lookups no-op until granted.
        if (!ContactsHelper.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }

        binding.tabPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = tabOrder.size
            override fun createFragment(position: Int): Fragment = when (position) {
                1 -> PortfolioFragment()
                2 -> MoneyFragment()
                3 -> MoreFragment()
                else -> HomeFragment()
            }
        }
        binding.tabPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    currentTab = tabOrder[position]
                    binding.bottomNav.menu.findItem(currentTab)?.isChecked = true
                    // The Pay tab slides the nav down while the keyboard is up;
                    // never let that offset leak into the other tabs.
                    binding.bottomNav.translationY = 0f
                }
            }
        )
        binding.bottomNav.setOnItemSelectedListener { item ->
            binding.tabPager.setCurrentItem(tabOrder.indexOf(item.itemId), true)
            true
        }

        // Back from a secondary tab returns to Pay; back from Pay exits.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.tabPager.currentItem != 0) {
                    binding.tabPager.setCurrentItem(0, true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    fun bottomNav(): BottomNavigationView = binding.bottomNav

    /** Switch to the Bank tab programmatically (e.g. from the balance pill). */
    fun openBankTab() {
        binding.tabPager.setCurrentItem(tabOrder.indexOf(R.id.nav_money), true)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONTACTS) ContactsHelper.invalidate()
    }

    companion object {
        private const val REQ_CONTACTS = 41
        const val MAX_FEED = 20
        const val MAX_RECENTS = 5

        private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val dayFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

        fun dayLabel(ts: Long): String {
            val cal = Calendar.getInstance()
            val today = cal.get(Calendar.DAY_OF_YEAR); val year = cal.get(Calendar.YEAR)
            cal.timeInMillis = ts
            return when {
                cal.get(Calendar.YEAR) == year && cal.get(Calendar.DAY_OF_YEAR) == today -> "Today"
                cal.get(Calendar.YEAR) == year && cal.get(Calendar.DAY_OF_YEAR) == today - 1 -> "Yesterday"
                else -> dayFmt.format(Date(ts))
            }
        }

        fun timeLabel(ts: Long): String = timeFmt.format(Date(ts))

        private val avatarBgs = intArrayOf(
            R.drawable.bg_avatar_1e4032, R.drawable.bg_avatar_1d3a4e,
            R.drawable.bg_avatar_43305a, R.drawable.bg_avatar_4e3a20
        )
        fun avatarBgFor(key: String): Int =
            avatarBgs[(Math.abs(key.hashCode())) % avatarBgs.size]

        fun initials(name: String?, address: String?): String {
            val clean = name?.takeIf { it.isNotBlank() && it != "Unknown Payee" }
            if (clean != null) {
                val parts = clean.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                val s = when {
                    parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}"
                    parts.isNotEmpty() -> parts[0].take(2)
                    else -> ""
                }.uppercase()
                if (s.isNotBlank()) return s
            }
            return address?.firstOrNull { it.isLetter() }?.uppercase() ?: "#"
        }

        fun displayName(t: Transaction): String {
            t.storedName?.let { return it }
            val addr = t.payeeAddress ?: return "Payment"
            return if (addr.contains('@')) addr else "+91 ${formatMobileForDisplay(addr)}"
        }
    }
}
