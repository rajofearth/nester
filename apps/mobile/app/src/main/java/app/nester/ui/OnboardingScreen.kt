@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.nester.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.nester.api.NesterApi
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import app.nester.data.SettingsStore
import app.nester.pair.PairParseResult
import app.nester.pair.PairingParser
import app.nester.store.NesterStore
import app.nester.store.StoredPairing
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun OnboardingScreen(store: NesterStore, settings: SettingsStore, onConnected: () -> Unit) {
    var pendingPairing by remember { mutableStateOf<StoredPairing?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun verify(pairing: StoredPairing) {
        verifying = true
        verifyError = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    NesterApi(NesterApi.ApiConfig(pairing.host, pairing.port)).health()
                }
                verifying = false
            } catch (e: Exception) {
                verifyError = "Could not reach the host (${e.message ?: "network error"})"
                verifying = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            "Nester",
            style = MaterialTheme.typography.displayLarge,
            fontSize = 45.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 48.dp),
        )
        Text(
            "Scan the QR Code Of Host Device",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp, bottom = 16.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(380f / 507f)
                .clip(RoundedCornerShape(20.dp)),
        ) {
            QrScannerPane(
                onResult = { raw ->
                    val result = PairingParser.parse(raw)
                    if (result is PairParseResult.Ok) {
                        val pairing = StoredPairing(
                            host = result.config.host,
                            port = result.config.port,
                            token = result.config.token,
                        )
                        store.savePairing(pairing)
                        pendingPairing = pairing
                        verify(pairing)
                    }
                },
            )
        }
        pendingPairing?.let { pairing ->
            if (verifyError != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                ) {
                    ErrorSurface(
                        text = verifyError ?: "",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { verify(pairing) }) { Text("Retry") }
                }
            } else {
                Button(
                    onClick = onConnected,
                    enabled = !verifying,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .height(56.dp),
                ) {
                    if (verifying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            Icons.Rounded.Hub,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            "Connected !",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        }
        if (pendingPairing == null) {
            OutlinedButton(
                onClick = { showManual = true },
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .height(56.dp),
            ) {
                Text("Enter details instead")
            }
            if (store.loadPairing() != null) {
                TextButton(
                    onClick = onConnected,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                ) { Text("Keep current pairing and go back") }
            }
        }
    }

    if (showManual) {
        ManualPairDialog(
            store = store,
            onDismiss = { showManual = false },
            onPaired = { pairing ->
                showManual = false
                pendingPairing = pairing
            },
        )
    }
}

@Composable
private fun ErrorSurface(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ManualPairDialog(
    store: NesterStore,
    onDismiss: () -> Unit,
    onPaired: (StoredPairing) -> Unit,
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        modifier = Modifier.width(312.dp),
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Pair with host", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host (IP)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                inputError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !checking,
                onClick = {
                    inputError = null
                    val hostTrim = host.trim()
                    val portNum = port.toIntOrNull()?.takeIf { it in 1..65535 }
                    val tokenTrim = token.trim()
                    if (hostTrim.isEmpty()) {
                        inputError = "Enter the host IP shown on the host screen"
                        return@TextButton
                    }
                    if (portNum == null) {
                        inputError = "Port must be between 1 and 65535"
                        return@TextButton
                    }
                    if (tokenTrim.length < 16) {
                        inputError = "Token must be at least 16 characters"
                        return@TextButton
                    }
                    checking = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                NesterApi(NesterApi.ApiConfig(hostTrim, portNum)).health()
                            }
                            val pairing = StoredPairing(hostTrim, portNum, tokenTrim)
                            store.savePairing(pairing)
                            onPaired(pairing)
                        } catch (e: Exception) {
                            inputError = "Pairing failed: host did not answer (${e.message ?: "network error"})"
                        } finally {
                            checking = false
                        }
                    }
                },
            ) {
                Text(if (checking) "Verifying..." else "Save and verify")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !checking) { Text("Cancel") }
        },
    )
}

@Composable
private fun QrScannerPane(onResult: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.inverseSurface),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.size(40.dp),
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    shape = RoundedCornerShape(28.dp),
                ) {
                    Text("Grant camera access")
                }
            }
        }
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val scanner = BarcodeScanning.getClient()
                analysis.setAnalyzer(executor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val input = InputImage.fromMediaImage(
                            mediaImage,
                            imageProxy.imageInfo.rotationDegrees,
                        )
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull()?.rawValue?.let { value ->
                                    onResult(value)
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}
