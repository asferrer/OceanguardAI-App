package com.oceanguard.ai.species

import com.oceanguard.ai.ui.screens.SpeciesBatchItemState
import com.oceanguard.ai.ui.screens.SpeciesBatchItem
import com.oceanguard.ai.ui.screens.SpeciesBatchUiState
import com.oceanguard.ai.ui.screens.SpeciesUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [SpeciesBatchUiState] / [SpeciesBatchItemState] state models
 * and the [SpeciesUiState] sealed interface — pure data-layer behaviour with no
 * Android runtime, Room, or ViewModel scope required.
 *
 * Rationale: [SpeciesViewModel] delegates I/O to [ImagePreprocessor] (concrete
 * class, Context-dependent) and [LocationProvider] (concrete class, Play-Services
 * dependent), making full VM integration tests unsuitable for a plain JVM harness.
 * These tests verify the data model contracts that the VM builds on, which is the
 * stable surface tested across recompositions.
 */
class SpeciesViewModelTest {

    // -----------------------------------------------------------------------
    // SpeciesUiState — sealed interface contracts
    // -----------------------------------------------------------------------

    @Test
    fun `SpeciesUiState Idle has no results or error`() {
        val state: SpeciesUiState = SpeciesUiState.Idle
        assertNotNull(state)
    }

    @Test
    fun `SpeciesUiState Complete holds result list`() {
        val results = listOf(
            com.oceanguard.ai.inference.species.IdentificationResult(
                speciesKey     = "sp_A",
                scientificName = "Species albus",
                idSource       = com.oceanguard.ai.data.species.SpeciesIdSource.RAG_CORE,
                cosineScore    = 0.95f,
                confidence     = 0.90f,
            )
        )
        val state = SpeciesUiState.Complete(results)
        assertEquals(1, state.results.size)
        assertEquals("sp_A", state.results[0].speciesKey)
    }

    @Test
    fun `SpeciesUiState Error holds message`() {
        val state = SpeciesUiState.Error("disk error")
        assertEquals("disk error", state.message)
    }

    // -----------------------------------------------------------------------
    // SpeciesBatchItemState transitions
    // -----------------------------------------------------------------------

    @Test
    fun `SpeciesBatchItemState Pending is initial state of SpeciesBatchItem`() {
        val item = SpeciesBatchItem(
            uri = org.mockito.Mockito.mock(android.net.Uri::class.java),
        )
        assertTrue(item.state is SpeciesBatchItemState.Pending)
    }

    @Test
    fun `SpeciesBatchItemState Done holds identification results`() {
        val result = com.oceanguard.ai.inference.species.IdentificationResult(
            speciesKey     = "sp_B",
            scientificName = "Species beta",
            idSource       = com.oceanguard.ai.data.species.SpeciesIdSource.RAG_TENTATIVE,
            cosineScore    = 0.5f,
            confidence     = 0.55f,
        )
        val done = SpeciesBatchItemState.Done(listOf(result))
        assertEquals(1, done.results.size)
        assertEquals("sp_B", done.results[0].speciesKey)
    }

    @Test
    fun `SpeciesBatchItemState Failed holds error message`() {
        val failed = SpeciesBatchItemState.Failed("network error")
        assertEquals("network error", failed.message)
    }

    @Test
    fun `SpeciesBatchItem copy transitions state correctly`() {
        val uri = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val item = SpeciesBatchItem(uri)

        val analyzing = item.copy(state = SpeciesBatchItemState.Analyzing)
        assertTrue(analyzing.state is SpeciesBatchItemState.Analyzing)
        assertTrue(analyzing.uri === uri)

        val done = analyzing.copy(state = SpeciesBatchItemState.Done(emptyList()))
        assertTrue(done.state is SpeciesBatchItemState.Done)
    }

    // -----------------------------------------------------------------------
    // SpeciesBatchUiState — sealed class contracts
    // -----------------------------------------------------------------------

    @Test
    fun `SpeciesBatchUiState Idle is initial`() {
        val state: SpeciesBatchUiState = SpeciesBatchUiState.Idle
        assertNotNull(state)
    }

    @Test
    fun `SpeciesBatchUiState Running holds items and index`() {
        val uris = listOf(
            org.mockito.Mockito.mock(android.net.Uri::class.java),
            org.mockito.Mockito.mock(android.net.Uri::class.java),
        )
        val items = uris.map { SpeciesBatchItem(it) }
        val running = SpeciesBatchUiState.Running(items, currentIndex = 1, startTimeMs = 1000L)

        assertEquals(2, running.items.size)
        assertEquals(1, running.currentIndex)
        assertEquals(1000L, running.startTimeMs)
        assertTrue(running.items[0].state is SpeciesBatchItemState.Pending)
    }

    @Test
    fun `SpeciesBatchUiState Complete has non-negative elapsed time`() {
        val uri = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val items = listOf(
            SpeciesBatchItem(uri, SpeciesBatchItemState.Done(emptyList()))
        )
        val startMs = 1_000L
        val endMs   = 5_000L
        val complete = SpeciesBatchUiState.Complete(items, startMs, endMs)

        assertTrue(complete.endTimeMs >= complete.startTimeMs)
        assertEquals(4_000L, complete.endTimeMs - complete.startTimeMs)
    }

    @Test
    fun `SpeciesBatchUiState Complete mixed items are preserved`() {
        val uri1 = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val uri2 = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val items = listOf(
            SpeciesBatchItem(uri1, SpeciesBatchItemState.Done(emptyList())),
            SpeciesBatchItem(uri2, SpeciesBatchItemState.Failed("error")),
        )
        val complete = SpeciesBatchUiState.Complete(items, 0L, 100L)

        assertEquals(2, complete.items.size)
        assertTrue(complete.items[0].state is SpeciesBatchItemState.Done)
        assertTrue(complete.items[1].state is SpeciesBatchItemState.Failed)
    }

    // -----------------------------------------------------------------------
    // Species count computation — mirrors what SpeciesBatchResultScreen computes
    // -----------------------------------------------------------------------

    @Test
    fun `total species found sums results across Done items`() {
        val result = com.oceanguard.ai.inference.species.IdentificationResult(
            speciesKey     = "sp_X",
            scientificName = "Species X",
            idSource       = com.oceanguard.ai.data.species.SpeciesIdSource.RAG_CORE,
            cosineScore    = 0.9f,
            confidence     = 0.85f,
        )
        val uri = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val items = listOf(
            SpeciesBatchItem(uri, SpeciesBatchItemState.Done(listOf(result, result))),
            SpeciesBatchItem(uri, SpeciesBatchItemState.Failed("err")),
            SpeciesBatchItem(uri, SpeciesBatchItemState.Done(listOf(result))),
        )
        val totalSpecies = items.sumOf { item ->
            (item.state as? SpeciesBatchItemState.Done)?.results?.size ?: 0
        }
        assertEquals(3, totalSpecies)
    }

    @Test
    fun `success rate computed correctly from items`() {
        val uri = org.mockito.Mockito.mock(android.net.Uri::class.java)
        val items = listOf(
            SpeciesBatchItem(uri, SpeciesBatchItemState.Done(emptyList())),
            SpeciesBatchItem(uri, SpeciesBatchItemState.Done(emptyList())),
            SpeciesBatchItem(uri, SpeciesBatchItemState.Failed("err")),
        )
        val total = items.size
        val failed = items.count { it.state is SpeciesBatchItemState.Failed }
        val successPct = ((total - failed) * 100) / total
        assertEquals(66, successPct)
    }
}
