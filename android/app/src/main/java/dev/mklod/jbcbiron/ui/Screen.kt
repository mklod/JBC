package dev.mklod.jbcbiron.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.mklod.jbcbiron.IronUi
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun App(
    irons: List<IronUi>,
    bluetoothOn: Boolean,
    onSetTemp: (String, Int) -> Unit,
    onPower: (String, Boolean) -> Unit,
) {
    // setpoint dialog state
    var dialogId by remember { mutableStateOf<String?>(null) }
    var dialogName by remember { mutableStateOf("") }
    var dialogVal by remember { mutableStateOf(340) }

    Column(
        Modifier.fillMaxSize().background(C.bg).statusBarsPadding().padding(14.dp, 10.dp, 14.dp, 12.dp)
    ) {
        when {
            !bluetoothOn -> Empty("Bluetooth is off — enable it to find irons.")
            irons.isEmpty() -> Empty("Scanning for irons…\nWake or dock a handle, and close the phone app.")
            else -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                irons.forEach { iron ->
                    val cardMod = Modifier.weight(1f)   // ColumnScope here
                    key(iron.id) {
                        IronCard(
                            iron = iron,
                            modifier = cardMod,
                            onToggle = { onPower(iron.id, it) },
                            onSetpointTap = {
                                dialogId = iron.id
                                dialogName = iron.name
                                dialogVal = (iron.status?.setpointC ?: 340)
                                    .let { (it / 10.0).roundToInt() * 10 }.coerceIn(100, 450)
                            },
                        )
                    }
                }
            }
        }
    }

    dialogId?.let { id ->
        SetpointDialog(
            name = dialogName,
            value = dialogVal,
            onDelta = { dialogVal = (dialogVal + it).coerceIn(100, 450) },
            onCancel = { dialogId = null },
            onSet = { onSetTemp(id, dialogVal); dialogId = null },
        )
    }
}

@Composable
private fun Empty(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(msg, color = C.muted, fontSize = 15.sp,
            modifier = Modifier.padding(40.dp))
    }
}

@Composable
private fun IronCard(iron: IronUi, modifier: Modifier = Modifier, onToggle: (Boolean) -> Unit, onSetpointTap: () -> Unit) {
    val s = iron.status
    val realOn = s?.isOn == true

    // Optimistic toggle: flip the knob immediately on tap, then reconcile with
    // the iron's real status; auto-revert after a timeout (covers a failed or
    // ignored command). Mirrors the web dashboard's pending-window behaviour.
    var pendingOn by remember { mutableStateOf<Boolean?>(null) }
    val shownOn = pendingOn ?: realOn
    LaunchedEffect(pendingOn, realOn) {
        val p = pendingOn ?: return@LaunchedEffect
        if (p == realOn) pendingOn = null        // real status caught up
        else { delay(2500); pendingOn = null }   // timeout → revert to real
    }

    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(C.card)
            .border(1.dp, C.cardBorder, RoundedCornerShape(14.dp))
            .padding(18.dp, 16.dp),
    ) {
        // header: dot + name + state pill + toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(9.dp).clip(CircleShape)
                    .background(if (iron.connected) C.green else C.red)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                iron.name, color = C.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatePill(pillFor(iron))
            Spacer(Modifier.width(10.dp))
            AppleToggle(checked = shownOn, enabled = iron.connected) {
                val next = !shownOn
                pendingOn = next
                onToggle(next)
            }
        }
        Spacer(Modifier.height(16.dp))

        // tiles
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(Modifier.weight(1f).clickable(enabled = iron.connected) { onSetpointTap() }) {
                TileLabel("SETPOINT", hint = "tap to change")
                Spacer(Modifier.height(2.dp))
                BigValue(s?.setpointC?.toString() ?: "--", "°C")
            }
            Tile(Modifier.weight(1f)) {
                // Only claim a charge direction when actually connected and the
                // iron is doing something — a disconnected or OFF card must not
                // say "discharging" (that was stale/misleading).
                val chg = when {
                    !iron.connected -> null
                    s == null -> null
                    s.status == "CHARGE" -> "charging" to C.green
                    s.status == "OFF" -> null
                    else -> "discharging" to C.red
                }
                TileLabel("BATTERY", chg = chg)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    BigValue(s?.batteryPct?.toString() ?: "--", "%")
                    s?.batteryV?.let {
                        Text(" · %.2fV".format(it), color = C.muted, fontSize = 13.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        Graph(iron.history, Modifier.weight(1f))
    }
}

@Composable
private fun Tile(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(9.dp))
            .background(C.tile)
            .border(1.dp, C.tileBorder, RoundedCornerShape(9.dp))
            .padding(12.dp, 10.dp),
        content = content,
    )
}

