package com.moonkata.textreader.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the binary search that scroll-mode navigation (ReaderScrollContent) uses to find the paragraph index at the current position. */
class CollectionExtTest {

    @Test
    fun emptyList_returnsZero() {
        assertEquals(0, emptyList<Int>().binarySearchFloor(5))
    }

    @Test
    fun valueBelowAllElements_fallsBackToZero() {
        // There's no real "floor" here (the value is below even the smallest element), but per
        // the documented fallback behavior it should return 0.
        assertEquals(0, listOf(10, 20, 30).binarySearchFloor(1))
    }

    @Test
    fun valueAboveAllElements_returnsLastIndex() {
        assertEquals(2, listOf(10, 20, 30).binarySearchFloor(999))
    }

    @Test
    fun exactMatch_returnsThatIndex() {
        assertEquals(1, listOf(10, 20, 30).binarySearchFloor(20))
    }

    @Test
    fun valueBetweenTwoElements_returnsTheLowerIndex() {
        assertEquals(1, listOf(10, 20, 30).binarySearchFloor(25))
    }

    @Test
    fun duplicateValues_returnsTheLastMatchingIndex() {
        assertEquals(2, listOf(5, 5, 5).binarySearchFloor(5))
    }

    @Test
    fun singleElementList_matchAndBelow() {
        assertEquals(0, listOf(10).binarySearchFloor(10))
        assertEquals(0, listOf(10).binarySearchFloor(5))
        assertEquals(0, listOf(10).binarySearchFloor(999))
    }
}
