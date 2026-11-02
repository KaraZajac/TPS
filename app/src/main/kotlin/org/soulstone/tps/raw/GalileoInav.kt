package org.soulstone.tps.raw

import kotlin.math.PI
import kotlin.math.pow
import org.soulstone.tps.gnss.Constellation

/**
 * Galileo I/NAV on E1-B (Galileo OS SIS ICD, section 4.3).
 *
 * Android delivers one nominal page per message: the even and odd page parts, 114 bits
 * each, back to back in 29 bytes. The even part carries 112 bits of a 128-bit word, the
 * odd part the last 16, and a CRC-24Q covers both.
 */
object GalileoInav {

    /** CRC-24Q (polynomial 0x864CFB, initial value 0), bit by bit over [length] bits from [start]. */
    fun crc24q(bits: Bits, start: Int, length: Int): Int {
        var crc = 0
        for (i in start until start + length) {
            val top = (crc ushr 23) and 1
            crc = (crc shl 1) and 0xFFFFFF
            if (top xor bits.bit(i) == 1) crc = crc xor 0x864CFB
        }
        return crc
    }

    /** The 128-bit word of a page, or null for a bad CRC, an alert page or the wrong layout. */
    fun word(message: ByteArray): Bits? {
        if (message.size < 29) return null
        val b = Bits(message)
        if (b.bit(0) != 0 || b.bit(114) != 1) return null    // even part, then odd part
        if (b.bit(1) != 0 || b.bit(115) != 0) return null    // alert pages carry no ephemeris
        if (crc24q(b, 0, 196) != b.u(196, 24).toInt()) return null
        val word = BooleanArray(128)
        for (i in 0 until 112) word[i] = b.bit(2 + i) == 1
        for (i in 0 until 16) word[112 + i] = b.bit(116 + i) == 1
        return Bits.of(word)
    }

    fun wordType(w: Bits): Int = w.u(0, 6).toInt()

    private val P2 = 2.0

    class Word1(val iod: Int, val toe: Double, val m0: Double, val e: Double, val sqrtA: Double)
    class Word2(val iod: Int, val omega0: Double, val i0: Double, val omega: Double, val idot: Double)
    class Word3(val iod: Int, val omegaDot: Double, val deltaN: Double, val cuc: Double, val cus: Double, val crc: Double, val crs: Double)
    class Word4(val iod: Int, val svid: Int, val cic: Double, val cis: Double, val toc: Double, val af0: Double, val af1: Double, val af2: Double)
    class Word5(val bgdE1E5b: Double, val e1bHealth: Int, val e1bDataValid: Boolean)

    fun word1(w: Bits) = Word1(
        iod = w.u(6, 10).toInt(),
        toe = w.u(16, 14) * 60.0,
        m0 = w.s(30, 32) * P2.pow(-31) * PI,
        e = w.u(62, 32) * P2.pow(-33),
        sqrtA = w.u(94, 32) * P2.pow(-19),
    )

    fun word2(w: Bits) = Word2(
        iod = w.u(6, 10).toInt(),
        omega0 = w.s(16, 32) * P2.pow(-31) * PI,
        i0 = w.s(48, 32) * P2.pow(-31) * PI,
        omega = w.s(80, 32) * P2.pow(-31) * PI,
        idot = w.s(112, 14) * P2.pow(-43) * PI,
    )

    fun word3(w: Bits) = Word3(
        iod = w.u(6, 10).toInt(),
        omegaDot = w.s(16, 24) * P2.pow(-43) * PI,
        deltaN = w.s(40, 16) * P2.pow(-43) * PI,
        cuc = w.s(56, 16) * P2.pow(-29),
        cus = w.s(72, 16) * P2.pow(-29),
        crc = w.s(88, 16) * P2.pow(-5),
        crs = w.s(104, 16) * P2.pow(-5),
    )

    fun word4(w: Bits) = Word4(
        iod = w.u(6, 10).toInt(),
        svid = w.u(16, 6).toInt(),
        cic = w.s(22, 16) * P2.pow(-29),
        cis = w.s(38, 16) * P2.pow(-29),
        toc = w.u(54, 14) * 60.0,
        af0 = w.s(68, 31) * P2.pow(-34),
        af1 = w.s(99, 21) * P2.pow(-46),
        af2 = w.s(120, 6) * P2.pow(-59),
    )

    fun word5(w: Bits) = Word5(
        bgdE1E5b = w.s(57, 10) * P2.pow(-32),
        e1bHealth = w.u(69, 2).toInt(),
        e1bDataValid = w.u(72, 1) == 0L,
    )
}

/** Collects I/NAV word types 1–5 per satellite into ephemerides (word types 1–4 must share an IODnav). */
class GalileoInavAssembler {
    private val w1 = HashMap<Int, GalileoInav.Word1>()
    private val w2 = HashMap<Int, GalileoInav.Word2>()
    private val w3 = HashMap<Int, GalileoInav.Word3>()
    private val w4 = HashMap<Int, GalileoInav.Word4>()
    private val w5 = HashMap<Int, GalileoInav.Word5>()

    var rejected = 0
        private set

    fun accept(svid: Int, data: ByteArray): KeplerEphemeris? {
        val w = GalileoInav.word(data) ?: run { rejected++; return null }
        when (GalileoInav.wordType(w)) {
            1 -> w1[svid] = GalileoInav.word1(w)
            2 -> w2[svid] = GalileoInav.word2(w)
            3 -> w3[svid] = GalileoInav.word3(w)
            4 -> w4[svid] = GalileoInav.word4(w)
            5 -> w5[svid] = GalileoInav.word5(w)
            else -> return null
        }
        val a = w1[svid] ?: return null
        val b = w2[svid] ?: return null
        val c = w3[svid] ?: return null
        val d = w4[svid] ?: return null
        if (a.iod != b.iod || a.iod != c.iod || a.iod != d.iod || d.svid != svid) return null
        val health = w5[svid]
        return KeplerEphemeris(
            constellation = Constellation.GALILEO, svid = svid, iod = a.iod, toe = a.toe, toc = d.toc,
            sqrtA = a.sqrtA, e = a.e, i0 = b.i0, omega0 = b.omega0, omega = b.omega, m0 = a.m0,
            deltaN = c.deltaN, idot = b.idot, omegaDot = c.omegaDot,
            cuc = c.cuc, cus = c.cus, crc = c.crc, crs = c.crs, cic = d.cic, cis = d.cis,
            af0 = d.af0, af1 = d.af1, af2 = d.af2,
            // E1 single-frequency users correct with BGD(E1,E5b); 0 until word 5 arrives.
            groupDelay = health?.bgdE1E5b ?: 0.0,
            healthy = health == null || (health.e1bHealth == 0 && health.e1bDataValid),
        )
    }
}
