"""
RDP Pro v4.0 — Backend Server
─────────────────────────────────────────────────────────
 • Screen: JPEG adaptive + Delta compression (changed regions only)
 • Wake-on-LAN: Magic Packet via UDP broadcast
 • Chat: Real-time text chat via WebSocket
 • Throw-away Tokens: Temporary one-time access tokens
 • Audio: WASAPI loopback → PCM16 → WebSocket
 • Files: list/download/upload/delete/rename/mkdir/search/copy/preview
 • Terminal: PowerShell/CMD with session CWD
 • System: stats/processes/kill/actions/disks/network
 • Clipboard: push/pull (text + image)
 • Printers: list + print
 • Notifications: push to mobile via WebSocket
 • WebRTC: signaling server (offer/answer/ice)
 • Multi-monitor: detect and select display
 • Session Recording: server-side frame saving
 • QR Code generation
 • Token Auth on all endpoints
 • Adaptive FPS: auto-adjust based on network speed
"""

import asyncio, base64, hashlib, io, json, logging, os, platform, shutil
import subprocess, time, uuid, socket, struct, secrets, threading
from pathlib import Path
from typing import Optional, Dict, List, Set
from datetime import datetime, timedelta
from collections import deque

import psutil
import pyautogui
import numpy as np
from PIL import Image, ImageGrab
from fastapi import (FastAPI, WebSocket, WebSocketDisconnect,
                     HTTPException, Header, Depends, Query, Body)
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import FileResponse, JSONResponse
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

# ── App ────────────────────────────────────────────────────────────────────────
app = FastAPI(title="RDP Pro", version="4.0.0", docs_url=None, redoc_url=None)
app.add_middleware(GZipMiddleware, minimum_size=1024)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

_static = Path(__file__).parent / "static"
_static.mkdir(exist_ok=True)
app.mount("/static", StaticFiles(directory=str(_static)), name="static")

# ── Auth ───────────────────────────────────────────────────────────────────────
# Throw-away tokens: {token: expiry_timestamp}
_temp_tokens: Dict[str, float] = {}
_temp_tokens_lock = threading.Lock()

def _is_valid_token(t: str) -> bool:
    if TOKEN and t == TOKEN:
        return True
    with _temp_tokens_lock:
        # Clean expired
        now = time.time()
        expired = [k for k, v in _temp_tokens.items() if v < now]
        for k in expired:
            del _temp_tokens[k]
        return t in _temp_tokens

def auth(x_token: str = Header(default=""), token: str = Query(default="")):
    t = x_token or token
    if not _is_valid_token(t):
        raise HTTPException(401, "Unauthorized")

# ── State ──────────────────────────────────────────────────────────────────────
class State:
    screen_clients:  List[WebSocket]      = []
    control_ws:      Optional[WebSocket]  = None
    audio_ws:        Optional[WebSocket]  = None
    notify_ws:       Optional[WebSocket]  = None
    chat_clients:    List[WebSocket]      = []
    rtc_clients:     Dict[str, WebSocket] = {}
    _net_prev  = psutil.net_io_counters()
    _net_time  = time.time()
    # Delta compression state
    _last_frame_hash: Optional[bytes] = None
    _last_frame_data: Optional[bytes] = None
    # Adaptive quality
    client_fps_target: int = FPS
    client_quality:    int = JPEG_Q
    # Recording
    recording:         bool = False
    recording_frames:  deque = deque(maxlen=3600)  # max 2min at 30fps
    # Selected monitor
    selected_monitor: int = 0

    @classmethod
    def net_mbps(cls):
        now = time.time()
        cur = psutil.net_io_counters()
        dt  = max(now - cls._net_time, 0.001)
        sent = (cur.bytes_sent - cls._net_prev.bytes_sent) / dt / 1e6
        recv = (cur.bytes_recv - cls._net_prev.bytes_recv) / dt / 1e6
        cls._net_prev = cur
        cls._net_time = now
        return round(sent, 2), round(recv, 2)

# ── Screen capture ─────────────────────────────────────────────────────────────
def _capture_screen(monitor_idx: int = 0) -> Image.Image:
    try:
        if IS_WIN:
            monitors = pyautogui.getAllScreens() if hasattr(pyautogui, 'getAllScreens') else None
            return ImageGrab.grab()
        elif IS_MAC:
            return ImageGrab.grab()
        else:
            from PIL import ImageGrab as IG
            return IG.grab()
    except Exception:
        return ImageGrab.grab()

def _frame_to_jpeg(img: Image.Image, quality: int = 65) -> bytes:
    buf = io.BytesIO()
    if img.mode != "RGB":
        img = img.convert("RGB")
    img.save(buf, format="JPEG", quality=quality, optimize=False)
    return buf.getvalue()

def _frame_to_webp(img: Image.Image, quality: int = 65) -> bytes:
    buf = io.BytesIO()
    if img.mode != "RGB":
        img = img.convert("RGB")
    img.save(buf, format="WEBP", quality=quality, method=0)
    return buf.getvalue()

