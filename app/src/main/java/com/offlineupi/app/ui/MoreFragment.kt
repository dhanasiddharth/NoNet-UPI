package com.offlineupi.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.offlineupi.app.databinding.FragmentMoreBinding

/** "More" tab — links to transactions and recipients. */
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
