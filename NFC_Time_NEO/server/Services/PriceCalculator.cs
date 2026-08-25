using System.Globalization;
using NfcServer.Models;

namespace NfcServer.Services;

public static class PriceCalculator
{
    /// <summary>
    /// 游玩时长超时费计算：
    /// 超时15分钟内免费缓冲（0元）
    /// 超时15~30分钟加收8元
    /// 超时30~60分钟加收15元（每满1小时15元）
    /// </summary>
    public static double CalculatePlayOvertimeFee(int extraMinutes)
    {
        if (extraMinutes <= 15) return 0.0;
        int fullHours = extraMinutes / 60;
        int remMinutes = extraMinutes % 60;

        double remFee = 0.0;
        if (remMinutes <= 15)
        {
            remFee = 0.0;
        }
        else if (remMinutes <= 30)
        {
            remFee = 8.0;
        }
        else
        {
            remFee = 15.0;
        }

        return (fullHours * 15.0) + remFee;
    }

    /// <summary>
    /// 智能豆板精准阶梯计费：
    /// 1. 1小时内：5元 (不满1小时按1小时计)
    /// 2. 满1小时后：
    ///    - 超时15分钟内：不另收 (N * 5元)
    ///    - 超时15~30分钟：加收半小时2.5元 (N * 5 + 2.5元)
    ///    - 超时30分钟以上：按下一整小时计 ((N + 1) * 5元)
    /// </summary>
    public static (double fee, string detail) CalculateDoubanFee(double doubanElapsedSeconds)
    {
        int totalMinutes = Math.Max(1, (int)Math.Ceiling(Math.Max(1.0, doubanElapsedSeconds) / 60.0));
        double fee;
        string detail;

        if (totalMinutes <= 60)
        {
            fee = 5.0;
            detail = $"已用{totalMinutes}分(首小时内 ¥5.0)";
        }
        else
        {
            int nHours = totalMinutes / 60;
            int remMinutes = totalMinutes % 60;

            if (remMinutes == 0)
            {
                fee = nHours * 5.0;
                detail = $"已用{nHours}小时(¥{fee:F1})";
            }
            else if (remMinutes <= 15)
            {
                fee = nHours * 5.0;
                detail = $"已用{nHours}h{remMinutes}分(超时≤15分免收, 计{nHours}h ¥{fee:F1})";
            }
            else if (remMinutes <= 30)
            {
                fee = (nHours * 5.0) + 2.5;
                detail = $"已用{nHours}h{remMinutes}分(加半小时¥2.5, 计¥{fee:F1})";
            }
            else
            {
                fee = (nHours + 1) * 5.0;
                detail = $"已用{nHours}h{remMinutes}分(超30分进整, 计{nHours + 1}h ¥{fee:F1})";
            }
        }

        return (fee, detail);
    }

