package io.sanford.wormhole_william.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen QR scanner built on CameraX (Camera2) + ML Kit barcode scanning.
 *
 * This replaces the previous ZXing scanner, whose Camera1 pipeline failed to
 * decode on some modern high-resolution sensors (e.g. Samsung S22 Ultra): the
 * preview displayed but the decoder never locked onto the code.
 *
 * [onResult] is invoked at most once with the raw contents of the first QR code
 * detected. [onClose] is invoked when the user dismisses the scanner or denies
 * the camera permission.
 */
@Composable
fun QrScannerScreen(
    onResult: (String) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) onClose()
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (hasCameraPermission) {
            CameraPreview(
                onBarcode = onResult,
                onCameraError = {
                    Toast.makeText(
                        context,
                        "Unable to open the camera",
                        Toast.LENGTH_LONG
                    ).show()
                    onClose()
                },
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp)
            ) {
                Text(
                    text = "Point the camera at a wormhole QR code",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onBarcode: (String) -> Unit,
    onCameraError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // Single-thread executor so ML Kit analysis never runs on the main thread.
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    // Keep the latest callbacks without recreating the analyzer on recomposition.
    val currentOnBarcode = rememberUpdatedState(onBarcode)
    val currentOnCameraError = rememberUpdatedState(onCameraError)

    // Fire onBarcode at most once even though frames keep arriving until the
    // composable leaves the tree.
    val delivered = remember { AtomicBoolean(false) }
    // Set once the composable is disposed, so a camera-provider callback that
    // resolves after disposal does not bind a camera that nothing will unbind.
    val disposed = remember { AtomicBoolean(false) }

    // Owned here so it can be closed (releases ML Kit native resources).
    val analyzer = remember {
        BarcodeAnalyzer { value ->
            if (delivered.compareAndSet(false, true)) {
                // Deliver on the main thread, but skip if the scanner was
                // disposed (e.g. the user tapped Cancel) between detection and
                // this runnable executing, so a cancelled scan never sets a code.
                mainExecutor.execute {
                    if (!disposed.get()) currentOnBarcode.value(value)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            // Don't block the main thread: only unbind if the provider is ready.
            // If it isn't, the pending listener sees `disposed` and skips binding,
            // so there is nothing to unbind.
            if (cameraProviderFuture.isDone) {
                runCatching { cameraProviderFuture.get().unbindAll() }
            }
            analysisExecutor.shutdown()
            analyzer.close()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }

            cameraProviderFuture.addListener({
                // The composable may have been disposed before the provider
                // resolved; binding now would leak the camera.
                if (!disposed.get()) {
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(analysisExecutor, analyzer) }

                    try {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (e: Exception) {
                        // Binding can fail on a device with no usable back
                        // camera; surface it and close rather than leaving a
                        // black overlay. This runs on the main executor.
                        e.printStackTrace()
                        currentOnCameraError.value()
                    }
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

/**
 * ImageAnalysis.Analyzer that feeds frames to ML Kit and reports the first
 * QR code's raw value via [onBarcode].
 */
private class BarcodeAnalyzer(
    private val onBarcode: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    @ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        val image = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                barcodes.firstNotNullOfOrNull { it.rawValue }?.let(onBarcode)
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /** Releases the ML Kit scanner's native resources. */
    fun close() {
        scanner.close()
    }
}
