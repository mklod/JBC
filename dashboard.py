#!/usr/bin/env python3
"""
JBC B·IRON live dashboard — minimal replacement for the official app's core.

Handles ONE OR MORE irons at once: it discovers every advertised JBC handle,
connects to each simultaneously, and shows them side by side. Each panel has:

  1. set working temperature
  2. power on / off  — big Apple-style toggle (green = ON, grey = OFF)
  3. status (charging / off / hibernation / work / …)
  4. battery (voltage + %)
  5. live tip-temperature graph

Self-contained web page (no external assets, no CDN) at http://localhost:8770.

    pip install bleak
    python dashboard.py            # connect + serve, open the URL it prints
    python dashboard.py --raw-log frames.tsv   # also record every raw BLE frame

Each iron accepts only ONE BLE connection at a time: close the phone app first.
A handle that is OFF and out of its cradle stops advertising and won't appear
until it's docked or woken.
"""
# Last modified: 2026-10-09--0113
import argparse
import asyncio
import json
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

from bleak import BleakClient

from jbc_biron import find_all_irons, parse_status, WRITE_CHAR, NOTIFY_CHAR

PORT = 8770
HISTORY_SECONDS = 300
POLL_INTERVAL = 0.25
RESCAN_SECONDS = 10            # re-scan for newly-woken irons this often

# device address -> record. Guarded by _lock.
_lock = threading.Lock()
_devices = {}                  # addr -> {name, connected, last, history, error}
_senders = {}                  # addr -> Sender (only while connected)
_connecting = set()            # addrs with a live connect task
_loop = None
_raw_log = None                # open file when --raw-log is given


def log_raw(addr, name, text):
    """Append one raw notify frame (diagnostics, e.g. capturing a tip swap)."""
    if _raw_log is None:
        return
    with _lock:
        _raw_log.write(f"{time.time():.3f}\t{addr}\t{name}\t{text}\n")
        _raw_log.flush()


class Sender:
    def __init__(self, client):
        self.client = client

    async def send(self, frame):
        await self.client.write_gatt_char(WRITE_CHAR, frame.encode(), response=True)

    async def set_temp(self, c):
        await self.send(f"<T{max(100, min(450, int(c)))}>")

    async def power_on(self):
        await self.send("<M>")

    async def power_off(self):
        await self.send("<L>")


def _ensure(addr, name):
    if addr not in _devices:
        _devices[addr] = {
            "name": name, "connected": False, "last": None,
            "error": None, "history": deque(maxlen=int(HISTORY_SECONDS / POLL_INTERVAL)),
        }


async def device_loop(dev, name):
    addr = dev.address
    with _lock:
        _ensure(addr, name)
    # Retry the connect+GATT-discovery a few times fast — freshly-woken handles
    # often lose the notify-characteristic lookup on the first attempt (a
    # discovery race), and waiting for the slow rescan feels broken.
    try:
        for attempt in range(5):
            try:
                async with BleakClient(dev) as client:
                    sender = Sender(client)

                    def on_notify(_c, data: bytearray):
                        text = data.decode("utf-8", "replace").strip()
                        log_raw(addr, name, text)
                        if text.startswith("E"):
                            st = parse_status(text)
                            with _lock:
                                rec = _devices[addr]
                                rec["last"] = st
                                # Only graph a trustworthy tip reading — a
                                # cartridge swap opens the thermocouple (~1100 °C
                                # rail value) and would spike the chart.
                                if st and st.get("tip_valid"):
                                    rec["history"].append((time.time(), st["current_c"]))

                    await client.start_notify(NOTIFY_CHAR, on_notify)
                    with _lock:
                        _devices[addr]["connected"] = True
                        _devices[addr]["error"] = None
                        _senders[addr] = sender
                    while True:
                        await client.write_gatt_char(WRITE_CHAR, b"<E>", response=True)
                        await asyncio.sleep(POLL_INTERVAL)
            except Exception as e:
                with _lock:
                    if addr in _devices:
                        _devices[addr]["connected"] = False
                        _devices[addr]["error"] = f"{e!r}"
                    _senders.pop(addr, None)
                await asyncio.sleep(2.0)   # brief backoff, then retry
    finally:
        with _lock:
            _connecting.discard(addr)


