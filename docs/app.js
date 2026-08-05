let currentFilter = 'all';

// Built-in fixed Gist ID
const FIXED_GIST_ID = "6582c66b24bad75381f70abdae62e81b";

// GitHub Raw URL (CDN) for unlimited, rate-limit free reading!
const RAW_GIST_URL = `https://gist.githubusercontent.com/raw/${FIXED_GIST_ID}/cards_data.json`;

document.addEventListener('DOMContentLoaded', () => {
    // Tab Button Click Listeners
    const tabBtns = document.querySelectorAll('.tab-btn');
    tabBtns.forEach(btn => {
        btn.addEventListener('click', (e) => {
            tabBtns.forEach(b => b.classList.remove('active'));
            const target = e.currentTarget;
            target.classList.add('active');
            currentFilter = target.getAttribute('data-filter') || 'all';
            fetchCardsFromGist();
        });
    });

    fetchCardsFromGist();
    setInterval(fetchCardsFromGist, 3000); // 3-second polling interval
});

async function fetchCardsFromGist() {
    try {
        // Use raw CDN link with timestamp bypass to avoid GitHub API Rate Limit (403 Limit Exceeded)
        const res = await fetch(`${RAW_GIST_URL}?t=${Date.now()}`);
        if (!res.ok) {
            console.warn("Raw fetch HTTP error: ", res.status);
            return;
        }
        const fileContent = await res.text();

        if (fileContent) {
            let rawCards = [];
            try {
                rawCards = JSON.parse(fileContent);
            } catch (parseErr) {
                console.error("JSON parse error: ", parseErr);
                rawCards = [];
            }
            const computedCards = computeCardsState(rawCards);
            renderDashboard(computedCards);
        } else {
            renderDashboard([]);
        }
    } catch (err) {
        console.error('Failed to fetch from Raw Gist CDN:', err);
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

    document.getElementById('cardCounter').textContent = `${cards.length} 张卡片`;
    document.getElementById('countRunning').textContent = running;
    document.getElementById('countPaused').textContent = paused;
    document.getElementById('countStopped').textContent = stopped;
    document.getElementById('countExpired').textContent = expired;

    // Filter cards
    const filtered = cards.filter(c => {
        if (currentFilter === 'running') return c.status === 1 && !c.isOverdue;
        if (currentFilter === 'expired') return c.isOverdue || c.status === 3;
        return true;
    });

    const grid = document.getElementById('cardsGrid');
    if (filtered.length === 0) {
        grid.innerHTML = `
            <div class="empty-cute-view">
                <div class="empty-cute-emoji">🐾</div>
                <p>暂时没有卡片在计时中哦~<br>快去手机端刷卡试试吧！</p>
            </div>
        `;
        return;
    }

    grid.innerHTML = filtered.map(card => {
        let isExpiredMode = card.isOverdue || card.status === 3;
        let badgeClass = 'badge-gray';
        let statusText = '💤 还没开始';
        let timeColorClass = '';

        if (isExpiredMode) {
            badgeClass = 'badge-pink-red';
            statusText = '⏰ 已超时';
            timeColorClass = 'text-red';
        } else if (card.status === 1) {
            badgeClass = 'badge-mint';
            statusText = '🏃 进行中';
            timeColorClass = 'text-mint';
        } else if (card.status === 2) {
            badgeClass = 'badge-yellow';
            statusText = '☕ 歇一歇';
            timeColorClass = 'text-yellow';
        }

        const remainingSec = card.remainingSeconds;
        const targetSec = card.targetDurationSeconds;
        let pct = 100;
        if (targetSec > 0) {
            pct = Math.max(0, Math.min(100, (remainingSec / targetSec) * 100));
        }

        let timeHtml = '';
        if (isExpiredMode) {
            timeHtml = `
                <div class="cute-time-val text-red">00:00</div>
                <div class="overdue-cute-alert">🚨 超时小长跑: ${formatTime(card.overdueSeconds)}</div>
            `;
        } else {
            timeHtml = `
                <div class="cute-time-val ${timeColorClass}">
                    ${formatTime(remainingSec)}
                </div>
            `;
        }

        return `
            <div class="cute-card-item ${isExpiredMode ? 'card-expired-theme' : ''}">
                <div class="card-top-bar">
                    <div>
                        <div class="card-title-text">${escapeHtml(card.name)}</div>
                        <div class="card-uid-sub">UID: ${escapeHtml(card.cardId)}</div>
                    </div>
                    <span class="pill-badge ${badgeClass}">${statusText}</span>
                </div>

                <div class="timer-cute-box">
                    ${timeHtml}
                </div>

                <div class="cute-progress-bar">
                    <div class="cute-progress-fill" style="width: ${isExpiredMode ? 0 : pct}%; ${card.status === 2 ? 'background: #fbbd23' : ''}"></div>
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
