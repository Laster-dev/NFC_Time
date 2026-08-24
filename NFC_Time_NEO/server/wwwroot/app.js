let currentFilter = 'all';
let allCardsData = [];

const DEFAULT_REMOTE_API = "http://43.140.218.3:5000";

function getApiBaseUrl() {
    const saved = localStorage.getItem('nfc_server_api_url');
    if (saved) return saved.replace(/\/+$/, '');
    
    // 如果直接从服务端部署的 Web 端口访问，使用相对路径
    if (window.location.port === '5000') {
        return '';
    }
    // 如果在 GitHub Pages、客户端文件或外部域名下打开，默认连接云端服务器
    return DEFAULT_REMOTE_API;
}

document.addEventListener('DOMContentLoaded', () => {
    // Clock
    setInterval(updateLiveClock, 1000);
    updateLiveClock();

    // Tab buttons
    const tabBtns = document.querySelectorAll('.tab-pill');
    tabBtns.forEach(btn => {
        btn.addEventListener('click', (e) => {
            tabBtns.forEach(b => b.classList.remove('active'));
            const target = e.currentTarget;
            target.classList.add('active');
            currentFilter = target.getAttribute('data-filter') || 'all';
            renderCards(allCardsData);
        });
    });

    fetchCards();
    setInterval(fetchCards, 1200); // 1.2s 实时轮询
});

function updateLiveClock() {
    const now = new Date();
    const pad = n => n.toString().padStart(2, '0');
    const str = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
    const el = document.getElementById('liveClock');
    if (el) el.textContent = str;
}

function updateServerStatusBadge(online, host) {
    const btn = document.getElementById('btnServerStatus');
    const txt = document.getElementById('serverStatusText');
    if (!btn || !txt) return;

    if (online) {
        btn.className = 'server-status-btn status-online';
        txt.textContent = `🟢 ${host}`;
    } else {
        btn.className = 'server-status-btn status-offline';
        txt.textContent = `🔴 离线 (点击切换)`;
    }
}

