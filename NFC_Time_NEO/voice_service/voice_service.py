import os
import sys
import json
import time
import asyncio
import threading
import tempfile
import ctypes
import webbrowser
import wave
import math
import struct
import winsound
from datetime import datetime, timezone
import urllib.parse
import urllib.request
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler

# 强制控制台编码为 UTF-8 (兼容 pythonw 无窗口静默环境)
if sys.stdout is not None:
    try:
        sys.stdout.reconfigure(encoding='utf-8')
    except Exception:
        pass

import edge_tts
from PIL import Image, ImageDraw
import pystray

# Windows CoreAudio 音频管理库
try:
    from pycaw.pycaw import AudioUtilities, ISimpleAudioVolume
    PYCAW_AVAILABLE = True
except Exception:
    PYCAW_AVAILABLE = False

WEB_PORT = 5050
DEFAULT_SERVER_API_URL = "http://43.140.218.3:5000/api/cards"
CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "voice_config.json")

SUPPORTED_VOICES = [
    {"id": "zh-CN-XiaoxiaoNeural", "name": "晓晓 (温柔女声 · 推荐)", "gender": "Female", "desc": "自然温暖、亲切柔和"},
    {"id": "zh-CN-YunxiNeural", "name": "云希 (阳光男声 · 推荐)", "gender": "Male", "desc": "清澈明朗、自然流畅"},
    {"id": "zh-CN-XiaoyiNeural", "name": "晓伊 (活泼少女)", "gender": "Female", "desc": "轻快灵动、活泼热情"},
    {"id": "zh-CN-YunjianNeural", "name": "云健 (沉稳男声)", "gender": "Male", "desc": "稳重有力、广播播音"},
    {"id": "zh-CN-YunyangNeural", "name": "云扬 (专业男声)", "gender": "Male", "desc": "专业播报、清晰严谨"},
    {"id": "zh-CN-liaoning-XiaobeiNeural", "name": "晓北 (辽宁方言)", "gender": "Female", "desc": "东北风情、热情大方"},
    {"id": "zh-CN-shaanxi-XiaoniNeural", "name": "晓妮 (陕西方言)", "gender": "Female", "desc": "陕西特色、生动亲切"},
    {"id": "zh-TW-HsiaoChenNeural", "name": "晓臻 (台湾女声)", "gender": "Female", "desc": "温婉恬静、甜美柔和"}
]

DEFAULT_CONFIG = {
    "server_api_url": DEFAULT_SERVER_API_URL,
    "poll_interval_seconds": 10,
    "pause_system_music": True,
    "enable_voice_alert": True,
    "expired_template": "{卡片列表}即将超时，请留意游玩时间",
    "selected_voice_name": "zh-CN-XiaoxiaoNeural",
    "speech_rate": "-20%",
    "volume_boost": 2.5
}

class AppState:
    def __init__(self):
        self.config = self.load_config()
        self.alerted_sessions = set()
        self.is_speaking = False
        self.last_poll_time = None
        self.last_poll_status = "未开始"
        self.monitored_cards = []
        self.logs = []
        self.lock = threading.Lock()

    def log(self, tag, msg):
        ts = datetime.now().strftime("%H:%M:%S")
        entry = f"[{ts}] [{tag}] {msg}"
        if sys.stdout is not None:
            try:
                print(entry)
            except Exception:
                pass
        with self.lock:
            self.logs.append({"time": ts, "tag": tag, "msg": msg})
            if len(self.logs) > 100:
                self.logs.pop(0)

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

# ==========================================
# 🔊 音频播放与提示音控制
# ==========================================
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
        pass

def toggle_media_play_pause():
    try:
        ctypes.windll.user32.keybd_event(0xB3, 0, 0, 0)
        ctypes.windll.user32.keybd_event(0xB3, 0, 2, 0)
    except Exception:
        pass

def generate_chime_wav(filepath):
    """合成清脆悦耳的双音手机提示音 (叮咚 E5 659.25Hz -> A5 880Hz)"""
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

