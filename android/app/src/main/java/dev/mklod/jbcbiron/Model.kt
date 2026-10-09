package dev.mklod.jbcbiron
// Last modified: 2026-10-09--0119

/**
 * JBC B·IRON status frame parsing — a direct port of the live-verified
 * parse_status() in the project's jbc_biron.py (see ../PROTOCOL.md).
 *
 * Status reply:  E;f1;f2;f3;...   fields split on ';'
 *   f1  = battery pack voltage, centivolts   (8.38 V docked/full)
 *   f2  = current tip temperature (°C)
 *   f3  = status code (see STATUS)
 *   f5  = working setpoint (°C)   — moved by <T…>
 *   f7  = sleep delay setting
 *   f10 = live countdown, ¼-s ticks
 *   f12 = lock flag (1 = locked)
 *   f17 = max temperature (°C)
 */

val STATUS = mapOf(
    0 to "WORK", 1 to "NO CARTRIDGE", 2 to "CHARGE", 3 to "LOW BATTERY",
    4 to "WORK", 5 to "HIBERNATION", 6 to "SHORT CIRCUIT", 7 to "SLEEP",
    8 to "OFF", 9 to "OFF", 10 to "COVER",
)

// Tip-reading sanity limits — 1:1 with jbc_biron.py, from a live tip-swap capture
// (../captures/2026-10-09-tipswap.tsv). Pulling/re-seating a cartridge opens the
// tip thermocouple (rail ~1100-1600 °C) and the firmware SLEWS its reported
// reading toward/away from that rail at ~1000 °C/s, so for a few frames the junk
// lands anywhere between real and rail (618, 1551, 1185, 822, 465 … seen live).
//
// The tip can't legitimately exceed its setpoint, bar a little regulator
// overshoot (351 seen at a 350 setpoint).
const val TIP_OVERSHOOT_C = 10
// Real heat-up peaks ~160 °C/s; the artifact slew is ~1000 °C/s.
const val TIP_MAX_SLEW_C_PER_S = 500
// Idle readings flicker by ±1 °C (26/27/28 at room temp). The graph holds its
// value until the reading moves at least this far, so idle and at-setpoint lines
// plot flat; at heat-up rates (~20 °C/frame) the lag is invisible.
const val TIP_DEADBAND_C = 3
const val FALLBACK_MAX_C = 450   // work-range top, if the frame lacks a setpoint

// Everything except a literal OFF counts as "on" for the master toggle.
private val OFF_STATES = setOf("OFF")

data class IronStatus(
    val currentC: Int?,
    val setpointC: Int?,
    val maxC: Int?,
    val sleepDelay: Int?,
    val batteryV: Double?,
    val batteryPct: Int?,
    val countdownS: Int?,
    val status: String,
    val locked: Boolean,
    // Is this single frame's tip reading plausible? False above setpoint (+
    // overshoot) or with NO CARTRIDGE. The slew tail of a swap needs history —
    // see TipTrace.
    val tipValid: Boolean,
) {
    val isOn: Boolean get() = status !in OFF_STATES
}

/** UI-facing per-iron record. */
data class IronUi(
    val id: String,          // BLE address
    val name: String,        // e.g. JBC_NANO
    val connected: Boolean,
    val status: IronStatus?,
    val history: List<Pair<Long, Int>>,  // (epochMillis, tipC)
)

// 2S Li-ion resting-voltage → state-of-charge curve (per-cell V → %).
private val SOC_CURVE = listOf(
    3.30 to 0, 3.45 to 5, 3.55 to 12, 3.65 to 22, 3.72 to 35,
    3.78 to 48, 3.85 to 62, 3.95 to 76, 4.05 to 89, 4.20 to 100,
)

fun batteryPctFromVoltage(packV: Double?): Int? {
    if (packV == null || packV <= 0.0) return null
    val cell = packV / 2.0
    if (cell <= SOC_CURVE.first().first) return 0
    if (cell >= SOC_CURVE.last().first) return 100
    for (i in 0 until SOC_CURVE.size - 1) {
        val (v0, p0) = SOC_CURVE[i]
        val (v1, p1) = SOC_CURVE[i + 1]
        if (cell in v0..v1) {
            return Math.round(p0 + (p1 - p0) * (cell - v0) / (v1 - v0)).toInt()
        }
    }
    return 100
}

fun parseStatus(text: String): IronStatus? {
    val f = text.trim().split(";")
    if (f.isEmpty() || f[0] != "E") return null
    fun num(i: Int): Int? = f.getOrNull(i)?.trim()?.toIntOrNull()

    val battRaw = num(1)
    val battV = battRaw?.let { it / 100.0 }
    val current = num(2)
    val setpoint = num(5)
    val maxC = num(17)
    val status = STATUS[num(3)] ?: "?"
    val ceiling = (setpoint?.takeIf { it > 0 } ?: maxC?.takeIf { it > 0 } ?: FALLBACK_MAX_C) +
        TIP_OVERSHOOT_C
    return IronStatus(
        currentC = current,
        setpointC = setpoint,
        maxC = maxC,
        sleepDelay = num(7),
        batteryV = battV,
        batteryPct = batteryPctFromVoltage(battV),
        countdownS = num(10)?.let { it / 4 },
        status = status,
        locked = f.getOrNull(12) == "1",
        tipValid = current != null && current <= ceiling && status != "NO CARTRIDGE",
    )
}

/**
 * Per-iron filter turning status frames into clean graph samples (port of
 * jbc_biron.py TipTrace). [sample] returns the °C value to plot, or null to skip
 * the frame: drops implausible frames ([IronStatus.tipValid]), drops the
 * firmware's artifact slew (faster than [TIP_MAX_SLEW_C_PER_S] vs the previous
 * raw frame, valid or not), then holds the value inside [TIP_DEADBAND_C] so idle
 * noise plots flat.
 */
class TipTrace {
    private var prevT = 0L          // epoch ms of the previous frame
    private var prevRaw: Int? = null
    private var held: Int? = null   // last plotted value

    fun sample(tMillis: Long, st: IronStatus): Int? {
        val raw = st.currentC ?: return null
        val pRaw = prevRaw
        val pT = prevT
        prevRaw = raw
        prevT = tMillis
        if (!st.tipValid) return null
        if (pRaw != null) {
            val dtS = maxOf(tMillis - pT, 100L) / 1000.0
            if (Math.abs(raw - pRaw) / dtS > TIP_MAX_SLEW_C_PER_S) return null
        }
        val h = held
        if (h == null || Math.abs(raw - h) >= TIP_DEADBAND_C) held = raw
        return held
    }
}