async function fetchCards() {
    const baseUrl = getApiBaseUrl();
    const host = baseUrl ? baseUrl.replace(/^https?:\/\//, '') : '本地服务';
    const url = `${baseUrl}/api/cards?t=${Date.now()}`;

    try {
        const res = await fetch(url, { mode: 'cors' });
        if (!res.ok) {
            updateServerStatusBadge(false, host);
            return;
        }
        const cards = await res.json();
        allCardsData = cards || [];
        updateServerStatusBadge(true, host);
        renderCards(allCardsData);
    } catch (err) {
        updateServerStatusBadge(false, host);
    }
}

function formatDuration(totalSeconds) {
    const isNegative = totalSeconds < 0;
    const abs = Math.abs(Math.floor(totalSeconds));
    const h = Math.floor(abs / 3600);
    const m = Math.floor((abs % 3600) / 60);
    const s = abs % 60;
    const pad = n => n.toString().padStart(2, '0');

    let str = `${pad(m)}:${pad(s)}`;
    if (h > 0) str = `${pad(h)}:` + str;
    return isNegative ? `-${str}` : str;
}

function formatPresetPlan(plan) {
    switch (plan?.toLowerCase()) {
        case '1h': return '1小时 (¥12.9)';
        case '3h': return '3小时 (¥29.9)';
        default: return '无预设 (按时结算)';
    }
}

function formatTimeOnly(isoStr) {
    if (!isoStr) return '--:--:--';
    try {
        const d = new Date(isoStr);
        const pad = n => n.toString().padStart(2, '0');
        return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
    } catch {
        return '--:--:--';
    }
}

function renderCards(cards) {
    let countRunning = 0;
    let countPostpay = 0;
    let countDouban = 0;
    let countExpired = 0;

    cards.forEach(c => {
        if (c.status === 1 || c.status === 2 || c.status === 3) {
            if (c.status === 1) countRunning++;
            if (c.isPostPay) countPostpay++;
            if (c.useDouban) countDouban++;
            if (c.isOverdue || c.status === 3) countExpired++;
        }
    });

    const activeCards = cards.filter(c => c.status !== 0);

    const elCountAll = document.getElementById('countAll');
    if (elCountAll) elCountAll.textContent = activeCards.length;
    const elCountRunning = document.getElementById('countRunning');
    if (elCountRunning) elCountRunning.textContent = countRunning;
    const elCountPostpay = document.getElementById('countPostpay');
    if (elCountPostpay) elCountPostpay.textContent = countPostpay;
    const elCountDouban = document.getElementById('countDouban');
    if (elCountDouban) elCountDouban.textContent = countDouban;
    const elCountExpired = document.getElementById('countExpired');
    if (elCountExpired) elCountExpired.textContent = countExpired;

    // Filter
    const filtered = activeCards.filter(c => {
        if (currentFilter === 'running') return c.status === 1 && !c.isOverdue;
        if (currentFilter === 'postpay') return c.isPostPay;
        if (currentFilter === 'douban') return c.useDouban;
        if (currentFilter === 'expired') return c.isOverdue || c.status === 3;
        return true;
    });

    const grid = document.getElementById('cardsGrid');
    if (!grid) return;

    if (filtered.length === 0) {
        grid.innerHTML = `
            <div class="empty-cute-box">
                <div class="empty-mascot">🌙</div>
                <p>当前暂无制作中的手作卡片~<br>欢迎贴卡开启新的手作时光 ✨</p>
            </div>
        `;
        return;
    }

    grid.innerHTML = filtered.map(card => {
        const isOverdue = card.isOverdue || card.status === 3;
        let statusBadgeClass = 'badge-mint';
        let statusText = '🌟 制作中';

        if (card.status === 2) {
            statusBadgeClass = 'badge-amber';
            statusText = '🍵 憩息中';
        } else if (isOverdue) {
            statusBadgeClass = 'badge-rose';
            statusText = '🚨 已超时';
        } else if (card.timerMode === 1) {
            statusBadgeClass = 'badge-blue';
            statusText = '⏱️ 正计时';
        }

        // Time display
        let timeLabel = card.timerMode === 1 ? '⏱️ 已玩时长 (正计时)' : (isOverdue ? '⚠️ 超时时长' : '⏳ 剩余时间');
        let timeVal = card.timerMode === 1 ? formatDuration(card.elapsedSeconds) : (isOverdue ? formatDuration(card.overdueSeconds) : formatDuration(card.remainingSeconds));
        let timeColorClass = card.timerMode === 1 ? 'text-blue' : (isOverdue ? 'text-rose' : (card.status === 2 ? 'text-amber' : 'text-mint'));

        // Tags
        const doubanFee = card.pricing ? card.pricing.doubanFee : 0;
        const doubanTag = card.useDouban
            ? `<span class="tag-badge tag-douban-on">📟 智能豆板: 进行中 (已用 ${formatDuration(card.doubanElapsedSeconds)} · 豆板费 ¥${doubanFee.toFixed(1)})</span>`
            : `<span class="tag-badge tag-douban-off">📟 智能豆板: 未使用</span>`;

        const paymentTag = card.isPostPay
            ? `<span class="tag-badge tag-unpaid">⚠️ 未付款 (玩完再付)</span>`
            : `<span class="tag-badge tag-paid">✅ 已付款 (先付款)</span>`;

        const presetTag = `<span class="tag-badge tag-preset">📦 预设套餐: ${formatPresetPlan(card.presetPlan)}</span>`;
        const remarkTag = card.remark ? `<span class="tag-badge" style="background:#F1F5F9; color:#475569;">📝 ${escapeHtml(card.remark)}</span>` : '';

        // Overdue strip
        const overdueStripHtml = isOverdue
            ? `<div class="overdue-strip">🚨 已超时: ${formatDuration(card.overdueSeconds)} (超时加时标准: 1h ¥15, 半小时 ¥8)</div>`
            : '';

        // Pricing breakdown
        let pricingHtml = '';
        if (card.pricing) {
            const p = card.pricing;
            const items = (p.breakdownItems || []).map(it => `<div class="pricing-item-text">${escapeHtml(it)}</div>`).join('');
            const isPrepaid = !card.isPostPay && card.presetPlan && card.presetPlan !== 'none';
            const priceTitle = isPrepaid ? (p.needToPay > 0 ? '💰 需补收加时/豆板差价:' : '💰 已预付套餐金额:') : '💰 实时最优预估结算价:';
            const priceDisplay = isPrepaid ? (p.needToPay > 0 ? `需补收 ¥${p.needToPay.toFixed(1)}` : `¥${p.playFee.toFixed(1)}`) : `¥${p.totalPrice.toFixed(1)}`;
            const priceColor = isPrepaid && p.needToPay > 0 ? '#DC2626' : (card.isPostPay ? '#D97706' : '#059669');

            pricingHtml = `
                <div class="pricing-breakdown-box">
                    <div class="pricing-header-row">
                        <div>
                            <span class="pricing-plan-badge">${escapeHtml(p.bestPlanName || '结算方案')}</span>
                            <span style="font-size:0.75rem; color:${priceColor}; font-weight:800; margin-left:4px;">${priceTitle}</span>
                        </div>
                        <div class="pricing-total-price" style="color:${priceColor};">${priceDisplay}</div>
                    </div>
                    ${p.formula ? `<div class="pricing-formula">📐 计算说明: ${escapeHtml(p.formula)}</div>` : ''}
                    <div class="pricing-items-list">${items}</div>
                </div>
            `;
        }

        return `
            <div class="cute-card ${isOverdue ? 'card-overdue' : ''}">
                <div class="card-top">
                    <div class="card-title-area">
                        <div class="card-name">${escapeHtml(card.name)}</div>
                        <div class="card-uid">UID: ${escapeHtml(card.cardId)}</div>
                    </div>
                    <span class="status-badge ${statusBadgeClass}">${statusText}</span>
                </div>

                <div class="tags-row">
                    ${doubanTag}
                    ${paymentTag}
                    ${presetTag}
                    ${remarkTag}
                </div>

                <div class="time-display-area">
                    <div class="time-left-block">
                        <span class="time-label">${timeLabel}</span>
                        <span class="time-value ${timeColorClass}">${timeVal}</span>
                    </div>
                    <div class="time-right-block">
                        <div>🕒 开始时间: ${formatTimeOnly(card.startTimeUtc)}</div>
                        ${card.useDouban ? `<div>📟 豆板开始: ${formatTimeOnly(card.doubanStartTimeUtc)}</div>` : ''}
                    </div>
                </div>

                ${overdueStripHtml}

                ${pricingHtml}
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

// Modal controls
function openServerModal() {
    document.getElementById('modalApiInput').value = getApiBaseUrl() || DEFAULT_REMOTE_API;
    document.getElementById('serverModal').style.display = 'flex';
}

function closeServerModal() {
    document.getElementById('serverModal').style.display = 'none';
}

function setModalUrl(url) {
    document.getElementById('modalApiInput').value = url;
}

function saveServerModal() {
    const val = document.getElementById('modalApiInput').value.trim();
    if (val) {
        localStorage.setItem('nfc_server_api_url', val);
    } else {
        localStorage.removeItem('nfc_server_api_url');
    }
    closeServerModal();
    fetchCards();
}
