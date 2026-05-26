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
// UI state
// ---------------------------------------------------------------------------

sealed interface SpeciesUiState {
    data object Idle : SpeciesUiState
    data object Identifying : SpeciesUiState
    data class Complete(val results: List<IdentificationResult>) : SpeciesUiState
    data class Error(val message: String) : SpeciesUiState
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
 * All Android-framework dependencies are injected; this class has no direct
 * Context usage beyond what [ImagePreprocessor] and [LocationProvider] encapsulate.
 * Construction is handled by [Factory] — MainActivity wires it at integration time.
 *
 * @param appContext      Application context (for [ImagePreprocessor] / [LocationProvider]).
 * @param identifier      Species identification pipeline.
 * @param repo            Persistence layer for BioDex observations.
 * @param imagePreprocessor  Resize + orientation-correct before inference.
 * @param locationProvider   One-shot last-known GPS fix.
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

    /**
     * Run the full identification pipeline for the image at [uri].
     *
     * State transitions: Idle → Identifying → Complete | Error.
     * Each [IdentificationResult] is persisted via [repo.saveObservation] before
     * the [SpeciesUiState.Complete] state is emitted.
     */
    fun identifyFromImage(uri: Uri) {
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
