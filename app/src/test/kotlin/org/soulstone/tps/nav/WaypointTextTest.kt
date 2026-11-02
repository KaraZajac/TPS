package org.soulstone.tps.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaypointTextTest {

    private fun line(text: String) = WaypointText.parseLine(text)

    private fun assertAt(lat: Double, lon: Double, label: String?, p: WaypointText.Parsed?, tolerance: Double = 1e-9) {
        requireNotNull(p) { "nothing parsed" }
        assertEquals("latitude", lat, p.latitude, tolerance)
        assertEquals("longitude", lon, p.longitude, tolerance)
        assertEquals("label", label, p.label)
    }

    @Test
    fun labelThenDecimal() = assertAt(44.46044, -110.82814, "Trailhead", line("Trailhead, 44.46044, -110.82814"))

    @Test
    fun decimalThenLabel() = assertAt(44.46044, -110.82814, "Camp 2", line("44.46044 -110.82814 Camp 2"))

    @Test
    fun noLabel() = assertAt(44.46044, -110.82814, null, line("44.46044,-110.82814"))

    @Test
    fun hemisphereLetters() {
        assertAt(44.46044, -110.82814, "Car", line("Car: 44.46044 N, 110.82814 W"))
        assertAt(-33.86785, 151.20732, "Opera House", line("Opera House 33.86785S 151.20732E"))
        // Longitude first, but the letters say so.
        assertAt(44.46044, -110.82814, null, line("110.82814 W 44.46044 N"))
    }

    @Test
    fun strayNumbersInTheLabelDontWin() =
        assertAt(44.1, -110.2, "Gate 12", line("Gate 12, 44.1, -110.2"))

    @Test
    fun degreesMinutesSeconds() {
        val p = line("44°27'37.6\"N 110°49'41.3\"W Camp")
        assertAt(44 + 27 / 60.0 + 37.6 / 3600, -(110 + 49 / 60.0 + 41.3 / 3600), "Camp", p, 1e-9)
    }

    @Test
    fun degreesAndDecimalMinutes() =
        assertAt(44 + 27.626 / 60, -(110 + 49.688 / 60), "Ridge", line("Ridge 44° 27.626' N, 110° 49.688' W"))

    @Test
    fun geohash() {
        val cell = Geohash.decode("9xchyjc2v")!!
        assertAt(cell.latitude, cell.longitude, "Car", line("Car 9xchyjc2v"))
        assertAt(cell.latitude, cell.longitude, "Car", line("9XCHYJC2V Car"))
    }

    @Test
    fun aWordMadeOfGeohashLettersIsntAGeohash() {
        val cell = Geohash.decode("9xchyjc2v")!!
        assertAt(cell.latitude, cell.longitude, "dense camp", line("9xchyjc2v dense camp"))
        assertNull(line("dense camp"))
    }

    @Test
    fun outOfRangeIsRejected() {
        assertNull(line("Nowhere, 95.0, 10.0"))
        assertNull(line("Nowhere, 10.0, 190.0"))
    }

    @Test
    fun nothingToRead() {
        assertNull(line("just a note"))
        assertNull(line("44.46044"))
    }

    @Test
    fun aWholeList() {
        val r = WaypointText.parse(
            """
            # my places
            Trailhead, 44.46044, -110.82814

            Car 9xchyjc2v
            not a place
            44°27'37.6"N 110°49'41.3"W
            """.trimIndent()
        )
        assertEquals(listOf("Trailhead", "Car", null), r.waypoints.map { it.label })
        assertEquals(1, r.errors.size)
        assertEquals(true, r.errors.single().startsWith("line 5:"))
    }

    @Test
    fun storageRoundTrip() {
        val list = listOf(
            Waypoint(1, "Trailhead", 44.46044, -110.82814),
            Waypoint(7, "Tab\tin\nlabel", -33.86785, 151.20732),
        )
        val back = WaypointText.deserialize(WaypointText.serialize(list))
        assertEquals(listOf(1L, 7L), back.map { it.id })
        assertEquals("Tab in label", back[1].label)
        assertEquals(list[0], back[0])
        assertEquals(emptyList<Waypoint>(), WaypointText.deserialize(""))
    }

    @Test
    fun exportReadsBack() {
        val list = listOf(Waypoint(1, "Smith's, 2nd gate", 44.46044, -110.82814))
        val again = WaypointText.parse(WaypointText.export(list)).waypoints.single()
        assertAt(44.46044, -110.82814, "Smith's, 2nd gate", again)
    }
}
