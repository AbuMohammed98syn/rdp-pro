"""
RDP Pro v3.1 — Backend Server (Full Edition)
────────────────────────────────────────────
 • Screen: JPEG adaptive + multi-monitor
 • Audio: WASAPI loopback → PCM16 → WebSocket
 • Files: list/download/upload/delete/rename/mkdir/search/copy/preview
 • Terminal: PowerShell/CMD/bash مع session CWD
 • System: stats/processes/kill/actions/disks/network
 • Clipboard: push/pull
 • Printers: list + print
 • Notifications: push to mobile via WebSocket
 • WebRTC: signaling server (offer/answer/ice)
 • QR Code generation
 • Token Auth على كل endpoint
"""

import asyncio, base64, hashlib, io, json, logging, os, platform, shutil
import subprocess, time, uuid, socket
from pathlib import Path
from typing import Optional, Dict, List

import psutil
import pyautogui
import numpy as np
from PIL import Image, ImageGrab
from fastapi import (FastAPI, WebSocket, WebSocketDisconnect,
                     HTTPException, Header, Depends, Query)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

# ── Optional deps ──────────────────────────────────────────────────────────────
try:
    import soundcard as sc
    AUDIO_OK = True
except ImportError:
    sc = None
    AUDIO_OK = False

try:
    import xxhash
    def _hash(d): return xxhash.xxh64(d).digest()
except ImportError:
    def _hash(d): return hashlib.md5(d).digest()

# ── Logging ────────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[logging.StreamHandler(),
              logging.FileHandler("rdp_pro.log", encoding="utf-8")]
)
log = logging.getLogger("rdp")

# ── Config ─────────────────────────────────────────────────────────────────────
HOST       = os.getenv("RDP_HOST",    "0.0.0.0")
PORT       = int(os.getenv("RDP_PORT",    "8000"))
TOKEN      = os.getenv("RDP_TOKEN",   "rdppro-secret-2024")
JPEG_Q     = int(os.getenv("JPEG_QUALITY", "65"))
FPS        = int(os.getenv("FPS_TARGET",    "30"))
FRAME_MS   = 1000 // FPS

IS_WIN = platform.system() == "Windows"
IS_MAC = platform.system() == "Darwin"
IS_LIN = platform.system() == "Linux"

pyautogui.FAILSAFE = False
pyautogui.PAUSE    = 0

# ── App ─────────────────────────────────────────────────────────────────────────
app = FastAPI(title="RDP Pro", version="3.1.0", docs_url=None, redoc_url=None)
app.add_middleware(GZipMiddleware, minimum_size=1024)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

_static = Path(__file__).parent / "static"
_static.mkdir(exist_ok=True)
app.mount("/static", StaticFiles(directory=str(_static)), name="static")

# ── Auth ───────────────────────────────────────────────────────────────────────
def auth(x_token: str = Header(default=""), token: str = Query(default="")):
    t = x_token or token
    if TOKEN and t != TOKEN:
        raise HTTPException(401, "Unauthorized")

# ── State ──────────────────────────────────────────────────────────────────────
class State:
    screen_clients: List[WebSocket] = []
    control_ws:     Optional[WebSocket] = None
    audio_ws:       Optional[WebSocket] = None
    notify_ws:      Optional[WebSocket] = None
    rtc_clients:    Dict[str, WebSocket] = {}
    _net_prev  = psutil.net_io_counters()
    _net_time  = time.time()

    @classmethod
    def net_mbps(cls):
        now = time.time()
        cur = psutil.net_io_counters()
        dt  = max(now - cls._net_time, 0.001)
        s   = (cur.bytes_sent - cls._net_prev.bytes_sent) / dt / 125000
        r   = (cur.bytes_recv - cls._net_prev.bytes_recv) / dt / 125000
        cls._net_prev = cur
        cls._net_time = now
        return round(s, 2), round(r, 2)

    @staticmethod
    def local_ip():
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]; s.close()
            return ip
        except:
            return "127.0.0.1"

st = State()

