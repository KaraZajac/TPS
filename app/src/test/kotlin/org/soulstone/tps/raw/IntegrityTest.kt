package org.soulstone.tps.raw

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

/**
 * The integrity checks against staged attacks on a synthetic sky ([SyntheticSky]): each must be
 * caught within seconds, at the right level and for the right reason, and a normal sky must
 * raise nothing. The trouble always starts at second 60, once everything has settled.
 */
class IntegrityTest {

    private class Watch(val reports: List<IntegrityReport>, val fixes: List<Pair<FusedFix?, Ecef>>) {
        /** The first second from the attack on that shows [severity] for [check]. */
        fun first(check: Check, severity: Severity): Int? =
            reports.withIndex().firstOrNull { (i, r) -> i >= ATTACK && r.checks.any { it.check == check && it.severity == severity } }?.index
    }

    private fun watch(sky: Sky, seconds: Int = 90): Watch {
        val filter = SuperPosition()
        val monitor = IntegrityMonitor()
        val reports = ArrayList<IntegrityReport>()
        val fixes = ArrayList<Pair<FusedFix?, Ecef>>()
        for (r in SyntheticSky.rounds(sky, seconds)) {
            val f = filter.step(r.clock, r.observations, SyntheticSky.start, null, r.tow)
            fixes += f to r.truth
            reports += monitor.update(
                IntegrityInput(
                    elapsedS = r.t,
                    signals = r.signals,
                    agc = r.agc,
                    fused = f,
                    chip = Geo.toGeodetic(r.truth),
                    chipAccuracyM = 5.0,
                    gnssMinusElapsedMs = r.gnssMinusElapsedMs,
                )
            )
        }
        return Watch(reports, fixes)
    }

    @Test
    fun aNormalSkyRaisesNothing() {
        val w = watch(Sky(), 180)
        val raised = w.reports.withIndex().filter { it.value.level != Severity.OK }
        assertTrue("raised ${raised.firstOrNull()?.let { "at ${it.index}: ${it.value.headline}" }}", raised.isEmpty())
        // And with the receiver gain reported, and steady, still nothing.
        assertTrue(watch(Sky(agcDb = { 3.0 }), 120).reports.all { it.level == Severity.OK })
    }

    @Test
    fun aFakeGpsSignalIsCaughtAndTpsStaysPut() {
        // From second 60, every GPS signal is timed for a place 2 km north.
        val w = watch(Sky(spoof = { t, c -> if (t >= ATTACK && c == Constellation.GPS) 0.0 to 2_000.0 else null }))
        val at = w.first(Check.SYSTEMS, Severity.ALERT)
        assertNotNull("not caught", at)
        assertTrue("caught at $at", at!! <= ATTACK + 5)
        val headline = w.reports[at].headline!!
        assertEquals(Threat.SPOOFING, headline.threat)
        assertTrue(headline.text, headline.text.startsWith("GPS doesn't agree with"))
        // Galileo and GLONASS still agree, so TPS's own position doesn't follow the fake.
        val (f, truth) = w.fixes.last()
        val off = Solver.offset(Geo.toGeodetic(truth), f!!.ecef).let { hypot(it[0], it[1]) }
        assertTrue("TPS followed the fake: $off m off", off < 5.0)
    }

    @Test
    fun aWholeSkyTakeoverShowsAsAJump() {
        // From second 60, every signal is timed for a place 5 km east.
        val w = watch(Sky(spoof = { t, _ -> if (t >= ATTACK) 5_000.0 to 0.0 else null }))
        val at = w.first(Check.JUMPS, Severity.ALERT)
        assertNotNull("no jump raised", at)
        assertTrue("caught at $at", at!! <= ATTACK + 8)
        assertTrue(w.reports[at].headline!!.text, w.reports[at].headline!!.text.contains("5.0 km"))
    }

    @Test
    fun aJumpInGnssTimeIsCaught() {
        val w = watch(Sky(timeJumpMs = { t -> if (t >= ATTACK) 3_000L else 0L }))
        assertEquals(ATTACK, w.first(Check.TIME, Severity.ALERT))
        // Steady time raised nothing before the jump.
        assertTrue(w.reports.take(ATTACK).all { r -> r.checks.first { it.check == Check.TIME }.severity == Severity.OK })
        assertTrue(w.reports[ATTACK].headline!!.text.contains("+3.0 s"))
    }

    @Test
    fun jammingWithoutGainReportsIsACaution() {
        // Signals 15 dB weaker on every system, half the satellites lost.
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 },
            keep = { t, _, svid -> t < ATTACK || svid % 2 == 0 },
        ))
        val at = w.first(Check.SIGNALS, Severity.CAUTION)
        assertNotNull("not caught", at)
        assertTrue("caught at $at", at!! <= ATTACK + 4)
        assertEquals(Threat.JAMMING, w.reports[at].checks.first { it.check == Check.SIGNALS }.threat)
        assertEquals(null, w.first(Check.SIGNALS, Severity.ALERT))
    }

    @Test
    fun jammingWithTheGainFallingIsAnAlert() {
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 },
            agcDb = { t -> if (t >= ATTACK) -8.0 else 3.0 },
        ))
        val at = w.first(Check.SIGNALS, Severity.ALERT)
        assertNotNull("no alert", at)
        assertTrue("caught at $at", at!! <= ATTACK + 5)
        assertEquals(Threat.JAMMING, w.reports[at].headline!!.threat)
    }

    @Test
    fun anOverpoweringTransmitterIsACaution() {
        // To take over a receiver, a transmitter has to be well above the sky: here 16 dB.
        val w = watch(Sky(cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() + if (t >= ATTACK) 16 else 0 }))
        val at = w.first(Check.SIGNALS, Severity.CAUTION)
        assertNotNull(at)
        assertEquals(Threat.SPOOFING, w.reports[at!!].checks.first { it.check == Check.SIGNALS }.threat)
    }

    @Test
    fun walkingOutOfABasementIsNotAnAttack() {
        // A minute of basement-weak signals, then every signal 18 dB stronger: out of the door.
        val w = watch(Sky(cn0 = { t, _, el, r -> 5 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() + if (t >= ATTACK) 18 else 0 }), 120)
        assertEquals(null, w.first(Check.SIGNALS, Severity.CAUTION))
        assertTrue(w.reports.all { it.level == Severity.OK })
    }

    @Test
    fun oneTransmitterLooksTooEven() {
        val w = watch(Sky(cn0 = { t, _, el, r -> if (t >= ATTACK) 44 + 0.3 * r.nextGaussian() else 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() }))
        val at = w.first(Check.PATTERN, Severity.CAUTION)
        assertNotNull(at)
        assertTrue("caught at $at", at!! <= ATTACK + 12)
    }

    @Test
    fun aClockJumpIsNoted() {
        val w = watch(Sky(steered = false, clockStep = { t -> if (t == ATTACK) 600.0 else 0.0 }))
        assertEquals(ATTACK, w.first(Check.CLOCK, Severity.CAUTION))
    }

    @Test
    fun aWarningFadesWhenItsCauseEnds() {
        // Ten seconds of jamming, then a clean sky.
        val w = watch(Sky(cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t in ATTACK until ATTACK + 10) 15 else 0 }), 120)
        assertNotNull(w.first(Check.SIGNALS, Severity.CAUTION))
        assertEquals(Severity.OK, w.reports.last().level)
    }

    private companion object {
        const val ATTACK = 60
    }
}
