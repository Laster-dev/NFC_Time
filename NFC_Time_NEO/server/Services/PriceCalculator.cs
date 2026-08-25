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
        double playElapsedSeconds = card.ElapsedSeconds;
        int totalPlayMinutes = Math.Max(1, (int)Math.Ceiling(playElapsedSeconds / 60.0));

        // 1. 智能豆板费用
        double doubanFee = 0.0;
        string doubanDetail = "";
        if (card.UseDouban)
        {
            var dRes = CalculateDoubanFee(card.DoubanElapsedSeconds);
            doubanFee = dRes.fee;
            doubanDetail = dRes.detail;
        }

        // 2. 场景 A: 购买了预设套餐 (先付款)
        if (!card.IsPostPay && !string.IsNullOrWhiteSpace(card.PresetPlan) && card.PresetPlan != "none" && card.PresetPlan != "custom")
        {
            string basePlanName;
            double basePlanFee;
            int baseMinutes;

            if (card.PresetPlan == "3h")
            {
                basePlanName = "3小时套餐";
                basePlanFee = 29.9;
                baseMinutes = 180;
            }
            else
            {
                basePlanName = "1小时套餐";
                basePlanFee = 12.9;
                baseMinutes = 60;
            }

            int extraMin = Math.Max(0, totalPlayMinutes - baseMinutes);
            double overtimeFee = CalculatePlayOvertimeFee(extraMin);
            double totalPrice = Math.Round(basePlanFee + overtimeFee + doubanFee, 1);
            double needToPay = Math.Round(overtimeFee + doubanFee, 1); // 先付款客户需补收的差价

            var breakdown = new List<string>
            {
                $"📦 已选套餐: {basePlanName} (已付基价 ¥{basePlanFee:F1})"
            };

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

            string formula;
            if (needToPay > 0)
            {
                formula = $"已付¥{basePlanFee:F1} + 需补收¥{needToPay:F1}" +
                          (overtimeFee > 0 ? $" [加时¥{overtimeFee:F1}]" : "") +
                          (doubanFee > 0 ? $" [豆板¥{doubanFee:F1}]" : "") +
                          $" = 总价¥{totalPrice:F1}";
            }
            else
            {
                formula = $"已付¥{basePlanFee:F1} (未超时/无补收)";
            }

            return new PricingResult
            {
                TotalPrice = totalPrice,
                NeedToPay = needToPay,
                BestPlanName = basePlanName,
                PlayFee = basePlanFee,
                PlayOvertimeFee = overtimeFee,
                DoubanFee = doubanFee,
                DoubanOvertimeFee = 0.0,
                Formula = formula,
                BreakdownItems = breakdown
            };
        }

        // 3. 场景 B: 玩完再付 (后付款) -> 全场智能推荐最优解 (比对所有套餐与组合)
        var candidates = new List<(string planName, double playFee, double playOvertimeFee, string overtimeText, double total)>();

        // 方案 1: 单买 1小时套餐 (12.9) + 超时
        int ex1 = Math.Max(0, totalPlayMinutes - 60);
        double ot1 = CalculatePlayOvertimeFee(ex1);
        candidates.Add(("1小时套餐", 12.9, ot1, ex1 > 0 ? $"超时{ex1}分" : "未超时", 12.9 + ot1));

        // 方案 2: 单买 3小时套餐 (29.9) + 超时
        int ex3 = Math.Max(0, totalPlayMinutes - 180);
        double ot3 = CalculatePlayOvertimeFee(ex3);
        candidates.Add(("3小时套餐", 29.9, ot3, ex3 > 0 ? $"超时{ex3}分" : "未超时", 29.9 + ot3));

        // 方案 3: 拼套餐 3小时(29.9) + 1小时(12.9) = 42.8元 (240分钟) + 超时
        int ex3_1 = Math.Max(0, totalPlayMinutes - 240);
        double ot3_1 = CalculatePlayOvertimeFee(ex3_1);
        candidates.Add(("3小时+1小时组合(4h)", 42.8, ot3_1, ex3_1 > 0 ? $"超时{ex3_1}分" : "未超时", 42.8 + ot3_1));

        // 方案 4: 拼套餐 3小时(29.9) + 1小时(12.9) + 1小时(12.9) = 55.7元 (300分钟) + 超时
        int ex3_2 = Math.Max(0, totalPlayMinutes - 300);
        double ot3_2 = CalculatePlayOvertimeFee(ex3_2);
        candidates.Add(("3小时+2小时组合(5h)", 55.7, ot3_2, ex3_2 > 0 ? $"超时{ex3_2}分" : "未超时", 55.7 + ot3_2));

        // 方案 5: 下午场套餐 (43.9元，330分钟即 14:00-19:30 5.5小时)
        int exAft = Math.Max(0, totalPlayMinutes - 330);
        double otAft = CalculatePlayOvertimeFee(exAft);
        candidates.Add(("下午场套餐(¥43.9)", 43.9, otAft, exAft > 0 ? $"超时{exAft}分" : "场次内", 43.9 + otAft));

        // 方案 6: 全天不限时套餐 (59.9元)
        candidates.Add(("全天不限时套餐", 59.9, 0.0, "不限时", 59.9));

        // 选取游玩部分最省钱的方案
        var best = candidates.OrderBy(c => c.total).First();
        double finalTotalPrice = Math.Round(best.total + doubanFee, 1);

        var bestBreakdown = new List<string>
        {
            $"💡 自动推荐最优: {best.planName} (¥{best.playFee:F1})"
        };

        if (best.playOvertimeFee > 0)
        {
            bestBreakdown.Add($"⏳ 游玩超时加时: +¥{best.playOvertimeFee:F1} ({best.overtimeText})");
        }

        if (card.UseDouban)
        {
            bestBreakdown.Add($"📟 智能豆板: +¥{doubanFee:F1} ({doubanDetail})");
        }

        string bestFormula = $"游玩¥{best.total:F1}({best.planName})" +
                             (doubanFee > 0 ? $" + 豆板¥{doubanFee:F1}" : "") +
                             $" = 应收¥{finalTotalPrice:F1}";

        return new PricingResult
        {
            TotalPrice = finalTotalPrice,
            NeedToPay = finalTotalPrice, // 玩完再付客户应收全额
            BestPlanName = best.planName,
            PlayFee = best.playFee,
            PlayOvertimeFee = best.playOvertimeFee,
            DoubanFee = doubanFee,
            DoubanOvertimeFee = 0.0,
            Formula = bestFormula,
            BreakdownItems = bestBreakdown
        };
    }
}
