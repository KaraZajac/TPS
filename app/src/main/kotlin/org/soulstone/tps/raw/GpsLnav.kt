package org.soulstone.tps.raw

import kotlin.math.PI
import kotlin.math.pow
import org.soulstone.tps.gnss.Constellation

/** Reads big-endian bit fields: bit 0 is the most significant bit of the first byte. */
class Bits(private val bytes: ByteArray) {
    val size: Int get() = bytes.size * 8

    fun bit(i: Int): Int = (bytes[i / 8].toInt() shr (7 - i % 8)) and 1

    fun u(start: Int, length: Int): Long {
        var v = 0L
        for (i in start until start + length) v = (v shl 1) or bit(i).toLong()
        return v
    }

    /** Two's complement. */
    fun s(start: Int, length: Int): Long {
        val v = u(start, length)
        return if (v and (1L shl (length - 1)) != 0L) v - (1L shl length) else v
    }

    companion object {
        fun of(bits: BooleanArray): Bits {
            val bytes = ByteArray((bits.size + 7) / 8)
            bits.forEachIndexed { i, b -> if (b) bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
            return Bits(bytes)
        }
    }
}

/** The ionosphere model GPS broadcasts in subframe 4, page 18. */
data class Klobuchar(val alpha: DoubleArray, val beta: DoubleArray)

/**
 * GPS (and QZSS) L1 C/A legacy navigation message (LNAV), IS-GPS-200.
 *
 * Android delivers one subframe per message: ten 30-bit words, each in the low 30 bits
 * of 4 bytes, most significant first. Each word is 24 data bits and 6 parity bits; when
 * the previous word's last parity bit (D30*) is 1, the data bits were sent inverted.
 */
object GpsLnav {

    /** IS-GPS-200 parity, as bit masks over (D29*, D30*, d1..d24). From RTKLIB. */
    internal val HAMMING = intArrayOf(
        0xBB1F3480.toInt(), 0x5D8F9A40, 0xAEC7CD00.toInt(), 0x5763E680, 0x6BB1F340, 0x8B7A89C0.toInt(),
    )

    /**
     * The 240 data bits of a subframe (ten 24-bit words, parity removed), or null if the
     * parity fails. Chips differ on whether they undo the inversion themselves, so both are
     * tried; real data has to pass all ten words one way or the other.
     */
    fun dataBits(message: ByteArray): Bits? {
        if (message.size < 40) return null
        val raw = IntArray(10) { w ->
            ((message[4 * w].toInt() and 0x3F) shl 24) or ((message[4 * w + 1].toInt() and 0xFF) shl 16) or
                ((message[4 * w + 2].toInt() and 0xFF) shl 8) or (message[4 * w + 3].toInt() and 0xFF)
        }
        return decode(raw, alreadyCorrected = false) ?: decode(raw, alreadyCorrected = true)
    }

    private fun decode(raw: IntArray, alreadyCorrected: Boolean): Bits? {
        val out = BooleanArray(240)
        // Words 2 and 10 end in 00 by design, so word 1 starts with D29* = D30* = 0.
        var d29 = 0
        var d30 = 0
        for (w in 0 until 10) {
            val received = raw[w]
            // The word as transmitted: re-invert it if the chip already undid the inversion.
            val sent = if (alreadyCorrected && d30 == 1) received xor 0x3FFFFFC0 else received
            var word = (d29 shl 31) or (d30 shl 30) or sent
            if (d30 == 1) word = word xor 0x3FFFFFC0
            var parity = 0
            for (mask in HAMMING) {
                parity = parity shl 1
                var v = (word and mask) ushr 6
                var p = 0
                while (v != 0) { p = p xor (v and 1); v = v ushr 1 }
                parity = parity or p
            }
            if (parity != (word and 0x3F)) return null
            for (b in 0 until 24) out[w * 24 + b] = (word ushr (29 - b)) and 1 == 1
            d29 = (sent ushr 1) and 1
            d30 = sent and 1
        }
        return Bits.of(out)
    }

    /** Bit offset of data bit [bit] (1–24) of word [word] (1–10) in the 240 data bits. */
    private fun at(word: Int, bit: Int) = (word - 1) * 24 + (bit - 1)

    private val P2 = 2.0
    private fun Bits.u(word: Int, bit: Int, len: Int) = u(at(word, bit), len)
    private fun Bits.s(word: Int, bit: Int, len: Int) = s(at(word, bit), len)

    fun subframeId(d: Bits): Int = d.u(2, 20, 3).toInt()

    /** Time of week at the start of the next subframe, seconds. */
    fun towSeconds(d: Bits): Int = d.u(2, 1, 17).toInt() * 6

    class Subframe1(val week: Int, val health: Int, val iodc: Int, val tgd: Double, val toc: Double, val af2: Double, val af1: Double, val af0: Double)
    class Subframe2(val iode: Int, val crs: Double, val deltaN: Double, val m0: Double, val cuc: Double, val e: Double, val cus: Double, val sqrtA: Double, val toe: Double)
    class Subframe3(val cic: Double, val omega0: Double, val cis: Double, val i0: Double, val crc: Double, val omega: Double, val omegaDot: Double, val iode: Int, val idot: Double)