def play_mp3_native(file_path):
    try:
        alias = f"tts_{int(time.time()*1000)}"
        mci = ctypes.windll.winmm.mciSendStringW
        mci(f'open "{file_path}" type mpegvideo alias {alias}', None, 0, 0)
        mci(f'play {alias} wait', None, 0, 0)
        mci(f'close {alias}', None, 0, 0)
    except Exception as e:
        print("Play MP3 error:", e)

async def _async_speak(text, voice_name, volume_boost, pause_music, speech_rate):
    if state.is_speaking:
        return
    state.is_speaking = True
    try:
        vol_percent = int((volume_boost - 1.0) * 100)
        vol_str = f"+{vol_percent}%" if vol_percent >= 0 else f"{vol_percent}%"
        rate_str = speech_rate if speech_rate else "-20%"

        state.log("TTS", f"📢 合成播报: 语速={rate_str}, 音量={vol_str} | 内容: \"{text}\"")

        temp_mp3 = os.path.join(tempfile.gettempdir(), f"edge_tts_{int(time.time()*1000)}.mp3")
        communicate = edge_tts.Communicate(text, voice_name, volume=vol_str, rate=rate_str)
        await communicate.save(temp_mp3)

        if pause_music:
            toggle_media_play_pause()
            mute_other_audio_sessions(True)
            time.sleep(0.15)

        # 1. 播放清脆提示音
        play_chime_sound()

        # 2. 停顿 0.7 秒
        time.sleep(0.7)

        # 3. 播报温和清晰语音
        play_mp3_native(temp_mp3)

        if pause_music:
            time.sleep(0.2)
            mute_other_audio_sessions(False)
            toggle_media_play_pause()

        try:
            os.remove(temp_mp3)
        except Exception:
            pass
    except Exception as e:
        state.log("TTS_ERR", f"播报异常: {e}")
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

# ==========================================
# 🛰️ 轮询监听线程
# ==========================================
def voice_monitor_loop():
    state.log("INIT", "🎙️ NFC_Time 语音播报监听服务已启动")
    state.log("RULE", "播报规则: 仅播报「先付款」倒计时卡片；场次套餐与后付款不播报")

    while True:
        try:
            url = state.config.get("server_api_url", DEFAULT_SERVER_API_URL)
            req_url = url + ("&" if "?" in url else "?") + f"t={int(time.time()*1000)}"
            req = urllib.request.Request(req_url, headers={"User-Agent": "NFC_Time_VoiceService/2.0"})
            
            with urllib.request.urlopen(req, timeout=5) as resp:
                cards = json.loads(resp.read().decode("utf-8"))

            state.last_poll_time = datetime.now().strftime("%H:%M:%S")
            state.last_poll_status = f"正常 (获取到 {len(cards)} 张卡片)"
            state.monitored_cards = cards

            new_expired_cards = []

            for c in cards:
                status = c.get("status", 0)
                timer_mode = c.get("timerMode", 0) # 0: Countdown, 1: Countup
                is_post_pay = c.get("isPostPay", False)
                preset_plan = (c.get("presetPlan") or "").lower()
                remaining_seconds = c.get("remainingSeconds", 0)
                is_overdue = c.get("isOverdue", False)
                card_id = c.get("cardId", "")
                start_str = c.get("startTimeUtc", "")

                # 规则过滤 1: 后付款（玩完再付）不播报！
                if is_post_pay:
                    continue

                # 规则过滤 2: 正计时模式（先玩后付）不播报！
                if timer_mode == 1:
                    continue

                # 规则过滤 3: 固定场次套餐（上午场、下午场、全天场）不播报！
                if preset_plan in ["morning", "afternoon", "allday"]:
                    continue

                # 仅对先付款普通倒计时（如1h, 3h等）即将到期或超时进行播报
                session_key = f"{card_id}_{start_str}" if start_str else card_id
                is_time_up = (status == 1 and remaining_seconds <= 60) or status == 3 or is_overdue

                if is_time_up and (status == 1 or status == 3):
                    if session_key not in state.alerted_sessions:
                        new_expired_cards.append(c)
                        state.alerted_sessions.add(session_key)
                elif status == 0:
                    state.alerted_sessions.discard(session_key)

            if new_expired_cards and state.config.get("enable_voice_alert", True):
                names = [c.get("name") or c.get("cardId", "") for c in new_expired_cards]
                formatted_names = format_card_list_chinese(names)
                tpl = state.config.get("expired_template", "{卡片列表}即将超时，请留意游玩时间")
                speech_text = tpl.replace("{卡片列表}", formatted_names).replace("{卡片名称}", formatted_names)
                state.log("ALERT", f"🔔 触发超时语音播报: {formatted_names}")
                speak_text(speech_text)

        except Exception as e:
            state.last_poll_status = f"连接失败 ({e})"

        time.sleep(max(3, state.config.get("poll_interval_seconds", 10)))

