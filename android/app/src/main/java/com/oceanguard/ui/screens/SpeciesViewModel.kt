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
    data class Complete(val results: List<IdentificationResult>) : SpeciesUiState
    data class Error(val message: String) : SpeciesUiState
}

// ---------------------------------------------------------------------------
// UI state — batch (gallery multi-select) path
// ---------------------------------------------------------------------------

/** Processing state of a single image within a species batch job. */
sealed interface SpeciesBatchItemState {
    data object Pending : SpeciesBatchItemState
    data object Analyzing : SpeciesBatchItemState
    data class Done(val results: List<IdentificationResult>) : SpeciesBatchItemState
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
                val bitmap = imagePreprocessor.loadAndPreprocess(uri, 512)
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
                _uiState.value = SpeciesUiState.Complete(results)
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
                    val bitmap = imagePreprocessor.loadAndPreprocess(uri, 512)
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
                    items[index] = items[index].copy(state = SpeciesBatchItemState.Done(results))
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