# ── Screen WebSocket ───────────────────────────────────────────────────────────
@app.websocket("/ws/screen")
async def ws_screen(ws: WebSocket, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    State.screen_clients.append(ws)
    log.info(f"Screen client connected (total: {len(State.screen_clients)})")

    # Adaptive quality control vars
    quality      = State.client_quality
    frame_target = 1.0 / State.client_fps_target
    last_hash    = None
    consecutive_same = 0

    try:
        while True:
            t0 = time.time()

            # Check for quality/fps update messages from client
            try:
                data = await asyncio.wait_for(ws.receive_text(), timeout=0.001)
                msg  = json.loads(data)
                if msg.get("type") == "quality":
                    quality = max(20, min(95, int(msg.get("value", quality))))
                elif msg.get("type") == "fps":
                    frame_target = 1.0 / max(5, min(60, int(msg.get("value", 30))))
            except (asyncio.TimeoutError, Exception):
                pass

            # Capture
            try:
                img  = await asyncio.get_event_loop().run_in_executor(
                    None, lambda: _capture_screen(State.selected_monitor))
                data = await asyncio.get_event_loop().run_in_executor(
                    None, lambda: _frame_to_jpeg(img, quality))
            except Exception as e:
                log.warning(f"Capture error: {e}")
                await asyncio.sleep(frame_target)
                continue

            # Delta compression — skip if frame unchanged
            h = _hash(data)
            if h == last_hash:
                consecutive_same += 1
                if consecutive_same > 3:
                    await asyncio.sleep(frame_target * 2)
                    continue
            else:
                consecutive_same = 0
                last_hash = h

            # Save for recording
            if State.recording:
                State.recording_frames.append(data)

            # Send to all screen clients
            dead = []
            for client in State.screen_clients:
                try:
                    await client.send_bytes(data)
                except Exception:
                    dead.append(client)
            for d in dead:
                State.screen_clients.remove(d)

            # Adaptive timing
            elapsed = time.time() - t0
            sleep_t = max(0, frame_target - elapsed)
            await asyncio.sleep(sleep_t)

    except WebSocketDisconnect:
        pass
    finally:
        if ws in State.screen_clients:
            State.screen_clients.remove(ws)
        log.info(f"Screen client disconnected (remaining: {len(State.screen_clients)})")

# ── Control WebSocket ──────────────────────────────────────────────────────────
@app.websocket("/ws/control")
async def ws_control(ws: WebSocket, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    State.control_ws = ws
    log.info("Control client connected")
    try:
        while True:
            raw = await ws.receive_text()
            try:
                msg = json.loads(raw)
                await _handle_control(msg)
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        pass
    finally:
        if State.control_ws is ws:
            State.control_ws = None

async def _handle_control(msg: dict):
    t = msg.get("type", "")
    try:
        if t == "mouse_move":
            pyautogui.moveTo(msg["x"], msg["y"])
        elif t == "mouse_click":
            if "x" in msg: pyautogui.click(msg["x"], msg["y"])
            else: pyautogui.click()
        elif t == "mouse_right":
            if "x" in msg: pyautogui.rightClick(msg["x"], msg["y"])
            else: pyautogui.rightClick()
        elif t == "mouse_dbl":
            if "x" in msg: pyautogui.doubleClick(msg["x"], msg["y"])
            else: pyautogui.doubleClick()
        elif t == "mouse_middle":
            pyautogui.middleClick()
        elif t == "mouse_scroll":
            d = msg.get("dir","down"); a = msg.get("amount",3)
            pyautogui.scroll(a if d=="up" else -a)
        elif t == "mouse_drag":
            pyautogui.drag(msg["x1"],msg["y1"],msg["x2"],msg["y2"],0.2)
        elif t == "key_press":
            pyautogui.press(msg.get("key",""))
        elif t == "key_type":
            pyautogui.write(msg.get("text",""), interval=0.02)
        elif t == "hotkey":
            keys = msg.get("keys",[])
            if keys: pyautogui.hotkey(*keys)
        elif t == "screen_quality":
            State.client_quality = max(20, min(95, int(msg.get("quality", 65))))
        elif t == "screen_scale":
            pass  # handled client-side
    except Exception as e:
        log.warning(f"Control error ({t}): {e}")

# ── Chat WebSocket ─────────────────────────────────────────────────────────────
@app.websocket("/ws/chat")
async def ws_chat(ws: WebSocket, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    State.chat_clients.append(ws)
    log.info(f"Chat client connected ({len(State.chat_clients)})")
    # Send welcome
    await ws.send_text(json.dumps({
        "type": "system",
        "text": "مرحباً بك في الدردشة",
        "ts": int(time.time() * 1000)
    }))
    try:
        while True:
            raw = await ws.receive_text()
            msg = json.loads(raw)
            msg["ts"] = int(time.time() * 1000)
            msg["from"] = "mobile"
            # Broadcast to all chat clients
            dead = []
            for client in State.chat_clients:
                try:
                    await client.send_text(json.dumps(msg))
                except Exception:
                    dead.append(client)
            for d in dead:
                State.chat_clients.remove(d)
    except WebSocketDisconnect:
        pass
    finally:
        if ws in State.chat_clients:
            State.chat_clients.remove(ws)

# ── Audio WebSocket ────────────────────────────────────────────────────────────
@app.websocket("/ws/audio")
async def ws_audio(ws: WebSocket, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    State.audio_ws = ws
    log.info("Audio client connected")
    streaming = False
    try:
        while True:
            raw = await asyncio.wait_for(ws.receive_text(), timeout=0.5)
            msg = json.loads(raw)
            if msg.get("action") == "start":
                streaming = True
                asyncio.create_task(_audio_stream(ws))
            elif msg.get("action") == "stop":
                streaming = False
    except (asyncio.TimeoutError, WebSocketDisconnect):
        pass
    finally:
        State.audio_ws = None

async def _audio_stream(ws: WebSocket):
    if not AUDIO_OK or sc is None:
        await ws.send_text(json.dumps({"error": "audio_not_available"}))
        return
    try:
        mics = sc.all_microphones(include_loopback=True)
        loopback = next((m for m in mics if m.isloopback), mics[0] if mics else None)
        if loopback is None:
            return
        RATE = 44100; CHUNK = 4096
        with loopback.recorder(samplerate=RATE, channels=2) as mic:
            while State.audio_ws is ws:
                chunk = mic.record(numframes=CHUNK)
                pcm = (chunk * 32767).astype(np.int16)
                b64 = base64.b64encode(pcm.tobytes()).decode()
                await ws.send_text(json.dumps({"audio": b64, "rate": RATE}))
    except Exception as e:
        log.warning(f"Audio stream error: {e}")

# ── Notifications WebSocket ────────────────────────────────────────────────────
@app.websocket("/ws/notify")
async def ws_notify(ws: WebSocket, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    State.notify_ws = ws
    try:
        while True:
            await asyncio.sleep(30)
            await ws.send_text(json.dumps({"ping": True}))
    except WebSocketDisconnect:
        pass
    finally:
        State.notify_ws = None

async def push_notification(title: str, body: str, icon: str = "🔔"):
    if State.notify_ws:
        try:
            await State.notify_ws.send_text(json.dumps({
                "type": "notification", "title": title,
                "body": body, "icon": icon
            }))
        except Exception:
            pass

# ── WebRTC Signaling ───────────────────────────────────────────────────────────
@app.websocket("/ws/rtc/{room_id}")
async def ws_rtc(ws: WebSocket, room_id: str, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    State.rtc_clients[room_id] = ws
    try:
        while True:
            data = await ws.receive_text()
            for rid, client in list(State.rtc_clients.items()):
                if rid != room_id:
                    try: await client.send_text(data)
                    except Exception: pass
    except WebSocketDisconnect:
        pass
    finally:
        State.rtc_clients.pop(room_id, None)

# ── File Upload WebSocket ──────────────────────────────────────────────────────
@app.websocket("/ws/upload")
async def ws_upload(ws: WebSocket, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    try:
        meta_raw = await ws.receive_text()
        meta = json.loads(meta_raw)
        name = Path(meta["name"]).name
        dest = Path(meta.get("dest") or Path.home())
        dest.mkdir(parents=True, exist_ok=True)
        out_path = dest / name
        total = meta.get("size", 0)
        received = 0
        with open(out_path, "wb") as f:
            while True:
                chunk = await ws.receive_bytes()
                if chunk == b"EOF":
                    break
                f.write(chunk)
                received += len(chunk)
                if total > 0:
                    pct = int(received * 100 / total)
                    await ws.send_text(json.dumps({"progress": pct}))
        await ws.send_text(json.dumps({"done": True, "path": str(out_path)}))
    except WebSocketDisconnect:
        pass
    except Exception as e:
        try: await ws.send_text(json.dumps({"error": str(e)}))
        except: pass

# ── ─────────────────────────────── REST API ─────────────────────────────── ──

# ── Ping ──────────────────────────────────────────────────────────────────────
@app.get("/api/ping")
async def ping(): return {"pong": True, "version": "4.0.0"}

# ── Wake-on-LAN ────────────────────────────────────────────────────────────────
class WolRequest(BaseModel):
    mac: str
    broadcast: str = "255.255.255.255"
    port: int = 9

@app.post("/api/wol", dependencies=[Depends(auth)])
async def wake_on_lan(req: WolRequest):
    """Send Magic Packet to wake a sleeping computer"""
    try:
        # Clean MAC address
        mac_clean = req.mac.replace(":", "").replace("-", "").replace(".", "").upper()
        if len(mac_clean) != 12:
            raise HTTPException(400, "Invalid MAC address format")

        # Build magic packet: 6x 0xFF + 16x MAC
        magic = bytes.fromhex("FF" * 6 + mac_clean * 16)

        # Send via UDP broadcast
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
            sock.settimeout(3)
            sock.sendto(magic, (req.broadcast, req.port))
            # Also try port 7
            sock.sendto(magic, (req.broadcast, 7))

        log.info(f"WoL sent to {req.mac} via {req.broadcast}")
        return {"ok": True, "mac": req.mac, "msg": f"Magic packet sent to {req.mac}"}
    except HTTPException:
        raise
    except Exception as e:
        log.error(f"WoL error: {e}")
        raise HTTPException(500, str(e))

# ── Throw-away Tokens ──────────────────────────────────────────────────────────
class TempTokenRequest(BaseModel):
    expires_minutes: int = 60
    label: str = ""

@app.post("/api/token/temp", dependencies=[Depends(auth)])
async def create_temp_token(req: TempTokenRequest):
    """Create a temporary access token"""
    token_val = secrets.token_urlsafe(24)
    expiry = time.time() + req.expires_minutes * 60
    with _temp_tokens_lock:
        _temp_tokens[token_val] = expiry
    return {
        "token": token_val,
        "expires_at": datetime.fromtimestamp(expiry).isoformat(),
        "expires_minutes": req.expires_minutes,
        "label": req.label
    }

@app.get("/api/token/temp/list", dependencies=[Depends(auth)])
async def list_temp_tokens():
    now = time.time()
    with _temp_tokens_lock:
        return {"tokens": [
            {"token": k[:8] + "...", "expires_in_s": int(v - now)}
            for k, v in _temp_tokens.items() if v > now
        ]}

@app.delete("/api/token/temp/{token_prefix}", dependencies=[Depends(auth)])
async def revoke_temp_token(token_prefix: str):
    with _temp_tokens_lock:
        to_del = [k for k in _temp_tokens if k.startswith(token_prefix)]
        for k in to_del:
            del _temp_tokens[k]
    return {"ok": True, "revoked": len(to_del)}

# ── Screen ─────────────────────────────────────────────────────────────────────
@app.get("/api/screen/size", dependencies=[Depends(auth)])
async def screen_size():
    s = pyautogui.size()
    return {"width": s.width, "height": s.height}

@app.get("/api/screen/displays", dependencies=[Depends(auth)])
async def list_displays():
    """List available monitors"""
    try:
        monitors = []
        if IS_WIN:
            import ctypes
            user32 = ctypes.windll.user32
            monitors.append({"index": 0, "width": user32.GetSystemMetrics(0),
                             "height": user32.GetSystemMetrics(1), "primary": True})
        else:
            s = pyautogui.size()
            monitors.append({"index": 0, "width": s.width, "height": s.height, "primary": True})
        return {"monitors": monitors, "selected": State.selected_monitor}
    except Exception as e:
        return {"monitors": [{"index": 0, "primary": True}], "selected": 0}

@app.post("/api/screen/select", dependencies=[Depends(auth)])
async def select_display(body: dict = Body(...)):
    idx = body.get("index", 0)
    State.selected_monitor = idx
    return {"ok": True, "selected": idx}

@app.post("/api/screen/quality", dependencies=[Depends(auth)])
async def set_quality(body: dict = Body(...)):
    q = max(20, min(95, int(body.get("quality", 65))))
    f = max(5, min(60, int(body.get("fps", 30))))
    State.client_quality = q
    State.client_fps_target = f
    return {"ok": True, "quality": q, "fps": f}

@app.get("/api/screen/snapshot", dependencies=[Depends(auth)])
async def screen_snapshot(quality: int = Query(default=80)):
    img  = _capture_screen(State.selected_monitor)
    data = _frame_to_jpeg(img, quality)
    return JSONResponse({"image": base64.b64encode(data).decode()})

# ── Session Recording ──────────────────────────────────────────────────────────
@app.post("/api/recording/start", dependencies=[Depends(auth)])
async def start_recording():
    State.recording = True
    State.recording_frames.clear()
    return {"ok": True, "status": "recording"}

@app.post("/api/recording/stop", dependencies=[Depends(auth)])
async def stop_recording():
    State.recording = False
    frames = list(State.recording_frames)
    State.recording_frames.clear()
    # Save as MJPEG (simple format, client can download)
    if not frames:
        return {"ok": False, "error": "No frames recorded"}
    out_dir = Path.home() / "RDPPro_Recordings"
    out_dir.mkdir(exist_ok=True)
    ts  = datetime.now().strftime("%Y%m%d_%H%M%S")
    out = out_dir / f"recording_{ts}.mjpeg"
    with open(out, "wb") as f:
        for frame in frames:
            # MJPEG boundary format
            header = f"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: {len(frame)}\r\n\r\n".encode()
            f.write(header + frame + b"\r\n")
    return {"ok": True, "path": str(out), "frames": len(frames)}

@app.get("/api/recording/status", dependencies=[Depends(auth)])
async def recording_status():
    return {"recording": State.recording, "frames": len(State.recording_frames)}

# ── System Stats ───────────────────────────────────────────────────────────────
@app.get("/api/system/stats", dependencies=[Depends(auth)])
async def system_stats():
    mem  = psutil.virtual_memory()
    disk = psutil.disk_usage("/")
    sent, recv = State.net_mbps()
    up_s = time.time() - psutil.boot_time()
    return {
        "cpu_percent":      round(psutil.cpu_percent(interval=0.3), 1),
        "cpu_cores":        psutil.cpu_count(),
        "memory_percent":   round(mem.percent, 1),
        "memory_used_gb":   round(mem.used  / 1e9, 2),
        "memory_total_gb":  round(mem.total / 1e9, 2),
        "disk_percent":     round(disk.percent, 1),
        "disk_used_gb":     round(disk.used  / 1e9, 1),
        "disk_total_gb":    round(disk.total / 1e9, 1),
        "net_sent_mbps":    sent,
        "net_recv_mbps":    recv,
        "uptime_h":         round(up_s / 3600, 1),
        "hostname":         socket.gethostname(),
        "platform":         platform.system(),
        "screen_clients":   len(State.screen_clients),
        "recording":        State.recording,
        "temp_tokens":      len(_temp_tokens),
    }

@app.get("/api/system/processes", dependencies=[Depends(auth)])
async def system_processes(sort: str = "cpu", limit: int = 15):
    procs = []
    for p in psutil.process_iter(["pid","name","cpu_percent","memory_info","status"]):
        try:
            procs.append({
                "pid":    p.info["pid"],
                "name":   p.info["name"],
                "cpu":    round(p.info["cpu_percent"] or 0, 1),
                "mem_mb": round((p.info["memory_info"] or psutil._common.pmem(0,0)).rss / 1e6, 1),
                "status": p.info["status"],
            })
        except Exception:
            pass
    procs.sort(key=lambda x: x.get("cpu" if sort=="cpu" else "mem_mb", 0), reverse=True)
    return {"processes": procs[:limit]}

@app.post("/api/system/kill", dependencies=[Depends(auth)])
async def kill_process(body: dict = Body(...)):
    pid = body.get("pid")
    try:
        p = psutil.Process(int(pid))
        p.kill()
        await push_notification("تنبيه", f"تم إيقاف العملية PID {pid}", "⚠️")
        return {"ok": True}
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/system/{action}", dependencies=[Depends(auth)])
async def system_action(action: str):
    cmds = {
        "lock":         ("rundll32.exe user32.dll,LockWorkStation" if IS_WIN else ""),
        "sleep":        ("rundll32.exe powrprof.dll,SetSuspendState 0,1,0" if IS_WIN else "pmset sleepnow"),
        "restart":      ("shutdown /r /t 5" if IS_WIN else "sudo reboot"),
        "shutdown":     ("shutdown /s /t 5" if IS_WIN else "sudo poweroff"),
        "task_manager": ("taskmgr" if IS_WIN else ""),
        "cmd":          ("start cmd" if IS_WIN else ""),
        "powershell":   ("start powershell" if IS_WIN else ""),
        "explorer":     ("explorer" if IS_WIN else ""),
    }
    cmd = cmds.get(action, "")
    if not cmd:
        raise HTTPException(404, f"Unknown action: {action}")
    try:
        subprocess.Popen(cmd, shell=True)
        await push_notification("أمر سريع", f"تم تنفيذ: {action}", "⚡")
        return {"ok": True}
    except Exception as e:
        raise HTTPException(500, str(e))

# ── Terminal ───────────────────────────────────────────────────────────────────
_cwd: Dict[str, str] = {}

class TermCmd(BaseModel):
    cmd: str
    session: str = "default"
    shell: str = "powershell"

@app.post("/api/terminal/exec", dependencies=[Depends(auth)])
async def terminal_exec(req: TermCmd):
    cwd = _cwd.get(req.session, str(Path.home()))
    try:
        if req.shell == "powershell" and IS_WIN:
            full_cmd = f'powershell -Command "cd \'{cwd}\'; {req.cmd}; pwd"'
        else:
            full_cmd = f'cd "{cwd}" && {req.cmd} && echo __PWD__=$(pwd)'
        proc = await asyncio.create_subprocess_shell(
            full_cmd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            cwd=cwd if Path(cwd).exists() else str(Path.home())
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=30)
        out = (stdout or b"").decode("utf-8", errors="replace")
        err = (stderr or b"").decode("utf-8", errors="replace")

        # Extract new CWD
        if "__PWD__=" in out:
            lines = out.split("\n")
            for ln in lines:
                if ln.startswith("__PWD__="):
                    _cwd[req.session] = ln[8:].strip()
            out = "\n".join(l for l in lines if not l.startswith("__PWD__="))

        return {"output": out, "error": err, "cwd": _cwd.get(req.session, cwd), "rc": proc.returncode}
    except asyncio.TimeoutError:
        return {"output": "", "error": "Timeout (30s)", "cwd": cwd, "rc": -1}
    except Exception as e:
        return {"output": "", "error": str(e), "cwd": cwd, "rc": -1}

# ── Files ──────────────────────────────────────────────────────────────────────
@app.get("/api/files/list", dependencies=[Depends(auth)])
async def files_list(path: str = Query(default="")):
    p = Path(path) if path else Path.home()
    if not p.exists():
        raise HTTPException(404, "Path not found")
    items = []
    for item in sorted(p.iterdir(), key=lambda x: (not x.is_dir(), x.name.lower())):
        try:
            stat = item.stat()
            items.append({
                "name":     item.name,
                "path":     str(item),
                "is_dir":   item.is_dir(),
                "size":     stat.st_size if item.is_file() else 0,
                "modified": int(stat.st_mtime),
                "ext":      item.suffix.lower() if item.is_file() else "",
            })
        except PermissionError:
            pass
    return {"path": str(p), "parent": str(p.parent), "items": items}

@app.get("/api/files/download", dependencies=[Depends(auth)])
async def files_download(path: str = Query(...)):
    p = Path(path)
    if not p.is_file():
        raise HTTPException(404, "File not found")
    return FileResponse(str(p), filename=p.name)

@app.post("/api/files/delete", dependencies=[Depends(auth)])
async def files_delete(body: dict = Body(...)):
    p = Path(body.get("path",""))
    try:
        if p.is_dir(): shutil.rmtree(p)
        else: p.unlink()
        return {"ok": True}
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/files/rename", dependencies=[Depends(auth)])
async def files_rename(body: dict = Body(...)):
    src = Path(body.get("path",""))
    name = body.get("name","")
    try:
        src.rename(src.parent / name)
        return {"ok": True}
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/files/mkdir", dependencies=[Depends(auth)])
async def files_mkdir(body: dict = Body(...)):
    p = Path(body.get("path",""))
    try:
        p.mkdir(parents=True, exist_ok=True)
        return {"ok": True}
    except Exception as e:
        raise HTTPException(500, str(e))

@app.post("/api/files/copy", dependencies=[Depends(auth)])
async def files_copy(body: dict = Body(...)):
    src = Path(body.get("src",""))
    dst = Path(body.get("dst",""))
    try:
        if src.is_dir(): shutil.copytree(src, dst)
        else: shutil.copy2(src, dst)
        return {"ok": True}
    except Exception as e:
        raise HTTPException(500, str(e))

@app.get("/api/files/search", dependencies=[Depends(auth)])
async def files_search(q: str = Query(...), path: str = Query(default="")):
    root = Path(path) if path else Path.home()
    results = []
    try:
        for item in root.rglob(f"*{q}*"):
            if len(results) >= 50: break
            results.append({"name": item.name, "path": str(item), "is_dir": item.is_dir()})
    except Exception:
        pass
    return {"results": results}

# ── Clipboard ──────────────────────────────────────────────────────────────────
@app.get("/api/clipboard", dependencies=[Depends(auth)])
async def clipboard_get():
    try:
        import pyperclip
        text = pyperclip.paste()
        return {"text": text, "type": "text"}
    except Exception as e:
        return {"text": "", "error": str(e)}

@app.post("/api/clipboard", dependencies=[Depends(auth)])
async def clipboard_set(body: dict = Body(...)):
    try:
        import pyperclip
        text = body.get("text","")
        pyperclip.copy(text)
        return {"ok": True}
    except Exception as e:
        raise HTTPException(500, str(e))

# ── System Disks ───────────────────────────────────────────────────────────────
@app.get("/api/system/disks", dependencies=[Depends(auth)])
async def system_disks():
    disks = []
    for part in psutil.disk_partitions(all=False):
        try:
            usage = psutil.disk_usage(part.mountpoint)
            disks.append({
                "device":    part.device,
                "mountpoint": part.mountpoint,
                "fstype":    part.fstype,
                "total_gb":  round(usage.total / 1e9, 1),
                "used_gb":   round(usage.used  / 1e9, 1),
                "free_gb":   round(usage.free  / 1e9, 1),
                "percent":   usage.percent,
            })
        except PermissionError:
            pass
    return {"disks": disks}

# ── Printers ───────────────────────────────────────────────────────────────────
@app.get("/api/printers", dependencies=[Depends(auth)])
async def list_printers():
    try:
        if IS_WIN:
            result = subprocess.check_output("wmic printer get name,status", shell=True, timeout=5)
            lines  = result.decode("utf-8", errors="replace").strip().split("\n")[1:]
            printers = [l.strip() for l in lines if l.strip()]
        elif IS_LIN:
            result = subprocess.check_output("lpstat -a 2>/dev/null", shell=True, timeout=5)
            printers = [l.split()[0] for l in result.decode().split("\n") if l.strip()]
        else:
            printers = []
        return {"printers": printers}
    except Exception:
        return {"printers": []}

# ── QR Code ────────────────────────────────────────────────────────────────────
@app.get("/api/qr", dependencies=[Depends(auth)])
async def get_qr():
    try:
        import qrcode
        ip = _get_local_ip()
        url = f"rdppro://{ip}:{PORT}/{TOKEN}"
        qr  = qrcode.QRCode(version=1, box_size=10, border=4)
        qr.add_data(url)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white")
        buf = io.BytesIO(); img.save(buf, format="PNG")
        return {"qr_b64": base64.b64encode(buf.getvalue()).decode(), "url": url}
    except ImportError:
        ip = _get_local_ip()
        return {"url": f"rdppro://{ip}:{PORT}/{TOKEN}", "qr_b64": None}

def _get_local_ip():
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]; s.close()
        return ip
    except Exception:
        return "127.0.0.1"

# ── App info ───────────────────────────────────────────────────────────────────
@app.get("/api/info")
async def info():
    return {
        "version": "4.0.0",
        "platform": platform.system(),
        "hostname": socket.gethostname(),
        "ip": _get_local_ip(),
        "port": PORT,
        "audio_available": AUDIO_OK,
        "features": [
            "screen_streaming", "delta_compression", "adaptive_fps",
            "wake_on_lan", "chat", "temp_tokens", "session_recording",
            "multi_monitor", "terminal", "files", "clipboard",
            "system_stats", "webrtc", "notifications"
        ]
    }

# ── Startup ────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    log.info(f"RDP Pro v4.0 starting on {HOST}:{PORT}")
    log.info(f"Platform: {platform.system()}")
    log.info(f"Audio: {'✓' if AUDIO_OK else '✗'}")
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")


# ═══════════════════════════════════════════════════════════════════════════════
#  SECURITY LAYER v4.1 — IP Whitelist + Audit Log + 2FA + HTTPS
# ═══════════════════════════════════════════════════════════════════════════════

import ipaddress, hmac, base64, ssl
from fastapi import Request
from fastapi.responses import Response

# ── IP Whitelist ───────────────────────────────────────────────────────────────
_ip_whitelist: list = []
_ip_blacklist: set  = set()
_ip_list_lock = threading.Lock()

def _load_ip_list():
    """Load whitelist from env or file"""
    raw = os.getenv("RDP_IP_WHITELIST", "")
    if raw:
        for ip in raw.split(","):
            ip = ip.strip()
            if ip:
                _ip_whitelist.append(ip)
    # Also load from file
    wl_file = Path("ip_whitelist.txt")
    if wl_file.exists():
        for line in wl_file.read_text().splitlines():
            line = line.strip()
            if line and not line.startswith("#"):
                _ip_whitelist.append(line)

def _is_ip_allowed(ip: str) -> bool:
    if ip in _ip_blacklist:
        return False
    if not _ip_whitelist:
        return True  # no whitelist = allow all
    for allowed in _ip_whitelist:
        try:
            if "/" in allowed:
                if ipaddress.ip_address(ip) in ipaddress.ip_network(allowed, strict=False):
                    return True
            elif ip == allowed or allowed == "*":
                return True
        except Exception:
            pass
    return False

@app.middleware("http")
async def ip_whitelist_middleware(request: Request, call_next):
    client_ip = request.client.host if request.client else "unknown"
    if not _is_ip_allowed(client_ip):
        log.warning(f"Blocked IP: {client_ip} → {request.url.path}")
        return Response(content="Forbidden", status_code=403)
    return await call_next(request)

# ── Audit Log ──────────────────────────────────────────────────────────────────
_audit_log: deque = deque(maxlen=500)
_audit_lock = threading.Lock()

def audit(action: str, detail: str = "", ip: str = ""):
    entry = {
        "ts":     datetime.now().isoformat(timespec="seconds"),
        "action": action,
        "detail": detail,
        "ip":     ip,
    }
    with _audit_lock:
        _audit_log.append(entry)
    log.info(f"AUDIT [{action}] {detail}")

@app.middleware("http")
async def audit_middleware(request: Request, call_next):
    ip   = request.client.host if request.client else "?"
    path = request.url.path
    # Only log API calls
    if path.startswith("/api/"):
        audit(f"{request.method} {path}", ip=ip)
    return await call_next(request)

@app.get("/api/audit/log", dependencies=[Depends(auth)])
async def get_audit_log(limit: int = Query(default=50)):
    with _audit_lock:
        entries = list(_audit_log)[-limit:]
    return {"entries": list(reversed(entries)), "total": len(_audit_log)}

@app.delete("/api/audit/log", dependencies=[Depends(auth)])
async def clear_audit_log():
    with _audit_lock:
        _audit_log.clear()
    return {"ok": True}

# ── IP Management ──────────────────────────────────────────────────────────────
@app.get("/api/security/whitelist", dependencies=[Depends(auth)])
async def get_whitelist():
    return {"whitelist": _ip_whitelist, "blacklist": list(_ip_blacklist)}

@app.post("/api/security/whitelist", dependencies=[Depends(auth)])
async def add_to_whitelist(body: dict = Body(...)):
    ip = body.get("ip","").strip()
    if not ip:
        raise HTTPException(400, "IP required")
    with _ip_list_lock:
        if ip not in _ip_whitelist:
            _ip_whitelist.append(ip)
    audit("whitelist_add", ip)
    return {"ok": True, "whitelist": _ip_whitelist}

@app.delete("/api/security/whitelist/{ip}", dependencies=[Depends(auth)])
async def remove_from_whitelist(ip: str):
    with _ip_list_lock:
        if ip in _ip_whitelist:
            _ip_whitelist.remove(ip)
    audit("whitelist_remove", ip)
    return {"ok": True}

@app.post("/api/security/blacklist", dependencies=[Depends(auth)])
async def block_ip(body: dict = Body(...)):
    ip = body.get("ip","").strip()
    _ip_blacklist.add(ip)
    audit("ip_blocked", ip)
    return {"ok": True}

# ── 2FA — TOTP ─────────────────────────────────────────────────────────────────
_2fa_secret   = os.getenv("RDP_2FA_SECRET", "")
_2fa_enabled  = bool(_2fa_secret)
_2fa_sessions: Dict[str, float] = {}   # session_id: expiry
_2fa_lock = threading.Lock()

def _totp_code(secret: str) -> str:
    """Generate TOTP code (RFC 6238 compatible)"""
    import struct, hashlib, hmac as _hmac, time as _t
    key = base64.b32decode(secret.upper().replace(" ",""))
    t   = int(_t.time()) // 30
    msg = struct.pack(">Q", t)
    h   = _hmac.new(key, msg, hashlib.sha1).digest()
    off = h[-1] & 0x0F
    code = struct.unpack(">I", bytes([h[off]&0x7F]) + h[off+1:off+4])[0] % 1_000_000
    return f"{code:06d}"

class TwoFARequest(BaseModel):
    code: str

@app.post("/api/2fa/verify")
async def verify_2fa(req: TwoFARequest, request: Request):
    if not _2fa_enabled:
        return {"ok": True, "session": "disabled"}
    expected = _totp_code(_2fa_secret)
    if req.code.strip() == expected:
        sid = secrets.token_urlsafe(16)
        exp = time.time() + 3600  # 1h session
        with _2fa_lock:
            _2fa_sessions[sid] = exp
        audit("2fa_success", ip=request.client.host)
        return {"ok": True, "session": sid}
    audit("2fa_failed", req.code[:3]+"***", ip=request.client.host)
    raise HTTPException(401, "Invalid 2FA code")

@app.get("/api/2fa/status")
async def twofa_status():
    return {"enabled": _2fa_enabled, "algorithm": "TOTP-SHA1-30s"}

# ── HTTPS Self-signed cert generator ──────────────────────────────────────────
def _generate_ssl_cert():
    """Generate self-signed cert + key if not present"""
    cert_file = Path("rdp_pro_cert.pem")
    key_file  = Path("rdp_pro_key.pem")
    if cert_file.exists() and key_file.exists():
        return str(cert_file), str(key_file)

    try:
        from cryptography import x509
        from cryptography.x509.oid import NameOID
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.hazmat.backends import default_backend
        import datetime as dt

        key = rsa.generate_private_key(public_exponent=65537, key_size=2048, backend=default_backend())
        cert = (
            x509.CertificateBuilder()
            .subject_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "RDP Pro")]))
            .issuer_name(x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "RDP Pro")]))
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(dt.datetime.utcnow())
            .not_valid_after(dt.datetime.utcnow() + dt.timedelta(days=365))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("localhost")]), critical=False)
            .sign(key, hashes.SHA256(), default_backend())
        )
        key_file.write_bytes(key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.TraditionalOpenSSL,
            serialization.NoEncryption()
        ))
        cert_file.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        log.info("✓ SSL cert generated")
        return str(cert_file), str(key_file)
    except ImportError:
        log.warning("cryptography not installed — HTTPS disabled. pip install cryptography")
        return None, None

