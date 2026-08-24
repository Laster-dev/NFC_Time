let currentFilter = 'all';
let nfcNDEFReader = null;
let activeNfcCardId = null;
let voiceEnabled = false;
const alertedCardIds = new Set();

const POLL_INTERVAL_MS = 10000; // 每10秒拉取一次

document.addEventListener('DOMContentLoaded', () => {
    fetchCards();
    setInterval(fetchCards, POLL_INTERVAL_MS); // 10秒定时拉取

    initVoiceAlerts();

    // Filter handlers
    document.querySelectorAll('.btn-filter').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.btn-filter').forEach(b => b.classList.remove('active'));
            e.target.classList.add('active');
            currentFilter = e.target.dataset.filter;
            fetchCards();
        });
    });

    // Detect if running on Android and supports Web NFC (NDEFReader)
    const isAndroid = /Android/i.test(navigator.userAgent);
    const hasWebNFC = 'NDEFReader' in window;

    if (isAndroid && hasWebNFC) {
        const nfcSection = document.getElementById('androidNfcSection');
        nfcSection.style.display = 'flex';

        document.getElementById('btnToggleWebNfc').addEventListener('click', toggleWebNfc);
    }
});

function initVoiceAlerts() {
    const btnEnable = document.getElementById('btnEnableVoice');
    const btnTest = document.getElementById('btnTestVoice');
    const statusText = document.getElementById('speechStatusText');

    if (btnEnable) {
        btnEnable.addEventListener('click', () => {
            voiceEnabled = !voiceEnabled;
            if (voiceEnabled) {
                btnEnable.textContent = '✅ 语音提醒已激活';
                btnEnable.classList.add('active');
                statusText.textContent = '🟢 语音提醒已激活！每10秒自动拉取，计时到期将语音提醒："{卡片名称}号卡片即将超时"';
                speakText('语音提醒已激活');
            } else {
                btnEnable.textContent = '🔊 激活语音提醒';
                btnEnable.classList.remove('active');
                statusText.textContent = '未启用（提示：点击右侧按钮激活语音播报）';
            }
        });
    }

    if (btnTest) {
        btnTest.addEventListener('click', () => {
            speakText('1号卡片即将超时');
        });
    }
}

function speakText(text) {
    if (!('speechSynthesis' in window)) {
        alert('当前浏览器不支持语音合成 (Web Speech API)');
        return;
    }
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = 'zh-CN';
    utterance.rate = 1.0;
    utterance.pitch = 1.0;
    utterance.volume = 1.0;
    window.speechSynthesis.speak(utterance);
}

function checkAndTriggerVoiceAlerts(cards) {
    cards.forEach(card => {
        // 倒计时结束条件: isOverdue=true 或 (运行中且剩余时间<=0) 或 status=3(Expired)
        const isTimeUp = card.isOverdue || (card.status === 1 && card.remainingSeconds <= 0) || card.status === 3;

        if (isTimeUp) {
            if (!alertedCardIds.has(card.cardId)) {
                alertedCardIds.add(card.cardId);
                const cardDisplayName = card.name || card.cardId;
                if (voiceEnabled) {
                    speakText(`${cardDisplayName}号卡片即将超时`);
                }
            }
        } else {
            // 重置后解除已提醒状态
            alertedCardIds.delete(card.cardId);
        }
    });
}

async function toggleWebNfc() {
    const btn = document.getElementById('btnToggleWebNfc');
    const statusText = document.getElementById('webNfcStatusText');

    if (nfcNDEFReader) {
        // Stop scanning
        nfcNDEFReader = null;
        btn.textContent = '开始 Web 刷卡';
        btn.classList.remove('active');
        statusText.textContent = '点击“开始Web刷卡”，靠近NFC卡片可自动扫描定位。';
        return;
    }

    try {
        nfcNDEFReader = new NDEFReader();
        await nfcNDEFReader.scan();

        btn.textContent = '停止 Web 刷卡';
        btn.classList.add('active');
        statusText.textContent = '🟢 正在监听 NFC 刷卡中... 请将卡片贴近手机背面。';

        nfcNDEFReader.addEventListener('reading', ({ serialNumber }) => {
            if (serialNumber) {
                // Format serial number e.g. 04:A2:3B:8C
                const formattedId = serialNumber.toUpperCase();
                handleWebNfcSwiped(formattedId);
            }
        });

        nfcNDEFReader.addEventListener('readingerror', () => {
            statusText.textContent = '⚠️ NFC 读取错误，请重新贴近卡片。';
        });

    } catch (error) {
        console.error("Web NFC Error: ", error);
        statusText.textContent = '⚠️ Web NFC 权限拒绝或不符合 HTTPS/Localhost 要求。';
    }
}

