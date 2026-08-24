import os
import sys
import json
import time
import asyncio
import threading
import webbrowser
import tempfile
import ctypes
from datetime import datetime, timezone
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse
import urllib.request

# 强制控制台编码为 UTF-8，防止 GBK 编码报错
sys.stdout.reconfigure(encoding='utf-8')

import edge_tts
from PIL import Image, ImageDraw
import pystray

# 引入 Windows CoreAudio 音频管理库 (解决播放器不响应热键的问题)
try:
    from pycaw.pycaw import AudioUtilities, ISimpleAudioVolume
    PYCAW_AVAILABLE = True
except Exception:
    PYCAW_AVAILABLE = False

# 常量定义
GIST_URL = "https://gist.githubusercontent.com/raw/6582c66b24bad75381f70abdae62e81b/cards_data.json"
CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "config.json")
WEB_PORT = 5050
WEB_URL = f"http://localhost:{WEB_PORT}/"

# 开放 Neural AI 音色映射 (音色截然不同，完全不走微软 SAPI 本地合成)
VOICE_MODELS = [
    {"name": "zh-CN-XiaoxiaoNeural", "title": "🌟 晓晓 (经典甜美 AI 女声)"},
    {"name": "zh-CN-YunxiNeural", "title": "👦 云希 (广播级深情 AI 男声 - 音色明显不同)"},
    {"name": "zh-CN-YunjianNeural", "title": "📢 云健 (激情体育解说 AI 男声 - 超大音量)"},
    {"name": "zh-CN-XiaoyiNeural", "title": "🌸 晓伊 (亲切新闻播报 AI 女声)"},
    {"name": "zh-CN-YunyangNeural", "title": "🎙️ 云扬 (严肃专业新闻 AI 男声)"}
]

# 默认配置
DEFAULT_CONFIG = {
    "server_url": GIST_URL,
    "poll_interval_seconds": 60,
    "pause_system_music": True,
    "enable_voice_alert": True,
    "expired_template": "{卡片列表}即将超时",
    "selected_voice_name": "zh-CN-XiaoxiaoNeural",
    "volume_boost": 2.5
}

class AppState:
    def __init__(self):
        self.config = self.load_config()
        self.latest_cards = []
        self.alerted_card_ids = set()
        self.is_speaking = False
        self.lock = threading.Lock()

    def load_config(self):
        if os.path.exists(CONFIG_FILE):
            try:
                with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                    cfg = json.load(f)
                    merged = DEFAULT_CONFIG.copy()
                    merged.update(cfg)
                    return merged
            except Exception as e:
                print("Error loading config:", e)
        return DEFAULT_CONFIG.copy()

    def save_config(self, new_cfg):
        with self.lock:
            self.config.update(new_cfg)
            try:
                with open(CONFIG_FILE, "w", encoding="utf-8") as f:
                    json.dump(self.config, f, ensure_ascii=False, indent=2)
            except Exception as e:
                print("Error saving config:", e)

state = AppState()

# 双重保障 1: Windows CoreAudio 系统级进程静音/恢复 (支持网易云、QQ音乐、酷狗、Chrome、Edge等所有播放器)
def mute_other_audio_sessions(mute=True):
    if not PYCAW_AVAILABLE:
        return
    try:
        ctypes.windll.ole32.CoInitialize(None)
        current_pid = os.getpid()
        sessions = AudioUtilities.GetAllSessions()
        for session in sessions:
            if session.Process and session.ProcessId != current_pid:
                volume = session._ctl.QueryInterface(ISimpleAudioVolume)
                volume.SetMute(1 if mute else 0, None)
    except Exception as e:
        print("CoreAudio Mute Error:", e)

# 双重保障 2: Windows 全局媒体播放/暂停热键发送
def toggle_media_play_pause():
    try:
        ctypes.windll.user32.keybd_event(0xB3, 0, 0, 0)
        ctypes.windll.user32.keybd_event(0xB3, 0, 2, 0)
    except Exception:
        pass