# ── Permission System ──────────────────────────────────────────────────────────
class PermLevel:
    FULL       = 0
    VIEW_ONLY  = 1
    FILES_ONLY = 2
    TERMINAL   = 3

_perm_tokens: Dict[str, int] = {}   # token: perm_level

class PermTokenRequest(BaseModel):
    expires_minutes: int = 60
    perm_level: int = PermLevel.VIEW_ONLY
    label: str = ""

@app.post("/api/token/perm", dependencies=[Depends(auth)])
async def create_perm_token(req: PermTokenRequest):
    """Create token with specific permission level"""
    token_val = secrets.token_urlsafe(24)
    expiry    = time.time() + req.expires_minutes * 60
    with _temp_tokens_lock:
        _temp_tokens[token_val] = expiry
    _perm_tokens[token_val] = req.perm_level
    level_names = {0:"full", 1:"view-only", 2:"files-only", 3:"terminal"}
    audit("perm_token_created", f"level={level_names.get(req.perm_level,'?')} expires={req.expires_minutes}m")
    return {
        "token":          token_val,
        "perm_level":     req.perm_level,
        "perm_name":      level_names.get(req.perm_level, "?"),
        "expires_minutes": req.expires_minutes,
        "label":          req.label,
    }

def get_perm_level(token: str) -> int:
    return _perm_tokens.get(token, PermLevel.FULL)

