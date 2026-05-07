package com.oceanguard.ai.ui.screens

import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.oceanguard.ai.OceanGuardApp
import com.oceanguard.ai.data.SettingsRepository
import com.oceanguard.ai.inference.DetectionResult
import com.oceanguard.ai.inference.LiveDetectionManager
import com.oceanguard.ai.ui.MainViewModel
import com.oceanguard.ai.ui.components.spotlight.SpotlightOverlay
import com.oceanguard.ai.ui.components.spotlight.TourDefinitions
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightBounds
import com.oceanguard.ai.ui.components.spotlight.rememberSpotlightController
import com.oceanguard.ai.ui.components.spotlight.spotlightTarget
import com.oceanguard.ai.ui.components.ZoomSlider
import com.oceanguard.ai.ui.components.drawDetection
import com.oceanguard.ai.ui.components.pinchToZoom
import com.oceanguard.ai.ui.theme.OceanGreen
import androidx.compose.ui.res.stringResource
import com.oceanguard.ai.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

private const val TAG = "LiveDetectionScreen"

/**
 * LiveDetectionScreen — full-screen continuous detection with camera zoom.
 *
 * The camera preview runs at 30fps while RT-DETRv2 processes frames in the
 * background at ~0.3-1 FPS. Detections persist on the overlay until replaced
 * by new results. Includes pinch-to-zoom, zoom slider, resolution selector,
 * and a capture button for full pipeline analysis.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LiveDetectionScreen(
    navController: NavController,
    viewModel: MainViewModel,
) {
    val cameraPermissionState = rememberPermissionState(
        android.Manifest.permission.CAMERA
    )

    val liveContext = LocalContext.current
    val app = remember(liveContext) { liveContext.applicationContext as OceanGuardApp }
    val liveScope = rememberCoroutineScope()
    val boundsMap = rememberSpotlightBounds()
    val tourController = rememberSpotlightController(TourDefinitions.LIVE_DETECTION)
    val tourComplete by app.settingsRepository
        .isTourComplete(TourDefinitions.getScreenId(TourDefinitions.LIVE_DETECTION))
        .collectAsStateWithLifecycle(initialValue = true)

    LaunchedEffect(tourComplete) {
        if (!tourComplete) tourController.start()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraPermissionState.status.isGranted) {
            LiveDetectionContent(
                navController = navController,
                viewModel = viewModel,
                boundsMap = boundsMap,
                onBack = { navController.popBackStack() },
            )
        } else {
            // Permission request inline
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.live_permission_required),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.Button(
                    onClick = { cameraPermissionState.launchPermissionRequest() },
                ) {
                    Text(stringResource(R.string.live_btn_grant_permission))
                }
            }
        }

        SpotlightOverlay(
            controller = tourController,
            targetBounds = boundsMap,
            onComplete = {
                liveScope.launch {
                    app.settingsRepository.markTourComplete(
                        TourDefinitions.getScreenId(TourDefinitions.LIVE_DETECTION)
                    )
                }
            },
        )
    }
}

@Composable
private fun LiveDetectionContent(
    navController: NavController,
    viewModel: MainViewModel,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as OceanGuardApp }
    val threshold by app.settingsRepository.confidenceThreshold.collectAsStateWithLifecycle(
        initialValue = SettingsRepository.DEFAULT_CONFIDENCE_THRESHOLD
    )
    // Live mode is hard-wired to RT-DETRv2 regardless of the user's "active detector" preference.
    // Gemma 4 Vision takes 15-25 s/frame on Exynos 2200, which would collapse the camera loop
    // to ~0.05 FPS. The deep Gemma 4 path runs on demand via single-shot capture instead.
    val liveDetectionManager = remember {
        LiveDetectionManager(
            detector = app.rtdetrInference,
            context = context,
            repository = app.repository,
            locationProvider = app.locationProvider,
        )
    }
    val detectionState by liveDetectionManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Keep threshold in sync with settings
    LaunchedEffect(threshold) { liveDetectionManager.confidenceThreshold = threshold }

    // Camera state
    var zoomRatio by remember { mutableFloatStateOf(1f) }
    var maxZoomRatio by remember { mutableFloatStateOf(10f) }
    var cameraControl by remember { mutableStateOf<androidx.camera.core.CameraControl?>(null) }

    // ImageCapture for the capture button
    val imageCapture = remember { ImageCapture.Builder().build() }
    val captureExecutor = remember { Executors.newSingleThreadExecutor() }
    var isCapturing by remember { mutableStateOf(false) }

    // Lifecycle management
    DisposableEffect(Unit) {
        liveDetectionManager.start()
        onDispose {
            liveDetectionManager.stop()
            captureExecutor.shutdown()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pinchToZoom(
                currentZoom = zoomRatio,
                maxZoom = maxZoomRatio,
                onZoomChanged = { ratio ->
                    zoomRatio = ratio
                    cameraControl?.setZoomRatio(ratio)
                },
            )
    ) {
        // Layer 1: CameraX Preview + ImageAnalysis
        LiveCameraPreview(
            liveDetectionManager = liveDetectionManager,
            imageCapture = imageCapture,
            boundsMap = boundsMap,
            onCameraReady = { control, info ->
                cameraControl = control
                maxZoomRatio = info.zoomState.value?.maxZoomRatio ?: 10f
            },
        )

        // Layer 2: Bounding box overlay on camera preview
        LiveBoundingBoxOverlay(
            detections = detectionState.detections,
            isProcessing = detectionState.isProcessing,
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 3: Top HUD (back button + stats)
        TopHUD(
            fps = detectionState.fps,
            latencyMs = detectionState.inferenceTimeMs,
            isProcessing = detectionState.isProcessing,
            detectionCount = detectionState.detections.size,
            savedCount = detectionState.savedCount,
            onBack = onBack,
            boundsMap = boundsMap,
        )

        // Layer 4: Bottom controls (zoom, capture)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)),
                    ),
                )
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Zoom slider
            ZoomSlider(
                zoomRatio = zoomRatio,
                maxZoomRatio = maxZoomRatio,
                onZoomChanged = { ratio ->
                    zoomRatio = ratio
                    cameraControl?.setZoomRatio(ratio)
                },
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Capture button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.30f),
                        shape = CircleShape,
                    )
                    .background(
                        color = Color.Transparent,
                        shape = CircleShape,
                    )
                    .spotlightTarget("live_capture", boundsMap),
            ) {
                IconButton(
                    onClick = {
                        if (!isCapturing) {
                            isCapturing = true
                            liveDetectionManager.stop()

                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                .format(System.currentTimeMillis())
                            val photoFile = File(
                                context.getExternalFilesDir(null),
                                "OceanGuard_Live_$timestamp.jpg",
                            )
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                            imageCapture.takePicture(
                                outputOptions,
                                captureExecutor,
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        val savedUri = output.savedUri ?: Uri.fromFile(photoFile)
                                        Log.i(TAG, "Live capture saved: $savedUri")
                                        ContextCompat.getMainExecutor(context).execute {
                                            isCapturing = false
                                            scope.launch { app.achievementChecker.checkFirstLive() }
                                            viewModel.analyzeImage(savedUri)
                                            navController.navigate("results") {
                                                popUpTo("live_detection") { inclusive = true }
                                            }
                                        }
                                    }

                                    override fun onError(exc: ImageCaptureException) {
                                        Log.e(TAG, "Live capture failed", exc)
                                        ContextCompat.getMainExecutor(context).execute {
                                            isCapturing = false
                                            liveDetectionManager.start()
                                        }
                                    }
                                },
                            )
                        }
                    },
                    modifier = Modifier.size(64.dp),
                    enabled = !isCapturing,
                ) {
                    if (isCapturing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = stringResource(R.string.cd_capture_and_analyze),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.live_hint_tap_to_analyze),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// CameraX Preview + ImageAnalysis
// ---------------------------------------------------------------------------

@Composable
private fun LiveCameraPreview(
    liveDetectionManager: LiveDetectionManager,
    imageCapture: ImageCapture,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect>,
    onCameraReady: (androidx.camera.core.CameraControl, androidx.camera.core.CameraInfo) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

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


                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setResolutionSelector(
                            androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                                        android.util.Size(640, 640),
                                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                    )
                                )
                                .build()
                        )
                        .build()

                    imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val bitmap = imageProxy.toBitmap()
                        imageProxy.close() // Release immediately to free camera pipeline

                        scope.launch(Dispatchers.Default) {
                            try {
                                liveDetectionManager.processFrame(bitmap)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }

                    try {
                        cameraProvider.unbindAll()
                        val camera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis,
                            imageCapture,
                        )
                        onCameraReady(camera.cameraControl, camera.cameraInfo)
                    } catch (exc: Exception) {
                        Log.e(TAG, "CameraX binding failed", exc)
                    }
                },
                ContextCompat.getMainExecutor(ctx),
            )

            previewView
        },
        modifier = Modifier
            .fillMaxSize()
            .spotlightTarget("live_viewfinder", boundsMap),
    )
}

// ---------------------------------------------------------------------------
// Live bounding box overlay with scanning animation
// ---------------------------------------------------------------------------

@Composable
private fun LiveBoundingBoxOverlay(
    detections: List<DetectionResult>,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
) {
    var displaySize by remember { mutableStateOf(IntSize.Zero) }

    // Scanning line animation during inference
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val scanProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanLine",
    )

    Canvas(
        modifier = modifier.onSizeChanged { displaySize = it },
    ) {
        if (displaySize == IntSize.Zero) return@Canvas

        val scaleX = displaySize.width.toFloat()
        val scaleY = displaySize.height.toFloat()
        val strokePx = 3.dp.toPx()

        // Draw detections
        for (detection in detections) {
            drawDetection(detection, scaleX, scaleY, strokePx)
        }

        // Scanning line animation (only when processing)
        if (isProcessing) {
            val scanY = scanProgress * scaleY
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Cyan.copy(alpha = 0.6f),
                        Color.Cyan.copy(alpha = 0.8f),
                        Color.Cyan.copy(alpha = 0.6f),
                        Color.Transparent,
                    ),
                ),
                start = Offset(0f, scanY),
                end = Offset(scaleX, scanY),
                strokeWidth = 2.dp.toPx(),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Top HUD: back button + performance stats
// ---------------------------------------------------------------------------

@Composable
private fun TopHUD(
    fps: Float,
    latencyMs: Long,
    isProcessing: Boolean,
    detectionCount: Int,
    savedCount: Int = 0,
    onBack: () -> Unit,
    boundsMap: MutableMap<String, androidx.compose.ui.geometry.Rect> = mutableMapOf(),
) {
    // Pop animation trigger for detection count changes
    var prevCount by remember { mutableStateOf(detectionCount) }
    var popTrigger by remember { mutableStateOf(0) }
    if (detectionCount != prevCount) {
        prevCount = detectionCount
        popTrigger++
    }

    // Use LaunchedEffect to briefly spike scale on count change
    var spikeScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(popTrigger) {
        if (popTrigger > 0) {
            spikeScale = 1.25f
            kotlinx.coroutines.delay(100)
            spikeScale = 1f
        }
    }
    val animatedSpikeScale by animateFloatAsState(
        targetValue = spikeScale,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "spikeScale",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.55f),
                        Color.Black.copy(alpha = 0.25f),
                        Color.Transparent,
                    ),
                ),
            )
            .statusBarsPadding()
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Back button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = Color.Black.copy(alpha = 0.50f),
                        shape = CircleShape,
                    ),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.cd_go_back),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Stats badges
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.spotlightTarget("live_fps", boundsMap),
            ) {
                HudBadge(text = "%.1f FPS".format(fps))
                HudBadge(text = "${latencyMs}ms")
                if (detectionCount > 0) {
                    HudBadge(
                        text = stringResource(R.string.live_detection_count, detectionCount),
                        backgroundColor = OceanGreen.copy(alpha = 0.7f),
                        modifier = Modifier.graphicsLayer {
                            scaleX = animatedSpikeScale
                            scaleY = animatedSpikeScale
                        },
                    )
                }
                if (savedCount > 0) {
                    HudBadge(
                        text = stringResource(R.string.live_saved_count, savedCount),
                        backgroundColor = Color(0xFF1565C0).copy(alpha = 0.7f),
                    )
                }
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = Color.Cyan,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun HudBadge(
    text: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Black.copy(alpha = 0.55f),
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
