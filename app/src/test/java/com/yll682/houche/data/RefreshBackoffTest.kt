package com.yll682.houche.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshBackoffTest {
    @Test fun successAndRepeatedFailuresUse15Then30Then60Then60Seconds() {
        val backoff = RefreshBackoff(15)
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 60_000L, 60_000L),
            listOf(false, true, true, true, true).map(backoff::nextMillis))
    }

    @Test fun everySuccessResetsTheFailureSequence() {
        val backoff = RefreshBackoff(15)
        assertEquals(listOf(30_000L, 60_000L, 15_000L, 30_000L, 15_000L, 30_000L),
            listOf(true, true, false, true, false, true).map(backoff::nextMillis))
    }

    @Test fun failuresBeyondTheSecondKeepFourTimesTheConfiguredInterval() {
        val backoff = RefreshBackoff(10)
        assertEquals(20_000L, backoff.nextMillis(true))
        assertEquals(List(8) { 40_000L }, List(8) { backoff.nextMillis(true) })
        assertEquals(10_000L, backoff.nextMillis(false))
        assertEquals(20_000L, backoff.nextMillis(true))
    }

    @Test fun onlyFailureDelaysAreCappedAt60Seconds() {
        val backoff = RefreshBackoff(120)
        assertEquals(listOf(120_000L, 60_000L, 60_000L, 120_000L, 60_000L),
            listOf(false, true, true, false, true).map(backoff::nextMillis))
    }

    @Test fun eachSelectableIntervalHasTheExpectedBackoff() {
        mapOf(15 to listOf(30_000L, 60_000L), 20 to listOf(40_000L, 60_000L),
            30 to listOf(60_000L, 60_000L), 60 to listOf(60_000L, 60_000L),
            120 to listOf(60_000L, 60_000L)).forEach { (seconds, failures) ->
            val backoff = RefreshBackoff(seconds)
            assertEquals(seconds * 1000L, backoff.nextMillis(false))
            assertEquals(failures, List(2) { backoff.nextMillis(true) })
            assertEquals(seconds * 1000L, backoff.nextMillis(false))
        }
    }

    @Test fun manualOnlyHasNoFiniteDelay() {
        val backoff = RefreshBackoff(0)
        assertEquals(Long.MAX_VALUE, backoff.nextMillis(false))
        assertEquals(Long.MAX_VALUE, backoff.nextMillis(true))
    }
}
