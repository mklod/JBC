package dev.mklod.jbcbiron
// Last modified: 2026-10-09--0119

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** parseStatus() / TipTrace must stay 1:1 ports of jbc_biron.py. */
class ModelTest {
    // Real frame captured docked/charging (PROTOCOL.md).
    private val docked = "E;838;30;2;0;350;4;40;40;1200;1200;252;0;0;100;0;5;400;100;"

    private fun frame(tip: Int, code: Int = 0, setpoint: Int = 350) =
        "E;836;$tip;$code;19;$setpoint;4;48;47;1200;1199;254;0;0;100;0;5;450;100;"

    /** Feed (tip, statusCode) frames 360 ms apart (the live cadence); return plotted values. */
    private fun replay(seq: List<Pair<Int, Int>>): List<Int?> {
        val trace = TipTrace()
        return seq.mapIndexed { i, (tip, code) ->
            trace.sample(1_000_000L + i * 360L, parseStatus(frame(tip, code))!!)
        }
    }

    @Test fun parsesRealFrame() {
        val s = parseStatus(docked)!!
        assertEquals(30, s.currentC)
        assertEquals(350, s.setpointC)
        assertEquals(400, s.maxC)
        assertEquals(8.38, s.batteryV!!, 1e-9)
        assertEquals("CHARGE", s.status)
        assertFalse(s.locked)
        assertTrue(s.tipValid)
    }

    @Test fun ceilingIsSetpointPlusOvershoot() {
        assertTrue(parseStatus(frame(360))!!.tipValid)
        assertFalse(parseStatus(frame(361))!!.tipValid)
        assertFalse(parseStatus(frame(465))!!.tipValid)            // live slew tail
        assertFalse(parseStatus(frame(1551))!!.tipValid)
        assertTrue(parseStatus(frame(290, setpoint = 280))!!.tipValid)
        assertFalse(parseStatus(frame(300, setpoint = 280))!!.tipValid)
    }

    @Test fun noCartridgeIsInvalid() {
        assertFalse(parseStatus(frame(20, code = 1))!!.tipValid)
    }

    // Live capture 2026-10-09, JBC_NANO: heating, cartridge pulled (one 618
    // frame, then NO CARTRIDGE @ 20), re-seated (firmware slews down from 1551).
    @Test fun liveTipSwapNeverPlotsArtifacts() {
        val seq = listOf(
            224 to 0, 260 to 0, 297 to 0, 618 to 0,          // pull
            20 to 1, 20 to 1, 20 to 1,                       // NO CARTRIDGE
            1551 to 0, 1551 to 0, 1185 to 0, 822 to 0, 465 to 0, 113 to 0,   // re-seat slew
            134 to 0, 153 to 0, 172 to 0, 191 to 0,          // real heat-up
        )
        val out = replay(seq)
        assertEquals(listOf(224, 260, 297), out.subList(0, 3))
        out.subList(3, 13).forEach { assertNull(it) }       // 618 … 113 all dropped
        assertEquals(listOf(134, 153, 172, 191), out.subList(13, 17))
    }

    // Live capture 2026-10-09, JBC_Std OFF at room temp: raw flickers 26/27/28.
    @Test fun idleRoomTempPlotsFlat() {
        val raw = listOf(28, 27, 27, 27, 28, 28, 27, 27, 26, 27, 27, 28, 28, 27, 27, 26, 26, 26,
            27, 27, 26, 26, 27, 28, 28, 28, 27, 27, 26, 27, 27)
        val out = replay(raw.map { it to 9 })
        assertEquals(setOf(28), out.toSet())
    }
}
