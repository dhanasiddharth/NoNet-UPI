package com.offlineupi.app.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.offlineupi.app.BuildConfig
import com.offlineupi.app.R
import com.offlineupi.app.databinding.ActivityAboutBinding

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        binding.tvVersion.text = getString(R.string.about_version_label) + " " + BuildConfig.VERSION_NAME
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