function handleWebNfcSwiped(scannedCardId) {
    const statusText = document.getElementById('webNfcStatusText');
    statusText.textContent = `✨ 感应到卡片 UID: ${scannedCardId}，正在查找卡片...`;

    activeNfcCardId = scannedCardId;
    fetchCards().then(() => {
        // Scroll card into view and highlight
        const cardElement = document.getElementById(`card-${scannedCardId}`);
        if (cardElement) {
            cardElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
            cardElement.classList.add('highlight-swiped-card');
            setTimeout(() => {
                cardElement.classList.remove('highlight-swiped-card');
            }, 3000);
            statusText.textContent = `✅ 找到对应卡片 UID: ${scannedCardId}`;
        } else {
            statusText.textContent = `❓ 服务器上尚未注册此卡片 (${scannedCardId})，请先通过 App 刷卡初始化。`;
        }
    });
}

async function fetchCards() {
    try {
        const res = await fetch('/api/cards');
        if (!res.ok) return;
        const cards = await res.json();
        checkAndTriggerVoiceAlerts(cards);
        renderDashboard(cards);
    } catch (err) {
        console.error('Failed to fetch cards:', err);
    }
}

function formatTime(totalSeconds) {
    const isNegative = totalSeconds < 0;
    const absSeconds = Math.abs(Math.floor(totalSeconds));
    
    const h = Math.floor(absSeconds / 3600);
    const m = Math.floor((absSeconds % 3600) / 60);
    const s = absSeconds % 60;

    const pad = n => n.toString().padStart(2, '0');
    let str = `${pad(m)}:${pad(s)}`;
    if (h > 0) str = `${pad(h)}:` + str;
    
    return isNegative ? `-${str}` : str;
}

function renderDashboard(cards) {
    let running = 0, paused = 0, stopped = 0, expired = 0;
    cards.forEach(c => {
        if (c.isOverdue || c.status === 3) expired++;
        else if (c.status === 1) running++;
        else if (c.status === 2) paused++;
        else stopped++;
    });

    document.getElementById('totalCardsBadge').textContent = `${cards.length} 张卡片`;
    document.getElementById('countRunning').textContent = running;
    document.getElementById('countPaused').textContent = paused;
    document.getElementById('countStopped').textContent = stopped;
    document.getElementById('countExpired').textContent = expired;

    const filtered = cards.filter(c => {
        if (currentFilter === 'running') return c.status === 1 && !c.isOverdue;
        if (currentFilter === 'expired') return c.isOverdue || c.status === 3;
        return true;
    });

    const grid = document.getElementById('cardsGrid');
    if (filtered.length === 0) {
        grid.innerHTML = `
            <div class="empty-state">
                <p>暂无卡片数据，请使用安卓 App 进行 NFC 刷卡。</p>
            </div>
        `;
        return;
    }

    grid.innerHTML = filtered.map(card => {
        let statusClass = 'status-stopped';
        let statusBadgeClass = 'badge-stopped';
        let statusText = '未开始';

        if (card.isOverdue || card.status === 3) {
            statusClass = 'status-expired';
            statusBadgeClass = 'badge-expired';
            statusText = '⚠️ 已超时';
        } else if (card.status === 1) {
            statusClass = 'status-running';
            statusBadgeClass = 'badge-running';
            statusText = '进行中';
        } else if (card.status === 2) {
            statusClass = 'status-paused';
            statusBadgeClass = 'badge-paused';
            statusText = '已暂停';
        }

        const remainingSec = card.remainingSeconds;
        const targetSec = card.targetDurationSeconds;
        let pct = 100;
        if (targetSec > 0) {
            pct = Math.max(0, Math.min(100, (remainingSec / targetSec) * 100));
        }

        let timeHtml = '';
        if (card.isOverdue) {
            timeHtml = `
                <div class="time-main text-expired">00:00</div>
                <div class="overdue-alert">🚨 超时: ${formatTime(card.overdueSeconds)}</div>
            `;
        } else {
            timeHtml = `
                <div class="time-main ${card.status === 1 ? 'text-running' : (card.status === 2 ? 'text-paused' : '')}">
                    ${formatTime(remainingSec)}
                </div>
            `;
        }

        const isHighlighted = activeNfcCardId && activeNfcCardId.replace(/:/g, '').toUpperCase() === card.cardId.replace(/:/g, '').toUpperCase();

        return `
            <div class="nfc-card ${statusClass} ${isHighlighted ? 'highlight-swiped-card' : ''}" id="card-${card.cardId.toUpperCase()}">
                <div class="card-top">
                    <div>
                        <div class="card-title">${escapeHtml(card.name)}</div>
                        <div class="card-uid">UID: ${escapeHtml(card.cardId)}</div>
                    </div>
                    <span class="status-badge ${statusBadgeClass}">${statusText}</span>
                </div>

                <div class="card-timer-display">
                    ${timeHtml}
                </div>

                <div class="progress-bar-bg">
                    <div class="progress-bar-fill" style="width: ${card.isOverdue ? 0 : pct}%; ${card.status === 2 ? 'background: #f59e0b' : ''}"></div>
                </div>
            </div>
        `;
    }).join('');
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>"']/g, function(m) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[m];
    });
}
