package io.github.cozycode890.guzz.core.common

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Như `round(x, ndigits)` của Python với số thực: làm tròn nửa về số chẵn trên **giá trị nhị phân chính xác** của x
 * (`round(2.675, 2)` = 2.67 vì 2.675 thật ra là 2.67499…). Dùng cho các mốc giây ghi ra JSON giống desktop.
 */
fun pyRound(x: Double, ndigits: Int): Double {
    if (x.isNaN() || x.isInfinite()) return x
    return BigDecimal(x).setScale(ndigits, RoundingMode.HALF_EVEN).toDouble()
}

/** Như `round(x)` của Python (không có ndigits): ra số nguyên, nửa về số chẵn. */
fun pyRoundToLong(x: Double): Long = Math.rint(x).toLong()
