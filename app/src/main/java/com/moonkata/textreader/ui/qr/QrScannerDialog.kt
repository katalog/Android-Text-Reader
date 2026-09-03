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
import com.moonkata.textreader.data.sync.QrPairingPayload
import java.util.concurrent.Executors

/**
 * 카메라로 QR을 스캔해 동기화 페어링 정보를 읽어들이는 공용 컴포넌트(.docs/SYNC_MULTIUSER_PLAN.md
 * 스테이지 4). VSCode 읽기 위치 동기화(스테이지 5)와 PC 파일 동기화(스테이지 6) 두 페어링 흐름이
 * 공유한다 — QR 안의 `type` 필드로 어떤 종류인지 구분([QrPairingPayload] 참고).
 *
 * 카메라 권한이 없거나 거부되면 곧바로 [onDismiss]를 호출한다 — 호출한 쪽이 기존 수동 입력 필드로
 * 폴백하게 하기 위함(카메라 없는 폼팩터/권한 거부 대비, 계획 문서의 "1급 폴백 유지" 방침).
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
                    "QR 코드를 화면 중앙에 맞춰주세요",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(Icons.Default.Close, contentDescription = "닫기", tint = Color.White)
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
                    // 카메라를 못 열면(다른 앱이 점유 중 등) 조용히 포기한다 — 사용자가 닫기 버튼으로
                    // 나가면 호출한 쪽이 수동 입력으로 폴백한다.
                },
                ContextCompat.getMainExecutor(ctx),
            )
            previewView
        },
    )
}

/** 프레임 하나를 ML Kit에 넘겨 QR을 읽고, 우리가 기대하는 페이로드 형식이면 한 번만 [onPayload]를 부른다. */
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
