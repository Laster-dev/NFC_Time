let currentFilter = 'all';
let allCardsData = [];

// 默认直接走远程服务器 API
const REMOTE_API_SERVER = "http://43.140.218.3:5000";

function getApiUrl() {
    if (window.location.port === '5000') {
        return `/api/cards?t=${Date.now()}`;
    }
    return `${REMOTE_API_SERVER}/api/cards?t=${Date.now()}`;
}

document.addEventListener('DOMContentLoaded', () => {
    // Clock
    setInterval(updateLiveClock, 1000);
    updateLiveClock();

    // Tab buttons for Card filtering
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

    // Auto load initial NetEase music playlist
    loadNetEasePlaylist('3778678', document.getElementById('pill-3778678'));
});

function updateLiveClock() {
    const now = new Date();
    const pad = n => n.toString().padStart(2, '0');
    const str = `${pad(now.getHours())}:${pad(now.getMinutes())}:${pad(now.getSeconds())}`;
    const el = document.getElementById('liveClock');
    if (el) el.textContent = str;
}

// ==========================================
// 📱 Tab 页面切换 (计时 / 点歌 / 智能豆板)
// ==========================================
function switchTab(tabId, clickedBtn) {
    // 隐藏所有 Tab 页面
    document.querySelectorAll('.tab-section').forEach(sec => {
        sec.style.display = 'none';
    });
    // 显示选中的 Tab 页面
    const target = document.getElementById(tabId);
    if (target) target.style.display = 'flex';

    // 激活底部导航栏样式
    document.querySelectorAll('.nav-item').forEach(btn => {
        btn.classList.remove('active');
    });
    if (clickedBtn) {
        clickedBtn.classList.add('active');
    }
    window.scrollTo({ top: 0, behavior: 'smooth' });
}