@Composable
private fun TileLabel(text: String, hint: String? = null, chg: Pair<String, androidx.compose.ui.graphics.Color>? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = C.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        if (hint != null) {
            Spacer(Modifier.width(4.dp))
            Text(hint, color = C.faint, fontSize = 10.sp)
        }
        if (chg != null) {
            Spacer(Modifier.width(4.dp))
            Text("● ${chg.first}", color = chg.second, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun BigValue(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = C.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Text(unit, color = C.muted, fontSize = 13.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
    }
}

@Composable
private fun AppleToggle(checked: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val knobX by animateDpAsState(if (checked) 28.dp else 0.dp, label = "knob")
    val track by animateColorAsState(if (checked) C.green else C.trackOff, label = "track")
    Box(
        Modifier
            .size(66.dp, 38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(track)
            .clickable(enabled = enabled) { onClick() },
    ) {
        Box(
            Modifier
                .padding(start = 3.dp, top = 3.dp)
                .offset(x = knobX)
                .size(32.dp)
                .clip(CircleShape)
                .background(androidx.compose.ui.graphics.Color.White),
        )
    }
}

@Composable
private fun Graph(history: List<Pair<Long, Int>>, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(C.tile)
            .border(1.dp, C.tileBorder, RoundedCornerShape(9.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(2.dp)) {
            if (history.size < 2) return@Canvas
            val padL = 30f; val padTB = 24f; val padR = 8f
            val w = size.width; val h = size.height
            val temps = history.map { it.second }
            var lo = min(temps.min(), 20); var hi = max(temps.max(), 40)
            hi = max(hi, lo + 20); val span = (hi - lo).toFloat()
            // gridlines + labels
            for (i in 0..3) {
                val y = padTB + (h - 2 * padTB) * i / 3
                drawLine(C.tileBorder, Offset(padL, y), Offset(w - padR, y), 1f)
            }
            // polyline
            val tMin = history.first().first
            val tMax = history.last().first
            val tSpan = max(1L, tMax - tMin).toFloat()
            var prev: Offset? = null
            for (p in history) {
                val x = padL + (w - padL - padR) * (p.first - tMin) / tSpan
                val y = padTB + (h - 2 * padTB) * (1f - (p.second - lo) / span)
                val pt = Offset(x, y)
                prev?.let { drawLine(C.greenLine, it, pt, 2f) }
                prev = pt
            }
        }
        // y-axis labels drawn as text overlay
        val temps = history.map { it.second }
        if (temps.isNotEmpty()) {
            var lo = min(temps.min(), 20); var hi = max(temps.max(), 40); hi = max(hi, lo + 20)
            Column(Modifier.fillMaxSize().padding(start = 3.dp, top = 18.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween) {
                for (i in 0..3) {
                    val v = (hi - (hi - lo) * i / 3.0).roundToInt()
                    Text("$v°", color = C.muted, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun SetpointDialog(
    name: String, value: Int,
    onDelta: (Int) -> Unit, onCancel: () -> Unit, onSet: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier.width(280.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(C.modal)
                .border(1.dp, C.modalBorder, RoundedCornerShape(16.dp))
                .padding(24.dp, 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(name, color = C.muted, fontSize = 13.sp)
            Text("SETPOINT", color = C.muted, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(vertical = 6.dp)) {
                Text("$value", color = C.text, fontSize = 48.sp, fontWeight = FontWeight.SemiBold)
                Text("°C", color = C.muted, fontSize = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                Stepper("−10") { onDelta(-10) }
                Stepper("+10") { onDelta(10) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                GhostButton("Cancel", Modifier.weight(1f), onCancel)
                PrimaryButton("Set", Modifier.weight(1f), onSet)
            }
        }
    }
}

@Composable
private fun Stepper(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(88.dp, 52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(C.stepBg)
            .border(1.dp, C.stepBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = C.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun GhostButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
            .border(1.dp, C.stepBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = C.text, fontSize = 15.sp) }
}

@Composable
private fun PrimaryButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(42.dp).clip(RoundedCornerShape(10.dp))
            .background(C.green)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color0621, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

// --- sleep/activity state pill (matches the web dashboard's colored pill) ---
private data class Pill(val label: String, val color: Color)

private fun pillFor(iron: IronUi): Pill {
    if (!iron.connected) return Pill("offline", C.muted)
    val st = iron.status ?: return Pill("…", C.muted)
    return when (st.status) {
        "WORK" -> Pill("working", C.green)
        "CHARGE" -> Pill("charging", C.green)
        "SLEEP" -> Pill("asleep", C.amber)
        "HIBERNATION" -> Pill("hibernating", C.amber)
        "OFF" -> Pill("off", C.muted)
        "NO CARTRIDGE" -> Pill("no tip", C.amber)
        "LOW BATTERY" -> Pill("low batt", C.red)
        "SHORT CIRCUIT" -> Pill("short!", C.red)
        "COVER" -> Pill("cover", C.amber)
        else -> Pill(st.status.lowercase(), C.muted)
    }
}

@Composable
private fun StatePill(p: Pill) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(p.color.copy(alpha = 0.18f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(p.label, color = p.color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

private val Color0621 = Color(0xFF06210F)