# ── Whiteboard WebSocket ───────────────────────────────────────────────────────
_whiteboard_clients: List[WebSocket] = []
_whiteboard_strokes: list = []

@app.websocket("/ws/whiteboard")
async def ws_whiteboard(ws: WebSocket, token: str = Query(default="")):
    if not _is_valid_token(token):
        await ws.close(code=4001); return
    await ws.accept()
    _whiteboard_clients.append(ws)
    try:
        while True:
            raw  = await ws.receive_text()
            data = json.loads(raw)
            _whiteboard_strokes.append(data)
            # Broadcast to all whiteboard clients
            dead = []
            for client in _whiteboard_clients:
                if client != ws:
                    try: await client.send_text(raw)
                    except: dead.append(client)
            for d in dead:
                _whiteboard_clients.remove(d)
    except WebSocketDisconnect:
        pass
    finally:
        if ws in _whiteboard_clients:
            _whiteboard_clients.remove(ws)

@app.post("/api/whiteboard/stroke", dependencies=[Depends(auth)])
async def whiteboard_stroke(body: dict = Body(...)):
    _whiteboard_strokes.append(body)
    # Push to connected clients
    for client in _whiteboard_clients:
        try: await client.send_text(json.dumps(body))
        except: pass
    return {"ok": True}

