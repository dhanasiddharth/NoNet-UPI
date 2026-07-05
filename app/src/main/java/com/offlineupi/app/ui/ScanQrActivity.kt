package com.offlineupi.app.ui

import com.offlineupi.app.util.applySystemBarInsets

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.offlineupi.app.R
import com.offlineupi.app.camera.QrCodeAnalyzer
import com.offlineupi.app.databinding.ActivityScanQrBinding
import com.offlineupi.app.model.UpiPaymentData
import com.offlineupi.app.viewmodel.ScanQrViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Handles camera permission, starts CameraX preview, and uses ML Kit
 * offline barcode scanning to detect UPI QR codes.
 *
 * SECURITY: Camera feed is not recorded or stored. Only the decoded string is processed.
 */
class ScanQrActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanQrBinding
    private val viewModel: ScanQrViewModel by viewModels()
    private lateinit var cameraExecutor: ExecutorService
    private var hasNavigated = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                showError(getString(R.string.error_camera_permission_denied))
                binding.tvPermissionMessage.text = getString(R.string.error_camera_permission_denied)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanQrBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()


        observeViewModel()
        checkCameraPermission()
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED -> startCamera()

            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                binding.tvPermissionMessage.text = getString(R.string.camera_permission_rationale)
                requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            }

            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        binding.tvPermissionMessage.text = getString(R.string.scan_instruction)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, QrCodeAnalyzer { rawQr ->
                        runOnUiThread {
                            viewModel.processQrResult(rawQr)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) {
                showError(getString(R.string.error_camera_unavailable))
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun observeViewModel() {
        viewModel.scanState.observe(this) { state ->
            when (state) {
                is ScanQrViewModel.ScanState.Idle -> Unit

                is ScanQrViewModel.ScanState.Success -> {
                    if (!hasNavigated) {
                        hasNavigated = true
                        navigateToConfirmation(state.data)
                    }
                }

                is ScanQrViewModel.ScanState.Error -> {
                    showError(state.message)
                }
            }
        }
    }

    private fun navigateToConfirmation(data: UpiPaymentData) {
        val intent = Intent(this, ConfirmationActivity::class.java).apply {
            putExtra(ConfirmationActivity.EXTRA_PAYEE_ADDRESS, data.payeeAddress)
            putExtra(ConfirmationActivity.EXTRA_PAYEE_NAME, data.payeeName)
            putExtra(ConfirmationActivity.EXTRA_AMOUNT, data.amount)
        }
        startActivity(intent)
        // Reset so user can scan again if they come back
        viewModel.reset()
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
        viewModel.reset()
    }

    override fun onResume() {
        super.onResume()
        hasNavigated = false
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
