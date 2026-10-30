package top.yexkr.findscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import top.yexkr.findscanner.data.ConnectionConfig

class ScannerNavigationTest {
    private val histories = listOf(Page.SampleHistory, Page.InferenceHistory)
    private fun ScannerState.back() = navigateTo(backPage())

    @Test fun historiesOpenedBeforeConnectingReturnToConnection() {
        for (history in histories) {
            val state = ScannerState(page = Page.Connection).navigateTo(history).back()
            assertEquals(Page.Connection, state.page)
            assertNull(state.config)
        }
    }

    @Test fun historiesOpenedFromConnectionSettingsDoNotReusePreviousConnectionToEnterHome() {
        for (history in histories) {
            val state = ScannerState(page = Page.Connection, config = ConnectionConfig("debug", "region", "device"))
                .navigateTo(history).back()
            assertEquals(Page.Connection, state.page)
        }
    }

    @Test fun historiesReturnToTheirEntryPage() {
        for (origin in listOf(Page.Home, Page.Collection, Page.Inference, Page.Permissions)) {
            for (history in histories) {
                val state = ScannerState(page = origin, config = ConnectionConfig("debug", "region", "device"))
                    .navigateTo(history).back()
                assertEquals(origin, state.page)
            }
        }
    }

    @Test fun switchingHistoryListsPreservesConnectionEntry() {
        val state = ScannerState(page = Page.Connection)
            .navigateTo(Page.SampleHistory).navigateTo(Page.InferenceHistory).navigateTo(Page.SampleHistory).back()
        assertEquals(Page.Connection, state.page)
    }

    @Test fun sampleDetailReturnsThroughHistoryToConnection() {
        val detail = ScannerState(page = Page.Connection).navigateTo(Page.SampleHistory)
            .copy(page = Page.Detail, detailFromHistory = true)
        assertEquals(Page.SampleHistory, detail.back().page)
        assertEquals(Page.Connection, detail.back().back().page)
        assertEquals(Page.Connection, detail.navigateTo(Page.InferenceHistory).back().page)
    }

    @Test fun inferenceDetailReturnsThroughHistoryToConnection() {
        val detail = ScannerState(page = Page.Connection).navigateTo(Page.InferenceHistory)
            .copy(page = Page.Inference, inferenceFromHistory = true)
        assertEquals(Page.InferenceHistory, detail.back().page)
        assertEquals(Page.Connection, detail.back().back().page)
        assertEquals(Page.Connection, detail.navigateTo(Page.SampleHistory).back().page)
    }

    @Test fun newHistoryVisitUsesNewEntryPage() {
        val state = ScannerState(page = Page.Connection, config = ConnectionConfig("debug", "region", "device"))
            .navigateTo(Page.SampleHistory).back().navigateTo(Page.Home).navigateTo(Page.InferenceHistory).back()
        assertEquals(Page.Home, state.page)
    }

    @Test fun returningToLiveInferenceDoesNotKeepHistoricalDetailBackTarget() {
        val detail = ScannerState(page = Page.Inference, config = ConnectionConfig("http://test.invalid/", "region", "device"))
            .navigateTo(Page.InferenceHistory).copy(page = Page.Inference, inferenceFromHistory = true)
        val state = detail.back().back()
        assertEquals(Page.Inference, state.page)
        assertEquals(Page.Home, state.back().page)
    }

    @Test fun homeStillRequiresConnectionAndSessionDetailsStillReturnToCollection() {
        assertEquals(Page.Connection, ScannerState().navigateTo(Page.Home).page)
        assertEquals(Page.Collection, ScannerState(page = Page.Detail).back().page)
        assertEquals(Page.Home, ScannerState(page = Page.Inference).backPage())
    }
}
