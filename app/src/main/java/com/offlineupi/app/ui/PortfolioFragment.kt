package com.offlineupi.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.offlineupi.app.databinding.FragmentPortfolioBinding

/** Money tab — placeholder shell for the Phase 2 portfolio (Yahoo Finance import). */
class PortfolioFragment : Fragment() {

    private var _binding: FragmentPortfolioBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPortfolioBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
