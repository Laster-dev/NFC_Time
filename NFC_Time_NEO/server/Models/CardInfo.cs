using System.Text.Json.Serialization;

namespace NfcServer.Models;

public enum CardStatus
{
    Stopped = 0,
    Running = 1,
    Paused = 2,
    Expired = 3
}

public class CardInfo
{
    public required string CardId { get; set; }
    public string Name { get; set; } = string.Empty;
    public CardStatus Status { get; set; } = CardStatus.Stopped;
    public int TimerMode { get; set; } = 0; // 0: Countdown, 1: Countup (先玩后付)
    
    // 客户属性
    public bool IsPostPay { get; set; } = false; // 是否后付款（玩完再付）
    public string PresetPlan { get; set; } = "none"; // none, 1h, 3h
    
    // 智能豆板属性 (支持中途开启使用)
    public bool UseDouban { get; set; } = false; // 是否使用智能豆板
    public DateTime? DoubanStartTimeUtc { get; set; } // 智能豆板开始使用时间
    public string DoubanPlan { get; set; } = "hourly"; // hourly (按小时5元), afternoon (下午场27.9元), allday (全天33.9元)
    public int DoubanSavedSeconds { get; set; } = 0; // 豆板暂停时累积的秒数

    // 时间与计时属性
    public DateTime? StartTimeUtc { get; set; } // 游玩开始时间（支持修改/补录）
    public int TargetDurationSeconds { get; set; } = 0;
    public int SavedRemainingSeconds { get; set; } = 0;
    public DateTime? LastSwipeTimeUtc { get; set; }
    public string Remark { get; set; } = string.Empty;
    public DateTime UpdatedAtUtc { get; set; } = DateTime.UtcNow;

    // 动态计算属性
    public double RemainingSeconds
    {
        get
        {
            if (Status == CardStatus.Stopped) return 0;
            if (Status == CardStatus.Paused) return SavedRemainingSeconds;
            if (Status == CardStatus.Running && StartTimeUtc.HasValue)
            {
                if (TimerMode == 1)
                {
                    // 正计时模式下显示已玩时间
                    var currentSegment = (DateTime.UtcNow - StartTimeUtc.Value).TotalSeconds;
                    return Math.Max(0, SavedRemainingSeconds + currentSegment);
                }
                else
                {
                    var elapsed = (DateTime.UtcNow - StartTimeUtc.Value).TotalSeconds;
                    return SavedRemainingSeconds - elapsed;
                }
            }
            return SavedRemainingSeconds;
        }
    }

    public double ElapsedSeconds
    {
        get
        {
            if (Status == CardStatus.Stopped) return 0;
            if (TimerMode == 1) return RemainingSeconds;
            
            // 倒计时下已玩时间 = 目标时间 - 剩余时间 (包含超时)
            if (Status == CardStatus.Running && StartTimeUtc.HasValue)
            {
                var nowElapsed = (DateTime.UtcNow - StartTimeUtc.Value).TotalSeconds;
                var totalSpent = (TargetDurationSeconds - SavedRemainingSeconds) + nowElapsed;
                return Math.Max(0, totalSpent);
            }
            return Math.Max(0, TargetDurationSeconds - SavedRemainingSeconds);
        }
    }

    public double OverdueSeconds
    {
        get
        {
            if (TimerMode == 1) return 0;
            var rem = RemainingSeconds;
            return rem < 0 ? Math.Abs(rem) : 0;
        }
    }

    public bool IsOverdue => TimerMode == 0 && RemainingSeconds < 0 && (Status == CardStatus.Running || Status == CardStatus.Expired);

    // 智能豆板已使用时长（秒）
    public double DoubanElapsedSeconds
    {
        get
        {
            if (!UseDouban) return 0;
            if (Status == CardStatus.Stopped) return 0;
            if (DoubanStartTimeUtc.HasValue && Status == CardStatus.Running)
            {
                var segment = (DateTime.UtcNow - DoubanStartTimeUtc.Value).TotalSeconds;
                return Math.Max(0, DoubanSavedSeconds + segment);
            }
            return DoubanSavedSeconds;
        }
    }

    // 计费结果 (由 PriceCalculator 填充)
    public PricingResult? Pricing { get; set; }
}

public class PricingResult
{
    public double TotalPrice { get; set; }
    public double NeedToPay { get; set; } // 先付款需补收差价，后付款为应收全款
    public string BestPlanName { get; set; } = string.Empty;
    public double PlayFee { get; set; }
    public double PlayOvertimeFee { get; set; }
    public double DoubanFee { get; set; }
    public double DoubanOvertimeFee { get; set; }
    public string Formula { get; set; } = string.Empty;
    public List<string> BreakdownItems { get; set; } = new();
}

public class SwipeRequest
{
    public required string CardId { get; set; }
    public string? CardName { get; set; }
}

public class SetTimerRequest
{
    public int DurationSeconds { get; set; }
    public string Action { get; set; } = "start"; // start, pause, resume, stop, reset
    public int TimerMode { get; set; } = 0; // 0: 倒计时, 1: 正计时
    public bool IsPostPay { get; set; } = false;
    public string PresetPlan { get; set; } = "none";
    public bool UseDouban { get; set; } = false;
    public string DoubanPlan { get; set; } = "hourly";
    public DateTime? CustomStartTimeUtc { get; set; } // 自定义/修改开始时间
    public DateTime? CustomDoubanStartTimeUtc { get; set; } // 自定义豆板开始时间
}

public class UpdateCardConfigRequest
{
    public string? Name { get; set; }
    public string? Remark { get; set; }
    public bool? IsPostPay { get; set; }
    public string? PresetPlan { get; set; }
    public bool? UseDouban { get; set; }
    public string? DoubanPlan { get; set; }
    public DateTime? StartTimeUtc { get; set; } // 修改开始时间
    public DateTime? DoubanStartTimeUtc { get; set; } // 修改/启动豆板时间
}

public class AddTimeRequest
{
    public int AddSeconds { get; set; }
}

public class AuthRequest
{
    public required string Password { get; set; }
}

public class ChangePasswordRequest
{
    public required string OldPassword { get; set; }
    public required string NewPassword { get; set; }
}
