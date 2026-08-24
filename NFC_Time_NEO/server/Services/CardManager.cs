using System.Collections.Concurrent;
using System.Text.Json;
using NfcServer.Models;

namespace NfcServer.Services;

public class CardManager
{
    private readonly ConcurrentDictionary<string, CardInfo> _cards = new(StringComparer.OrdinalIgnoreCase);
    private readonly string _dataFilePath;
    private readonly object _fileLock = new();

    public CardManager(IWebHostEnvironment env)
    {
        var dataDir = Path.Combine(env.ContentRootPath, "data");
        if (!Directory.Exists(dataDir)) Directory.CreateDirectory(dataDir);
        _dataFilePath = Path.Combine(dataDir, "cards.json");
        LoadFromDisk();
    }

    private void LoadFromDisk()
    {
        lock (_fileLock)
        {
            if (!File.Exists(_dataFilePath)) return;
            try
            {
                var json = File.ReadAllText(_dataFilePath);
                var list = JsonSerializer.Deserialize(json, AppJsonSerializerContext.Default.ListCardInfo);
                if (list != null)
                {
                    _cards.Clear();
                    foreach (var card in list)
                    {
                        if (!string.IsNullOrWhiteSpace(card.CardId))
                        {
                            _cards[card.CardId] = card;
                        }
                    }
                    AppLogger.Info("CARD_STORE", $"成功从 cards.json 加载 {_cards.Count} 张卡片档案");
                }
            }
            catch (Exception ex)
            {
                AppLogger.Error("CARD_STORE", "加载 cards.json 失败", ex);
            }
        }
    }

    private void SaveToDisk()
    {
        lock (_fileLock)
        {
            try
            {
                var list = _cards.Values.ToList();
                var json = JsonSerializer.Serialize(list, AppJsonSerializerContext.Default.ListCardInfo);
                File.WriteAllText(_dataFilePath, json);
            }
            catch (Exception ex)
            {
                AppLogger.Error("CARD_STORE", "保存 cards.json 失败", ex);
            }
        }
    }

    public IEnumerable<CardInfo> GetAllCards()
    {
        var cards = _cards.Values.ToList();
        foreach (var card in cards)
        {
            if (card.TimerMode == 0 && card.Status == CardStatus.Running && card.RemainingSeconds <= 0)
            {
                card.Status = CardStatus.Expired;
            }

            if (card.Status != CardStatus.Stopped)
            {
                card.Pricing = PriceCalculator.ComputeCardPricing(card);
            }
            else
            {
                card.Pricing = null;
            }
        }

        return cards.OrderByDescending(c => c.LastSwipeTimeUtc ?? DateTime.MinValue);
    }

    /// <summary>
    /// 仅获取正在制作/计时中的活跃卡片 (Status != Stopped)，大幅减少网络开销
    /// </summary>
    public IEnumerable<CardInfo> GetActiveCards()
    {
        var cards = _cards.Values.Where(c => c.Status != CardStatus.Stopped).ToList();
        foreach (var card in cards)
        {
            if (card.TimerMode == 0 && card.Status == CardStatus.Running && card.RemainingSeconds <= 0)
            {
                card.Status = CardStatus.Expired;
            }
            card.Pricing = PriceCalculator.ComputeCardPricing(card);
        }

        return cards.OrderByDescending(c => c.LastSwipeTimeUtc ?? DateTime.MinValue);
    }

    /// <summary>
    /// 增量同步：仅返回自 sinceUtc 以来有修改更新的卡片，无变更则返回空列表 (0KB)
    /// </summary>
    public IEnumerable<CardInfo> GetDeltaCards(DateTime sinceUtc)
    {
        var cards = _cards.Values.Where(c => c.UpdatedAtUtc > sinceUtc).ToList();
        foreach (var card in cards)
        {
            if (card.TimerMode == 0 && card.Status == CardStatus.Running && card.RemainingSeconds <= 0)
            {
                card.Status = CardStatus.Expired;
            }
            if (card.Status != CardStatus.Stopped)
            {
                card.Pricing = PriceCalculator.ComputeCardPricing(card);
            }
            else
            {
                card.Pricing = null;
            }
        }
        return cards;
    }

