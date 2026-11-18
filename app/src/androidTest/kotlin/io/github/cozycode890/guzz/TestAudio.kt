package io.github.cozycode890.guzz

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.sin

/** WAV 16 kHz mono cho test thiết bị: tiếng 440 Hz ngắt quãng mỗi nửa giây, đủ cho cắt lặng và chuẩn hoá. */
fun writeToneWav(file: File, seconds: Int) {
    file.parentFile?.mkdirs()
    val rate = 16_000
    val n = rate * seconds
    val pcm = ByteArray(n * 2)
    for (i in 0 until n) {
        val on = (i / (rate / 2)) % 2 == 0
        val v = if (on) (sin(2 * PI * 440 * i / rate) * 8000).toInt() else 0
        pcm[2 * i] = (v and 0xff).toByte()
        pcm[2 * i + 1] = (v shr 8 and 0xff).toByte()
    }
    fun le32(x: Int) = byteArrayOf(x.toByte(), (x shr 8).toByte(), (x shr 16).toByte(), (x shr 24).toByte())
    fun le16(x: Int) = byteArrayOf(x.toByte(), (x shr 8).toByte())
    RandomAccessFile(file, "rw").use { f ->
        f.setLength(0)
        f.write("RIFF".toByteArray())
        f.write(le32(36 + pcm.size))
        f.write("WAVEfmt ".toByteArray())
        f.write(le32(16))
        f.write(le16(1))
        f.write(le16(1))
        f.write(le32(rate))
        f.write(le32(rate * 2))
        f.write(le16(2))
        f.write(le16(16))
        f.write("data".toByteArray())
        f.write(le32(pcm.size))
        f.write(pcm)
    }
}