# ── Screen Streamer ────────────────────────────────────────────────────────────
class ScreenStreamer:
    def __init__(self):
        self.quality  = JPEG_Q
        self.scale    = 1.0
        self.monitor  = 0
        self._last    = b""
        self._fps_cnt = 0
        self._fps_t   = time.time()
        self.fps_live = 0

    def monitors(self):
        try:
            from screeninfo import get_monitors
            return [{"index": i, "name": m.name or f"Monitor {i+1}",
                     "width": m.width, "height": m.height,
                     "x": m.x, "y": m.y,
                     "primary": getattr(m, "is_primary", i == 0)}
                    for i, m in enumerate(get_monitors())]
        except:
            w, h = pyautogui.size()
            return [{"index": 0, "name": "Primary", "width": w, "height": h,
                     "x": 0, "y": 0, "primary": True}]

    def capture(self) -> Optional[bytes]:
        try:
            mons = self.monitors()
            if self.monitor > 0 and self.monitor < len(mons):
                m    = mons[self.monitor]
                bbox = (m["x"], m["y"], m["x"] + m["width"], m["y"] + m["height"])
                img  = ImageGrab.grab(bbox=bbox)
            else:
                img = ImageGrab.grab()

            if self.scale != 1.0:
                img = img.resize(
                    (int(img.width * self.scale), int(img.height * self.scale)),
                    Image.LANCZOS)

            if img.mode != "RGB":
                img = img.convert("RGB")

            buf = io.BytesIO()
            img.save(buf, "JPEG", quality=self.quality, optimize=True, progressive=True)
            data = buf.getvalue()

            h = _hash(data)
            if h == self._last:
                return None
            self._last = h

            # FPS counter
            self._fps_cnt += 1
            now = time.time()
            if now - self._fps_t >= 1.0:
                self.fps_live   = self._fps_cnt
                self._fps_cnt   = 0
                self._fps_t     = now
            return data
        except Exception as e:
            log.error(f"capture: {e}")
            return None

streamer = ScreenStreamer()

# ── WS: Screen ─────────────────────────────────────────────────────────────────
@app.websocket("/ws/screen")
async def ws_screen(ws: WebSocket, token: str = Query("")):
    if TOKEN and token != TOKEN:
        await ws.close(4001); return
    await ws.accept()
    st.screen_clients.append(ws)
    log.info(f"Screen+: {len(st.screen_clients)} clients")
    try:
        while True:
            t0   = time.monotonic()
            data = streamer.capture()
            if data:
                dead = []
                for c in list(st.screen_clients):
                    try:
                        await c.send_bytes(data)
                    except:
                        dead.append(c)
                for d in dead:
                    st.screen_clients.remove(d)
            elapsed = (time.monotonic() - t0) * 1000
            await asyncio.sleep(max(0, FRAME_MS - elapsed) / 1000)
    except (WebSocketDisconnect, Exception):
        pass
    finally:
        if ws in st.screen_clients:
            st.screen_clients.remove(ws)

# ── WS: Control ────────────────────────────────────────────────────────────────
@app.websocket("/ws/control")
async def ws_control(ws: WebSocket, token: str = Query("")):
    if TOKEN and token != TOKEN:
        await ws.close(4001); return
    await ws.accept()
    st.control_ws = ws
    try:
        while True:
            await _ctrl(json.loads(await ws.receive_text()))
    except (WebSocketDisconnect, Exception):
        st.control_ws = None

async def _ctrl(m: dict):
    t = m.get("type", "")
    try:
        if   t == "mouse_move":    pyautogui.moveTo(m["x"], m["y"])
        elif t == "mouse_click":
            b = m.get("btn","left")
            if "x" in m: pyautogui.click(m["x"], m["y"], button=b)
            else:         pyautogui.click(button=b)
        elif t == "mouse_dbl":     pyautogui.doubleClick(m.get("x"), m.get("y"))
        elif t == "mouse_right":   pyautogui.rightClick(m.get("x"), m.get("y"))
        elif t == "mouse_middle":  pyautogui.middleClick(m.get("x"), m.get("y"))
        elif t == "mouse_scroll":
            a = m.get("amount", 3)
            pyautogui.scroll(-a if m.get("dir","up") == "down" else a)
        elif t == "mouse_drag":
            pyautogui.mouseDown(m["x1"], m["y1"])
            await asyncio.sleep(0.04)
            pyautogui.moveTo(m["x2"], m["y2"], duration=0.08)
            pyautogui.mouseUp()
        elif t == "key_press":     pyautogui.press(m["key"])
        elif t == "key_down":      pyautogui.keyDown(m["key"])
        elif t == "key_up":        pyautogui.keyUp(m["key"])
        elif t == "key_type":      pyautogui.typewrite(m["text"], interval=0.012)
        elif t == "hotkey":        pyautogui.hotkey(*m["keys"])
        elif t == "screen_quality": streamer.quality = int(m.get("quality", 65))
        elif t == "screen_scale":   streamer.scale   = float(m.get("scale",  1.0))
        elif t == "screen_monitor": streamer.monitor = int(m.get("monitor",  0))
    except Exception as e:
        log.debug(f"ctrl {t}: {e}")