def play_mp3_native(file_path):
    try:
        alias = f"tts_{int(time.time()*1000)}"
        mci = ctypes.windll.winmm.mciSendStringW
        mci(f'open "{file_path}" type mpegvideo alias {alias}', None, 0, 0)
        mci(f'play {alias} wait', None, 0, 0)
        mci(f'close {alias}', None, 0, 0)
    except Exception as e:
        print("Play MP3 error:", e)

def generate_chime_wav(filepath):
    """合成清脆悦耳的双音手机提示音 (叮咚 E5 -> A5)"""
    try:
        sample_rate = 44100
        tones = [(659.25, 0.18, 0.45), (880.0, 0.38, 0.55)]
        frames = []
        for freq, duration, amp in tones:
            num_samples = int(sample_rate * duration)
            for i in range(num_samples):
                t = i / sample_rate
                envelope = math.exp(-3.5 * t / duration)
                val = amp * envelope * math.sin(2.0 * math.pi * freq * t)
                sample = int(val * 32767.0)
                sample = max(-32768, min(32767, sample))
                frames.append(struct.pack('<h', sample))
        with wave.open(filepath, 'w') as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(sample_rate)
            wf.writeframes(b''.join(frames))
    except Exception as e:
        print("Generate chime error:", e)

def play_chime_sound():
    try:
        chime_wav = os.path.join(tempfile.gettempdir(), "nfc_chime.wav")
        if not os.path.exists(chime_wav):
            generate_chime_wav(chime_wav)
        winsound.PlaySound(chime_wav, winsound.SND_FILENAME)
    except Exception as e:
        print("Play chime error:", e)

# Edge TTS 语音合成与大音量支持
async def _async_speak(text, voice_name, volume_boost, pause_music, speech_rate="-20%"):
    if state.is_speaking:
        return
    state.is_speaking = True
    try:
        vol_percent = int((volume_boost - 1.0) * 100)
        vol_str = f"+{vol_percent}%" if vol_percent >= 0 else f"{vol_percent}%"
        rate_str = speech_rate if speech_rate else "-20%"

        print(f"[TTS] Python Edge-TTS 正在合成 [{voice_name}] (语速: {rate_str}, 音量: {vol_str}): \"{text}\"")

        temp_mp3 = os.path.join(tempfile.gettempdir(), f"edge_tts_{int(time.time()*1000)}.mp3")
        communicate = edge_tts.Communicate(text, voice_name, volume=vol_str, rate=rate_str)
        await communicate.save(temp_mp3)

        # 1. 语音文件生成完毕，播放前双重保障（发送媒体暂停键 + CoreAudio 音频通道强效静音其他软件）
        if pause_music:
            print("[Music] ⏸️ 准备开始播报，双重强效暂停/静音系统背景音乐...")
            toggle_media_play_pause()
            mute_other_audio_sessions(True)
            time.sleep(0.15)

        # 2. 播放手机提示音 (叮咚)
        print("[TTS] 🔔 播放新消息提示音...")
        play_chime_sound()

        # 3. 贴心停顿 0.7 秒，让人缓过来
        time.sleep(0.7)

        # 4. 原生大音量播放平稳语音
        play_mp3_native(temp_mp3)

        # 5. 语音播报完毕，双重恢复系统背景音乐与音频通道
        if pause_music:
            time.sleep(0.2)
            print("[Music] ▶️ 语音播报完毕，双重恢复系统背景音乐...")
            mute_other_audio_sessions(False)
            toggle_media_play_pause()

        try:
            os.remove(temp_mp3)
        except Exception:
            pass
    except Exception as e:
        print("TTS Error:", e)
    finally:
        state.is_speaking = False

def speak_text(text, voice_name=None, volume_boost=None, pause_music=None, speech_rate=None):
    if voice_name is None:
        voice_name = state.config.get("selected_voice_name", "zh-CN-XiaoxiaoNeural")
    if volume_boost is None:
        volume_boost = state.config.get("volume_boost", 2.5)
    if pause_music is None:
        pause_music = state.config.get("pause_system_music", True)
    if speech_rate is None:
        speech_rate = state.config.get("speech_rate", "-20%")

    def runner():
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        loop.run_until_complete(_async_speak(text, voice_name, volume_boost, pause_music, speech_rate))
        loop.close()

    threading.Thread(target=runner, daemon=True).start()

