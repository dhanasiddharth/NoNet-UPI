package com.offlineupi.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.offlineupi.app.R
import com.offlineupi.app.data.SecureBalanceStore
import com.offlineupi.app.data.Transaction
import com.offlineupi.app.data.TransactionStore
import com.offlineupi.app.data.storedName
import com.offlineupi.app.databinding.FragmentHomeBinding
import com.offlineupi.app.util.ContactsHelper
import com.offlineupi.app.util.formatIndianNumber
import com.offlineupi.app.util.normalizeIndianMobile

/** Pay tab — ledger feed on top, pay dock at the bottom. */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: TransactionStore
    private lateinit var balanceStore: SecureBalanceStore
    private var balanceVisible = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = TransactionStore(requireContext())
        balanceStore = SecureBalanceStore(requireContext())

        binding.rvFeed.layoutManager = LinearLayoutManager(requireContext())
        // rvRecents' layout manager is set in loadRecents() once the fit count
        // is known (single row that fills the width, no scroll).

        binding.btnScanQr.setOnClickListener {
            startActivity(Intent(requireContext(), ScanQrActivity::class.java))
        }
        binding.btnToId.setOnClickListener { expandPayeeInput() }
        binding.btnPayGo.setOnClickListener { submitPayeeInput() }
        binding.btnKbToggle.setOnClickListener { toggleKeyboardMode() }
        binding.etPayeeInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) { submitPayeeInput(); true } else false
        }

        setupImeChoreography()
    }

    override fun onResume() {
        super.onResume()
        refreshBalance()
        loadFeed()
        loadRecents()
        resetDockState()
    }

    /** Restore the dock to a consistent state when returning from another screen. */
    private fun resetDockState() {
        imeAnimating = false
        wasImeVisible = false
        morphAnimator?.cancel()
        if (!inputExpanded) {
            binding.etPayeeInput.clearFocus()
            binding.dock.requestFocus()
        }
        binding.payRow.post { setPayRowMorph(if (inputExpanded) 1f else 0f) }
        ViewCompat.getRootWindowInsets(binding.root)?.let { syncWithIme(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ---- IME choreography ----
    // The host's bottom nav slides down beneath the keyboard while the pay dock
    // grows like a bottom sheet, tracked frame-by-frame with the IME animation.
    private var imeAnimating = false
    private var wasImeVisible = false
    private var dockBasePadBottom = 0

    private fun syncWithIme(insets: WindowInsetsCompat) {
        val b = _binding ?: return
        val nav = (activity as? MainActivity)?.bottomNav() ?: return
        val imeH = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
        val navBarH = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
        nav.translationY = imeH.toFloat().coerceAtMost((nav.height + navBarH).toFloat())
        val grow = (imeH - navBarH - nav.height).coerceAtLeast(0)
        b.dock.setPadding(
            b.dock.paddingLeft, b.dock.paddingTop,
            b.dock.paddingRight, dockBasePadBottom + grow
        )
        // Collapse only on an actual keyboard hide (visible -> hidden), never on
        // the insets passes that fire before the show animation begins.
        if (imeH == 0 && wasImeVisible && inputExpanded
            && b.etPayeeInput.text.isNullOrEmpty()
        ) {
            collapsePayeeInput()
        }
        wasImeVisible = imeH > 0
    }

    private fun setupImeChoreography() {
        dockBasePadBottom = binding.dock.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.dock) { _, insets ->
            if (!imeAnimating) syncWithIme(insets)
            insets
        }
        ViewCompat.setWindowInsetsAnimationCallback(
            binding.dock,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    imeAnimating = true
                }
                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    syncWithIme(insets)
                    return insets
                }
                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    imeAnimating = false
                    _binding?.let { b ->
                        ViewCompat.getRootWindowInsets(b.root)?.let { syncWithIme(it) }
                    }
                }
            }
        )
    }

    // ---- Balance pill ----
    private fun refreshBalance() {
        val bal = balanceStore.getBalance()
        if (bal.isNullOrBlank()) {
            binding.tvBalancePill.text = "Check balance"
            binding.ivBalanceEye.visibility = View.GONE
            binding.balancePill.setOnClickListener {
                (requireActivity() as MainActivity).openBankTab()
            }
        } else {
            binding.ivBalanceEye.visibility = View.VISIBLE
            renderBalance(bal)
            binding.balancePill.setOnClickListener { toggleBalance() }
        }
    }

    private fun renderBalance(bal: String) {
        binding.tvBalancePill.text = if (balanceVisible) bal else "•••••"
        binding.ivBalanceEye.setImageResource(
            if (balanceVisible) R.drawable.ic_visibility else R.drawable.ic_visibility_off
        )
    }

    private fun toggleBalance() {
        val bal = balanceStore.getBalance() ?: return
        balanceVisible = !balanceVisible
        renderBalance(bal)
    }

    // ---- Payee input: in-place width morph ----
    // Scan QR shrinks to a 52dp square (corners intact, label hidden), the pill
    // collapses as the input grows into its place, Pay grows in at the end.
    private var inputExpanded = false
    private var morphAnimator: ValueAnimator? = null

    private fun setPayRowMorph(p: Float) {
        val b = _binding ?: return
        val rowW = b.payRow.width - b.payRow.paddingLeft - b.payRow.paddingRight
        if (rowW <= 0) return
        val gap = dp(8)
        val scanFull = (rowW - gap) / 2
        val scanSmall = dp(52)
        val payW = dp(76)
        val toggleW = dp(48)

        fun lerp(a: Int, c: Int) = (a + (c - a) * p).toInt()

        val scanW = lerp(scanFull, scanSmall)
        val pillW = lerp(scanFull, 0)
        val inputW = lerp(0, rowW - scanSmall - toggleW - payW - gap * 3)
        val payWNow = lerp(0, payW)
        val toggleWNow = lerp(0, toggleW)

        fun setW(v: View, w: Int, endGap: Int) {
            val lp = v.layoutParams as LinearLayout.LayoutParams
            lp.width = w; lp.weight = 0f; lp.marginEnd = endGap
            v.layoutParams = lp
        }
        setW(b.btnScanQr, scanW, gap)
        setW(b.btnToId, pillW, 0)
        setW(b.etPayeeInput, inputW, if (toggleWNow > 0) gap else 0)
        setW(b.btnKbToggle, toggleWNow, if (payWNow > 0) gap else 0)
        setW(b.btnPayGo, payWNow, 0)

        // Labels hide as soon as the shrink starts; the QR icon centers in the square.
        val shrinking = p > 0.05f
        b.btnScanQr.text = if (shrinking) "" else "Scan QR"
        b.btnScanQr.iconPadding = if (shrinking) 0 else dp(6)
        if (shrinking) {
            b.btnToId.text = ""
            b.btnToId.setCompoundDrawablesRelative(null, null, null, null)
        } else if (b.btnToId.text.isNullOrEmpty()) {
            b.btnToId.text = "Number / UPI ID"
            b.btnToId.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_dialpad, 0, 0, 0
            )
        }
    }

    private fun animateMorph(to: Float, onDone: (() -> Unit)? = null) {
        morphAnimator?.cancel()
        val from = if (to == 1f) 0f else 1f
        morphAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 220
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { setPayRowMorph(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onDone?.invoke()
                }
            })
            start()
        }
    }

    /** Phone keypad by default (most payees are numbers); ABC switches to text for UPI IDs. */
    private fun toggleKeyboardMode() {
        val b = _binding ?: return
        val toPhone = b.etPayeeInput.inputType != InputType.TYPE_CLASS_PHONE
        b.etPayeeInput.inputType = if (toPhone) {
            InputType.TYPE_CLASS_PHONE
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        b.etPayeeInput.imeOptions = EditorInfo.IME_ACTION_GO
        b.btnKbToggle.text = if (toPhone) "ABC" else "123"
        b.etPayeeInput.setSelection(b.etPayeeInput.text?.length ?: 0)
        requireContext().getSystemService(InputMethodManager::class.java)
            .restartInput(b.etPayeeInput)
    }

    private fun expandPayeeInput() {
        if (inputExpanded) return
        inputExpanded = true
        // Always start in number mode — the common case is a phone number.
        binding.etPayeeInput.inputType = InputType.TYPE_CLASS_PHONE
        binding.etPayeeInput.imeOptions = EditorInfo.IME_ACTION_GO
        binding.btnKbToggle.text = "ABC"
        animateMorph(1f)
        // requestFocus() refuses zero-sized views (targetSdk P+), and the input
        // has width 0 until the morph grows it — so focus must wait for the
        // first layout pass that gives the field real width. Only then show the
        // IME, so the keyboard binds the field instead of a null editor.
        binding.etPayeeInput.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or2: Int, ob: Int
            ) {
                if (v.width <= 0) return
                v.removeOnLayoutChangeListener(this)
                v.requestFocus()
                activity?.window?.let { w ->
                    WindowCompat.getInsetsController(w, v)
                        .show(WindowInsetsCompat.Type.ime())
                }
            }
        })
    }

    private fun collapsePayeeInput() {
        if (!inputExpanded) return
        inputExpanded = false
        binding.etPayeeInput.text?.clear()
        binding.etPayeeInput.clearFocus()
        binding.dock.requestFocus()
        animateMorph(0f)
    }

    private fun submitPayeeInput() {
        val raw = binding.etPayeeInput.text?.toString().orEmpty().trim()
        if (raw.isEmpty()) return
        requireContext().getSystemService(InputMethodManager::class.java)
            .hideSoftInputFromWindow(binding.etPayeeInput.windowToken, 0)
        collapsePayeeInput()
        startPayment(raw)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startPayment(raw: String, presetName: String? = null) {
        if (raw.isEmpty()) return
        val intent = Intent(requireContext(), ConfirmationActivity::class.java)
        if (raw.contains('@')) {
            intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_ADDRESS, raw)
            intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_TYPE, ConfirmationActivity.TYPE_VPA)
        } else {
            val normalized = normalizeIndianMobile(raw) ?: run {
                MaterialAlertDialogBuilder(requireContext())
                    .setMessage("Enter a valid 10-digit phone number or a UPI ID (with @).")
                    .setPositiveButton("OK", null).show()
                return
            }
            intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_ADDRESS, normalized)
            intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_TYPE, ConfirmationActivity.TYPE_PHONE)
        }
        presetName?.let { intent.putExtra(ConfirmationActivity.EXTRA_PAYEE_NAME, it) }
        startActivity(intent)
    }

    // ---- Quick-pay row: favourites pinned first, then places paid ≥2 times ----
    // One row that fills the width (no horizontal scroll); capped to what fits
    // so nothing scrolls off.
    private fun loadRecents() {
        val payments = store.getTransactions()
            .filter { it.type == "payment" && it.payeeAddress != null }
        val successful = payments.filter { it.status == "success" }
        val byPayee = successful.groupBy { it.payeeAddress!! }
        val favSet = com.offlineupi.app.data.PayeeMeta.favourites(requireContext())

        fun latest(txns: List<Transaction>) = txns.maxByOrNull { it.timestamp }!!
        // a favourite stays pinned even with no successful payment yet
        val pinned = favSet.mapNotNull { addr ->
            (byPayee[addr] ?: payments.filter { it.payeeAddress == addr }.ifEmpty { null })
                ?.let { latest(it) to it.size }
        }.sortedByDescending { it.second }.map { it.first }
        val frequent = pinned + byPayee
            .filterKeys { it !in favSet }
            .filter { it.value.size >= 2 }               // one-off places don't qualify
            .map { (_, txns) -> latest(txns) to txns.size }
            .sortedWith(compareByDescending<Pair<Transaction, Int>> { it.second } // most-paid first
                .thenByDescending { it.first.timestamp })  // ties → most recent
            .map { it.first }

        if (frequent.isEmpty()) {
            binding.rvRecents.visibility = View.GONE
            return
        }
        binding.rvRecents.visibility = View.VISIBLE

        // how many 72dp cells fit the row; then fill exactly that width
        val rowWidth = resources.displayMetrics.widthPixels -
            (40 * resources.displayMetrics.density).toInt()
        val maxFit = (rowWidth / (72 * resources.displayMetrics.density)).toInt().coerceIn(3, 7)
        val span = frequent.size.coerceIn(1, maxFit)
        val shown = frequent.take(span)

        binding.rvRecents.layoutManager = GridLayoutManager(requireContext(), span)
        binding.rvRecents.adapter = RecentsAdapter(shown, onLongClick = { txn ->
            PayeeEdit.show(requireContext(), txn.payeeAddress ?: return@RecentsAdapter,
                txn.storedName) { loadRecents(); loadFeed() }
        }) { txn ->
            startPayment(txn.payeeAddress ?: return@RecentsAdapter, txn.payeeName)
        }
    }

    // ---- Ledger feed (capped; "View all" opens the full searchable list) ----
    private fun loadFeed() {
        val payments = store.getTransactions().filter { it.type == "payment" }
        binding.emptyState.visibility = if (payments.isEmpty()) View.VISIBLE else View.GONE
        val items = buildFeedItems(payments.take(MainActivity.MAX_FEED), payments).toMutableList()
        if (payments.size > MainActivity.MAX_FEED) items.add(FeedItem.ViewAll)
        binding.rvFeed.adapter = FeedAdapter(items, onViewAll = {
            startActivity(Intent(requireContext(), TransactionHistoryActivity::class.java))
        }) { txn ->
            startActivity(Intent(requireContext(), TransactionReceiptActivity::class.java).apply {
                putExtra(TransactionReceiptActivity.EXTRA_TRANSACTION_ID, txn.id)
            })
        }
    }
}

