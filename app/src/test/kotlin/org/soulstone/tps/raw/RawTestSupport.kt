package org.soulstone.tps.raw

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import org.soulstone.tps.gnss.Constellation

/** Writes big-endian bit fields, two's complement for negatives. */
class BitWriter(size: Int) {
    val bits = BooleanArray(size)

    fun put(start: Int, length: Int, value: Long) {
        for (i in 0 until length) bits[start + i] = (value ushr (length - 1 - i)) and 1L == 1L
    }

    fun bytes(): ByteArray = Bits.of(bits).let { b -> ByteArray((bits.size + 7) / 8) { i -> b.u(i * 8, 8).toInt().toByte() } }
}

private fun q(value: Double, scale: Double) = (value / scale).roundToLong()

/** Encodes GPS LNAV subframes into Android's 40-byte layout, with IS-GPS-200 parity. */
object LnavEncoder {

    private fun word(d29: Int, d30: Int, data: Int): Int {
        val w = (d29 shl 31) or (d30 shl 30) or (data shl 6)
        var p = 0
        for (mask in GpsLnav.HAMMING) {
            var v = (w and mask) ushr 6
            var bit = 0
            while (v != 0) { bit = bit xor (v and 1); v = v ushr 1 }
            p = (p shl 1) or bit
        }
        val sent = if (d30 == 1) data xor 0xFFFFFF else data
        return (sent shl 6) or p
    }

    /** [data]: 240 bits (ten 24-bit words). Words 2 and 10 get their last two bits solved so they end in 00. */
    fun subframe(data: BitWriter, alreadyCorrected: Boolean = false): ByteArray {
        val out = ByteArray(40)
        var d29 = 0
        var d30 = 0
        for (w in 0 until 10) {
            var d = 0
            for (b in 0 until 24) d = (d shl 1) or (if (data.bits[w * 24 + b]) 1 else 0)
            var encoded = word(d29, d30, d)
            if (w == 1 || w == 9) {
                encoded = (0..3).map { t -> word(d29, d30, (d and 0xFFFFFC) or t) }.first { it and 3 == 0 }
            }
            // A chip that undoes the inversion itself hands over the original data bits.
            val delivered = if (alreadyCorrected && d30 == 1) encoded xor 0x3FFFFFC0 else encoded
            out[4 * w] = ((delivered ushr 24) and 0x3F).toByte()
            out[4 * w + 1] = (delivered ushr 16).toByte()
            out[4 * w + 2] = (delivered ushr 8).toByte()
            out[4 * w + 3] = delivered.toByte()
            d29 = (encoded ushr 1) and 1
            d30 = encoded and 1
        }
        return out
    }

    private fun at(word: Int, bit: Int) = (word - 1) * 24 + (bit - 1)

    private fun frame(subframeId: Int, tow: Int): BitWriter {
        val w = BitWriter(240)
        w.put(at(1, 1), 8, 0x8B)
        w.put(at(2, 1), 17, (tow / 6).toLong())
        w.put(at(2, 20), 3, subframeId.toLong())
        return w
    }

    /** Subframes 1–3 of [e], which should already be on the LNAV grid (see [quantize]). */
    fun subframes(e: KeplerEphemeris, tow: Int, alreadyCorrected: Boolean = false): List<ByteArray> {
        val two = 2.0
        val s1 = frame(1, tow).apply {
            put(at(3, 1), 10, 2400)
            put(at(3, 17), 6, if (e.healthy) 0 else 1)
            put(at(3, 23), 2, 0)
            put(at(7, 17), 8, q(e.groupDelay, two.pow(-31)))
            put(at(8, 1), 8, e.iod.toLong())
            put(at(8, 9), 16, q(e.toc, 16.0))
            put(at(9, 1), 8, q(e.af2, two.pow(-55)))
            put(at(9, 9), 16, q(e.af1, two.pow(-43)))
            put(at(10, 1), 22, q(e.af0, two.pow(-31)))
        }
        val s2 = frame(2, tow + 6).apply {
            put(at(3, 1), 8, e.iod.toLong())
            put(at(3, 9), 16, q(e.crs, two.pow(-5)))
            put(at(4, 1), 16, q(e.deltaN / PI, two.pow(-43)))
            put(at(4, 17), 32, q(e.m0 / PI, two.pow(-31)))
            put(at(6, 1), 16, q(e.cuc, two.pow(-29)))
            put(at(6, 17), 32, q(e.e, two.pow(-33)))
            put(at(8, 1), 16, q(e.cus, two.pow(-29)))
            put(at(8, 17), 32, q(e.sqrtA, two.pow(-19)))
            put(at(10, 1), 16, q(e.toe, 16.0))
        }
        val s3 = frame(3, tow + 12).apply {
            put(at(3, 1), 16, q(e.cic, two.pow(-29)))
            put(at(3, 17), 32, q(e.omega0 / PI, two.pow(-31)))
            put(at(5, 1), 16, q(e.cis, two.pow(-29)))
            put(at(5, 17), 32, q(e.i0 / PI, two.pow(-31)))
            put(at(7, 1), 16, q(e.crc, two.pow(-5)))
            put(at(7, 17), 32, q(e.omega / PI, two.pow(-31)))
            put(at(9, 1), 24, q(e.omegaDot / PI, two.pow(-43)))
            put(at(10, 1), 8, e.iod.toLong())
            put(at(10, 9), 14, q(e.idot / PI, two.pow(-43)))
        }
        return listOf(s1, s2, s3).map { subframe(it, alreadyCorrected) }
    }