# ── WS: Audio ──────────────────────────────────────────────────────────────────
@app.websocket("/ws/audio")
async def ws_audio(ws: WebSocket, token: str = Query("")):
    if TOKEN and token != TOKEN:
        await ws.close(4001); return
    await ws.accept()
    st.audio_ws = ws
    task: Optional[asyncio.Task] = None

    async def _stream():
        if not AUDIO_OK:
            await ws.send_text(json.dumps({"error":"soundcard غير مثبت"})); return
        try:
            spk  = sc.default_speaker()
            RATE = 44100; N = 2048
            loop = asyncio.get_event_loop()
            with sc.get_microphone(id=str(spk.name),
                                   include_loopback=True).recorder(
                                   samplerate=RATE, channels=1) as rec:
                while st.audio_ws == ws:
                    data  = await loop.run_in_executor(None, rec.record, N)
                    pcm16 = (np.clip(data,-1,1)*32767).astype(np.int16).tobytes()
                    b64   = base64.b64encode(pcm16).decode()
                    try:
                        await ws.send_text(json.dumps({"audio":b64,"rate":RATE,"samples":N}))
                    except:
                        break
        except Exception as e:
            try: await ws.send_text(json.dumps({"error":str(e)}))
            except: pass

    try:
        while True:
            d = json.loads(await ws.receive_text())
            if d.get("action") == "start" and task is None:
                task = asyncio.create_task(_stream())
            elif d.get("action") == "stop" and task:
                task.cancel(); task = None
    except (WebSocketDisconnect, Exception):
        if task: task.cancel()
        st.audio_ws = None

# ── WS: Notifications ─────────────────────────────────────────────────────────
@app.websocket("/ws/notify")
async def ws_notify(ws: WebSocket, token: str = Query("")):
    if TOKEN and token != TOKEN:
        await ws.close(4001); return
    await ws.accept()
    st.notify_ws = ws
    try:
        while True: await ws.receive_text()
    except (WebSocketDisconnect, Exception):
        st.notify_ws = None

async def _notify(title: str, body: str, icon: str = "info"):
    if st.notify_ws:
        try:
            await st.notify_ws.send_text(json.dumps(
                {"title":title,"body":body,"icon":icon,"ts":int(time.time()*1000)}))
        except:
            st.notify_ws = None

# ── WS: WebRTC Signaling ───────────────────────────────────────────────────────
@app.websocket("/ws/rtc/{room}")
async def ws_rtc(ws: WebSocket, room: str, token: str = Query("")):
    if TOKEN and token != TOKEN:
        await ws.close(4001); return
    await ws.accept()
    cid = str(uuid.uuid4())[:8]
    st.rtc_clients[cid] = ws
    log.info(f"RTC {cid} joined room {room}")

    async def _bcast(msg: dict, exclude=""):
        dead = []
        for k, c in list(st.rtc_clients.items()):
            if k == exclude: continue
            try:    await c.send_text(json.dumps(msg))
            except: dead.append(k)
        for d in dead: st.rtc_clients.pop(d, None)

    try:
        await ws.send_text(json.dumps({
            "type":"your_id","id":cid,
            "peers":[k for k in st.rtc_clients if k != cid]
        }))
        await _bcast({"type":"peer_joined","id":cid}, exclude=cid)
        while True:
            msg = json.loads(await ws.receive_text())
            msg["from"] = cid
            to = msg.pop("to", None)
            if to and to in st.rtc_clients:
                try:    await st.rtc_clients[to].send_text(json.dumps(msg))
                except: st.rtc_clients.pop(to, None)
            else:
                await _bcast(msg, exclude=cid)
    except (WebSocketDisconnect, Exception):
        pass
    finally:
        st.rtc_clients.pop(cid, None)
        await _bcast({"type":"peer_left","id":cid})

