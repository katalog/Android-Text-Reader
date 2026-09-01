package com.moonkata.textreader.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** 스크롤 모드 내비게이션(ReaderScrollContent)이 현재 위치의 문단 인덱스를 찾을 때 쓰는 이진 탐색 검증. */
class CollectionExtTest {

    @Test
    fun emptyList_returnsZero() {
        assertEquals(0, emptyList<Int>().binarySearchFloor(5))
    }

    @Test
    fun valueBelowAllElements_fallsBackToZero() {
        // 진짜 "floor"는 없지만(가장 작은 원소보다도 작음), 문서화된 폴백 동작대로 0을 반환해야 함.
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