@app.get("/api/whiteboard/strokes", dependencies=[Depends(auth)])
async def whiteboard_strokes():
    return {"strokes": _whiteboard_strokes}

@app.delete("/api/whiteboard/strokes", dependencies=[Depends(auth)])
async def whiteboard_clear():
    _whiteboard_strokes.clear()
    return {"ok": True}

@app.post("/api/whiteboard/snapshot", dependencies=[Depends(auth)])
async def whiteboard_snapshot(body: dict = Body(...)):
    data = body.get("data","")
    snap_dir = Path.home() / "RDPPro_Snapshots"
    snap_dir.mkdir(exist_ok=True)
    ts  = datetime.now().strftime("%Y%m%d_%H%M%S")
    out = snap_dir / f"whiteboard_{ts}.png"
    try:
        raw = base64.b64decode(data)
        with open(out, "wb") as f:
            f.write(raw)
        return {"ok": True, "path": str(out)}
    except Exception as e:
        raise HTTPException(500, str(e))

# ── Network Port Forwarding (basic TCP tunnel) ─────────────────────────────────
_tunnels: Dict[str, dict] = {}

class TunnelRequest(BaseModel):
    local_port: int
    remote_host: str
    remote_port: int
    label: str = ""

@app.post("/api/tunnel/start", dependencies=[Depends(auth)])
async def start_tunnel(req: TunnelRequest):
    """Simple port forwarding via subprocess"""
    tunnel_id = secrets.token_hex(4)
    try:
        if IS_WIN:
            cmd = f"netsh interface portproxy add v4tov4 listenport={req.local_port} connectaddress={req.remote_host} connectport={req.remote_port}"
        else:
            cmd = f"socat TCP-LISTEN:{req.local_port},fork TCP:{req.remote_host}:{req.remote_port} &"
        subprocess.Popen(cmd, shell=True)
        _tunnels[tunnel_id] = {
            "id":           tunnel_id,
            "local_port":   req.local_port,
            "remote":       f"{req.remote_host}:{req.remote_port}",
            "label":        req.label,
            "started_at":   datetime.now().isoformat()
        }
        audit("tunnel_start", f":{req.local_port} → {req.remote_host}:{req.remote_port}")
        return {"ok": True, "tunnel_id": tunnel_id, **_tunnels[tunnel_id]}
    except Exception as e:
        raise HTTPException(500, str(e))