# ── WS: File Upload ────────────────────────────────────────────────────────────
@app.websocket("/ws/upload")
async def ws_upload(ws: WebSocket, token: str = Query("")):
    if TOKEN and token != TOKEN:
        await ws.close(4001); return
    await ws.accept()
    try:
        meta     = json.loads(await ws.receive_text())
        fname    = Path(meta["name"]).name
        dest_dir = Path(meta.get("dest", str(Path.home() / "Downloads"))).resolve()
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / fname
        i = 1
        while dest.exists():
            dest = dest_dir / f"{Path(fname).stem}_{i}{Path(fname).suffix}"
            i += 1

        total    = meta.get("size", 0)
        received = 0
        with open(dest, "wb") as f:
            while True:
                chunk = await asyncio.wait_for(ws.receive_bytes(), timeout=60)
                if chunk == b"\x00EOF\x00":
                    break
                f.write(chunk)
                received += len(chunk)
                pct = int(received * 100 / total) if total else 0
                await ws.send_text(json.dumps({"progress":pct,"received":received}))

        await ws.send_text(json.dumps(
            {"done":True,"path":str(dest),"name":dest.name,"size":received}))
        asyncio.create_task(_notify("📁 ملف مستلم",
                                    f"{dest.name} ({_hsize(received)})","file"))
    except asyncio.TimeoutError:
        await ws.send_text(json.dumps({"error":"timeout"}))
    except (WebSocketDisconnect, Exception) as e:
        try: await ws.send_text(json.dumps({"error":str(e)}))
        except: pass

# ── Screen ─────────────────────────────────────────────────────────────────────
@app.get("/api/screen/size")
def screen_size(_=Depends(auth)):
    mons = streamer.monitors()
    m    = mons[streamer.monitor] if streamer.monitor < len(mons) else mons[0]
    return {**m, "all_monitors": mons, "fps_target": FPS, "fps_live": streamer.fps_live}

@app.get("/api/screen/monitors")
def screen_monitors(_=Depends(auth)):
    return {"monitors": streamer.monitors()}

class ScreenCfg(BaseModel):
    monitor: Optional[int]   = None
    quality: Optional[int]   = None
    scale:   Optional[float] = None
    fps:     Optional[int]   = None

@app.post("/api/screen/config")
def screen_config(c: ScreenCfg, _=Depends(auth)):
    global FPS, FRAME_MS
    if c.monitor is not None: streamer.monitor = c.monitor
    if c.quality is not None: streamer.quality = max(10, min(95, c.quality))
    if c.scale   is not None: streamer.scale   = max(0.3, min(2.0, c.scale))
    if c.fps     is not None:
        FPS = max(1, min(60, c.fps)); FRAME_MS = 1000 // FPS
    return {"ok":True,"monitor":streamer.monitor,"quality":streamer.quality,
            "scale":streamer.scale,"fps":FPS}

@app.get("/api/screen/screenshot")
def screenshot(_=Depends(auth)):
    data = streamer.capture() or b""
    return {"image": base64.b64encode(data).decode(), "ts": int(time.time()*1000)}

# ── System Stats ────────────────────────────────────────────────────────────────
@app.get("/api/system/stats")
def sys_stats(_=Depends(auth)):
    cpu  = psutil.cpu_percent(interval=0.1)
    mem  = psutil.virtual_memory()
    disk = psutil.disk_usage("/")
    sent, recv = st.net_mbps()
    uptime = (time.time() - psutil.boot_time()) / 3600

    temps = {}
    try:
        for k, v in (psutil.sensors_temperatures() or {}).items():
            if v: temps[k] = round(v[0].current, 1)
    except AttributeError:
        pass

    battery = None
    try:
        b = psutil.sensors_battery()
        if b: battery = {"percent": round(b.percent,1), "charging": b.power_plugged}
    except: pass

    return {
        "cpu_percent":     round(cpu, 1),
        "cpu_cores":       psutil.cpu_count(logical=False),
        "cpu_threads":     psutil.cpu_count(logical=True),
        "cpu_freq_mhz":    round((psutil.cpu_freq() or type("",(),{"current":0})()).current),
        "memory_percent":  round(mem.percent, 1),
        "memory_used_gb":  round(mem.used    / 1e9, 2),
        "memory_total_gb": round(mem.total   / 1e9, 2),
        "disk_percent":    round(disk.percent, 1),
        "disk_used_gb":    round(disk.used   / 1e9, 2),
        "disk_total_gb":   round(disk.total  / 1e9, 2),
        "disk_free_gb":    round(disk.free   / 1e9, 2),
        "net_sent_mbps":   sent,
        "net_recv_mbps":   recv,
        "uptime_h":        round(uptime, 2),
        "platform":        platform.system(),
        "platform_ver":    platform.version()[:50],
        "hostname":        platform.node(),
        "local_ip":        st.local_ip(),
        "temperatures":    temps,
        "battery":         battery,
        "screen_clients":  len(st.screen_clients),
        "fps_live":        streamer.fps_live,
    }