# 中文名称格式化函数
def format_card_list_chinese(raw_names):
    if not raw_names:
        return ""
    formatted = []
    for raw in raw_names:
        clean = raw.strip()
        if clean.isdigit():
            clean = str(int(clean))
        if not clean.endswith("卡片"):
            clean += "卡片" if clean.endswith("号") else "号卡片"
        formatted.append(clean)

    if len(formatted) == 1:
        return formatted[0]
    if len(formatted) == 2:
        return f"{formatted[0]}和{formatted[1]}"
    return "、".join(formatted[:-1]) + "和" + formatted[-1]

# 核心倒计时轮询服务
def card_monitor_loop():
    while True:
        try:
            url = state.config.get("server_url", GIST_URL)
            req_url = url + ("&" if "?" in url else "?") + f"t={int(time.time()*1000)}"
            req = urllib.request.Request(req_url, headers={"User-Agent": "Mozilla/5.0"})
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))

            now_utc = datetime.now(timezone.utc)
            computed_cards = []
            new_expired_cards = []

            for c in data:
                saved_remaining = c.get("savedRemainingSeconds", 0)
                status = c.get("status", 0)
                timer_mode = c.get("timerMode", 0)
                start_str = c.get("startTimeUtc")
                remaining = saved_remaining
                elapsed = saved_remaining
                is_overdue = False

                if timer_mode == 1:
                    # 正计时 (先玩后付)
                    if status == 1 and start_str:
                        try:
                            start_time = datetime.fromisoformat(start_str.replace("Z", "+00:00"))
                            current_segment = max(0, (now_utc - start_time).total_seconds())
                            elapsed = saved_remaining + current_segment
                        except Exception:
                            pass
                    remaining = elapsed
                    is_overdue = False
                    is_time_up = False
                else:
                    # 倒计时
                    if status == 1 and start_str:
                        try:
                            start_time = datetime.fromisoformat(start_str.replace("Z", "+00:00"))
                            elapsed_sec = (now_utc - start_time).total_seconds()
                            remaining = saved_remaining - elapsed_sec
                            if remaining <= 0:
                                status = 3
                        except Exception:
                            pass

                    is_overdue = remaining < 0 and (status == 1 or status == 3)
                    is_time_up = is_overdue or status == 3 or (status == 1 and remaining <= 60)

                c_copy = dict(c)
                c_copy["remainingSeconds"] = remaining
                c_copy["elapsedSeconds"] = elapsed
                c_copy["isOverdue"] = is_overdue
                c_copy["status"] = status
                computed_cards.append(c_copy)

                card_id = c.get("cardId", "")
                session_key = f"{card_id}_{start_str}" if start_str else card_id

                if is_time_up:
                    if session_key not in state.alerted_card_ids and card_id not in state.alerted_card_ids:
                        new_expired_cards.append(c_copy)
                else:
                    state.alerted_card_ids.discard(card_id)
                    state.alerted_card_ids.discard(session_key)

            state.latest_cards = computed_cards

            if new_expired_cards and state.config.get("enable_voice_alert", True):
                for c in new_expired_cards:
                    cid = c.get("cardId", "")
                    st = c.get("startTimeUtc", "")
                    skey = f"{cid}_{st}" if st else cid
                    state.alerted_card_ids.add(skey)

                names = [c.get("name") or c.get("cardId", "") for c in new_expired_cards]
                formatted_names = format_card_list_chinese(names)
                tpl = state.config.get("expired_template", "{卡片列表}即将超时")
                speech_text = tpl.replace("{卡片列表}", formatted_names).replace("{卡片名称}", formatted_names)

                print(f"[Alert] 发现 {len(new_expired_cards)} 张新超时卡片: {formatted_names}")
                speak_text(speech_text)

        except Exception as e:
            print("Poll Error:", e)

        interval = max(5, state.config.get("poll_interval_seconds", 60))
        time.sleep(interval)