// ---------- Feed adapter (day headers + rows, shared with the Transactions screen) ----------
sealed class FeedItem {
    data class Header(val label: String, val total: String?) : FeedItem()
    data class Row(val txn: Transaction) : FeedItem()
    object ViewAll : FeedItem()
}

internal fun buildFeedItems(
    txns: List<Transaction>,
    totalsFrom: List<Transaction> = txns,   // full history — capped/filtered
                                            // lists would under-report the day
): List<FeedItem> {
    // per-day spend on the header — verified payments only, so failed and
    // refunded attempts don't inflate the day
    fun spentOn(day: String): String? {
        val total = totalsFrom.filter {
            MainActivity.dayLabel(it.timestamp) == day && it.status == "success"
        }.sumOf { it.amount?.replace(",", "")?.toDoubleOrNull() ?: 0.0 }
        if (total < 0.005) return null
        val s = if (total % 1.0 < 0.005) "%.0f".format(total) else "%.2f".format(total)
        return "₹" + formatIndianNumber(s)
    }
    val items = mutableListOf<FeedItem>()
    var lastDay: String? = null
    for (t in txns) {
        val day = MainActivity.dayLabel(t.timestamp)
        if (day != lastDay) { items.add(FeedItem.Header(day, spentOn(day))); lastDay = day }
        items.add(FeedItem.Row(t))
    }
    return items
}