@app.get("/api/system/disks")
def sys_disks(_=Depends(auth)):
    result = []
    for p in psutil.disk_partitions(all=False):
        try:
            u = psutil.disk_usage(p.mountpoint)
            result.append({"device":p.device,"mountpoint":p.mountpoint,
                            "fstype":p.fstype,"total_gb":round(u.total/1e9,2),
                            "used_gb":round(u.used/1e9,2),"free_gb":round(u.free/1e9,2),
                            "percent":round(u.percent,1)})
        except: pass
    return {"disks": result}

@app.get("/api/system/network")
def sys_network(_=Depends(auth)):
    ifaces = []
    for name, addrs in psutil.net_if_addrs().items():
        ips = [a.address for a in addrs if a.family == socket.AF_INET]
        if ips: ifaces.append({"name": name, "ip": ips[0]})
    return {"interfaces": ifaces, "local_ip": st.local_ip()}

# ── Processes ──────────────────────────────────────────────────────────────────
@app.get("/api/system/processes")
def processes(sort: str = "cpu", limit: int = 25, search: str = "", _=Depends(auth)):
    procs = []
    for p in psutil.process_iter(["pid","name","cpu_percent","memory_info","status","username"]):
        try:
            i = p.info
            if search and search.lower() not in (i["name"] or "").lower(): continue
            mem = round((i["memory_info"].rss if i["memory_info"] else 0) / 1e6, 1)
            procs.append({"pid":i["pid"],"name":i["name"],"cpu":round(i["cpu_percent"] or 0,1),
                          "mem_mb":mem,"status":i["status"],
                          "user":(i.get("username","") or "")[:20]})
        except (psutil.NoSuchProcess, psutil.AccessDenied, psutil.ZombieProcess): pass
    procs.sort(key=lambda x: x["cpu" if sort=="cpu" else "mem_mb"], reverse=True)
    return {"processes": procs[:limit], "total": len(procs)}

class KillReq(BaseModel):
    pid: int
    force: bool = False

@app.post("/api/system/kill")
def kill_proc(r: KillReq, _=Depends(auth)):
    try:
        p = psutil.Process(r.pid)
        name = p.name()
        p.kill() if r.force else p.terminate()
        return {"ok":True,"name":name,"pid":r.pid}
    except psutil.NoSuchProcess: return {"ok":False,"error":"غير موجود"}
    except psutil.AccessDenied:  return {"ok":False,"error":"لا صلاحية"}
    except Exception as e:       return {"ok":False,"error":str(e)}

# ── System Actions ─────────────────────────────────────────────────────────────
def _run(*a):
    try: subprocess.Popen(list(a)); return True
    except Exception as e: log.error(f"run {a}: {e}"); return False

ACTIONS = {
    "lock":          lambda: _run("rundll32.exe","user32.dll,LockWorkStation") if IS_WIN
                             else _run("loginctl","lock-session"),
    "sleep":         lambda: _run("rundll32.exe","powrprof.dll,SetSuspendState","0","1","0") if IS_WIN
                             else _run("systemctl","suspend"),
    "hibernate":     lambda: _run("shutdown","/h") if IS_WIN else _run("systemctl","hibernate"),
    "restart":       lambda: _run("shutdown","/r","/t","5") if IS_WIN else _run("reboot"),
    "shutdown":      lambda: _run("shutdown","/s","/t","5") if IS_WIN else _run("poweroff"),
    "abort":         lambda: _run("shutdown","/a"),
    "task_manager":  lambda: _run("taskmgr.exe"),
    "cmd":           lambda: _run("cmd.exe"),
    "powershell":    lambda: _run("powershell.exe"),
    "explorer":      lambda: _run("explorer.exe"),
    "notepad":       lambda: _run("notepad.exe"),
    "calc":          lambda: _run("calc.exe"),
    "mute":          lambda: pyautogui.press("volumemute"),
    "vol_up":        lambda: pyautogui.press("volumeup"),
    "vol_down":      lambda: pyautogui.press("volumedown"),
    "prev_track":    lambda: pyautogui.press("prevtrack"),
    "next_track":    lambda: pyautogui.press("nexttrack"),
    "play_pause":    lambda: pyautogui.press("playpause"),
    "screenshot":    lambda: pyautogui.press("printscreen"),
    "snipping":      lambda: pyautogui.hotkey("win","shift","s"),
    "action_center": lambda: pyautogui.hotkey("win","a"),
    "settings":      lambda: pyautogui.hotkey("win","i"),
    "run_dialog":    lambda: pyautogui.hotkey("win","r"),
    "show_desktop":  lambda: pyautogui.hotkey("win","d"),
}

