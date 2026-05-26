package com.oceanguard.ai.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.oceanguard.ai.data.species.SpeciesCatalog
import com.oceanguard.ai.data.species.SpeciesCatalogEntry
import com.oceanguard.ai.data.species.SpeciesCollectionRepository
import com.oceanguard.ai.data.species.SpeciesDexEntry
import com.oceanguard.ai.data.species.SpeciesObservation
import com.oceanguard.ai.ui.components.dex.DexItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * ViewModel for the BioDex feature (SpeciesDexScreen + SpeciesDexDetailScreen).
 *
 * Holds no Android-framework dependencies beyond ViewModel; lifecycle-safe
 * state is exposed as [StateFlow]. The constructor receives the repository and
 * catalog by injection — the DI graph (OceanGuardApp) wires them at integration
 * time via the [Factory].
 *
 * Species names are resolved from [SpeciesCatalog] using the device locale at
 * collection time. Common names live in the catalog JSON (not strings.xml) as
 * specified in the architecture plan.
 */
class SpeciesDexViewModel(
    private val repo: SpeciesCollectionRepository,
    private val catalog: SpeciesCatalog,
) : ViewModel() {

    // ---------------------------------------------------------------------------
    // Grid state — all dex entries mapped to DexItem for the shared card
    // ---------------------------------------------------------------------------

    val dexItems: StateFlow<List<DexItem>> = repo.allDexEntries
        .map { entries -> entries.map { it.toDexItem() } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    val discoveredCount: StateFlow<Int> = repo.discoveredCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * Total catalog size; used for the "N / total" header counter.
     * TODO(integration): SpeciesCatalog.totalCount() is an extension beyond
     * the minimal contract — add it to SpeciesCatalog alongside allKeys().
     */
    val catalogTotal: Int get() = catalog.totalCount()

    // ---------------------------------------------------------------------------
    // Detail state — observations for the currently open species
    // ---------------------------------------------------------------------------

    fun observationsForSpecies(speciesKey: String): Flow<List<SpeciesObservation>> =
        repo.observationsForSpecies(speciesKey)

    fun catalogEntry(speciesKey: String): SpeciesCatalogEntry? =
        catalog.byKey(speciesKey)

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    fun toggleFavorite(speciesKey: String, currentValue: Boolean) {
        viewModelScope.launch {
            repo.setFavorite(speciesKey, !currentValue)
        }
    }

    // ---------------------------------------------------------------------------
    // Mapping helpers
    // ---------------------------------------------------------------------------

    private fun SpeciesDexEntry.toDexItem(): DexItem {
        val entry = catalog.byKey(speciesKey)
        val lang = Locale.getDefault().language
        val displayName = entry?.commonNames?.get(lang)
            ?: entry?.commonNames?.get("en")
            ?: scientificName
        return DexItem(
            key = speciesKey,
            displayName = displayName,
            spritePath = entry?.spritePath,
            discovered = true,
            count = timesObserved,
            isFavorite = isFavorite,
            subtitle = scientificName,
        )
    }

    // ---------------------------------------------------------------------------
    // Factory — used by integration layer; wires repo + catalog from DI graph
    // ---------------------------------------------------------------------------

    class Factory(
        private val repo: SpeciesCollectionRepository,
        private val catalog: SpeciesCatalog,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SpeciesDexViewModel::class.java))
            return SpeciesDexViewModel(repo, catalog) as T
        }
    }
}
