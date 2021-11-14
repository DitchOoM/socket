package com.ditchoom.data

import kotlin.experimental.and

operator fun Byte.get(bitNumber: Int): Boolean {
    require(bitNumber in 0..7)
    val shift = 7 - bitNumber
    val bitMask = (1 shl shift).toByte()
    val masked = (this and bitMask)
    return masked.toInt() != 0
}

fun Byte.last4Bits() = (toUByte() and 15u).toByte()

fun Byte.toBooleanArray(): BooleanArray {
    val booleanArray = BooleanArray(8)
    booleanArray[0] = get(0)
    booleanArray[1] = get(1)
    booleanArray[2] = get(2)
    booleanArray[3] = get(3)
    booleanArray[4] = get(4)
    booleanArray[5] = get(5)
    booleanArray[6] = get(6)
    booleanArray[7] = get(7)
    return booleanArray
}
fun BooleanArray.toByte(): Byte {
    var b = 0
    for (i in 0..7) {
        if (this[i]) b = b or (1 shl 7 - i)
    }
    return b.toByte()
}