@app.post("/api/system/{action}")
def sys_action(action: str, _=Depends(auth)):
    fn = ACTIONS.get(action)
    if not fn:
        raise HTTPException(400, f"Unknown: {action}. Available: {list(ACTIONS)}")
    ok = fn()
    return {"ok": True if ok is None else bool(ok), "action": action}

@app.get("/api/system/actions")
def list_actions(_=Depends(auth)):
    return {"actions": list(ACTIONS.keys())}

# ── Terminal ───────────────────────────────────────────────────────────────────
_sessions: Dict[int, str] = {}

class ExecReq(BaseModel):
    command:    str
    timeout:    int = 30
    session:    int = 0
    shell_type: str = "auto"  # auto | powershell | cmd | bash

@app.post("/api/terminal/exec")
def term_exec(r: ExecReq, _=Depends(auth)):
    cmd = r.command.strip()
    cwd = _sessions.get(r.session, str(Path.home()))
    if not cmd:
        return {"stdout":"","stderr":"","cwd":cwd,"code":0}

    # cd داخلي
    if cmd.lower().startswith("cd ") or cmd == "cd":
        target = cmd[3:].strip().strip('"\'') if len(cmd) > 3 else str(Path.home())
        try:
            p = (Path(cwd) / target).resolve()
            if p.is_dir():
                _sessions[r.session] = str(p)
                return {"stdout":"","stderr":"","cwd":str(p),"code":0}
            return {"stdout":"","stderr":f"المجلد غير موجود: {p}","cwd":cwd,"code":1}
        except Exception as e:
            return {"stdout":"","stderr":str(e),"cwd":cwd,"code":1}

    # تحديد shell
    if r.shell_type == "powershell" or (r.shell_type == "auto" and IS_WIN):
        args = ["powershell.exe","-NoProfile","-NonInteractive","-Command",cmd]
        sh   = False
    elif r.shell_type == "cmd":
        args = ["cmd.exe","/c",cmd]; sh = False
    else:
        args = cmd; sh = True

    try:
        res = subprocess.run(args, shell=sh, capture_output=True, text=True,
                             timeout=r.timeout, cwd=cwd,
                             encoding="utf-8", errors="replace")
        return {"stdout":res.stdout[:10000],"stderr":res.stderr[:3000],
                "cwd":cwd,"code":res.returncode}
    except subprocess.TimeoutExpired:
        return {"stdout":"","stderr":f"Timeout ({r.timeout}s)","cwd":cwd,"code":-1}
    except Exception as e:
        return {"stdout":"","stderr":str(e),"cwd":cwd,"code":-1}

@app.post("/api/terminal/clear/{sid}")
def term_clear(sid: int, _=Depends(auth)):
    _sessions.pop(sid, None)
    return {"ok":True}

# ── Clipboard ──────────────────────────────────────────────────────────────────
class ClipReq(BaseModel):
    text: str

@app.post("/api/clipboard")
def clip_set(r: ClipReq, _=Depends(auth)):
    try:
        import pyperclip; pyperclip.copy(r.text)
        return {"ok":True}
    except Exception as e:
        return {"ok":False,"error":str(e)}

@app.get("/api/clipboard")
def clip_get(_=Depends(auth)):
    try:
        import pyperclip
        t = pyperclip.paste()
        return {"text":t,"length":len(t)}
    except Exception as e:
        return {"text":"","error":str(e)}

# ── Files ──────────────────────────────────────────────────────────────────────
def _sp(p: str) -> Path: return Path(p).resolve()

def _hsize(b: int) -> str:
    for u in ("B","KB","MB","GB","TB"):
        if b < 1024: return f"{b:.1f} {u}"
        b //= 1024
    return f"{b} PB"

