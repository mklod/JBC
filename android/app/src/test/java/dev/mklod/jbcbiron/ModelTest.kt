package dev.mklod.jbcbiron
// Last modified: 2026-10-09--0107

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** parseStatus() must stay a 1:1 port of jbc_biron.py parse_status(). */
class ModelTest {
    // Real frame captured docked/charging (PROTOCOL.md).
    private val docked = "E;838;30;2;0;350;4;40;40;1200;1200;252;0;0;100;0;5;400;100;"

    private fun frame(tip: Int, code: Int) =
        "E;815;$tip;$code;0;350;4;40;0;1200;1064;253;0;0;100;0;5;400;100;"

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

    @Test fun tipSwapRailValueIsInvalid() {
        // Open thermocouple during a cartridge swap reads ~1100 °C.
        assertFalse(parseStatus(frame(1100, 1))!!.tipValid)   // NO CARTRIDGE
        assertFalse(parseStatus(frame(1100, 0))!!.tipValid)   // still WORK
        assertFalse(parseStatus(frame(501, 0))!!.tipValid)
    }

    @Test fun noCartridgeIsInvalidEvenAtPlausibleTemp() {
        assertFalse(parseStatus(frame(28, 1))!!.tipValid)
    }

    @Test fun realTemperaturesAreValid() {
        for (t in listOf(28, 340, 450, TIP_MAX_PLAUSIBLE_C)) {
            assertTrue("$t", parseStatus(frame(t, 0))!!.tipValid)
        }
    }

    @Test fun rejectsNonStatusFrames() {
        assertEquals(null, parseStatus("S;"))
        assertEquals(null, parseStatus("V;8886928 ;"))
    }
}
