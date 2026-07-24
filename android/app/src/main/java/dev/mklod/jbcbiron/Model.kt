package dev.mklod.jbcbiron

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
    return IronStatus(
        currentC = num(2),
        setpointC = num(5),
        maxC = num(17),
        sleepDelay = num(7),
        batteryV = battV,
        batteryPct = batteryPctFromVoltage(battV),
        countdownS = num(10)?.let { it / 4 },
        status = STATUS[num(3)] ?: "?",
        locked = f.getOrNull(12) == "1",
    )
}