internal class FeedAdapter(
    private val items: List<FeedItem>,
    private val onViewAll: (() -> Unit)? = null,
    private val onClick: (Transaction) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemViewType(position: Int) = when (items[position]) {
        is FeedItem.Header -> 0
        is FeedItem.Row -> 1
        FeedItem.ViewAll -> 2
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> HeaderVH(inf.inflate(R.layout.item_day_header, parent, false))
            2 -> ViewAllVH(inf.inflate(R.layout.item_view_all, parent, false))
            else -> RowVH(inf.inflate(R.layout.item_transaction, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FeedItem.Header -> (holder as HeaderVH).bind(item)
            is FeedItem.Row -> (holder as RowVH).bind(item.txn, onClick)
            FeedItem.ViewAll -> holder.itemView.setOnClickListener { onViewAll?.invoke() }
        }
    }

    override fun getItemCount() = items.size

    class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
        private val day: TextView = v.findViewById(R.id.tvDayHeader)
        private val total: TextView = v.findViewById(R.id.tvDayTotal)
        fun bind(item: FeedItem.Header) {
            day.text = item.label
            total.text = item.total ?: ""
        }
    }

    class ViewAllVH(v: View) : RecyclerView.ViewHolder(v)

    class RowVH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: TextView = v.findViewById(R.id.tvAvatar)
        private val payee: TextView = v.findViewById(R.id.tvPayee)
        private val sub: TextView = v.findViewById(R.id.tvDateTime)
        private val amount: TextView = v.findViewById(R.id.tvAmount)
        private val chip: TextView = v.findViewById(R.id.tvStatusChip)

        fun bind(t: Transaction, onClick: (Transaction) -> Unit) {
            val ctx = itemView.context
            avatar.text = MainActivity.initials(t.payeeName, t.payeeAddress)
            avatar.setBackgroundResource(MainActivity.avatarBgFor(t.payeeAddress ?: t.id))
            val contactName = ContactsHelper.applyPhotoToAvatar(avatar, t.payeeAddress)
            payee.text = com.offlineupi.app.data.PayeeMeta.label(ctx, t.payeeAddress)
                ?: t.storedName ?: contactName ?: MainActivity.displayName(t)
            sub.text = MainActivity.timeLabel(t.timestamp)

            // the feed is payments-only, so no minus sign — every row is money out
            amount.text = t.amount?.let { "₹${formatIndianNumber(it)}" } ?: "—"
            amount.setTextColor(ctx.getColor(R.color.text_primary))

            val (label, bg, color) = when (t.status) {
                "success" -> Triple("VERIFIED", R.drawable.bg_chip_verified, R.color.accent_green)
                "reversed" -> Triple("REVERSED", R.drawable.bg_chip_reversed, R.color.accent_amber)
                "pending" -> Triple("CHECKING", R.drawable.bg_chip_checking, R.color.status_progress)
                else -> Triple("FAILED", R.drawable.bg_chip_failed, R.color.accent_red)
            }
            val icon = when (t.status) {
                "success" -> R.drawable.ic_status_check
                "reversed" -> R.drawable.ic_status_reverse
                "pending" -> R.drawable.ic_status_pending
                else -> R.drawable.ic_status_fail
            }
            chip.text = label
            chip.setBackgroundResource(bg)
            chip.setTextColor(ctx.getColor(color))
            chip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(
                chip, ColorStateList.valueOf(ctx.getColor(color))
            )
            chip.compoundDrawablePadding = (4 * ctx.resources.displayMetrics.density).toInt()

            itemView.setOnClickListener { onClick(t) }
        }
    }
}

