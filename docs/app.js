let currentFilter = 'all';
let nfcNDEFReader = null;
let activeNfcCardId = null;

// Built-in fixed Gist ID
const FIXED_GIST_ID = "6582c66b24bad75381f70abdae62e81b";

document.addEventListener('DOMContentLoaded', () => {
    fetchCardsFromGist();
    setInterval(fetchCardsFromGist, 2000); // Poll GitHub Gist every 2 seconds

    // Filter handlers
    document.querySelectorAll('.btn-filter').forEach(btn => {
        btn.addEventListener('click', (e) => {
            document.querySelectorAll('.btn-filter').forEach(b => b.classList.remove('active'));
            e.target.classList.add('active');
            currentFilter = e.target.dataset.filter;
            fetchCardsFromGist();
        });
    });

    // Detect Android & Web NFC support
    const isAndroid = /Android/i.test(navigator.userAgent);
    const hasWebNFC = 'NDEFReader' in window;

    if (isAndroid && hasWebNFC) {
        const nfcSection = document.getElementById('androidNfcSection');
        nfcSection.style.display = 'flex';
        document.getElementById('btnToggleWebNfc').addEventListener('click', toggleWebNfc);
        showAndroidNfcBanner();
    }
});

function showAndroidNfcBanner() {
    const statusText = document.getElementById('webNfcStatusText');
    if (statusText) {
        statusText.textContent = '📲 检测到安卓设备！点击“开始Web刷卡”，靠近NFC卡片可自动识别查找卡片。';
    }
}

async function fetchCardsFromGist() {
    if (!FIXED_GIST_ID) {
        renderDashboard([]);
        return;
    }

    try {
        // Use raw gist URL directly to completely bypass GitHub API Unauthenticated Rate Limits (403 Rate Limit Exceeded)
        const rawUrl = `https://gist.githubusercontent.com/Laster-dev/${FIXED_GIST_ID}/raw/cards_data.json?t=${Date.now()}`;
        const res = await fetch(rawUrl);
        if (!res.ok) return;
        const rawCards = await res.json();
        
        const computedCards = computeCardsState(rawCards);
        renderDashboard(computedCards);
    } catch (err) {
        console.error('Failed to fetch from GitHub Gist:', err);
    }
}

function computeCardsState(cards) {
    if (!Array.isArray(cards)) return [];
    const now = Date.now();

    return cards.map(c => {
        let remaining = c.savedRemainingSeconds || 0;
        let status = c.status; // 0: Stopped, 1: Running, 2: Paused, 3: Expired

        if (status === 1 && c.startTimeUtc) {
            const elapsed = (now - new Date(c.startTimeUtc).getTime()) / 1000;
            remaining = (c.savedRemainingSeconds || 0) - elapsed;
            if (remaining <= 0) {
                status = 3; // Expired
            }
        }

        const isOverdue = remaining < 0 && (status === 1 || status === 3);
        const overdueSeconds = isOverdue ? Math.abs(remaining) : 0;

        return {
            ...c,
            status,
            remainingSeconds: remaining,
            overdueSeconds,
            isOverdue
        };
    });
}

async function toggleWebNfc() {
    const btn = document.getElementById('btnToggleWebNfc');
    const statusText = document.getElementById('webNfcStatusText');

    if (nfcNDEFReader) {
        nfcNDEFReader = null;
        btn.textContent = '开始 Web 刷卡';
        btn.classList.remove('active');
        statusText.textContent = '点击“开始Web刷卡”，靠近NFC卡片可自动识别查找卡片。';
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
                const formattedId = serialNumber.toUpperCase();
                handleWebNfcSwiped(formattedId);
            }
        });

    } catch (error) {
        console.error("Web NFC Error: ", error);
        statusText.textContent = '⚠️ Web NFC 权限拒绝或不支持。';
    }
}

function handleWebNfcSwiped(scannedCardId) {
    const statusText = document.getElementById('webNfcStatusText');
    statusText.textContent = `✨ 感应到卡片 UID: ${scannedCardId}，正在查找卡片...`;

    activeNfcCardId = scannedCardId;
    fetchCardsFromGist().then(() => {
        const cardElement = document.getElementById(`card-${scannedCardId}`);
        if (cardElement) {
            cardElement.scrollIntoView({ behavior: 'smooth', block: 'center' });
            cardElement.classList.add('highlight-swiped-card');
            setTimeout(() => {
                cardElement.classList.remove('highlight-swiped-card');
            }, 3000);
            statusText.textContent = `✅ 找到对应卡片 UID: ${scannedCardId}`;
        } else {
            statusText.textContent = `❓ 数据源中尚未注册此卡片 (${scannedCardId})。`;
        }
    });
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
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039}' }[m];
    });
}