# Web 控制台服务器
class WebRequestHandler(BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        return

    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        if path in ["/", "/index.html"]:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(get_web_html().encode("utf-8"))
        elif path == "/api/cards":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(state.latest_cards, ensure_ascii=False).encode("utf-8"))
        elif path == "/api/config":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(state.config, ensure_ascii=False).encode("utf-8"))
        elif path == "/api/voices":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(VOICE_MODELS, ensure_ascii=False).encode("utf-8"))
        else:
            self.send_error(404)

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        content_length = int(self.headers.get("Content-Length", 0))
        body_bytes = self.rfile.read(content_length) if content_length > 0 else b""
        req_data = json.loads(body_bytes.decode("utf-8")) if body_bytes else {}

        if path == "/api/config":
            state.save_config(req_data)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"success":true}')
        elif path == "/api/test-speech":
            voice = req_data.get("selected_voice_name") or state.config.get("selected_voice_name")
            vol = req_data.get("volume_boost") or state.config.get("volume_boost")
            tpl = req_data.get("expired_template") or state.config.get("expired_template")
            speech_text = tpl.replace("{卡片列表}", "1号卡片和2号卡片").replace("{卡片名称}", "1号卡片和2号卡片")
            speak_text(speech_text, voice_name=voice, volume_boost=vol)

            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"success":true}')
        else:
            self.send_error(404)

class ReusableHTTPServer(HTTPServer):
    allow_reuse_address = True

def run_web_server():
    try:
        server = ReusableHTTPServer(("0.0.0.0", WEB_PORT), WebRequestHandler)
        print(f"[Web] Python Web 监控控制台已启动: {WEB_URL}")
        server.serve_forever()
    except Exception as e:
        print("Web Server Error:", e)