    /** [e] rounded to the values LNAV can carry, so encode → decode is exact. */
    fun quantize(e: KeplerEphemeris): KeplerEphemeris {
        val two = 2.0
        fun r(v: Double, s: Double) = q(v, s) * s
        fun rp(v: Double, s: Double) = q(v / PI, s) * s * PI
        return e.copy(
            toe = r(e.toe, 16.0), toc = r(e.toc, 16.0), sqrtA = r(e.sqrtA, two.pow(-19)), e = r(e.e, two.pow(-33)),
            i0 = rp(e.i0, two.pow(-31)), omega0 = rp(e.omega0, two.pow(-31)), omega = rp(e.omega, two.pow(-31)),
            m0 = rp(e.m0, two.pow(-31)), deltaN = rp(e.deltaN, two.pow(-43)), idot = rp(e.idot, two.pow(-43)),
            omegaDot = rp(e.omegaDot, two.pow(-43)), cuc = r(e.cuc, two.pow(-29)), cus = r(e.cus, two.pow(-29)),
            crc = r(e.crc, two.pow(-5)), crs = r(e.crs, two.pow(-5)), cic = r(e.cic, two.pow(-29)), cis = r(e.cis, two.pow(-29)),
            af0 = r(e.af0, two.pow(-31)), af1 = r(e.af1, two.pow(-43)), af2 = r(e.af2, two.pow(-55)),
            groupDelay = r(e.groupDelay, two.pow(-31)),
        )
    }
}

/** Encodes Galileo I/NAV word types 1–5 into Android's 29-byte page layout, with CRC-24Q. */
object InavEncoder {

    fun page(word: BitWriter): ByteArray {
        val p = BitWriter(232)
        p.put(0, 1, 0)
        p.put(1, 1, 0)
        for (i in 0 until 112) p.bits[2 + i] = word.bits[i]
        p.put(114, 1, 1)
        p.put(115, 1, 0)
        for (i in 0 until 16) p.bits[116 + i] = word.bits[112 + i]
        val crc = GalileoInav.crc24q(Bits.of(p.bits), 0, 196)
        p.put(196, 24, crc.toLong())
        return p.bytes().copyOf(29)
    }

    fun pages(e: KeplerEphemeris, bgd: Double = e.groupDelay): List<ByteArray> {
        val two = 2.0
        val w1 = BitWriter(128).apply {
            put(0, 6, 1); put(6, 10, e.iod.toLong()); put(16, 14, q(e.toe, 60.0))
            put(30, 32, q(e.m0 / PI, two.pow(-31))); put(62, 32, q(e.e, two.pow(-33))); put(94, 32, q(e.sqrtA, two.pow(-19)))
        }
        val w2 = BitWriter(128).apply {
            put(0, 6, 2); put(6, 10, e.iod.toLong())
            put(16, 32, q(e.omega0 / PI, two.pow(-31))); put(48, 32, q(e.i0 / PI, two.pow(-31)))
            put(80, 32, q(e.omega / PI, two.pow(-31))); put(112, 14, q(e.idot / PI, two.pow(-43)))
        }
        val w3 = BitWriter(128).apply {
            put(0, 6, 3); put(6, 10, e.iod.toLong())
            put(16, 24, q(e.omegaDot / PI, two.pow(-43))); put(40, 16, q(e.deltaN / PI, two.pow(-43)))
            put(56, 16, q(e.cuc, two.pow(-29))); put(72, 16, q(e.cus, two.pow(-29)))
            put(88, 16, q(e.crc, two.pow(-5))); put(104, 16, q(e.crs, two.pow(-5)))
        }
        val w4 = BitWriter(128).apply {
            put(0, 6, 4); put(6, 10, e.iod.toLong()); put(16, 6, e.svid.toLong())
            put(22, 16, q(e.cic, two.pow(-29))); put(38, 16, q(e.cis, two.pow(-29)))
            put(54, 14, q(e.toc, 60.0)); put(68, 31, q(e.af0, two.pow(-34)))
            put(99, 21, q(e.af1, two.pow(-46))); put(120, 6, q(e.af2, two.pow(-59)))
        }
        val w5 = BitWriter(128).apply {
            put(0, 6, 5); put(57, 10, q(bgd, two.pow(-32)))
        }
        return listOf(w1, w2, w3, w4, w5).map { page(it) }
    }