// ---------- Recents adapter ----------
internal class RecentsAdapter(
    private val items: List<Transaction>,
    private val onLongClick: (Transaction) -> Unit = {},
    private val onClick: (Transaction) -> Unit
) : RecyclerView.Adapter<RecentsAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_recent, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(items[position], onClick, onLongClick)
    override fun getItemCount() = items.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val avatar: TextView = v.findViewById(R.id.tvRecentAvatar)
        private val name: TextView = v.findViewById(R.id.tvRecentName)
        fun bind(t: Transaction, onClick: (Transaction) -> Unit, onLongClick: (Transaction) -> Unit) {
            val ctx = itemView.context
            avatar.text = MainActivity.initials(t.payeeName, t.payeeAddress)
            avatar.setBackgroundResource(MainActivity.avatarBgFor(t.payeeAddress ?: t.id))
            val contactName = ContactsHelper.applyPhotoToAvatar(avatar, t.payeeAddress)
            // a user label wins whole; resolved names shorten to the first word
            val label = com.offlineupi.app.data.PayeeMeta.label(ctx, t.payeeAddress)
            name.text = label?.take(12)
                ?: (t.storedName ?: contactName ?: MainActivity.displayName(t))
                    .substringBefore(" ").take(10)
            itemView.setOnClickListener { onClick(t) }
            itemView.setOnLongClickListener { onLongClick(t); true }
        }
    }
}
