package org.soulstone.tps.gnss

/**
 * The navigation systems Android reports, and how TPS names, colours and draws them.
 *
 * [androidId] is the platform's `GnssStatus.CONSTELLATION_*` value. The values are
 * kept as plain ints so that everything in this package except [GnssEngine] has no
 * Android dependency and runs in plain JVM unit tests.
 *
 * Declaration order is card order. Colours were chosen to stay clear of the fix
 * ring's green. A colour-blindness simulation found GPS blue and BeiDou violet
 * almost identical for red-green colour blindness, so each system also has its own
 * [shape], drawn by default.
 */
enum class Constellation(
    val androidId: Int,
    val displayName: String,
    val operator: String,
    val prefix: String,
    val argb: Long,
    val shape: MarkShape,
) {
    GPS(1, "GPS", "United States", "G", 0xFF2196F3, MarkShape.CIRCLE),
    GALILEO(6, "Galileo", "European Union", "E", 0xFFF4C20D, MarkShape.TRIANGLE),
    BEIDOU(5, "BeiDou", "China", "C", 0xFF9D7BFF, MarkShape.DIAMOND),
    GLONASS(3, "GLONASS", "Russia", "R", 0xFFE5484D, MarkShape.SQUARE),
    QZSS(4, "QZSS", "Japan", "J", 0xFFFF6FB5, MarkShape.STAR),
    NAVIC(7, "NavIC", "India", "I", 0xFF26C6DA, MarkShape.TRIANGLE_DOWN),
    SBAS(2, "SBAS", "augmentation", "S", 0xFF9E9E9E, MarkShape.CROSS),

    /** `CONSTELLATION_UNKNOWN`, or a value newer than this app. On API 26–28 NavIC can arrive this way. */
    UNKNOWN(0, "Other", "unknown system", "?", 0xFF9AA3B2, MarkShape.CIRCLE);

    /** The name on a constellation card: GPS, GALILEO, BEIDOU, … */
    val cardName: String get() = displayName.uppercase()

    companion object {
        /** The seven systems TPS always lists, in card order. */
        val KNOWN: List<Constellation> = entries.filter { it != UNKNOWN }

        fun fromAndroid(id: Int): Constellation = entries.firstOrNull { it.androidId == id } ?: UNKNOWN
    }
}

/** How a constellation's satellites are drawn on the sky circle and in the legend. */
enum class MarkShape { CIRCLE, SQUARE, TRIANGLE, DIAMOND, STAR, TRIANGLE_DOWN, CROSS }

/** Human labels for satellite IDs (Android's SVIDs), following AOSP's `GnssStatus.getSvid` ranges. */
object SatelliteLabels {

    fun label(constellation: Constellation, svid: Int): String = when (constellation) {
        Constellation.GPS -> "G" + pad2(svid)
        Constellation.GALILEO -> "E" + pad2(svid)
        Constellation.BEIDOU -> "C" + pad2(svid)
        Constellation.NAVIC -> "I" + pad2(svid)
        Constellation.SBAS -> "S$svid"
        // 1–25 is the orbital slot. 93–106 is the frequency channel k (-7..+6) plus 100,
        // which chips report when they don't know the slot.
        Constellation.GLONASS ->
            if (svid in 93..106) "R k=" + signed(svid - 100) else "R" + pad2(svid)
        // 193–206 are the L1 C/A PRNs (J01–J14). 183–192 are the L1S (SLAS) signals,
        // whose PRN is the satellite's L1 C/A PRN minus 10.
        Constellation.QZSS -> when (svid) {
            in 193..206 -> "J" + pad2(svid - 192)
            in 183..192 -> "J" + pad2(svid - 182)
            else -> "J$svid"
        }
        Constellation.UNKNOWN -> "?$svid"
    }

    private fun pad2(n: Int): String = if (n in 0..9) "0$n" else n.toString()
    private fun signed(n: Int): String = if (n > 0) "+$n" else n.toString()
}

/**
 * Which augmentation system an SBAS PRN belongs to.
 *
 * From the GPS.gov "L1 C/A PRN Code Assignments" table, Jan 2026 edition. PRNs it
 * lists as reserved or unallocated are left out. Reported as SBAS, PRNs 183–192 are
 * QZSS's sub-metre augmentation (SLAS) signals.
 */
object Sbas {
    private val SYSTEMS: Map<Int, String> = mapOf(
        120 to "ANGA", 121 to "EGNOS", 122 to "SouthPAN", 123 to "EGNOS", 124 to "SouthPAN",
        125 to "SDCM", 127 to "GAGAN", 128 to "GAGAN", 129 to "MSAS", 130 to "BDSBAS",
        131 to "WAAS", 132 to "GAGAN", 133 to "WAAS", 134 to "KASS", 135 to "WAAS",
        136 to "EGNOS", 137 to "MSAS", 138 to "WAAS", 139 to "MSAS", 140 to "SDCM",
        141 to "SDCM", 142 to "KASS", 143 to "BDSBAS", 144 to "BDSBAS", 145 to "Pak-SBAS",
        147 to "ANGA", 148 to "A-SBAS", 150 to "EGNOS", 158 to "UK SBAS",
    )

    fun system(prn: Int): String? = SYSTEMS[prn] ?: if (prn in 183..192) "SLAS" else null
}