# HTML 界面
def get_web_html():
    return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>NFC 卡片倒计时 Web 监控控制台 (Python AI 神经网络音色版)</title>
    <style>
        :root {
            --bg-dark: #0f172a;
            --bg-card: #1e293b;
            --primary: #9333ea;
            --primary-hover: #a855f7;
            --text-main: #f8fafc;
            --text-muted: #94a3b8;
            --color-running: #10b981;
            --color-expired: #ef4444;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            font-family: system-ui, -apple-system, sans-serif;
            background-color: var(--bg-dark);
            color: var(--text-main);
            padding: 24px;
        }
        .container { max-width: 960px; margin: 0 auto; }
        .header {
            display: flex;
            justify-content: space-between;
            align-items: center;
            padding-bottom: 20px;
            border-bottom: 1px solid #334155;
            margin-bottom: 20px;
        }
        .header h1 {
            font-size: 1.5rem;
            background: linear-gradient(135deg, #c084fc, #60a5fa);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
        .panel {
            background: var(--bg-card);
            border: 1px solid #334155;
            border-radius: 12px;
            padding: 20px;
            margin-bottom: 24px;
        }
        .panel h2 { font-size: 1.1rem; color: #c084fc; margin-bottom: 16px; border-bottom: 1px solid #334155; padding-bottom: 8px; }
        .form-group { margin-bottom: 16px; }
        .form-group label { display: block; font-weight: 600; margin-bottom: 6px; font-size: 0.95rem; }
        .form-control {
            width: 100%;
            padding: 10px 14px;
            background: #0f172a;
            border: 1px solid #475569;
            border-radius: 8px;
            color: var(--text-main);
            font-size: 0.95rem;
        }
        .form-control:focus { border-color: #a855f7; outline: none; }
        .flex-row { display: flex; gap: 16px; align-items: center; }
        .btn {
            background: var(--primary);
            color: white;
            border: none;
            padding: 10px 20px;
            border-radius: 8px;
            font-weight: 600;
            cursor: pointer;
            transition: all 0.2s;
        }
        .btn:hover { background: var(--primary-hover); }
        .btn-success { background: #10b981; }
        .btn-success:hover { background: #34d399; }
        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
            gap: 16px;
        }
        .card {
            background: var(--bg-card);
            border-radius: 10px;
            padding: 18px;
            border: 1px solid #334155;
        }
        .card.expired { border-color: var(--color-expired); box-shadow: 0 0 12px rgba(239,68,68,0.3); }
        .card.running { border-color: var(--color-running); }
        .card-title { font-size: 1.1rem; font-weight: 700; }
        .card-time { font-size: 2rem; font-family: monospace; font-weight: 700; margin-top: 10px; text-align: center; }
        .text-running { color: var(--color-running); }
        .text-expired { color: var(--color-expired); }
        .tip { font-size: 0.85rem; color: var(--text-muted); margin-top: 4px; }
    </style>
</head>
<body>
    <div class="container">
        <header class="header">
            <h1>⚡ NFC 卡片倒计时 Web 监控控制台 (Python AI 神经网络版)</h1>
            <div style="font-size: 0.9rem; color: #60a5fa;">
                🤖 Python 后台服务运行中
            </div>
        </header>

        <!-- 配置参数区 -->
        <div class="panel">
            <h2>⚙️ 语音播报与参数配置 (完全独立 AI 音色，改即生效)</h2>
            <div class="form-group">
                <label>🗣️ 超时语音模板设置 (变量: {卡片列表})</label>
                <input type="text" id="tplExpired" class="form-control" value="{卡片列表}即将超时">
                <div class="tip">示例: "01" "02" 自动转化为: "1号卡片和2号卡片即将超时"</div>
            </div>

            <div class="flex-row">
                <div class="form-group" style="flex: 1;">
                    <label>🎙️ 神经网络 AI 音色选择 (音色截然不同)</label>
                    <select id="selVoice" class="form-control"></select>
                </div>
                <div class="form-group" style="flex: 1;">
                    <label>📢 音量强效放大倍数: <span id="lblVolumeVal" style="color:#f43f5e;">2.5x</span></label>
                    <input type="range" id="rngVolume" min="1.0" max="5.0" step="0.5" value="2.5" class="form-control" oninput="updateVolumeLabel(this.value)">
                </div>
            </div>

            <div class="flex-row" style="margin-top: 10px;">
                <button class="btn btn-success" onclick="saveConfig()">💾 保存配置 (持久化到磁盘)</button>
                <button class="btn" onclick="testSpeech()">🗣️ 测试试听声音 (实时生效)</button>
            </div>
        </div>

        <!-- 实时卡片监控 -->
        <div class="panel">
            <h2>📊 实时卡片监控状态 (GitHub 云端数据库同步)</h2>
            <main class="grid" id="cardsGrid">
                <div style="grid-column: 1/-1; text-align: center; padding: 40px; color: var(--text-muted);">正在拉取卡片...</div>
            </main>
        </div>
    </div>

    <script>
        function updateVolumeLabel(val) {
            document.getElementById('lblVolumeVal').textContent = parseFloat(val).toFixed(1) + 'x';
        }

        async function loadConfig() {
            try {
                const [cfgRes, voicesRes] = await Promise.all([
                    fetch('/api/config'),
                    fetch('/api/voices')
                ]);

                if (voicesRes.ok) {
                    const voices = await voicesRes.json();
                    const sel = document.getElementById('selVoice');
                    sel.innerHTML = voices.map(v => `<option value="${v.name}">${v.title}</option>`).join('');
                }

                if (cfgRes.ok) {
                    const cfg = await cfgRes.json();
                    document.getElementById('tplExpired').value = cfg.expired_template || '{卡片列表}即将超时';
                    document.getElementById('rngVolume').value = cfg.volume_boost || 2.5;
                    updateVolumeLabel(cfg.volume_boost || 2.5);
                    if (cfg.selected_voice_name) {
                        document.getElementById('selVoice').value = cfg.selected_voice_name;
                    }
                }
            } catch (err) {
                console.error('加载配置失败:', err);
            }
        }

        async function saveConfig() {
            const expired_template = document.getElementById('tplExpired').value;
            const volume_boost = parseFloat(document.getElementById('rngVolume').value);
            const selected_voice_name = document.getElementById('selVoice').value;

            try {
                const res = await fetch('/api/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        expired_template,
                        volume_boost,
                        selected_voice_name,
                        server_url: "https://gist.githubusercontent.com/raw/6582c66b24bad75381f70abdae62e81b/cards_data.json",
                        poll_interval_seconds: 60,
                        pause_system_music: true,
                        enable_voice_alert: true
                    })
                });

                if (res.ok) {
                    alert('配置已成功保存并持久化！');
                }
            } catch (err) {
                alert('保存配置失败');
            }
        }

        async function testSpeech() {
            const expired_template = document.getElementById('tplExpired').value;
            const volume_boost = parseFloat(document.getElementById('rngVolume').value);
            const selected_voice_name = document.getElementById('selVoice').value;

            await fetch('/api/test-speech', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ expired_template, volume_boost, selected_voice_name })
            });
        }

        async function loadCards() {
            try {
                const res = await fetch('/api/cards');
                const cards = await res.json();
                renderCards(cards);
            } catch (err) { }
        }

        function renderCards(cards) {
            const grid = document.getElementById('cardsGrid');
            if (!cards || cards.length === 0) {
                grid.innerHTML = '<div style="grid-column: 1/-1; text-align: center; padding: 40px; color: var(--text-muted);">暂无卡片数据</div>';
                return;
            }
            grid.innerHTML = cards.map(c => {
                const isOverdue = c.isOverdue || c.remainingSeconds <= 0;
                const name = c.name || c.cardId;
                let cleanName = name;
                if (!isNaN(cleanName)) cleanName = parseInt(cleanName, 10).toString();
                if (!cleanName.endsWith('卡片')) cleanName += cleanName.endsWith('号') ? '卡片' : '号卡片';

                return `
                    <div class="card ${isOverdue ? 'expired' : (c.status === 1 ? 'running' : '')}">
                        <div class="card-title">${cleanName}</div>
                        <div class="card-time ${isOverdue ? 'text-expired' : 'text-running'}">
                            ${formatTime(c.remainingSeconds || 0)}
                        </div>
                    </div>
                `;
            }).join('');
        }

        function formatTime(totalSec) {
            const abs = Math.abs(Math.floor(totalSec));
            const m = Math.floor(abs / 60).toString().padStart(2, '0');
            const s = (abs % 60).toString().padStart(2, '0');
            return (totalSec < 0 ? '-' : '') + m + ':' + s;
        }

        loadConfig();
        loadCards();
        setInterval(loadCards, 5000);
    </script>