async def scanner_loop():
    """Discover irons and spawn a connection task for each new/dropped one."""
    while True:
        try:
            irons = await find_all_irons(timeout=5.0)
        except Exception:
            irons = []
        for dev, name in irons:
            addr = dev.address
            with _lock:
                already = addr in _connecting or _senders.get(addr) is not None
                if not already:
                    _connecting.add(addr)
                    _ensure(addr, name)
                    _devices[addr]["name"] = name
            if not already:
                asyncio.ensure_future(device_loop(dev, name))
        await asyncio.sleep(RESCAN_SECONDS)


def start_ble_thread():
    global _loop

    def run():
        global _loop
        _loop = asyncio.new_event_loop()
        asyncio.set_event_loop(_loop)
        _loop.run_until_complete(scanner_loop())

    threading.Thread(target=run, daemon=True).start()


def command(addr, make_coro):
    with _lock:
        sender = _senders.get(addr)
    if sender is None or _loop is None:
        return False, "not connected"
    try:
        fut = asyncio.run_coroutine_threadsafe(make_coro(sender), _loop)
        fut.result(timeout=3)
        return True, "ok"
    except Exception as e:
        return False, repr(e)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/":
            body = PAGE.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        elif path == "/api/status":
            now = time.time()
            with _lock:
                devs = []
                for addr, rec in _devices.items():
                    devs.append({
                        "id": addr,
                        "name": rec["name"],
                        "connected": rec["connected"],
                        "error": rec["error"],
                        "last": rec["last"],
                        "history": [[round(now - t, 1), c] for t, c in rec["history"]],
                    })
            devs.sort(key=lambda d: d["name"])
            self._json({"devices": devs})
        else:
            self._json({"error": "not found"}, 404)

    def do_POST(self):
        p = urlparse(self.path)
        q = parse_qs(p.query)
        addr = q.get("id", [""])[0]
        if p.path == "/api/settemp":
            c = q.get("c", ["0"])[0]
            ok, msg = command(addr, lambda s: s.set_temp(c))
            self._json({"ok": ok, "msg": msg})
        elif p.path == "/api/on":
            ok, msg = command(addr, lambda s: s.power_on())
            self._json({"ok": ok, "msg": msg})
        elif p.path == "/api/off":
            ok, msg = command(addr, lambda s: s.power_off())
            self._json({"ok": ok, "msg": msg})
        else:
            self._json({"error": "not found"}, 404)


