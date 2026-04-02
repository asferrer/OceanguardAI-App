package com.oceanguard.ai.ui.screens

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.oceanguard.ai.ModelStatus
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.OceanGradientButton
import com.oceanguard.ai.ui.components.OnGradientColor
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "CameraScreen"

/**
 * CameraScreen — full-screen CameraX viewfinder with debris-capture controls.
 *
 * Design decisions for underwater usability:
 * - 72 dp circular shutter button with high-contrast white border so it is
 *   clearly visible through a dive mask at depth.
 * - Permission rationale shown inline (no separate dialog) — one less
 *   tap for the diver.
 * - Camera preview fills the entire screen (edge-to-edge) to maximise the
 *   field of view when framing underwater shots.
 * - Optional capture confirmation preview (configurable in Settings).
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )

    // Read confirm capture setting
    val cameraContext = LocalContext.current
    val app = remember(cameraContext) { cameraContext.applicationContext as OceanGuardApp }
    val confirmCapture by app.settingsRepository.confirmCapture
        .collectAsStateWithLifecycle(initialValue = SettingsRepository.DEFAULT_CONFIRM_CAPTURE)

    val modelState by app.modelLoadingState.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.CAMERA)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.CAMERA))
        .collectAsStateWithLifecycle(initialValue = true)

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    // Request location permission alongside camera so GPS data is available
    // when saving sessions. Location is optional — the app works without it.
    val locationPermissions = rememberMultiplePermissionsState(
        permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    )

    // Request location silently when camera is already granted
    LaunchedEffect(cameraPermissionState.status.isGranted) {
        if (cameraPermissionState.status.isGranted && !locationPermissions.allPermissionsGranted) {
            locationPermissions.launchMultiplePermissionRequest()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            CameraPreviewContent(
                confirmCapture = confirmCapture,
                boundsMap = boundsMap,
                liveEnabled = modelState.rtdetr == ModelStatus.Ready,
                onNavigateToLive = {
                    navController.navigate("live_detection") {
                        popUpTo("camera") { inclusive = true }
                    }
                },
                onImageCaptured = { uri ->
                    viewModel.analyzeImage(uri)
                    navController.navigate("results") {
                        // Pop CameraScreen off the back stack so pressing back
                        // from results goes home, not back to the viewfinder.
                        popUpTo("camera") { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        } else {
            CameraPermissionScreen(
                shouldShowRationale = cameraPermissionState.status.shouldShowRationale,
                onRequestPermission = { cameraPermissionState.launchPermissionRequest() },
                onBack = { navController.popBackStack() },
            )
        }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                scope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.CAMERA)
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Camera viewfinder
// ---------------------------------------------------------------------------

@Composable
private fun CameraPreviewContent(
    confirmCapture: Boolean,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
    liveEnabled: Boolean,
    onNavigateToLive: () -> Unit,
    onImageCaptured: (Uri) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // ImageCapture use case — kept in state so the capture button lambda
    // can reference it without recomposition.
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    var isCapturing by remember { mutableStateOf(false) }
    var showGrid by remember { mutableStateOf(false) }

    // Capture confirmation state
    var pendingUri by remember { mutableStateOf<Uri?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "shutter_pulse")
    val shutterBorderWidth by infiniteTransition.animateFloat(
        initialValue = 3f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "shutter_border_width",
    )

    DisposableEffect(Unit) {
        onDispose { cameraExecutor.shutdown() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ----------------------------------------------------------------
        // CameraX preview via AndroidView
        // ----------------------------------------------------------------
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener(
                    {
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                            )
                        } catch (exc: Exception) {
                            Log.e(TAG, "CameraX use-case binding failed", exc)
                        }
                    },
                    ContextCompat.getMainExecutor(ctx),
                )

                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ----------------------------------------------------------------
        // Rule-of-thirds grid overlay
        // ----------------------------------------------------------------
        if (showGrid && pendingUri == null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 1.dp.toPx()
                val gridColor = Color.White.copy(alpha = 0.15f)
                // Vertical lines
                drawLine(gridColor, Offset(size.width / 3, 0f), Offset(size.width / 3, size.height), strokeWidth)
                drawLine(gridColor, Offset(size.width * 2 / 3, 0f), Offset(size.width * 2 / 3, size.height), strokeWidth)
                // Horizontal lines
                drawLine(gridColor, Offset(0f, size.height / 3), Offset(size.width, size.height / 3), strokeWidth)
                drawLine(gridColor, Offset(0f, size.height * 2 / 3), Offset(size.width, size.height * 2 / 3), strokeWidth)
            }
        }

        // ----------------------------------------------------------------
        // Capture preview overlay (when confirm mode is active)
        // ----------------------------------------------------------------
        if (pendingUri != null) {
            CapturePreviewOverlay(
                imageUri = pendingUri!!,
                onConfirm = {
                    val uri = pendingUri!!
                    pendingUri = null
                    onImageCaptured(uri)
                },
                onRetake = {
                    // Delete the temporary photo and return to viewfinder
                    try {
                        val path = pendingUri?.path
                        if (path != null) File(path).delete()
                    } catch (_: Exception) {}
                    pendingUri = null
                },
            )
        } else {
            // ----------------------------------------------------------------
            // Top overlay: back button + grid toggle + flash toggle
            // ----------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(52.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.50f),
                            shape = CircleShape,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_go_back),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = { showGrid = !showGrid },
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.50f),
                                shape = CircleShape,
                            )
                            .spotlightTarget("camera_grid", boundsMap),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.GridOn,
                            contentDescription = stringResource(R.string.cd_toggle_grid),
                            tint = Color.White.copy(alpha = if (showGrid) 1f else 0.8f),
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    IconButton(
                        onClick = {
                            flashMode = when (flashMode) {
                                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                                ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                                else -> ImageCapture.FLASH_MODE_OFF
                            }
                        },
                        modifier = Modifier
                            .size(52.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.50f),
                                shape = CircleShape,
                            )
                            .spotlightTarget("camera_flash", boundsMap),
                    ) {
                        Icon(
                            imageVector = when (flashMode) {
                                ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn
                                ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto
                                else -> Icons.Filled.FlashOff
                            },
                            contentDescription = stringResource(R.string.cd_flash_mode),
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            // ----------------------------------------------------------------
            // Bottom overlay: mode toggle + shutter button + hint label
            // ----------------------------------------------------------------
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f)),
                        ),
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Mode toggle: Capture | Live
                CameraModeToggle(
                    liveEnabled = liveEnabled,
                    onNavigateToLive = onNavigateToLive,
                    modifier = Modifier.spotlightTarget("camera_mode_toggle", boundsMap),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = stringResource(R.string.camera_hint_tap_to_capture),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.80f),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Shutter button — 72 dp circular target, well above the 48 dp
                // minimum, sized for gloved fingers at depth.
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.30f),
                            shape = CircleShape,
                        )
                        .border(width = shutterBorderWidth.dp, color = Color.White, shape = CircleShape)
                        .spotlightTarget("camera_shutter", boundsMap),
                ) {
                    IconButton(
                        onClick = {
                            if (!isCapturing) {
                                isCapturing = true
                                imageCapture.flashMode = flashMode
                                capturePhoto(
                                    context = context,
                                    imageCapture = imageCapture,
                                    executor = cameraExecutor,
                                    onSuccess = { uri ->
                                        isCapturing = false
                                        if (confirmCapture) {
                                            pendingUri = uri
                                        } else {
                                            onImageCaptured(uri)
                                        }
                                    },
                                    onError = { exc ->
                                        isCapturing = false
                                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                                    },
                                )
                            }
                        },
                        modifier = Modifier.size(72.dp),
                        enabled = !isCapturing,
                    ) {
                        if (isCapturing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(32.dp),
                                color = Color.White,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.CameraAlt,
                                contentDescription = stringResource(R.string.cd_capture_photo),
                                tint = Color.White,
                                modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Capture / Live mode toggle pill
// ---------------------------------------------------------------------------

@Composable
private fun CameraModeToggle(
    liveEnabled: Boolean,
    onNavigateToLive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.45f),
                shape = RoundedCornerShape(24.dp),
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Capture — always active on this screen
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = 0.25f),
        ) {
            Text(
                text = stringResource(R.string.camera_mode_capture),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Live — tapping navigates to live_detection
        Surface(
            onClick = onNavigateToLive,
            shape = RoundedCornerShape(20.dp),
            color = Color.Transparent,
            enabled = liveEnabled,
        ) {
            Text(
                text = stringResource(R.string.camera_mode_live),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = if (liveEnabled) Color.White.copy(alpha = 0.6f)
                        else Color.White.copy(alpha = 0.25f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Capture preview overlay — shown when confirm capture is enabled
// ---------------------------------------------------------------------------

@Composable
private fun CapturePreviewOverlay(
    imageUri: Uri,
    onConfirm: () -> Unit,
    onRetake: () -> Unit,
) {
    val context = LocalContext.current

    // Load bitmap from file URI and correct EXIF orientation
    val bitmap = remember(imageUri) {
        try {
            val stream = context.contentResolver.openInputStream(imageUri)
            val decoded = android.graphics.BitmapFactory.decodeStream(stream).also { stream?.close() }
                ?: return@remember null

            // Read EXIF orientation and rotate bitmap so preview matches capture orientation
            val exifStream = context.contentResolver.openInputStream(imageUri)
            val orientation = if (exifStream != null) {
                val exif = ExifInterface(exifStream)
                exifStream.close()
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } else ExifInterface.ORIENTATION_NORMAL

            val matrix = android.graphics.Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                else -> null
            }
            if (!matrix.isIdentity) {
                android.graphics.Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { if (it !== decoded) decoded.recycle() }
            } else decoded
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load preview bitmap", e)
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Captured image
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(R.string.cd_captured_photo_preview),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }

        // Bottom action bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                    ),
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.camera_btn_retake),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            OceanGradientButton(
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                height = 56.dp,
                cornerRadius = 16.dp,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = OnGradientColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.camera_btn_analyze),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = OnGradientColor,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Permission rationale screen
// ---------------------------------------------------------------------------

@Composable
private fun CameraPermissionScreen(
    shouldShowRationale: Boolean,
    onRequestPermission: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = { Text(stringResource(R.string.camera_permission_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_go_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Camera,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (shouldShowRationale) {
                    stringResource(R.string.camera_permission_rationale)
                } else {
                    stringResource(R.string.camera_permission_request)
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(32.dp))

            OceanGradientButton(
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth(),
                height = 64.dp,
                cornerRadius = 16.dp,
            ) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = OnGradientColor,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.camera_btn_grant_permission),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = OnGradientColor,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// CameraX capture helper
// ---------------------------------------------------------------------------

/**
 * Triggers a single still capture via CameraX [ImageCapture].
 *
 * The output file is written to the app's external files directory so it
 * can be shared with the photo picker and the inference pipeline.
 *
 * @param context       Used to resolve the output directory.
 * @param imageCapture  The [ImageCapture] use case bound to the lifecycle.
 * @param executor      Single-thread executor that drives the capture callback.
 * @param onSuccess     Called on the main thread with the file [Uri] on success.
 * @param onError       Called on the main thread with the [ImageCaptureException] on failure.
 */
private fun capturePhoto(
    context: Context,
    imageCapture: ImageCapture,
    executor: ExecutorService,
    onSuccess: (Uri) -> Unit,
    onError: (ImageCaptureException) -> Unit,
) {
    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
    val photoFile = File(
        context.getExternalFilesDir(null),
        "OceanGuard_$timestamp.jpg",
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                Log.i(TAG, "Photo saved: $savedUri")
                ContextCompat.getMainExecutor(context).execute { onSuccess(savedUri) }
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e(TAG, "Photo capture error", exc)
                ContextCompat.getMainExecutor(context).execute { onError(exc) }
            }
        },
    )
}