@app.get("/api/files/quick_links")
def quick_links(_=Depends(auth)):
    home = Path.home()
    links = {"🏠 Home":str(home),"🖥️ Desktop":str(home/"Desktop"),
             "📄 Documents":str(home/"Documents"),"🖼️ Pictures":str(home/"Pictures"),
             "🎵 Music":str(home/"Music"),"🎬 Videos":str(home/"Videos"),
             "⬇️ Downloads":str(home/"Downloads")}
    if IS_WIN:
        for d in ["C:\\","D:\\","E:\\"]:
            if Path(d).exists(): links[f"💾 {d}"] = d
    else:
        links["/"] = "/"
    return {k: v for k,v in links.items() if Path(v).exists()}

@app.get("/api/files/list")
def file_list(path: str, _=Depends(auth)):
    p = _sp(path)
    if not p.exists():   raise HTTPException(404, "غير موجود")
    if not p.is_dir():   raise HTTPException(400, "ليس مجلداً")
    items = []
    try:
        for e in sorted(p.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
            try:
                s  = e.stat()
                items.append({"name":e.name,"path":str(e),"is_dir":e.is_dir(),
                               "size_b":s.st_size if e.is_file() else 0,
                               "size_h":_hsize(s.st_size) if e.is_file() else "",
                               "mtime":int(s.st_mtime),"ext":e.suffix.lower(),
                               "hidden":e.name.startswith(".")})
            except PermissionError: pass
    except PermissionError: raise HTTPException(403,"لا صلاحية")
    parent = str(p.parent) if p.parent != p else None
    return {"path":str(p),"parent":parent,"files":items,"count":len(items)}

@app.get("/api/files/search")
def file_search(path: str, query: str, _=Depends(auth)):
    base = _sp(path)
    if not base.is_dir(): raise HTTPException(400,"ليس مجلداً")
    results, q = [], query.lower()
    try:
        for f in base.rglob("*"):
            if q in f.name.lower():
                try:
                    s = f.stat()
                    results.append({"name":f.name,"path":str(f),"is_dir":f.is_dir(),
                                    "size_h":_hsize(s.st_size) if f.is_file() else "",
                                    "rel":str(f.relative_to(base))})
                except: pass
            if len(results) >= 100: break
    except: pass
    return {"results":results,"count":len(results)}

@app.get("/api/files/download")
def file_download(path: str, _=Depends(auth)):
    p = _sp(path)
    if not p.is_file(): raise HTTPException(404,"الملف غير موجود")
    return FileResponse(str(p), filename=p.name, media_type="application/octet-stream")

@app.get("/api/files/preview")
def file_preview(path: str, _=Depends(auth)):
    p = _sp(path)
    if not p.is_file(): raise HTTPException(404)
    ext = p.suffix.lower()
    if ext in (".jpg",".jpeg",".png",".gif",".bmp",".webp"):
        return FileResponse(str(p))
    if ext in (".txt",".log",".md",".py",".js",".json",".xml",".csv",".kt",".java",".sh"):
        try:
            text = p.read_text(encoding="utf-8", errors="replace")[:50_000]
            return {"type":"text","content":text,"lines":text.count("\n"),"size":len(text)}
        except: raise HTTPException(400,"لا يمكن قراءة الملف")
    raise HTTPException(400,f"معاينة '{ext}' غير مدعومة")

class PathReq(BaseModel):
    path: str

class RenameReq(BaseModel):
    src: str; dst: str

class CopyReq(BaseModel):
    src: str; dst: str; move: bool = False

@app.post("/api/files/delete")
def file_delete(r: PathReq, _=Depends(auth)):
    p = _sp(r.path)
    try:
        shutil.rmtree(str(p)) if p.is_dir() else p.unlink()
        return {"ok":True}
    except Exception as e: return {"ok":False,"error":str(e)}

@app.post("/api/files/rename")
def file_rename(r: RenameReq, _=Depends(auth)):
    try:
        _sp(r.src).rename(_sp(r.dst))
        return {"ok":True,"new_path":r.dst}
    except Exception as e: return {"ok":False,"error":str(e)}

@app.post("/api/files/mkdir")
def file_mkdir(r: PathReq, _=Depends(auth)):
    try:
        _sp(r.path).mkdir(parents=True, exist_ok=True)
        return {"ok":True}
    except Exception as e: return {"ok":False,"error":str(e)}

@app.post("/api/files/copy")
def file_copy(r: CopyReq, _=Depends(auth)):
    s, d = _sp(r.src), _sp(r.dst)
    try:
        if r.move: shutil.move(str(s), str(d))
        elif s.is_dir(): shutil.copytree(str(s), str(d))
        else: shutil.copy2(str(s), str(d))
        return {"ok":True,"dst":str(d)}
    except Exception as e: return {"ok":False,"error":str(e)}

# ── Printers (Windows) ─────────────────────────────────────────────────────────
@app.get("/api/printers")
def printers(_=Depends(auth)):
    if not IS_WIN: return {"printers":[],"note":"Windows only"}
    try:
        r = subprocess.run(
            ["powershell","-NoProfile","-Command",
             "Get-Printer|Select Name,DriverName,PrinterStatus,Default|ConvertTo-Json"],
            capture_output=True, text=True, timeout=10)
        raw = r.stdout.strip()
        if not raw: return {"printers":[]}
        data = json.loads(raw)
        if isinstance(data, dict): data = [data]
        return {"printers":[{"name":p.get("Name",""),"driver":p.get("DriverName",""),
                              "default":p.get("Default",False)} for p in data]}
    except Exception as e: return {"printers":[],"error":str(e)}

class PrintReq(BaseModel):
    file_path: str
    printer:   str = ""
    copies:    int = 1

@app.post("/api/printers/print")
def print_file(r: PrintReq, _=Depends(auth)):
    p = _sp(r.file_path)
    if not p.is_file(): return {"ok":False,"error":"الملف غير موجود"}
    try:
        if IS_WIN:
            subprocess.Popen(["powershell","-Command",
                               f'Start-Process -FilePath "{p}" -Verb Print -Wait'])
        else:
            args = ["lp"]
            if r.printer: args += ["-d",r.printer]
            args += ["-n",str(r.copies), str(p)]
            subprocess.Popen(args)
        return {"ok":True,"file":p.name}
    except Exception as e: return {"ok":False,"error":str(e)}

# ── Notifications ──────────────────────────────────────────────────────────────
class NotifyReq(BaseModel):
    title: str; body: str; icon: str = "info"

@app.post("/api/notify")
async def notify(r: NotifyReq, _=Depends(auth)):
    await _notify(r.title, r.body, r.icon)
    return {"ok":True}

# ── QR Code ────────────────────────────────────────────────────────────────────
@app.get("/api/qr")
def gen_qr(_=Depends(auth)):
    data = f"rdppro://{st.local_ip()}:{PORT}/{TOKEN}"
    try:
        import qrcode, io as _io
        qr  = qrcode.QRCode(box_size=6, border=2)
        qr.add_data(data); qr.make(fit=True)
        img = qr.make_image(fill_color="white", back_color="black")
        buf = _io.BytesIO(); img.save(buf,"PNG")
        return {"qr": base64.b64encode(buf.getvalue()).decode(), "data": data}
    except ImportError:
        return {"error":"pip install qrcode","data":data}

# ── Info ───────────────────────────────────────────────────────────────────────
@app.get("/")
def root():
    ip = st.local_ip()
    return {"name":"RDP Pro","version":"3.1.0","status":"running",
            "ip":ip,"port":PORT,"qr_url":f"rdppro://{ip}:{PORT}/{TOKEN[:4]}****",
            "screens":len(st.screen_clients),"audio":AUDIO_OK}

@app.get("/api/ping")
def ping(_=Depends(auth)):
    return {"pong":True,"ts":int(time.time()*1000),"version":"3.1.0"}

@app.get("/api/info")
def info(_=Depends(auth)):
    return {"version":"3.1.0","platform":platform.system(),
            "hostname":platform.node(),"ip":st.local_ip(),"port":PORT,
            "audio":AUDIO_OK,"monitors":streamer.monitors(),"fps":FPS,
            "quality":streamer.quality,"scale":streamer.scale,
            "actions":list(ACTIONS.keys())}

@app.on_event("startup")
async def startup():
    ip = st.local_ip()
    log.info("=" * 50)
    log.info(f"  RDP Pro v3.1.0 — http://{ip}:{PORT}")
    log.info(f"  QR:  rdppro://{ip}:{PORT}/{TOKEN[:4]}****")
    log.info(f"  Audio: {'✓' if AUDIO_OK else '✗ pip install soundcard'}")
    log.info("=" * 50)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host=HOST, port=PORT, reload=False,
                log_level="warning", ws_ping_interval=20, ws_ping_timeout=30)