# ==========================================
# 🌐 Web 管理后台服务 (Port: 5050)
# ==========================================
HTML_PAGE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>星野手作 · 语音播报服务后台</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;600;700;800&display=swap">
    <style>
        :root {
            --bg-base: #0B1118;
            --card-bg: #151F2C;
            --card-border: #233246;
            --primary: #F43F5E;
            --primary-hover: #E11D48;
            --accent: #38BDF8;
            --success: #10B981;
            --warning: #F59E0B;
            --text-main: #F8FAFC;
            --text-sub: #94A3B8;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
        body { background: var(--bg-base); color: var(--text-main); min-height: 100vh; padding: 24px 16px; }
        .container { max-width: 960px; margin: 0 auto; }
        
        /* Header */
        .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--card-border); }
        .title-box h1 { font-size: 1.5rem; font-weight: 800; color: #FFF; display: flex; align-items: center; gap: 8px; }
        .title-box p { font-size: 0.85rem; color: var(--text-sub); margin-top: 4px; }
        .status-pill { display: inline-flex; align-items: center; gap: 6px; padding: 6px 12px; border-radius: 9999px; font-size: 0.8rem; font-weight: 700; background: rgba(16,185,129,0.15); color: var(--success); border: 1px solid rgba(16,185,129,0.3); }
        
        /* Grid Layout */
        .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
        @media (max-width: 768px) { .grid { grid-template-columns: 1fr; } }
        
        /* Card Panel */
        .panel { background: var(--card-bg); border: 1px solid var(--card-border); border-radius: 16px; padding: 20px; box-shadow: 0 10px 25px -5px rgba(0,0,0,0.3); }
        .panel-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
        .panel-title { font-size: 1rem; font-weight: 700; color: #FFF; display: flex; align-items: center; gap: 6px; }
        
        /* Form Elements */
        .form-group { margin-bottom: 16px; }
        .form-label { display: block; font-size: 0.82rem; font-weight: 600; color: var(--text-sub); margin-bottom: 6px; }
        .form-control { width: 100%; padding: 10px 14px; background: #0E1622; border: 1px solid var(--card-border); border-radius: 10px; color: #FFF; font-size: 0.9rem; transition: all 0.2s; }
        .form-control:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 2px rgba(56,189,248,0.2); }
        select.form-control { cursor: pointer; }
        
        /* Quick URL helper buttons */
        .quick-presets { display: flex; gap: 6px; margin-top: 6px; }
        .btn-preset { font-size: 0.75rem; padding: 3px 8px; background: #1B2838; border: 1px solid var(--card-border); color: var(--accent); border-radius: 6px; cursor: pointer; }
        .btn-preset:hover { background: #23344A; }
        
        /* Range + Output row */
        .range-row { display: flex; align-items: center; gap: 12px; }
        .range-slider { flex: 1; accent-color: var(--primary); }
        .range-val { width: 60px; font-size: 0.85rem; font-weight: 700; color: var(--accent); text-align: right; }
        
        /* Toggle Switch */
        .toggle-row { display: flex; align-items: center; justify-content: space-between; padding: 10px 0; border-top: 1px solid rgba(255,255,255,0.05); }
        .switch { position: relative; display: inline-block; width: 44px; height: 24px; }
        .switch input { opacity: 0; width: 0; height: 0; }
        .slider { position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0; background-color: #334155; transition: .3s; border-radius: 24px; }
        .slider:before { position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px; background-color: white; transition: .3s; border-radius: 50%; }
        input:checked + .slider { background-color: var(--primary); }
        input:checked + .slider:before { transform: translateX(20px); }
        
        /* Buttons */
        .btn { display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 10px 18px; border-radius: 10px; font-size: 0.88rem; font-weight: 700; border: none; cursor: pointer; transition: all 0.2s; }
        .btn-primary { background: var(--primary); color: #FFF; width: 100%; }
        .btn-primary:hover { background: var(--primary-hover); }
        .btn-test { background: #0284C7; color: #FFF; width: 100%; margin-top: 8px; }
        .btn-test:hover { background: #0369A1; }
        
        /* Log terminal */
        .log-box { background: #080D13; border: 1px solid var(--card-border); border-radius: 10px; height: 210px; overflow-y: auto; padding: 10px; font-family: monospace; font-size: 0.78rem; color: #CBD5E1; }
        .log-line { margin-bottom: 4px; line-height: 1.4; }
        .log-tag { color: var(--accent); font-weight: bold; }
        .log-alert { color: #FB7185; }
        
        /* Status Badges List */
        .cards-list { display: flex; flex-wrap: wrap; gap: 8px; max-height: 140px; overflow-y: auto; }
        .mini-card-badge { font-size: 0.75rem; padding: 4px 8px; border-radius: 6px; background: #1B2838; border: 1px solid var(--card-border); color: #E2E8F0; }
        
        .toast { position: fixed; bottom: 20px; right: 20px; padding: 12px 20px; background: #1E293B; border-left: 4px solid var(--success); color: #FFF; border-radius: 8px; box-shadow: 0 10px 25px rgba(0,0,0,0.5); font-size: 0.85rem; font-weight: 600; display: none; z-index: 999; }
    </style>
</head>
<body>
    <div class="container">
        <!-- Header -->
        <div class="header">
            <div class="title-box">
                <h1>🎙️ 星野手作 · 语音播报服务</h1>
                <p>微软 Edge-TTS 高保真神经语音 · 自动前置叮咚提示音 · 智能免打扰</p>
            </div>
            <div id="serviceStatusPill" class="status-pill">🟢 服务运行中</div>
        </div>

        <div class="grid">
            <!-- Left: Settings Panel -->
            <div class="panel">
                <div class="panel-header">
                    <div class="panel-title">⚙️ 播报参数配置</div>
                </div>

                <div class="form-group">
                    <label class="form-label">📡 监听服务器 API 地址</label>
                    <input type="text" id="serverApiUrl" class="form-control" placeholder="http://127.0.0.1:5000/api/cards">
                    <div class="quick-presets">
                        <button class="btn-preset" onclick="setPresetUrl('http://43.140.218.3:5000/api/cards')">云端服务器 (43.140.218.3)</button>
                        <button class="btn-preset" onclick="setPresetUrl('http://127.0.0.1:5000/api/cards')">本地服务器 (127.0.0.1)</button>
                    </div>
                </div>

                <div class="form-group">
                    <label class="form-label">🗣️ 微软 Edge 神经语音音色</label>
                    <select id="voiceSelect" class="form-control"></select>
                </div>

                <div class="form-group">
                    <label class="form-label">⏱️ 语速调节 (已优化自然平稳)</label>
                    <div class="range-row">
                        <input type="range" id="speechRate" class="range-slider" min="-40" max="20" step="5" value="-20">
                        <span id="speechRateVal" class="range-val">-20%</span>
                    </div>
                </div>

                <div class="form-group">
                    <label class="form-label">🔊 音量增益倍数</label>
                    <div class="range-row">
                        <input type="range" id="volumeBoost" class="range-slider" min="1.0" max="3.5" step="0.1" value="2.5">
                        <span id="volumeBoostVal" class="range-val">2.5x</span>
                    </div>
                </div>

                <div class="form-group">
                    <label class="form-label">📝 超时播报文案模板</label>
                    <input type="text" id="expiredTemplate" class="form-control" value="{卡片列表}即将超时，请留意游玩时间">
                </div>

                <div class="toggle-row">
                    <div>
                        <div style="font-weight:700; font-size:0.88rem;">🔔 启用先付款卡片超时语音播报</div>
                        <div style="font-size:0.75rem; color:var(--text-sub);">仅播报先付款卡片，场次套餐与后付款不播报</div>
                    </div>
                    <label class="switch">
                        <input type="checkbox" id="enableVoiceAlert" checked>
                        <span class="slider"></span>
                    </label>
                </div>

                <div class="toggle-row">
                    <div>
                        <div style="font-weight:700; font-size:0.88rem;">🎵 播报时强效暂停/静音背景音乐</div>
                        <div style="font-size:0.75rem; color:var(--text-sub);">播报前自动淡出/暂停音乐，播报完毕后无缝恢复</div>
                    </div>
                    <label class="switch">
                        <input type="checkbox" id="pauseSystemMusic" checked>
                        <span class="slider"></span>
                    </label>
                </div>

                <button class="btn btn-primary" onclick="saveConfig()">💾 保存并应用配置</button>
            </div>

            <!-- Right: Test & Monitor Panel -->
            <div>
                <!-- Test Voice Panel -->
                <div class="panel" style="margin-bottom: 20px;">
                    <div class="panel-header">
                        <div class="panel-title">📢 即时试听测试</div>
                    </div>
                    <div class="form-group">
                        <label class="form-label">输入试听文本 (前置提示音 + 停顿 + 语音)</label>
                        <input type="text" id="testText" class="form-control" value="01号卡片即将超时，请留意游玩时间">
                    </div>
                    <button class="btn btn-test" onclick="triggerTestSpeak()">▶️ 立即测试播报</button>
                </div>

                <!-- Live Status & Logs -->
                <div class="panel">
                    <div class="panel-header">
                        <div class="panel-title">📜 实时服务状态与日志</div>
                        <span id="lastPollTime" style="font-size:0.75rem; color:var(--text-sub);">监听中...</span>
                    </div>

                    <div style="margin-bottom: 12px;">
                        <div style="font-size:0.8rem; font-weight:600; color:var(--text-sub); margin-bottom:6px;">🏷️ 当前活跃卡片:</div>
                        <div id="activeCardsList" class="cards-list">
                            <span style="font-size:0.75rem; color:#64748B;">暂无活跃制作中卡片</span>
                        </div>
                    </div>

                    <div class="log-box" id="logBox"></div>
                </div>
            </div>
        </div>
    </div>

    <div id="toast" class="toast">✅ 配置已成功保存！</div>

    <script>
        const VOICES = [
            { id: "zh-CN-XiaoxiaoNeural", name: "晓晓 (温柔女声 · 推荐)" },
            { id: "zh-CN-YunxiNeural", name: "云希 (阳光男声 · 推荐)" },
            { id: "zh-CN-XiaoyiNeural", name: "晓伊 (活泼少女)" },
            { id: "zh-CN-YunjianNeural", name: "云健 (沉稳男声)" },
            { id: "zh-CN-YunyangNeural", name: "云扬 (专业男声)" },
            { id: "zh-CN-liaoning-XiaobeiNeural", name: "晓北 (辽宁方言)" },
            { id: "zh-CN-shaanxi-XiaoniNeural", name: "晓妮 (陕西方言)" },
            { id: "zh-TW-HsiaoChenNeural", name: "晓臻 (台湾女声)" }
        ];

        function init() {
            const select = document.getElementById('voiceSelect');
            select.innerHTML = VOICES.map(v => `<option value="${v.id}">${v.name}</option>`).join('');

            document.getElementById('speechRate').addEventListener('input', e => {
                const val = parseInt(e.target.value);
                document.getElementById('speechRateVal').textContent = (val > 0 ? `+${val}%` : `${val}%`);
            });

            document.getElementById('volumeBoost').addEventListener('input', e => {
                document.getElementById('volumeBoostVal').textContent = `${parseFloat(e.target.value).toFixed(1)}x`;
            });

            fetchConfig();
            fetchStatus();
            setInterval(fetchStatus, 3000);
        }

        function setPresetUrl(url) {
            document.getElementById('serverApiUrl').value = url;
        }

        function showToast(msg) {
            const t = document.getElementById('toast');
            t.textContent = msg;
            t.style.display = 'block';
            setTimeout(() => { t.style.display = 'none'; }, 2500);
        }

        async function fetchConfig() {
            try {
                const res = await fetch('/api/config');
                const cfg = await res.json();
                document.getElementById('serverApiUrl').value = cfg.server_api_url || '';
                document.getElementById('voiceSelect').value = cfg.selected_voice_name || 'zh-CN-XiaoxiaoNeural';
                
                const rateNum = parseInt((cfg.speech_rate || '-20%').replace('%', ''));
                document.getElementById('speechRate').value = rateNum;
                document.getElementById('speechRateVal').textContent = (rateNum > 0 ? `+${rateNum}%` : `${rateNum}%`);

                document.getElementById('volumeBoost').value = cfg.volume_boost || 2.5;
                document.getElementById('volumeBoostVal').textContent = `${(cfg.volume_boost || 2.5).toFixed(1)}x`;

                document.getElementById('expiredTemplate').value = cfg.expired_template || '{卡片列表}即将超时，请留意游玩时间';
                document.getElementById('enableVoiceAlert').checked = cfg.enable_voice_alert !== false;
                document.getElementById('pauseSystemMusic').checked = cfg.pause_system_music !== false;
            } catch (e) {
                console.error(e);
            }
        }

        async function saveConfig() {
            const rateVal = parseInt(document.getElementById('speechRate').value);
            const rateStr = rateVal > 0 ? `+${rateVal}%` : `${rateVal}%`;

            const payload = {
                server_api_url: document.getElementById('serverApiUrl').value.trim(),
                selected_voice_name: document.getElementById('voiceSelect').value,
                speech_rate: rateStr,
                volume_boost: parseFloat(document.getElementById('volumeBoost').value),
                expired_template: document.getElementById('expiredTemplate').value.trim(),
                enable_voice_alert: document.getElementById('enableVoiceAlert').checked,
                pause_system_music: document.getElementById('pauseSystemMusic').checked
            };

            try {
                const res = await fetch('/api/config', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload)
                });
                if (res.ok) {
                    showToast('✅ 配置已成功保存并立即生效！');
                }
            } catch (e) {
                alert('保存失败: ' + e);
            }
        }

        async function triggerTestSpeak() {
            const text = document.getElementById('testText').value.trim();
            if (!text) return;
            const rateVal = parseInt(document.getElementById('speechRate').value);
            const rateStr = rateVal > 0 ? `+${rateVal}%` : `${rateVal}%`;

            try {
                await fetch('/api/test-speak', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({
                        text: text,
                        voice_name: document.getElementById('voiceSelect').value,
                        speech_rate: rateStr,
                        volume_boost: parseFloat(document.getElementById('volumeBoost').value),
                        pause_music: document.getElementById('pauseSystemMusic').checked
                    })
                });
                showToast('📢 正在播报测试语音...');
            } catch (e) {
                alert('试听请求失败: ' + e);
            }
        }

        async function fetchStatus() {
            try {
                const res = await fetch('/api/status');
                const data = await res.json();
                
                // Status pill
                const pill = document.getElementById('serviceStatusPill');
                if (data.is_speaking) {
                    pill.textContent = '🔊 正在播报语音...';
                    pill.style.color = '#F59E0B';
                } else {
                    pill.textContent = '🟢 服务运行中';
                    pill.style.color = '#10B981';
                }

                document.getElementById('lastPollTime').textContent = `最后轮询: ${data.last_poll_time || '未知'} (${data.last_poll_status})`;

                // Render active cards
                const cardsContainer = document.getElementById('activeCardsList');
                const active = (data.monitored_cards || []).filter(c => c.status !== 0);
                if (active.length === 0) {
                    cardsContainer.innerHTML = '<span style="font-size:0.75rem; color:#64748B;">暂无制作中卡片</span>';
                } else {
                    cardsContainer.innerHTML = active.map(c => {
                        const isPost = c.isPostPay ? '后付' : '先付';
                        return `<span class="mini-card-badge">🏷️ ${c.name || c.cardId} (${isPost})</span>`;
                    }).join('');
                }

                // Render logs
                const logBox = document.getElementById('logBox');
                logBox.innerHTML = (data.logs || []).map(l => {
                    const isAlert = l.tag === 'ALERT';
                    return `<div class="log-line ${isAlert ? 'log-alert' : ''}"><span class="log-tag">[${l.time}] [${l.tag}]</span> ${escapeHtml(l.msg)}</div>`;
                }).join('');
                logBox.scrollTop = logBox.scrollHeight;
            } catch (e) { }
        }

        function escapeHtml(str) {
            if (!str) return '';
            return str.replace(/[&<>"']/g, m => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' })[m]);
        }

        window.onload = init;
    </script>
</body>
</html>
"""

class VoiceWebHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path in ["/", "/index.html"]:
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(HTML_PAGE.encode("utf-8"))
            return

        if path == "/api/config":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(state.config, ensure_ascii=False).encode("utf-8"))
            return

        if path == "/api/status":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            res = {
                "is_speaking": state.is_speaking,
                "last_poll_time": state.last_poll_time,
                "last_poll_status": state.last_poll_status,
                "monitored_cards": state.monitored_cards,
                "logs": state.logs
            }
            self.wfile.write(json.dumps(res, ensure_ascii=False).encode("utf-8"))
            return

        if path == "/api/voices":
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps(SUPPORTED_VOICES, ensure_ascii=False).encode("utf-8"))
            return

        self.send_response(404)
        self.end_headers()

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8') if length > 0 else '{}'
        
        try:
            data = json.loads(body)
        except Exception:
            data = {}

        if path == "/api/config":
            state.save_config(data)
            state.log("CONFIG", f"⚙️ Web 后台已更新并保存配置")
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True}).encode("utf-8"))
            return

        if path == "/api/test-speak":
            text = data.get("text", "测试语音播报，星野手作欢迎您")
            voice_name = data.get("voice_name")
            speech_rate = data.get("speech_rate")
            volume_boost = data.get("volume_boost")
            pause_music = data.get("pause_music")

            state.log("TEST", f"🧪 触发 Web 试听播报: \"{text}\"")
            speak_text(text, voice_name=voice_name, volume_boost=volume_boost, pause_music=pause_music, speech_rate=speech_rate)

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.end_headers()
            self.wfile.write(json.dumps({"success": True}).encode("utf-8"))
            return

        self.send_response(404)
        self.end_headers()

    def log_message(self, format, *args):
        # 静默 HTTP 访问日志
        pass

def run_web_server():
    server = ThreadingHTTPServer(('0.0.0.0', WEB_PORT), VoiceWebHandler)
    state.log("WEB", f"🌐 Web 管理后台已在 http://127.0.0.1:{WEB_PORT} 启动")
    server.serve_forever()

# ==========================================
# 🎛️ 系统托盘图标
# ==========================================
def open_web_dashboard():
    webbrowser.open(f"http://127.0.0.1:{WEB_PORT}")

def create_tray_icon():
    image = Image.new('RGB', (64, 64), color=(244, 63, 94))
    d = ImageDraw.Draw(image)
    d.ellipse((8, 8, 56, 56), fill=(255, 255, 255))
    d.ellipse((14, 14, 50, 50), fill=(244, 63, 94))
    d.text((22, 22), "TTS", fill=(255, 255, 255))

    def on_exit(icon):
        state.log("EXIT", "正在停止语音服务...")
        icon.stop()
        os._exit(0)

    menu = pystray.Menu(
        pystray.MenuItem("🌐 打开 Web 管理后台", lambda: open_web_dashboard(), default=True),
        pystray.MenuItem("🎙️ 测试语音播报", lambda: speak_text("测试语音播报，星野手作欢迎您")),
        pystray.Menu.SEPARATOR,
        pystray.MenuItem("🛑 退出语音服务", on_exit)
    )

    icon = pystray.Icon("NFC_Time_Voice", image, "星野手作 · 语音播报服务 (双击打开后台)", menu)
    return icon

if __name__ == "__main__":
    try:
        # 1. 启动 Web 管理服务线程
        web_t = threading.Thread(target=run_web_server, daemon=True)
        web_t.start()

        # 2. 启动轮询监听线程
        poll_t = threading.Thread(target=voice_monitor_loop, daemon=True)
        poll_t.start()

        # 3. 运行系统托盘
        try:
            icon = create_tray_icon()
            icon.run()
        except Exception as e:
            state.log("TRAY", f"托盘异常 (转为后台常驻运行): {e}")

        while True:
            time.sleep(2)
    except Exception as e:
        import traceback
        with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "crash.log"), "w", encoding="utf-8") as f:
            f.write(traceback.format_exc())
