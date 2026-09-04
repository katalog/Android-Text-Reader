package com.moonkata.textreader.ui.qr

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.moonkata.textreader.R
import com.moonkata.textreader.data.sync.QrPairingPayload
import java.util.concurrent.Executors

/**
 * Shared component that scans a QR code with the camera to read sync pairing info
 * (.docs/SYNC_MULTIUSER_PLAN.md stage 4). Shared by the VSCode reading-position sync pairing flow
 * (stage 5) and the PC file-sync pairing flow (stage 6) — the `type` field inside the QR tells them
 * apart (see [QrPairingPayload]).
 *
 * Calls [onDismiss] immediately if the camera permission is missing or denied — so the caller falls
 * back to its existing manual-entry field (for camera-less form factors / permission denial, per the
 * plan doc's "always keep a first-class fallback" policy).
 */
@Composable
fun QrScannerDialog(
    onResult: (QrPairingPayload) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
        if (!granted) onDismiss()
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            if (hasCameraPermission) {
                QrCameraPreview(lifecycleOwner = lifecycleOwner, onResult = onResult)
                Text(
                    stringResource(R.string.qr_align_hint),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.qr_close_desc), tint = Color.White)
            }
        }
    }
}

@Composable
private fun QrCameraPreview(
    lifecycleOwner: LifecycleOwner,
    onResult: (QrPairingPayload) -> Unit,
) {
    val handled = remember { mutableStateOf(false) }
    val currentOnResult = rememberUpdatedState(onResult)
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener(
                {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val scanner = BarcodeScanning.getClient(
                        BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                            .build(),
                    )
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also {
                            it.setAnalyzer(executor) { imageProxy ->
                                processFrame(scanner, imageProxy, handled) { payload -> currentOnResult.value(payload) }
                            }
                        }
                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }
                    // Give up quietly if the camera can't be opened (another app has it, etc.) —
                    // if the user backs out via the close button, the caller falls back to manual entry.
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

/** Hands one frame to ML Kit to read a QR code, calling [onPayload] exactly once if it matches the payload format we expect. */
private fun processFrame(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    imageProxy: ImageProxy,
    handled: MutableState<Boolean>,
    onPayload: (QrPairingPayload) -> Unit,
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null || handled.value) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            if (handled.value) return@addOnSuccessListener
            for (barcode in barcodes) {
                val raw = barcode.rawValue ?: continue
                val payload = QrPairingPayload.parse(raw) ?: continue
                handled.value = true
                onPayload(payload)
                break
            }
        }
        .addOnCompleteListener { imageProxy.close() }
}