    /// <summary>
    /// 仅获取未使用卡片 (Status == Stopped)，仅在打开档案库页面时按需加载
    /// </summary>
    public IEnumerable<CardInfo> GetUnusedCards()
    {
        return _cards.Values
            .Where(c => c.Status == CardStatus.Stopped)
            .OrderByDescending(c => c.UpdatedAtUtc);
    }

    public CardInfo? GetCard(string cardId)
    {
        if (_cards.TryGetValue(cardId, out var card))
        {
            if (card.TimerMode == 0 && card.Status == CardStatus.Running && card.RemainingSeconds <= 0)
            {
                card.Status = CardStatus.Expired;
            }
            if (card.Status != CardStatus.Stopped)
            {
                card.Pricing = PriceCalculator.ComputeCardPricing(card);
            }
            return card;
        }
        return null;
    }

    public CardInfo HandleSwipe(string cardId, string? cardName = null)
    {
        var now = DateTime.UtcNow;
        var card = _cards.GetOrAdd(cardId, id => new CardInfo
        {
            CardId = id,
            Name = !string.IsNullOrWhiteSpace(cardName) ? cardName : $"卡片_{id}",
            LastSwipeTimeUtc = now,
            Status = CardStatus.Stopped,
            UpdatedAtUtc = now
        });

        card.LastSwipeTimeUtc = now;
        card.UpdatedAtUtc = now;
        if (!string.IsNullOrWhiteSpace(cardName))
        {
            card.Name = cardName;
        }

        SaveToDisk();
        return card;
    }

    public CardInfo? SetTimer(string cardId, SetTimerRequest req)
    {
        if (!_cards.TryGetValue(cardId, out var card)) return null;

        var action = req.Action?.ToLowerInvariant() ?? "start";
        var now = DateTime.UtcNow;
        card.UpdatedAtUtc = now;

        switch (action)
        {
            case "set_and_start":
            case "start":
            {
                // 全新的一轮计时：重置历史状态
                card.TimerMode = req.TimerMode;
                card.IsPostPay = req.IsPostPay;
                card.PresetPlan = req.PresetPlan ?? "none";
                card.UseDouban = req.UseDouban;
                card.DoubanPlan = req.DoubanPlan ?? "hourly";

                // 开始时间：如果指定了自定义开始时间则使用，否则为当前时间
                var start = req.CustomStartTimeUtc ?? now;
                card.StartTimeUtc = start;

                if (card.UseDouban)
                {
                    card.DoubanStartTimeUtc = req.CustomDoubanStartTimeUtc ?? start;
                    card.DoubanSavedSeconds = 0;
                }
                else
                {
                    card.DoubanStartTimeUtc = null;
                    card.DoubanSavedSeconds = 0;
                }

                if (card.TimerMode == 1)
                {
                    // 正计时 (先玩后付)
                    card.TargetDurationSeconds = 0;
                    card.SavedRemainingSeconds = 0;
                    card.Status = CardStatus.Running;
                }
                else
                {
                    // 倒计时
                    int dur = req.DurationSeconds > 0 ? req.DurationSeconds : 3600;
                    card.TargetDurationSeconds = dur;
                    card.SavedRemainingSeconds = dur;
                    card.Status = CardStatus.Running;
                }
                break;
            }

            case "pause":
            {
                if (card.Status == CardStatus.Running)
                {
                    if (card.TimerMode == 1)
                    {
                        card.SavedRemainingSeconds = (int)Math.Max(0, Math.Round(card.RemainingSeconds));
                    }
                    else
                    {
                        card.SavedRemainingSeconds = (int)Math.Round(card.RemainingSeconds);
                    }

                    if (card.UseDouban)
                    {
                        card.DoubanSavedSeconds = (int)Math.Round(card.DoubanElapsedSeconds);
                        card.DoubanStartTimeUtc = null;
                    }

                    card.Status = CardStatus.Paused;
                    card.StartTimeUtc = null;
                }
                break;
            }

            case "resume":
            {
                if (card.Status == CardStatus.Paused)
                {
                    card.StartTimeUtc = now;
                    if (card.UseDouban)
                    {
                        card.DoubanStartTimeUtc = now;
                    }
                    card.Status = CardStatus.Running;
                }
                break;
            }

            case "stop":
            case "reset":
            {
                // 停止计时：完全重置数据，为下一次全新计时做准备
                card.Status = CardStatus.Stopped;
                card.StartTimeUtc = null;
                card.DoubanStartTimeUtc = null;
                card.SavedRemainingSeconds = 0;
                card.DoubanSavedSeconds = 0;
                card.TargetDurationSeconds = 0;
                break;
            }
        }

        card.Pricing = card.Status != CardStatus.Stopped ? PriceCalculator.ComputeCardPricing(card) : null;
        SaveToDisk();
        return card;
    }