    /// <summary>
    /// 全面评估卡片计费与最优结算方案
    /// </summary>
    public static PricingResult ComputeCardPricing(CardInfo card)
    {
        int totalPlayMinutes = Math.Max(1, (int)Math.Ceiling(card.ElapsedSeconds / 60.0));

        // 1. 智能豆板费用
        double doubanFee = 0.0;
        string doubanDetail = "";
        if (card.UseDouban)
        {
            var (dFee, dDetail) = CalculateDoubanFee(card.DoubanElapsedSeconds);
            doubanFee = dFee;
            doubanDetail = dDetail;
        }

        string basePlanName;
        double basePlayFee;
        double overtimeFee;
        string formula;
        var breakdown = new List<string>();

        // 2. 场景 A: 购买了预设套餐 (先付款)
        if (!card.IsPostPay && !string.IsNullOrWhiteSpace(card.PresetPlan) && card.PresetPlan != "none" && card.PresetPlan != "custom")
        {
            int baseMinutes;
            if (card.PresetPlan == "3h")
            {
                basePlanName = "3小时套餐";
                basePlayFee = 29.9;
                baseMinutes = 180;
            }
            else
            {
                basePlanName = "1小时套餐";
                basePlayFee = 12.9;
                baseMinutes = 60;
            }

            int extraMin = Math.Max(0, totalPlayMinutes - baseMinutes);
            overtimeFee = CalculatePlayOvertimeFee(extraMin);

            breakdown.Add($"📦 已选套餐: {basePlanName} (基价 ¥{basePlayFee:F1})");
            if (overtimeFee > 0)
            {
                string otText = extraMin <= 30 ? $"超时{extraMin}分" : $"超时{extraMin / 60}小时{extraMin % 60}分";
                breakdown.Add($"⏳ 游玩超时加时: +¥{overtimeFee:F1} ({otText})");
            }
            else
            {
                breakdown.Add("⏳ 游玩时长: 正常未超时");
            }

            if (card.UseDouban)
            {
                breakdown.Add($"📟 智能豆板: +¥{doubanFee:F1} ({doubanDetail})");
            }

            formula = $"套餐¥{basePlayFee:F1}" +
                      (overtimeFee > 0 ? $" + 加时¥{overtimeFee:F1}" : "") +
                      (doubanFee > 0 ? $" + 豆板¥{doubanFee:F1}" : "");
        }
        else
        {
            // 3. 场景 B: 玩完再付 (后付款) -> 全场智能推荐最优解 (比对所有套餐与组合)
            var candidates = new List<(string planName, double playFee, double playOvertimeFee, string overtimeText, double total)>
            {
                ("1小时套餐", 12.9, CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 60)), "超时", 12.9 + CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 60))),
                ("3小时套餐", 29.9, CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 180)), "超时", 29.9 + CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 180))),
                ("3h+1h组合(4h)", 42.8, CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 240)), "超时", 42.8 + CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 240))),
                ("3h+2h组合(5h)", 55.7, CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 300)), "超时", 55.7 + CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 300))),
                ("下午场套餐", 43.9, CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 330)), "超时", 43.9 + CalculatePlayOvertimeFee(Math.Max(0, totalPlayMinutes - 330))),
                ("全天不限时", 59.9, 0.0, "不限时", 59.9)
            };

            var best = candidates.OrderBy(c => c.total).First();
            basePlanName = best.planName;
            basePlayFee = best.playFee;
            overtimeFee = best.playOvertimeFee;

            breakdown.Add($"💡 自动推荐最优: {best.planName} (¥{best.playFee:F1})");
            if (best.playOvertimeFee > 0)
            {
                breakdown.Add($"⏳ 游玩超时加时: +¥{best.playOvertimeFee:F1} ({best.overtimeText})");
            }
            if (card.UseDouban)
            {
                breakdown.Add($"📟 智能豆板: +¥{doubanFee:F1} ({doubanDetail})");
            }

            formula = $"游玩¥{best.total:F1}({best.planName})" +
                      (doubanFee > 0 ? $" + 豆板¥{doubanFee:F1}" : "");
        }

        // 构建结构化收款项列表
        var paymentItems = new List<PaymentBreakdownItem>();

        bool playPaid = card.PaidItems.Contains("play") || (!card.IsPostPay && !string.IsNullOrWhiteSpace(card.PresetPlan) && card.PresetPlan != "none" && !card.PaidItems.Contains("play_unpaid"));
        paymentItems.Add(new PaymentBreakdownItem
        {
            Id = "play",
            Title = $"基础游玩/套餐费 ({basePlanName})",
            Amount = basePlayFee,
            IsPaid = playPaid
        });

        if (overtimeFee > 0)
        {
            paymentItems.Add(new PaymentBreakdownItem
            {
                Id = "overtime",
                Title = "游玩超时加时费",
                Amount = overtimeFee,
                IsPaid = card.PaidItems.Contains("overtime")
            });
        }

        if (card.UseDouban && doubanFee > 0)
        {
            paymentItems.Add(new PaymentBreakdownItem
            {
                Id = "douban",
                Title = $"智能豆板使用费 ({doubanDetail})",
                Amount = doubanFee,
                IsPaid = card.PaidItems.Contains("douban")
            });
        }

        double paidAmount = Math.Round(paymentItems.Where(it => it.IsPaid).Sum(it => it.Amount), 1);
        double unpaidAmount = Math.Round(paymentItems.Where(it => !it.IsPaid).Sum(it => it.Amount), 1);
        double finalTotalPrice = Math.Round(paidAmount + unpaidAmount, 1);

        return new PricingResult
        {
            TotalPrice = finalTotalPrice,
            PaidAmount = paidAmount,
            UnpaidAmount = unpaidAmount,
            NeedToPay = unpaidAmount,
            BestPlanName = basePlanName,
            PlayFee = basePlayFee,
            PlayOvertimeFee = overtimeFee,
            DoubanFee = doubanFee,
            DoubanOvertimeFee = 0.0,
            Formula = $"{formula} = 应收总计 ¥{finalTotalPrice:F1}",
            BreakdownItems = breakdown,
            PaymentItems = paymentItems
        };
    }
}
