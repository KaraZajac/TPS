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
        fun first(check: Check, severity: Severity, from: Int = ATTACK): Int? =
            reports.withIndex().firstOrNull { (i, r) -> i >= from && r.checks.any { it.check == check && it.severity == severity } }?.index

        fun result(at: Int, check: Check) = reports[at].checks.first { it.check == check }
    }

    /**
     * [last] is what TPS remembered from its previous session; [input] adjusts each round's
     * evidence (the phone's clocks are on it already: GNSS time agrees with them).
     */
    private fun watch(
        sky: Sky, seconds: Int = 90, last: Remembered? = null, motion: (Int) -> Motion = { Motion.UNKNOWN },
        input: (Int, IntegrityInput) -> IntegrityInput = { _, i -> i },
    ): Watch {
        val filter = SuperPosition()
        val monitor = IntegrityMonitor()
        if (last != null) monitor.startSession(last)
        val reports = ArrayList<IntegrityReport>()
        val fixes = ArrayList<Pair<FusedFix?, Ecef>>()
        for ((k, r) in SyntheticSky.rounds(sky, seconds).withIndex()) {
            val f = filter.step(r.clock, r.observations, SyntheticSky.start, null, r.tow, motion = motion(k))
            fixes += f to r.truth
            val elapsedMs = BOOT_ELAPSED_MS + k * 1_000L
            reports += monitor.update(
                input(
                    k,
                    IntegrityInput(
                        elapsedS = r.t,
                        signals = r.signals,
                        agc = r.agc,
                        fused = f,
                        chip = Geo.toGeodetic(r.truth),
                        chipAccuracyM = 5.0,
                        gnssMinusElapsedMs = r.gnssMinusElapsedMs,
                        gnssTimeMs = elapsedMs + r.gnssMinusElapsedMs,
                        elapsedMs = elapsedMs,
                        wallMs = elapsedMs + GNSS_MINUS_ELAPSED_MS,
                        bootCount = BOOT,
                        motion = motion(k),
                    ),
                )
            )
        }
        return Watch(reports, fixes)
    }

    /** What TPS would have remembered [agoS] seconds before this session, at [where]. */
    private fun remembered(agoS: Long, where: Geodetic = SyntheticSky.home, boot: Int? = BOOT, gnssShiftMs: Long = 0) = Remembered(
        position = where,
        gnssTimeMs = BOOT_ELAPSED_MS - agoS * 1_000 + GNSS_MINUS_ELAPSED_MS + gnssShiftMs,
        elapsedMs = BOOT_ELAPSED_MS - agoS * 1_000,
        wallMs = BOOT_ELAPSED_MS - agoS * 1_000 + GNSS_MINUS_ELAPSED_MS,
        bootCount = boot,
    )

    @Test
    fun aNormalSkyRaisesNothing() {
        val w = watch(Sky(), 180)
        val raised = w.reports.withIndex().filter { it.value.level != Severity.OK }
        assertTrue("raised ${raised.firstOrNull()?.let { "at ${it.index}: ${it.value.headline}" }}", raised.isEmpty())
        assertEquals("nothing to compare with yet", w.result(0, Check.STARTUP).text)
        assertEquals("the GNSS chip, not a mock-location app", w.result(179, Check.SOURCE).text)
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
    fun aWholeSkyTakeoverIsHeldOffAndCalledOut() {
        // From second 60, every signal is timed for a place 5 km east. No motion sensors.
        val w = watch(Sky(spoof = { t, _ -> if (t >= ATTACK) 5_000.0 to 0.0 else null }))
        val at = w.first(Check.JUMPS, Severity.ALERT)
        assertNotNull("not raised", at)
        assertTrue("caught at $at", at!! <= ATTACK + 8)
        val text = w.reports[at].headline!!.text
        assertTrue(text, text.contains("5.0 km") && text.contains("holds its last position"))
        // TPS didn't follow.
        val (f, truth) = w.fixes.last()
        assertTrue(Solver.offset(Geo.toGeodetic(truth), f!!.ecef).let { hypot(it[0], it[1]) } < 10.0)
    }

    @Test
    fun aTakeoverAtRestIsHeldOffForAsLongAsItLasts() {
        val w = watch(Sky(spoof = { t, _ -> if (t >= ATTACK) 5_000.0 to 0.0 else null }), 180, motion = { Motion.STILL })
        val at = w.first(Check.MOTION, Severity.ALERT)
        assertNotNull("not raised", at)
        assertTrue("caught at $at", at!! <= ATTACK + 5)
        assertTrue(w.result(at, Check.MOTION).text.contains("TPS holds where the phone came to rest"))
        // Still up two minutes later, with TPS where the phone lies.
        assertEquals(Severity.ALERT, w.reports.last().level)
        val (f, truth) = w.fixes.last()
        assertTrue(Solver.offset(Geo.toGeodetic(truth), f!!.ecef).let { hypot(it[0], it[1]) } < 5.0)
        assertEquals("phone at rest", w.result(ATTACK - 1, Check.MOTION).text.substringBefore(";"))
    }

    @Test
    fun aSlowDragAtRestIsACautionThenAnAlert() {
        // 1 m/s east from second 60: a caution once it's beyond reflections' reach, an alert past 200 m.
        val w = watch(Sky(spoof = { t, _ -> if (t >= ATTACK) (t - ATTACK) * 1.0 to 0.0 else null }), 300, motion = { Motion.STILL })
        val caution = w.first(Check.MOTION, Severity.CAUTION)
        val alert = w.first(Check.MOTION, Severity.ALERT)
        assertNotNull(caution)
        assertNotNull(alert)
        assertTrue("caution at $caution, alert at $alert", caution!! < alert!! && alert in ATTACK + 190..ATTACK + 230)
    }

    @Test
    fun withoutMotionSensorsTakingTheSignalsPlaceLeavesACaution() {
        val w = watch(Sky(spoof = { t, _ -> if (t >= ATTACK) 5_000.0 to 0.0 else null }), 240)
        val last = w.result(239, Check.JUMPS)
        assertEquals(Severity.CAUTION, last.severity)
        assertTrue(last.text, last.text.contains("may be false"))
    }

    @Test
    fun aSmoothRideRaisesNothing() {
        // 20 m/s east while the motion sensors feel nothing.
        val w = watch(Sky(path = Path { t -> 20.0 * t to 0.0 }), 120, motion = { Motion.STILL })
        val raised = w.reports.withIndex().filter { it.value.level != Severity.OK }
        assertTrue("raised ${raised.firstOrNull()?.let { "at ${it.index}: ${it.value.headline}" }}", raised.isEmpty())
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
    fun jammingWithTheGainRisingIsAnAlertToo() {
        // Phones disagree on which way their gain moves under interference.
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 },
            agcDb = { t -> if (t >= ATTACK) 11.0 else 3.0 },
        ))
        val at = w.first(Check.SIGNALS, Severity.ALERT)
        assertNotNull("no alert", at)
        assertTrue("caught at $at", at!! <= ATTACK + 5)
        assertTrue(w.result(at, Check.SIGNALS).text, w.result(at, Check.SIGNALS).text.contains("gain moved +8 dB"))
        assertNotNull(w.first(Check.GAIN, Severity.CAUTION))
    }

    @Test
    fun aBlockedSkyWithSteadyGainIsNotJamming() {
        // Into a building: every signal 15 dB weaker, but no extra power in the band.
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 },
            agcDb = { 3.0 },
        ))
        assertEquals(null, w.first(Check.SIGNALS, Severity.CAUTION))
        assertEquals(null, w.first(Check.SIGNALS, Severity.ALERT))
        assertTrue(w.result(ATTACK + 5, Check.SIGNALS).text, w.result(ATTACK + 5, Check.SIGNALS).text.contains("sky got blocked"))
    }

    @Test
    fun aOneRoundGainBlipDoesntMakeABlockedSkyJamming() {
        // Kara's Sony: gain steady within ±0.7 dB, with single-round dips near 3 dB. One lands
        // just as the signals drop indoors.
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 },
            agcDb = { t -> if (t == ATTACK + 2) -1.0 else 3.0 },
        ))
        assertEquals(null, w.first(Check.SIGNALS, Severity.ALERT))
        assertTrue((ATTACK until ATTACK + 15).any { w.result(it, Check.SIGNALS).text.contains("sky got blocked") })
    }

    @Test
    fun jammingWhoseGainLagsARoundIsStillAnAlert() {
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 },
            agcDb = { t -> if (t >= ATTACK + 1) -8.0 else 3.0 },
        ))
        val at = w.first(Check.SIGNALS, Severity.ALERT)
        assertNotNull("no alert", at)
        assertTrue("caught at $at", at!! <= ATTACK + 4)
    }

    @Test
    fun aLongDropWithoutGainBecomesTheNewNormal() {
        // Indoors for good: a caution at first, not for as long as the phone stays there.
        val w = watch(Sky(cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 }), 180)
        assertNotNull(w.first(Check.SIGNALS, Severity.CAUTION))
        assertEquals(Severity.OK, w.reports.last().level)
        assertTrue(w.result(179, Check.SIGNALS).text, w.result(179, Check.SIGNALS).text.startsWith("normal"))
    }

    @Test
    fun interferenceTheGainConfirmsStaysUp() {
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() - if (t >= ATTACK) 15 else 0 },
            agcDb = { t -> if (t >= ATTACK) -8.0 else 3.0 },
        ), 180)
        assertEquals(Severity.ALERT, w.reports.last().level)
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
    fun aStrongerSkyWithTheGainMovingIsAnAlert() {
        // 8 dB stronger is no more than walking outdoors, unless the gain moves with it.
        val w = watch(Sky(
            cn0 = { t, _, el, r -> 22 + 18 * kotlin.math.sin(Math.toRadians(el)) + 2 * r.nextGaussian() + if (t >= ATTACK) 8 else 0 },
            agcDb = { t -> if (t >= ATTACK) -4.0 else 3.0 },
        ))
        val at = w.first(Check.SIGNALS, Severity.ALERT)
        assertNotNull("no alert", at)
        assertEquals(Threat.SPOOFING, w.result(at!!, Check.SIGNALS).threat)
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

    @Test
    fun aNormalRestartRaisesNothing() {
        // Same place, an hour later, same boot: the phone's clock counted what GNSS time says.
        val w = watch(Sky(), 30, remembered(3_600))
        assertTrue(w.reports.all { it.level == Severity.OK })
        val text = w.result(29, Check.STARTUP).text
        assertTrue(text, text.startsWith("0 m from last time, 60 min ago") && text.endsWith("GNSS time matches the phone's clock"))
    }

    @Test
    fun startingFarFromLastTimeIsAnAlert() {
        // Ten minutes ago TPS trusted a position 500 km north of here.
        val north = Geodetic(SyntheticSky.home.latitude + 4.5, SyntheticSky.home.longitude, SyntheticSky.home.height)
        val w = watch(Sky(), 130, remembered(600, north))
        val at = w.first(Check.STARTUP, Severity.ALERT, from = 0)
        assertEquals(0, at)
        assertTrue(w.result(0, Check.STARTUP).text, w.result(0, Check.STARTUP).text.contains("faster than an airliner"))
        // The alert fades after two minutes; the row still says what start-up found.
        assertEquals(Severity.OK, w.result(125, Check.STARTUP).severity)
        assertEquals("flagged at start-up: 500 km from last time, 10 min ago", w.result(125, Check.STARTUP).text)
        // A day later, 500 km is a drive.
        assertTrue(watch(Sky(), 30, remembered(86_400, north)).reports.all { it.level == Severity.OK })
    }

    @Test
    fun gnssTimeOffWhatThePhoneCountedIsAnAlert() {
        // Same boot, an hour ago; GNSS time now runs 30 s ahead of what the phone counted.
        val w = watch(Sky(), 30, remembered(3_600, gnssShiftMs = -30_000))
        assertEquals(0, w.first(Check.STARTUP, Severity.ALERT, from = 0))
        assertTrue(w.result(0, Check.STARTUP).text, w.result(0, Check.STARTUP).text.contains("+30.0 s"))
        // A day of sleep lets the phone's clock wander a few seconds: not an alarm.
        assertTrue(watch(Sky(), 30, remembered(86_400, gnssShiftMs = -4_000)).reports.all { it.level == Severity.OK })
    }

    @Test
    fun afterARebootTimeMustStillMoveForward() {
        // Another boot: the phone's clock started over, so only the order of events can be checked.
        val ok = watch(Sky(), 30, remembered(7_200, boot = BOOT - 1))
        assertTrue(ok.reports.all { it.level == Severity.OK })
        val w = watch(Sky(), 30, remembered(7_200, boot = BOOT - 1, gnssShiftMs = 10L * 86_400_000))
        assertEquals(0, w.first(Check.STARTUP, Severity.ALERT, from = 0))
        assertTrue(w.result(0, Check.STARTUP).text, w.result(0, Check.STARTUP).text.contains("earlier than when TPS last ran"))
    }

    @Test
    fun networkTimeAFewSecondsOffIsACaution() {
        val caution = watch(Sky(), 30) { _, i -> i.copy(gnssMinusNetworkMs = 3_000) }
        assertEquals(Severity.CAUTION, caution.result(29, Check.TIME).severity)
        val alert = watch(Sky(), 30) { _, i -> i.copy(gnssMinusNetworkMs = 8_000) }
        assertEquals(Severity.ALERT, alert.result(29, Check.TIME).severity)
        // Within a second or so is the network clock's own slack.
        assertTrue(watch(Sky(), 30) { _, i -> i.copy(gnssMinusNetworkMs = 1_200) }.reports.all { it.level == Severity.OK })
    }

    @Test
    fun aMockChipPositionIsAnAlert() {
        val w = watch(Sky()) { k, i -> i.copy(chipMock = k >= ATTACK) }
        assertEquals(ATTACK, w.first(Check.SOURCE, Severity.ALERT))
        assertEquals(Threat.SPOOFING, w.reports[ATTACK].headline!!.threat)
    }

    @Test
    fun perSignalGainIsGroupedByBand() {
        val agc = AgcBands.fromSignals(
            listOf(
                Triple(Constellation.GPS, 1_575.42e6, 2.0),
                Triple(Constellation.GPS, 1_575.42e6, 2.0),
                Triple(Constellation.GPS, 1_176.45e6, -1.0),
                // GLONASS: every satellite on its own carrier, one gain for the band.
                Triple(Constellation.GLONASS, 1_598.0625e6, 4.0),
                Triple(Constellation.GLONASS, 1_605.375e6, 4.0),
                Triple(Constellation.GALILEO, null, 1.5),
            )
        )
        assertEquals(
            listOf(
                Agc(Constellation.GPS, 1_176_450_000, -1.0),
                Agc(Constellation.GPS, 1_575_420_000, 2.0),
                Agc(Constellation.GALILEO, 1_575_420_000, 1.5),
                Agc(Constellation.GLONASS, 1_602_000_000, 4.0),
            ),
            agc,
        )
    }

    private companion object {
        const val ATTACK = 60
        /** The phone's monotonic clock at second 0, and its boot count. */
        const val BOOT_ELAPSED_MS = 50_000_000L
        const val BOOT = 7
        /** As [SyntheticSky] has it: GNSS time minus the monotonic clock. */
        const val GNSS_MINUS_ELAPSED_MS = 1_700_000_000_000L
    }
}