// ==========================================
// ⏱️ 计时卡片数据拉取与渲染
// ==========================================
async function fetchCards() {
    const url = getApiUrl();
    try {
        const res = await fetch(url, { mode: 'cors' });
        if (!res.ok) return;
        const cards = await res.json();
        allCardsData = cards || [];
        renderCards(allCardsData);
    } catch (err) {
        console.warn("Polling cards update:", err);
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
        case '1h': return '1小时套餐 (¥12.9)';
        case '3h': return '3小时套餐 (¥29.9)';
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

// ==========================================
// 🎵 点歌台逻辑 (网易云音乐 + ntfy.sh)
// ==========================================
let currentLoadedSongs = [];
let currentPlaylistId = '3778678';

const METING_MIRRORS = [
    (type, id) => `https://api.injahow.cn/meting/?type=${type}&id=${id}`,
    (type, id) => `https://meting.005.cx/api?server=netease&type=${type}&id=${id}`,
    (type, id) => `https://api.i-meto.com/meting/api?server=netease&type=${type}&id=${id}`
];

async function fetchNetEaseMeting(type, id) {
    for (const mirrorFn of METING_MIRRORS) {
        try {
            const url = mirrorFn(type, encodeURIComponent(id));
            const controller = new AbortController();
            const timeoutId = setTimeout(() => controller.abort(), 4500);

            const res = await fetch(url, { signal: controller.signal });
            clearTimeout(timeoutId);

            if (!res.ok) continue;
            const data = await res.json();

            if (Array.isArray(data) && data.length > 0) {
                return data.map(s => ({
                    id: s.id || s.songid || s.mid || s.url_id || '',
                    name: s.name || s.title || s.song || '未知歌曲',
                    artist: s.artist || s.author || s.singer || (Array.isArray(s.artists) ? s.artists.map(a=>a.name).join('/') : '未知歌手'),
                    pic: s.pic || s.cover || s.album_pic || ''
                }));
            }
        } catch (e) {
            // failover
        }
    }
    return null;
}

async function loadNetEasePlaylist(playlistId, pillElement) {
    currentPlaylistId = playlistId;
    if (pillElement) {
        document.querySelectorAll('.playlist-pill').forEach(p => p.classList.remove('active'));
        pillElement.classList.add('active');
    }

    const listArea = document.getElementById('musicSearchResultArea');
    const label = document.getElementById('musicListLabel');

    if (listArea) {
        listArea.innerHTML = `<div style="text-align: center; padding: 24px; color: var(--text-sub); font-size: 0.78rem;"><span>⏳ 正在加载精选歌单...</span></div>`;
    }
    if (label) label.textContent = '📜 网易云推荐曲目 (点击即可一键点歌)：';

    try {
        const songs = await fetchNetEaseMeting('playlist', playlistId);
        if (songs && songs.length > 0) {
            currentLoadedSongs = songs;
            renderNetEaseSongs(songs);
        } else {
            if (listArea) listArea.innerHTML = `<div style="text-align: center; padding: 20px; color: var(--text-sub); font-size: 0.78rem;">⚠️ 歌单加载繁忙，您可以直接在上方输入歌名点歌~</div>`;
        }
    } catch (err) {
        if (listArea) listArea.innerHTML = `<div style="text-align: center; padding: 20px; color: #BE123C; font-size: 0.78rem;">❌ 歌单加载失败，请直接输入歌名点歌~</div>`;
    }
}

async function searchNetEaseMusic() {
    const input = document.getElementById('musicSearchInput');
    const listArea = document.getElementById('musicSearchResultArea');
    const label = document.getElementById('musicListLabel');
    let val = (input.value || '').trim();

    if (!val) {
        showMusicToast('⚠️ 请输入歌曲名称、歌手或网易云链接~', '#BE123C', '#FFF1F2', '#FDA4AF');
        input.focus();
        return;
    }

    if (listArea) listArea.innerHTML = `<div style="text-align: center; padding: 24px; color: var(--text-sub); font-size: 0.78rem;"><span>🔍 正在搜索网易云音乐曲库...</span></div>`;
    if (label) label.textContent = `🔍 搜索 “${val}” 的结果：`;

    try {
        const songs = await fetchNetEaseMeting('search', val);
        if (songs && songs.length > 0) {
            currentLoadedSongs = songs;
            renderNetEaseSongs(songs);
        } else {
            if (listArea) {
                listArea.innerHTML = `
                    <div style="text-align: center; padding: 18px; color: var(--text-sub); font-size: 0.78rem;">
                        <p style="margin-bottom: 8px;">未在曲库找到完全匹配的结果，您可以直接点歌：</p>
                        <button type="button" class="music-pick-btn" style="padding: 6px 14px; font-size: 0.75rem;" onclick="selectSong('', '${escapeHtml(val)}', '未指定', '')">👉 点歌《${escapeHtml(val)}》</button>
                    </div>
                `;
            }
        }
    } catch (err) {
        if (listArea) {
            listArea.innerHTML = `
                <div style="text-align: center; padding: 18px; color: var(--text-sub); font-size: 0.78rem;">
                    <p style="margin-bottom: 8px;">搜索服务暂忙，您可以直接点这首歌：</p>
                    <button type="button" class="music-pick-btn" style="padding: 6px 14px; font-size: 0.75rem;" onclick="selectSong('', '${escapeHtml(val)}', '未指定', '')">👉 点歌《${escapeHtml(val)}》</button>
                </div>
            `;
        }
    }
}

function renderNetEaseSongs(songs) {
    const listArea = document.getElementById('musicSearchResultArea');
    if (!listArea) return;

    if (!songs || songs.length === 0) {
        listArea.innerHTML = `<div style="text-align: center; padding: 20px; color: var(--text-sub); font-size: 0.78rem;">暂无歌曲数据</div>`;
        return;
    }

    listArea.innerHTML = songs.map(s => {
        const songId = s.id || '';
        const songNameEsc = escapeHtml(s.name);
        const artistEsc = escapeHtml(s.artist || '未知歌手');
        const picUrl = s.pic || '';

        return `
            <div class="music-item-row">
                <div class="music-item-left">
                    ${picUrl ? `<img src="${picUrl}" class="music-item-pic" alt="cover" onerror="this.style.display='none'">` : `<div class="music-item-pic" style="display:flex;align-items:center;justify-content:center;font-size:16px;">🎵</div>`}
                    <div class="music-item-meta">
                        <span class="music-item-name" title="${songNameEsc}">${songNameEsc}</span>
                        <span class="music-item-artist" title="${artistEsc}">${artistEsc}</span>
                    </div>
                </div>
                <button type="button" class="music-pick-btn" onclick="selectSong('${songId}', '${songNameEsc}', '${artistEsc}', '${picUrl}')">点这首 🐾</button>
            </div>
        `;
    }).join('');
}

function selectSong(id, name, artist, pic) {
    const songIdInput = document.getElementById('musicSongId');
    const songNameInput = document.getElementById('musicSongName');
    const artistInput = document.getElementById('musicArtist');
    const selectedBox = document.getElementById('selectedSongBox');
    const dispName = document.getElementById('dispSelectedSong');
    const dispArtist = document.getElementById('dispSelectedArtist');

    if (songIdInput) songIdInput.value = id || '';
    if (songNameInput) songNameInput.value = name;
    if (artistInput) artistInput.value = artist;

    if (dispName) dispName.textContent = `🎵 已选：${name}`;
    if (dispArtist) dispArtist.textContent = `🎤 歌手：${artist || '未指定'}`;
    if (selectedBox) selectedBox.style.display = 'block';

    submitSongRequest();
}

async function submitSongRequest() {
    const songId = (document.getElementById('musicSongId')?.value || '').trim();
    const songName = (document.getElementById('musicSongName')?.value || '').trim();
    const submitBtn = document.getElementById('btnSubmitSong');
    const submitBtnText = document.getElementById('btnSubmitSongText');

    if (!songId && !songName) {
        showMusicToast('⚠️ 请先在上方列表中点击「点这首」选择一首歌曲哦~', '#BE123C', '#FFF1F2', '#FDA4AF');
        return;
    }

    const lastSent = localStorage.getItem('ntfy_last_song_sent');
    const now = Date.now();
    if (lastSent && (now - parseInt(lastSent)) < 2000) {
        const waitSec = Math.ceil((2000 - (now - parseInt(lastSent))) / 1000);
        showMusicToast(`⏳ 点歌太快啦，请等待 ${waitSec} 秒后再点下一首哦~`, '#D97706', '#FEF3C7', '#FCD34D');
        return;
    }

    if (submitBtn) submitBtn.disabled = true;
    if (submitBtnText) submitBtnText.textContent = '正在点歌...';

    const topic = 'XingYeShouZuo';
    const messageBody = songId ? `${songId}` : `${songName}`;

    try {
        const response = await fetch('https://ntfy.sh', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                topic: topic,
                title: `🎵 点歌: ${songName}`,
                message: messageBody,
                tags: ['musical_note', 'sparkles'],
                priority: 4,
                click: songId ? `https://music.163.com/song?id=${songId}` : undefined
            })
        });

        if (response.ok) {
            localStorage.setItem('ntfy_last_song_sent', Date.now().toString());
            showMusicToast(`🎉 点歌成功！《${songName}》已加入播放列表，当前歌曲播放完毕后将为您播放~ 🐱`, '#15803D', '#F0FDF4', '#86EFAC');
            
            let countdown = 2;
            const timer = setInterval(() => {
                countdown--;
                if (countdown > 0) {
                    if (submitBtnText) submitBtnText.textContent = `冷却中 (${countdown}s)`;
                } else {
                    clearInterval(timer);
                    if (submitBtn) submitBtn.disabled = false;
                    if (submitBtnText) submitBtnText.textContent = '再次点歌 🐾';
                }
            }, 1000);
        } else {
            throw new Error(`Status: ${response.status}`);
        }
    } catch (err) {
        console.error('ntfy push failed:', err);
        if (submitBtn) submitBtn.disabled = false;
        if (submitBtnText) submitBtnText.textContent = '立即点歌 🐾';
        showMusicToast('❌ 点歌失败，请检查网络连接后重试~', '#BE123C', '#FFF1F2', '#FDA4AF');
    }
}

function showMusicToast(text, color, bg, border) {
    const toast = document.getElementById('musicToast');
    if (!toast) return;
    toast.style.display = 'block';
    toast.style.color = color;
    toast.style.background = bg;
    toast.style.borderColor = border;
    toast.textContent = text;
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/[&<>"']/g, function(m) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }[m];
    });
}