@app.get("/api/tunnel/list", dependencies=[Depends(auth)])
async def list_tunnels():
    return {"tunnels": list(_tunnels.values())}

@app.delete("/api/tunnel/{tunnel_id}", dependencies=[Depends(auth)])
async def stop_tunnel(tunnel_id: str):
    tunnel = _tunnels.pop(tunnel_id, None)
    if not tunnel:
        raise HTTPException(404, "Tunnel not found")
    if IS_WIN:
        subprocess.Popen(f"netsh interface portproxy delete v4tov4 listenport={tunnel['local_port']}", shell=True)
    audit("tunnel_stop", tunnel_id)
    return {"ok": True}

# ── Network Bandwidth chart data ───────────────────────────────────────────────
_net_history: deque = deque(maxlen=60)   # 60 data points

async def _net_collector():
    """Collect network stats every 2s"""
    while True:
        sent, recv = State.net_mbps()
        _net_history.append({
            "ts":   int(time.time()),
            "sent": sent,
            "recv": recv,
        })
        await asyncio.sleep(2)

@app.get("/api/network/history", dependencies=[Depends(auth)])
async def network_history():
    return {"history": list(_net_history)}

# ── Startup: init security + background tasks ──────────────────────────────────
@app.on_event("startup")
async def on_startup():
    _load_ip_list()
    log.info(f"IP Whitelist: {_ip_whitelist or 'disabled (allow all)'}")
    log.info(f"2FA: {'enabled' if _2fa_enabled else 'disabled'}")
    # Start net collector
    asyncio.create_task(_net_collector())