    fun subframe1(d: Bits) = Subframe1(
        week = d.u(3, 1, 10).toInt(),
        health = d.u(3, 17, 6).toInt(),
        iodc = ((d.u(3, 23, 2) shl 8) or d.u(8, 1, 8)).toInt(),
        tgd = d.s(7, 17, 8) * P2.pow(-31),
        toc = d.u(8, 9, 16) * 16.0,
        af2 = d.s(9, 1, 8) * P2.pow(-55),
        af1 = d.s(9, 9, 16) * P2.pow(-43),
        af0 = d.s(10, 1, 22) * P2.pow(-31),
    )

    fun subframe2(d: Bits) = Subframe2(
        iode = d.u(3, 1, 8).toInt(),
        crs = d.s(3, 9, 16) * P2.pow(-5),
        deltaN = d.s(4, 1, 16) * P2.pow(-43) * PI,
        m0 = d.s(4, 17, 32) * P2.pow(-31) * PI,
        cuc = d.s(6, 1, 16) * P2.pow(-29),
        e = d.u(6, 17, 32) * P2.pow(-33),
        cus = d.s(8, 1, 16) * P2.pow(-29),
        sqrtA = d.u(8, 17, 32) * P2.pow(-19),
        toe = d.u(10, 1, 16) * 16.0,
    )

    fun subframe3(d: Bits) = Subframe3(
        cic = d.s(3, 1, 16) * P2.pow(-29),
        omega0 = d.s(3, 17, 32) * P2.pow(-31) * PI,
        cis = d.s(5, 1, 16) * P2.pow(-29),
        i0 = d.s(5, 17, 32) * P2.pow(-31) * PI,
        crc = d.s(7, 1, 16) * P2.pow(-5),
        omega = d.s(7, 17, 32) * P2.pow(-31) * PI,
        omegaDot = d.s(9, 1, 24) * P2.pow(-43) * PI,
        iode = d.u(10, 1, 8).toInt(),
        idot = d.s(10, 9, 14) * P2.pow(-43) * PI,
    )

    /** Subframe 4, page 18 (SV ID 56): the Klobuchar ionosphere coefficients. Null on other pages. */
    fun ionosphere(d: Bits): Klobuchar? {
        if (subframeId(d) != 4 || d.u(3, 3, 6).toInt() != 56) return null
        return Klobuchar(
            alpha = doubleArrayOf(
                d.s(3, 9, 8) * P2.pow(-30), d.s(3, 17, 8) * P2.pow(-27),
                d.s(4, 1, 8) * P2.pow(-24), d.s(4, 9, 8) * P2.pow(-24),
            ),
            beta = doubleArrayOf(
                d.s(4, 17, 8) * P2.pow(11), d.s(5, 1, 8) * P2.pow(14),
                d.s(5, 9, 8) * P2.pow(16), d.s(5, 17, 8) * P2.pow(16),
            ),
        )
    }
}

/** Collects LNAV subframes 1–3 per satellite into ephemerides, and the ionosphere model. */
class GpsLnavAssembler(private val constellation: Constellation) {
    private val sf1 = HashMap<Int, GpsLnav.Subframe1>()
    private val sf2 = HashMap<Int, GpsLnav.Subframe2>()
    private val sf3 = HashMap<Int, GpsLnav.Subframe3>()

    var klobuchar: Klobuchar? = null
        private set

    /** Subframes that failed parity both ways, for the CHIP card. */
    var rejected = 0
        private set

    /** Feeds one subframe; returns a new ephemeris when subframes 1–3 agree on their issue of data. */
    fun accept(svid: Int, data: ByteArray): KeplerEphemeris? {
        val d = GpsLnav.dataBits(data) ?: run { rejected++; return null }
        when (GpsLnav.subframeId(d)) {
            1 -> sf1[svid] = GpsLnav.subframe1(d)
            2 -> sf2[svid] = GpsLnav.subframe2(d)
            3 -> sf3[svid] = GpsLnav.subframe3(d)
            4 -> { GpsLnav.ionosphere(d)?.let { klobuchar = it }; return null }
            else -> return null
        }
        val a = sf1[svid] ?: return null
        val b = sf2[svid] ?: return null
        val c = sf3[svid] ?: return null
        if (b.iode != c.iode || (a.iodc and 0xFF) != b.iode) return null
        return KeplerEphemeris(
            constellation = constellation, svid = svid, iod = b.iode, toe = b.toe, toc = a.toc,
            sqrtA = b.sqrtA, e = b.e, i0 = c.i0, omega0 = c.omega0, omega = c.omega, m0 = b.m0,
            deltaN = b.deltaN, idot = c.idot, omegaDot = c.omegaDot,
            cuc = b.cuc, cus = b.cus, crc = c.crc, crs = b.crs, cic = c.cic, cis = c.cis,
            af0 = a.af0, af1 = a.af1, af2 = a.af2, groupDelay = a.tgd, healthy = a.health == 0,
        )
    }
}
