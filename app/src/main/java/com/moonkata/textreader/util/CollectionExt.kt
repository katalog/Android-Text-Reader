package com.moonkata.textreader.util

/** 정렬된 리스트에서 value 이하인 마지막 원소의 인덱스를 찾는다 (없으면 0). */
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
