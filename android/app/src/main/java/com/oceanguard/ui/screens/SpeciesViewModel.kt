package com.oceanguard.ai.ui.screens

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.inference.species.IdentificationResult
import com.oceanguard.ai.inference.species.SpeciesIdentifier
import com.oceanguard.ai.utils.ImagePreprocessor
import com.oceanguard.ai.utils.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// ---------------------------------------------------------------------------
// UI state — single-capture path
// ---------------------------------------------------------------------------

sealed interface SpeciesUiState {
    data object Idle : SpeciesUiState
    data object Identifying : SpeciesUiState
    /**
     * @param results        One result per detected organism (each may carry a bbox).
     * @param imageWidth     Intrinsic width (px) of the analysed full-res frame.
     * @param imageHeight    Intrinsic height (px) of the analysed full-res frame.
     *                       Needed to normalise each result's pixel bbox for the
     *                       overlay; 0 when unknown (renders box-less).
     */
    data class Complete(
        val results: List<IdentificationResult>,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : SpeciesUiState
    data class Error(val message: String) : SpeciesUiState
}

// ---------------------------------------------------------------------------
// UI state — batch (gallery multi-select) path
// ---------------------------------------------------------------------------

/** Processing state of a single image within a species batch job. */
sealed interface SpeciesBatchItemState {
    data object Pending : SpeciesBatchItemState
    data object Analyzing : SpeciesBatchItemState
    /**
     * @param results     One result per detected organism in this image.
     * @param imageWidth  Intrinsic width (px) of the analysed frame (0 = unknown).
     * @param imageHeight Intrinsic height (px) of the analysed frame (0 = unknown).
     */
    data class Done(
        val results: List<IdentificationResult>,
        val imageWidth: Int = 0,
        val imageHeight: Int = 0,
    ) : SpeciesBatchItemState
    data class Failed(val message: String) : SpeciesBatchItemState
}

/** One item in the batch list exposed to the batch result screen. */
data class SpeciesBatchItem(
    val uri: Uri,
    val state: SpeciesBatchItemState = SpeciesBatchItemState.Pending,
)

sealed interface SpeciesBatchUiState {
    data object Idle : SpeciesBatchUiState
    /** Processing in progress — snapshot of the current items list. */
    data class Running(
        val items: List<SpeciesBatchItem>,
        val currentIndex: Int,
        val startTimeMs: Long,
    ) : SpeciesBatchUiState
    data class Complete(
        val items: List<SpeciesBatchItem>,
        val startTimeMs: Long,
        val endTimeMs: Long,
    ) : SpeciesBatchUiState
    data class Error(val message: String) : SpeciesBatchUiState
}

// ---------------------------------------------------------------------------
// ViewModel
// ---------------------------------------------------------------------------

/**
 * ViewModel for the BioDex species identification flow.
 *
 * Orchestrates:
 * 1. Image preprocessing via [ImagePreprocessor].
 * 2. Optional GPS acquisition via [LocationProvider].
 * 3. Inference via [SpeciesIdentifier].
 * 4. Persistence of every result via [SpeciesCollectionRepository.saveObservation].
 *
 * Supports both the camera single-capture path ([identifyFromImage]) and the
 * gallery multi-select batch path ([identifyBatch]).
 */
class SpeciesViewModel(
    private val appContext: Context,
    private val identifier: SpeciesIdentifier,
    private val repo: SpeciesCollectionRepository,
    private val imagePreprocessor: ImagePreprocessor,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    companion object {
        /**
         * Target "long edge" passed to [ImagePreprocessor.loadAndPreprocess] for
         * the species path. [Int.MAX_VALUE] disables down-scaling so the organism
         * locator can crop the subject bbox from the original full-resolution
         * frame (more detail on the subject, less background noise to embed).
         */
        private const val FULL_RES = Int.MAX_VALUE
    }

    private val _uiState = MutableStateFlow<SpeciesUiState>(SpeciesUiState.Idle)
    val uiState: StateFlow<SpeciesUiState> = _uiState.asStateFlow()

    /** URI of the image currently displayed on the single-result screen. */
    private val _capturedImageUri = MutableStateFlow<Uri?>(null)
    val capturedImageUri: StateFlow<Uri?> = _capturedImageUri.asStateFlow()

    private val _batchUiState = MutableStateFlow<SpeciesBatchUiState>(SpeciesBatchUiState.Idle)
    val batchUiState: StateFlow<SpeciesBatchUiState> = _batchUiState.asStateFlow()

    /**
     * Run the full identification pipeline for the image at [uri].
     *
     * State transitions: Idle → Identifying → Complete | Error.
     */
    fun identifyFromImage(uri: Uri) {
        _capturedImageUri.value = uri
        viewModelScope.launch {
            _uiState.value = SpeciesUiState.Identifying
            runCatching {
                // Full-resolution load: the organism locator crops the subject
                // bbox from this frame, so down-scaling here would lose detail.
                val bitmap = imagePreprocessor.loadAndPreprocess(uri, FULL_RES)
                val loc = locationProvider.getLastKnownLocation()
                val lang = Locale.getDefault().language
                val imageWidth = bitmap.width
                val imageHeight = bitmap.height
                val results = identifier.identify(bitmap, loc, language = lang)
                results.forEach { result ->
                    repo.saveObservation(
                        result = result,
                        imageUri = uri.toString(),
                        thumbnailUri = null,
                        location = loc,
                    )
                }
                _uiState.value = SpeciesUiState.Complete(
                    results = results,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
            }.onFailure { e ->
                _uiState.value = SpeciesUiState.Error(
                    e.message ?: "Identification failed"
                )
            }
        }
    }

    /**
     * Run species identification on each URI in [uris] sequentially.
     *
     * State transitions: Idle → Running(per-item updates) → Complete | Error.
     * Each item moves through Pending → Analyzing → Done | Failed independently.
     */
    fun identifyBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val items = uris.map { SpeciesBatchItem(it) }.toMutableList()
        val startTime = System.currentTimeMillis()
        _batchUiState.value = SpeciesBatchUiState.Running(
            items = items.toList(),
            currentIndex = 0,
            startTimeMs = startTime,
        )

        viewModelScope.launch {
            for ((index, uri) in uris.withIndex()) {
                items[index] = items[index].copy(state = SpeciesBatchItemState.Analyzing)
                _batchUiState.value = SpeciesBatchUiState.Running(
                    items = items.toList(),
                    currentIndex = index,
                    startTimeMs = startTime,
                )
                runCatching {
                    // Full-resolution load: see [identifyFromImage] rationale.
                    val bitmap = imagePreprocessor.loadAndPreprocess(uri, FULL_RES)
                    val imageWidth = bitmap.width
                    val imageHeight = bitmap.height
                    val loc = locationProvider.getLastKnownLocation()
                    val lang = Locale.getDefault().language
                    val results = identifier.identify(bitmap, loc, language = lang)
                    results.forEach { result ->
                        repo.saveObservation(
                            result = result,
                            imageUri = uri.toString(),
                            thumbnailUri = null,
                            location = loc,
                        )
                    }
                    items[index] = items[index].copy(
                        state = SpeciesBatchItemState.Done(
                            results = results,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                        ),
                    )
                }.onFailure { e ->
                    items[index] = items[index].copy(
                        state = SpeciesBatchItemState.Failed(e.message ?: "Failed"),
                    )
                }
                _batchUiState.value = SpeciesBatchUiState.Running(
                    items = items.toList(),
                    currentIndex = index + 1,
                    startTimeMs = startTime,
                )
            }
            _batchUiState.value = SpeciesBatchUiState.Complete(
                items = items.toList(),
                startTimeMs = startTime,
                endTimeMs = System.currentTimeMillis(),
            )
        }
    }

    /** Reset both UI states back to Idle (e.g. before navigating away). */
    fun resetState() {
        _uiState.value = SpeciesUiState.Idle
        _batchUiState.value = SpeciesBatchUiState.Idle
        _capturedImageUri.value = null
    }

    // ---------------------------------------------------------------------------
    // Factory
    // ---------------------------------------------------------------------------

    class Factory(
        private val appContext: Context,
        private val identifier: SpeciesIdentifier,
        private val repo: SpeciesCollectionRepository,
        private val imagePreprocessor: ImagePreprocessor,
        private val locationProvider: LocationProvider,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SpeciesViewModel::class.java))
            return SpeciesViewModel(
                appContext = appContext,
                identifier = identifier,
                repo = repo,
                imagePreprocessor = imagePreprocessor,
                locationProvider = locationProvider,
            ) as T
        }
    }
}