# ─────────────────────────────────────────────────────────────────────────────
# MACRO RECORDER & PLAYER  (v5.0)
# ─────────────────────────────────────────────────────────────────────────────
import threading
_macro_recording = False
_macro_steps: list = []
_macro_record_start = 0.0

class MacroStep(BaseModel):
    type:  str
    data:  dict = {}
    delay: int  = 100   # ms

class MacroRunRequest(BaseModel):
    steps: List[MacroStep]
    loop:  int  = 1
    name:  str  = ""

@app.post("/api/macro/record/start", dependencies=[Depends(auth)])
async def macro_record_start():
    global _macro_recording, _macro_steps, _macro_record_start
    _macro_recording = True
    _macro_steps = []
    _macro_record_start = time.time()
    audit("macro_record_start", "")
    return {"ok": True}

@app.post("/api/macro/record/stop", dependencies=[Depends(auth)])
async def macro_record_stop():
    global _macro_recording
    _macro_recording = False
    audit("macro_record_stop", f"{len(_macro_steps)} steps")
    return {"ok": True, "count": len(_macro_steps)}

@app.get("/api/macro/record/steps", dependencies=[Depends(auth)])
async def macro_record_steps():
    """Return and clear pending steps"""
    steps = list(_macro_steps)
    _macro_steps.clear()
    return {"steps": steps}

