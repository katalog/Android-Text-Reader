package com.moonkata.textreader.util

/** Finds the index of the last element <= value in a sorted list (0 if none). */
fun List<Int>.binarySearchFloor(value: Int): Int {
    if (isEmpty()) return 0
    var lo = 0
    var hi = size - 1
    var result = 0
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (this[mid] <= value) {
            result = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result
}
