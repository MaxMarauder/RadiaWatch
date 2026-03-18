package com.maxmarauder.radiawatch

import java.time.Instant

internal data class RealTimeData(
    val timestamp: Instant,
    val doseRate: Float,       // μSv/h
    val countRate: Float,      // CPS
    val doseRateErr: Float,
    val countRateErr: Float,
)

internal object RadiacodeDataBuf {
    fun decodeLatestRealTime(dataBuf: ByteArray): RealTimeData? {
        val br = ByteReader(dataBuf)
        val baseTime = Instant.now().plusSeconds(128)
        var latest: RealTimeData? = null

        while (br.remaining >= 7) {
            br.readU8() // seq
            val eid = br.readU8()
            val gid = br.readU8()
            val tsOffset = br.readI32LE()
            val recordTime = baseTime.plusMillis(tsOffset.toLong() * 10L)

            when {
                eid == 0 && gid == 0 -> {
                    if (br.remaining < (4 + 4 + 2 + 2 + 2 + 1)) break
                    val countRate = br.readF32LE()
                    val doseRate = br.readF32LE()
                    val countRateErr = br.readU16LE() / 10.0f
                    val doseRateErr = br.readU16LE() / 10.0f
                    br.readU16LE() // flags
                    br.readU8()    // realTimeFlags
                    latest = RealTimeData(
                        timestamp = recordTime,
                        doseRate = doseRate,
                        countRate = countRate,
                        doseRateErr = doseRateErr,
                        countRateErr = countRateErr,
                    )
                }
                eid == 0 && gid == 1 -> { if (br.remaining < 8) break; br.readBytes(8) }
                eid == 0 && gid == 2 -> { if (br.remaining < 16) break; br.readBytes(16) }
                eid == 0 && gid == 3 -> { if (br.remaining < 14) break; br.readBytes(14) }
                eid == 0 && (gid == 4 || gid == 5) -> { if (br.remaining < 16) break; br.readBytes(16) }
                eid == 0 && gid == 6 -> { if (br.remaining < 6) break; br.readBytes(6) }
                eid == 0 && gid == 7 -> { if (br.remaining < 4) break; br.readBytes(4) }
                eid == 0 && (gid == 8 || gid == 9) -> { if (br.remaining < 6) break; br.readBytes(6) }
                eid == 1 && gid in 1..3 -> {
                    if (br.remaining < 6) break
                    val samplesNum = br.readU16LE()
                    br.readU32LE()
                    val bytesPerSample = when (gid) { 1 -> 8; 2 -> 16; else -> 14 }
                    val toSkip = bytesPerSample.toLong() * samplesNum.toLong()
                    if (toSkip > Int.MAX_VALUE.toLong()) break
                    val n = toSkip.toInt()
                    if (br.remaining < n) break
                    br.readBytes(n)
                }
                else -> break
            }
        }

        return latest
    }
}