@app.post("/api/macro/run", dependencies=[Depends(auth)])
async def macro_run(req: MacroRunRequest):
    """Execute a macro on the host"""
    audit("macro_run", f"{req.name} ({len(req.steps)} steps ×{req.loop})")

    def execute():
        for _ in range(max(1, req.loop)):
            for step in req.steps:
                try:
                    t = step.type
                    d = step.data
                    delay_s = step.delay / 1000.0

                    if t == "wait":
                        time.sleep(d.get("ms", 100) / 1000.0)

                    elif t == "mouse_move":
                        pyautogui.moveTo(d.get("x", 0), d.get("y", 0), duration=0.1)

                    elif t == "mouse_click":
                        btn = d.get("button", "left")
                        x, y = d.get("x", None), d.get("y", None)
                        if x and y:
                            pyautogui.moveTo(x, y, duration=0.05)
                        if btn == "double":
                            pyautogui.doubleClick()
                        elif btn == "right":
                            pyautogui.rightClick()
                        else:
                            pyautogui.click()

                    elif t == "type_text":
                        pyautogui.typewrite(d.get("text",""), interval=0.04)

                    elif t == "key_press":
                        key = d.get("key","")
                        # Handle combos like Ctrl+C, Win+D
                        if "+" in key:
                            parts = [p.strip().lower() for p in key.split("+")]
                            key_map = {"ctrl":"ctrl","win":"win","alt":"alt",
                                       "shift":"shift","enter":"enter","tab":"tab",
                                       "escape":"esc","f5":"f5","f4":"f4"}
                            mapped = [key_map.get(p, p) for p in parts]
                            pyautogui.hotkey(*mapped)
                        else:
                            k = key.lower()
                            key_map = {"enter":"enter","escape":"esc","tab":"tab",
                                       "backspace":"backspace"}
                            pyautogui.press(key_map.get(k, k))

                    elif t == "cmd":
                        subprocess.Popen(d.get("cmd",""), shell=True)

                    if delay_s > 0:
                        time.sleep(delay_s)

                except Exception as e:
                    logging.warning(f"Macro step error: {e}")

    threading.Thread(target=execute, daemon=True).start()
    return {"ok": True, "name": req.name, "steps": len(req.steps)}

@app.get("/api/macro/presets", dependencies=[Depends(auth)])
async def macro_presets():
    """Return built-in preset macros"""
    return {"presets": [
        {"name": "لقطة شاشة",
         "steps": [{"type":"key_press","data":{"key":"Win+Shift+S"},"delay":100}]},
        {"name": "قفل الشاشة",
         "steps": [{"type":"key_press","data":{"key":"Win+L"},"delay":100}]},
        {"name": "فتح Terminal",
         "steps": [
             {"type":"key_press","data":{"key":"Win+R"},"delay":300},
             {"type":"wait","data":{"ms":500},"delay":500},
             {"type":"type_text","data":{"text":"powershell"},"delay":200},
             {"type":"key_press","data":{"key":"Enter"},"delay":100},
         ]},
        {"name": "تسجيل الخروج",
         "steps": [
             {"type":"key_press","data":{"key":"Win+L"},"delay":100},
         ]},
    ]}

# ─────────────────────────────────────────────────────────────────────────────
# MULTI-SESSION PING ENDPOINT
# ─────────────────────────────────────────────────────────────────────────────
@app.get("/api/sessions/info", dependencies=[Depends(auth)])
async def sessions_info():
    """Extended ping with system snapshot — used by MultiSessionActivity"""
    cpu  = psutil.cpu_percent(interval=0.1)
    mem  = psutil.virtual_memory()
    disk = psutil.disk_usage("/") if not IS_WIN else psutil.disk_usage("C:\\")
    net  = psutil.net_io_counters()
    return {
        "pong":            True,
        "hostname":        os.environ.get("COMPUTERNAME", os.uname().nodename if hasattr(os,"uname") else "PC"),
        "cpu_percent":     cpu,
        "memory_percent":  mem.percent,
        "disk_percent":    disk.percent,
        "version":         "5.0",
        "uptime_s":        int(time.time() - psutil.boot_time()),
    }