    fun quantize(e: KeplerEphemeris): KeplerEphemeris {
        val two = 2.0
        fun r(v: Double, s: Double) = q(v, s) * s
        fun rp(v: Double, s: Double) = q(v / PI, s) * s * PI
        return e.copy(
            toe = r(e.toe, 60.0), toc = r(e.toc, 60.0), sqrtA = r(e.sqrtA, two.pow(-19)), e = r(e.e, two.pow(-33)),
            i0 = rp(e.i0, two.pow(-31)), omega0 = rp(e.omega0, two.pow(-31)), omega = rp(e.omega, two.pow(-31)),
            m0 = rp(e.m0, two.pow(-31)), deltaN = rp(e.deltaN, two.pow(-43)), idot = rp(e.idot, two.pow(-43)),
            omegaDot = rp(e.omegaDot, two.pow(-43)), cuc = r(e.cuc, two.pow(-29)), cus = r(e.cus, two.pow(-29)),
            crc = r(e.crc, two.pow(-5)), crs = r(e.crs, two.pow(-5)), cic = r(e.cic, two.pow(-29)), cis = r(e.cis, two.pow(-29)),
            af0 = r(e.af0, two.pow(-34)), af1 = r(e.af1, two.pow(-46)), af2 = r(e.af2, two.pow(-59)),
            groupDelay = r(e.groupDelay, two.pow(-32)),
        )
    }
}

/** Plausible broadcast orbits: six planes for GPS, three for Galileo. */
object SyntheticOrbits {
    const val TOE = 345_600.0   // Thursday 00:00 of the GPS week

    fun gps(): List<KeplerEphemeris> = List(24) { k ->
        val plane = k / 4
        LnavEncoder.quantize(
            base(Constellation.GPS, k + 1, 5153.7, 55.0, 60.0 * plane + 10, 90.0 * (k % 4) + 15.0 * plane, k)
        )
    }

    fun galileo(): List<KeplerEphemeris> = List(24) { k ->
        val plane = k / 8
        InavEncoder.quantize(
            base(Constellation.GALILEO, k + 1, 5440.6, 56.0, 120.0 * plane + 40, 45.0 * (k % 8) + 15.0 * plane, k)
                .copy(toe = 345_600.0, toc = 345_600.0)
        )
    }

    private fun base(c: Constellation, svid: Int, sqrtA: Double, incl: Double, raan: Double, anomaly: Double, k: Int) =
        KeplerEphemeris(
            constellation = c, svid = svid, iod = (17 + k) % 256, toe = TOE, toc = TOE,
            sqrtA = sqrtA, e = 0.004 + 0.0007 * k, i0 = Math.toRadians(incl + 0.1 * (k % 3)),
            omega0 = Math.toRadians(raan - 180), omega = Math.toRadians(30.0 + 7 * k), m0 = Math.toRadians(anomaly - 180),
            deltaN = 4.5e-9, idot = 1.2e-10, omegaDot = -8.1e-9,
            cuc = 2.1e-6, cus = 8.3e-6, crc = 230.0, crs = 45.0, cic = 1.1e-7, cis = -6.0e-8,
            af0 = 1.3e-4 * (if (k % 2 == 0) 1 else -1), af1 = 2.0e-12, af2 = 0.0, groupDelay = -1.1e-8, healthy = true,
        )

    /** Seconds of the week, wrapped. */
    fun wrap(t: Double) = t - floor(t / 604_800.0) * 604_800.0
}