</body>
</html>
"""

# 创建系统托盘图标
def create_tray_icon():
    image = Image.new('RGBA', (64, 64), color=(0, 0, 0, 0))
    dc = ImageDraw.Draw(image)
    dc.ellipse((4, 4, 60, 60), fill=(147, 51, 234, 255), outline=(192, 132, 252, 255), width=3)
    dc.line((32, 32, 32, 16), fill=(255, 255, 255, 255), width=4)
    dc.line((32, 32, 44, 32), fill=(255, 255, 255, 255), width=4)
    dc.ellipse((29, 29, 35, 35), fill=(255, 255, 255, 255))

    def on_open_web(icon, item):
        webbrowser.open(WEB_URL)

    def on_test_speech(icon, item):
        speak_text("1号卡片和2号卡片即将超时")

    def on_exit(icon, item):
        icon.stop()
        os._exit(0)

    menu = pystray.Menu(
        pystray.MenuItem("🌐 打开 Web 监控界面 (双击)", on_open_web, default=True),
        pystray.MenuItem("🗣️ 测试语音播报", on_test_speech),
        pystray.MenuItem("❌ 退出程序", on_exit)
    )

    icon = pystray.Icon("NFC_Time_Service", image, "NFC 卡片倒计时监控服务", menu)
    icon.run()

if __name__ == "__main__":
    print("Starting Python NFC Time Monitor Service...")
    # 1. 启动 Web 控制台
    threading.Thread(target=run_web_server, daemon=True).start()
    # 2. 启动卡片倒计时轮询后台任务
    threading.Thread(target=card_monitor_loop, daemon=True).start()

    # 3. 启动系统托盘图标 (主线程)
    create_tray_icon()