PAGE = r"""<!doctype html>
<html><head><meta charset="utf-8"><title>JBC B·IRON</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; font:15px/1.4 system-ui,sans-serif; background:#0f1113; color:#e7e9ea; }
  header { padding:14px 22px; border-bottom:1px solid #23272b; }
  h1 { font-size:16px; margin:0; font-weight:600; letter-spacing:.02em; }
  main { padding:20px; display:grid; grid-template-columns:repeat(auto-fit,minmax(360px,1fr)); gap:20px; max-width:1400px; margin:0 auto; }
  .card { background:#15181b; border:1px solid #23272b; border-radius:14px; padding:18px 20px; }
  .top { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:16px; }
  .id { display:flex; align-items:center; gap:10px; min-width:0; }
  .dot { width:9px; height:9px; border-radius:50%; background:#555; flex:none; }
  .dot.on { background:#37d67a; } .dot.off { background:#e0564b; }
  .nm { font-size:17px; font-weight:600; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
  .state { font-size:11px; font-weight:600; padding:2px 9px; border-radius:11px; white-space:nowrap; }
  .state.work { background:rgba(48,209,88,.16); color:#37d67a; }
  .state.sleep { background:rgba(255,179,64,.16); color:#ffb340; }
  .state.charge { background:rgba(95,216,138,.14); color:#5fd88a; }
  .state.off { background:#2a2e33; color:#8b9198; }
  /* Apple-style toggle */
  .sw { position:relative; width:66px; height:38px; flex:none; cursor:pointer; }
  .sw input { display:none; }
  .track { position:absolute; inset:0; border-radius:19px; background:#3a3f45; transition:background .25s; }
  .knob { position:absolute; top:3px; left:3px; width:32px; height:32px; border-radius:50%; background:#fff;
          box-shadow:0 2px 5px rgba(0,0,0,.45); transition:transform .25s; }
  .sw.checked .track { background:#30d158; }
  .sw.checked .knob { transform:translateX(28px); }
  .sw.pending .track { opacity:.55; }
  .tiles { display:grid; grid-template-columns:1fr 1fr; gap:10px; margin-bottom:14px; }
  .tile { background:#1c2024; border:1px solid #262b30; border-radius:9px; padding:10px 12px; }
  .tile .k { font-size:11px; color:#8b9198; text-transform:uppercase; letter-spacing:.05em; }
  .tile .v { font-size:24px; font-weight:600; margin-top:2px; font-variant-numeric:tabular-nums; }
  .tile .u { font-size:13px; color:#8b9198; font-weight:400; }
  .tile.tap { cursor:pointer; transition:background .15s, border-color .15s; }
  .tile.tap:hover { background:#22272c; border-color:#3a4046; }
  .hint { font-size:10px; color:#5f676e; text-transform:none; letter-spacing:0; margin-left:4px; }
  .chg { font-size:10px; font-weight:600; text-transform:none; letter-spacing:0; margin-left:4px; }
  .chg.charging { color:#30d158; }
  .chg.discharging { color:#e0564b; }
  /* modal */
  .overlay { position:fixed; inset:0; background:rgba(0,0,0,.55); display:none; align-items:center; justify-content:center; z-index:50; }
  .overlay.show { display:flex; }
  .modal { background:#1b1f23; border:1px solid #2c3238; border-radius:16px; padding:24px 26px; width:280px; text-align:center; box-shadow:0 20px 60px rgba(0,0,0,.5); }
  .mname { font-size:13px; color:#8b9198; margin-bottom:2px; }
  .mk { font-size:11px; color:#8b9198; text-transform:uppercase; letter-spacing:.05em; }
  .mval { font-size:48px; font-weight:650; margin:4px 0 18px; font-variant-numeric:tabular-nums; }
  .mval .u { font-size:22px; color:#8b9198; font-weight:400; }
  .steppers { display:flex; gap:12px; justify-content:center; margin-bottom:20px; }
  .steppers button { width:88px; height:52px; font-size:20px; font-weight:600; background:#262b30; border:1px solid #3a4046; border-radius:12px; color:#e7e9ea; cursor:pointer; }
  .steppers button:active { background:#30363c; }
  .mbtns { display:flex; gap:10px; }
  .mbtns button { flex:1; height:42px; border-radius:10px; font-size:15px; cursor:pointer; border:1px solid #3a4046; }
  .mbtns .ghost { background:transparent; color:#e7e9ea; }
  .mbtns .primary { background:#30d158; border-color:#30d158; color:#06210f; font-weight:600; }
  canvas { width:100%; height:150px; background:#1c2024; border:1px solid #262b30; border-radius:9px; display:block; }
  .ctl { display:flex; gap:8px; margin-top:14px; }
  input[type=number]{ width:88px; background:#0f1113; color:#e7e9ea; border:1px solid #333; border-radius:7px; padding:8px; font-size:15px; }
  button { background:#2a2e33; color:#e7e9ea; border:1px solid #3a3f45; border-radius:7px; padding:8px 14px; font-size:14px; cursor:pointer; }
  button:hover { background:#343940; }
  .empty { color:#8b9198; padding:40px 20px; text-align:center; }
  .err { color:#e0564b; font-size:12px; min-height:15px; margin-top:8px; }
  .badge { font-size:11px; color:#8b9198; font-variant-numeric:tabular-nums; }
</style></head><body>
<header><h1>JBC B·IRON — live control</h1></header>
<main id="grid"><div class="empty">Scanning for irons…</div></main>

<div id="overlay" class="overlay">
  <div class="modal">
    <div class="mname" id="m-name"></div>
    <div class="mk">Setpoint</div>
    <div class="mval"><span id="m-val">340</span><span class="u">°C</span></div>
    <div class="steppers">
      <button id="m-minus">−10</button>
      <button id="m-plus">+10</button>
    </div>
    <div class="mbtns">
      <button id="m-cancel" class="ghost">Cancel</button>
      <button id="m-set" class="primary">Set</button>
    </div>
  </div>
</div>
<script>
const $ = s => document.querySelector(s);
const cards = {};                     // id -> {el, canvas}
const OFF_STATES = new Set(['OFF']);  // everything else counts as ON
const pending = {};                   // id -> timestamp of last toggle click

function isOn(s){ return s && s.status && !OFF_STATES.has(s.status); }

// ---- setpoint modal (shared, ±10° steppers) ----
let modalTarget = null;   // {id}
let modalVal = 340;
const MIN_T=100, MAX_T=450;
function clampT(v){ return Math.max(MIN_T, Math.min(MAX_T, v)); }
function renderModalVal(){ $('#m-val').textContent = modalVal; }
function openModal(id, name, cur){
  modalTarget = {id};
  modalVal = clampT(Math.round(cur/10)*10);
  $('#m-name').textContent = name;
  renderModalVal();
  $('#overlay').classList.add('show');
}
function closeModal(){ $('#overlay').classList.remove('show'); modalTarget=null; }
$('#m-minus').addEventListener('click', ()=>{ modalVal=clampT(modalVal-10); renderModalVal(); });
$('#m-plus').addEventListener('click',  ()=>{ modalVal=clampT(modalVal+10); renderModalVal(); });
$('#m-cancel').addEventListener('click', closeModal);
$('#overlay').addEventListener('click', e=>{ if(e.target.id==='overlay') closeModal(); });
$('#m-set').addEventListener('click', ()=>{
  if(!modalTarget) return;
  fetch('/api/settemp?id='+encodeURIComponent(modalTarget.id)+'&c='+modalVal,{method:'POST'})
    .then(r=>r.json()).then(()=>closeModal());
});

function makeCard(d){
  const el = document.createElement('div'); el.className='card';
  el.innerHTML = `
    <div class="top">
      <div class="id"><span class="dot"></span><span class="nm"></span><span class="state"></span></div>
      <label class="sw"><input type="checkbox"><span class="track"></span><span class="knob"></span></label>
    </div>
    <div class="tiles">
      <div class="tile tap setptile"><div class="k">Setpoint <span class="hint">tap to change</span></div><div class="v"><span class="set">--</span><span class="u">°C</span></div></div>
      <div class="tile"><div class="k">Battery <span class="chg"></span></div><div class="v"><span class="bat">--</span><span class="u">% </span><span class="bv u"></span></div></div>
    </div>
    <canvas width="700" height="150"></canvas>
    <div class="err"></div>`;
  const sw = el.querySelector('.sw');
  sw.addEventListener('click', e => {
    e.preventDefault();
    const on = sw.classList.contains('checked');
    pending[d.id] = Date.now();
    sw.classList.toggle('checked', !on);   // optimistic: flip instantly on tap
    el.querySelector('.err').textContent = '';
    const revert = () => { sw.classList.toggle('checked', on); pending[d.id] = 0; };
    fetch('/api/' + (on ? 'off' : 'on') + '?id=' + encodeURIComponent(d.id), {method:'POST'})
      .then(r=>r.json())
      .then(j=>{ if(!j.ok){ el.querySelector('.err').textContent = j.msg; revert(); } })
      .catch(()=>{ el.querySelector('.err').textContent = 'command failed'; revert(); });
  });
  el.querySelector('.setptile').addEventListener('click', ()=>{
    const cur = parseInt(el.querySelector('.set').textContent) || 340;
    openModal(d.id, d.name, cur);
  });
  return el;
}

function drawGraph(canvas, hist){
  const ctx=canvas.getContext('2d'), W=canvas.width, H=canvas.height, pad=28;
  ctx.clearRect(0,0,W,H);
  if(hist.length<2) return;
  const temps=hist.map(p=>p[1]); let lo=Math.min(...temps,20), hi=Math.max(...temps,40);
  hi=Math.max(hi,lo+20); const span=hi-lo;
  ctx.strokeStyle='#262b30'; ctx.fillStyle='#8b9198'; ctx.font='10px system-ui'; ctx.lineWidth=1;
  for(let i=0;i<=3;i++){ const y=pad+(H-2*pad)*i/3; ctx.beginPath(); ctx.moveTo(pad,y); ctx.lineTo(W-6,y); ctx.stroke();
    ctx.fillText(Math.round(hi-span*i/3)+'°',3,y+3); }
  const tmax=hist[hist.length-1][0], tmin=hist[0][0], tspan=Math.max(1,tmax-tmin);
  ctx.beginPath(); ctx.strokeStyle='#37d67a'; ctx.lineWidth=2;
  hist.forEach((p,i)=>{ const x=pad+(W-pad-6)*(1-(p[0]-tmin)/tspan); const y=pad+(H-2*pad)*(1-(p[1]-lo)/span);
    i?ctx.lineTo(x,y):ctx.moveTo(x,y); });
  ctx.stroke();
}

async function poll(){
  let data;
  try { data = await (await fetch('/api/status')).json(); } catch(e){ return; }
  const grid = $('#grid');
  const empty = grid.querySelector('.empty');
  if(data.devices.length){ if(empty) empty.remove(); }
  else if(!empty){ grid.innerHTML='<div class="empty">No irons found. Dock or wake a handle, and close the phone app.</div>'; cardsClear(); return; }

  for(const d of data.devices){
    let c = cards[d.id];
    if(!c){ const el=makeCard(d); grid.appendChild(el); c=cards[d.id]={el, canvas:el.querySelector('canvas')}; }
    const el=c.el, s=d.last;
    el.querySelector('.dot').className = 'dot ' + (d.connected?'on':'off');
    el.querySelector('.nm').textContent = d.name;
    el.querySelector('.set').textContent = s?.setpoint_c ?? '--';
    el.querySelector('.bat').textContent = s?.battery_pct ?? '--';
    el.querySelector('.bv').textContent  = s?.battery_v!=null ? '· '+s.battery_v.toFixed(2)+'V' : '';
    const chg = el.querySelector('.chg');
    if(s && s.status==='CHARGE'){ chg.textContent='● charging'; chg.className='chg charging'; }
    else if(s){ chg.textContent='● discharging'; chg.className='chg discharging'; }
    else { chg.textContent=''; chg.className='chg'; }
    // sleep-state pill: makes the inactivity cool-down (WORK->SLEEP->HIBERNATION) visible
    const stp = el.querySelector('.state');
    const status = s?.status;
    let cls='off', txt=status ? status.toLowerCase() : '';
    if(!d.connected){ cls='off'; txt='offline'; }
    else if(status==='WORK'){ cls='work'; txt='working'; }
    else if(status==='SLEEP'){ cls='sleep'; txt='asleep'; }
    else if(status==='HIBERNATION'){ cls='sleep'; txt='hibernating'; }
    else if(status==='CHARGE'){ cls='charge'; txt='charging'; }
    else if(status==='OFF'){ cls='off'; txt='off'; }
    stp.textContent = txt; stp.className = 'state ' + cls;
    // toggle reflects real status unless a click is still settling (<2.5s)
    const sw=el.querySelector('.sw');
    const settling = pending[d.id] && (Date.now()-pending[d.id] < 2500);
    if(!settling){ sw.classList.remove('pending'); sw.classList.toggle('checked', isOn(s)); }
    drawGraph(c.canvas, (d.history||[]).slice().reverse());
  }
}
function cardsClear(){ for(const k in cards) delete cards[k]; }
setInterval(poll, 500); poll();
</script>
</body></html>"""


def main():
    global _raw_log
    ap = argparse.ArgumentParser(description="JBC B·IRON live web dashboard")
    ap.add_argument("--raw-log", metavar="PATH",
                    help="append every raw BLE frame (epoch, addr, name, frame) as TSV")
    args = ap.parse_args()
    if args.raw_log:
        _raw_log = open(args.raw_log, "a", encoding="utf-8")
        print(f"Logging raw frames -> {args.raw_log}")
    print("Scanning + connecting to JBC irons (close the phone app first) …")
    start_ble_thread()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"Dashboard: http://localhost:{PORT}")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