    public CardInfo? UpdateCardConfig(string cardId, UpdateCardConfigRequest req)
    {
        if (!_cards.TryGetValue(cardId, out var card)) return null;

        var now = DateTime.UtcNow;
        card.UpdatedAtUtc = now;

        if (!string.IsNullOrWhiteSpace(req.Name)) card.Name = req.Name.Trim();
        if (req.Remark != null) card.Remark = req.Remark.Trim();
        if (req.IsPostPay.HasValue) card.IsPostPay = req.IsPostPay.Value;
        if (req.PresetPlan != null) card.PresetPlan = req.PresetPlan;

        // 智能豆板更新（支持中途开启）
        if (req.UseDouban.HasValue)
        {
            if (req.UseDouban.Value && !card.UseDouban)
            {
                // 中途开启智能豆板
                card.UseDouban = true;
                card.DoubanStartTimeUtc = req.DoubanStartTimeUtc ?? now;
                card.DoubanSavedSeconds = 0;
                if (!string.IsNullOrWhiteSpace(req.DoubanPlan)) card.DoubanPlan = req.DoubanPlan;
            }
            else if (!req.UseDouban.Value && card.UseDouban)
            {
                // 中途关闭智能豆板
                card.UseDouban = false;
                card.DoubanStartTimeUtc = null;
                card.DoubanSavedSeconds = 0;
                card.DoubanPlan = "hourly";
            }
        }

        if (!string.IsNullOrWhiteSpace(req.DoubanPlan))
        {
            card.DoubanPlan = req.DoubanPlan;
        }

        // 修改游玩开始时间
        if (req.StartTimeUtc.HasValue && card.Status == CardStatus.Running)
        {
            card.StartTimeUtc = req.StartTimeUtc.Value;
        }

        // 修改智能豆板开始时间
        if (req.DoubanStartTimeUtc.HasValue && card.UseDouban && card.Status == CardStatus.Running)
        {
            card.DoubanStartTimeUtc = req.DoubanStartTimeUtc.Value;
        }

        if (card.Status != CardStatus.Stopped)
        {
            card.Pricing = PriceCalculator.ComputeCardPricing(card);
        }

        SaveToDisk();
        return card;
    }

    public CardInfo? AddTime(string cardId, int addSeconds)
    {
        if (!_cards.TryGetValue(cardId, out var card)) return null;

        card.TargetDurationSeconds += addSeconds;
        card.SavedRemainingSeconds += addSeconds;
        card.UpdatedAtUtc = DateTime.UtcNow;

        if (card.Status == CardStatus.Expired && card.RemainingSeconds > 0)
        {
            card.Status = CardStatus.Running;
        }

        card.Pricing = PriceCalculator.ComputeCardPricing(card);
        SaveToDisk();
        return card;
    }

    public CardInfo? ResetToUnused(string cardId)
    {
        if (!_cards.TryGetValue(cardId, out var card)) return null;

        card.Status = CardStatus.Stopped;
        card.StartTimeUtc = null;
        card.DoubanStartTimeUtc = null;
        card.SavedRemainingSeconds = 0;
        card.DoubanSavedSeconds = 0;
        card.TargetDurationSeconds = 0;
        card.UseDouban = false;
        card.PresetPlan = "none";
        card.IsPostPay = false;
        card.UpdatedAtUtc = DateTime.UtcNow;
        card.Pricing = null;

        SaveToDisk();
        return card;
    }

    public bool DeleteCard(string cardId)
    {
        var removed = _cards.TryRemove(cardId, out _);
        if (removed)
        {
            SaveToDisk();
        }
        return removed;
    }
}
