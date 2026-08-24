package com.xingye.crafttime.api

import java.util.Locale

object PriceCalculator {

    /// 游玩时长超时费计算：
    /// 超时15分钟内免费缓冲（0元）
    /// 超时15~30分钟加收8元
    /// 超时30~60分钟加收15元（每满1小时15元）
    fun calculatePlayOvertimeFee(extraMinutes: Int): Double {
        if (extraMinutes <= 15) return 0.0
        val fullHours = extraMinutes / 60
        val remMinutes = extraMinutes % 60

        val remFee = when {
            remMinutes <= 15 -> 0.0
            remMinutes <= 30 -> 8.0
            else -> 15.0
        }

        return (fullHours * 15.0) + remFee
    }

    /// 智能豆板精准阶梯计费：
    /// 1. 1小时内：5元 (不满1小时按1小时计)
    /// 2. 满1小时后：
    ///    - 超时15分钟内：不另收 (N * 5元)
    ///    - 超时15~30分钟：加收半小时2.5元 (N * 5 + 2.5元)
    ///    - 超时30分钟以上：按下一整小时计 ((N + 1) * 5元)
    fun calculateDoubanFee(doubanElapsedSeconds: Double): Pair<Double, String> {
        if (doubanElapsedSeconds <= 0) {
            return Pair(0.0, "未开启")
        }

        val totalMinutes = Math.max(1, Math.ceil(doubanElapsedSeconds / 60.0).toInt())
        val fee: Double
        val detail: String

        if (totalMinutes <= 60) {
            fee = 5.0
            detail = "已用${totalMinutes}分(首小时内 ¥5.0)"
        } else {
            val nHours = totalMinutes / 60
            val remMinutes = totalMinutes % 60

            when {
                remMinutes == 0 -> {
                    fee = nHours * 5.0
                    detail = "已用${nHours}小时(¥${String.format(Locale.US, "%.1f", fee)})"
                }
                remMinutes <= 15 -> {
                    fee = nHours * 5.0
                    detail = "已用${nHours}h${remMinutes}分(超时≤15分免收, 计${nHours}h ¥${String.format(Locale.US, "%.1f", fee)})"
                }
                remMinutes <= 30 -> {
                    fee = (nHours * 5.0) + 2.5
                    detail = "已用${nHours}h${remMinutes}分(加半小时¥2.5, 计¥${String.format(Locale.US, "%.1f", fee)})"
                }
                else -> {
                    fee = (nHours + 1) * 5.0
                    detail = "已用${nHours}h${remMinutes}分(超30分进整, 计${nHours + 1}h ¥${String.format(Locale.US, "%.1f", fee)})"
                }
            }
        }

        return Pair(fee, detail)
    }

    /// 全面评估卡片计费与最优结算方案
    fun computeCardPricing(card: CardInfo): PricingResult {
        val totalPlayMinutes = Math.max(1, Math.ceil(card.elapsedSeconds / 60.0).toInt())

        // 1. 智能豆板费用
        var doubanFee = 0.0
        var doubanDetail = ""
        if (card.useDouban) {
            val dRes = calculateDoubanFee(card.doubanElapsedSeconds)
            doubanFee = dRes.first
            doubanDetail = dRes.second
        }

        // 2. 场景 A: 购买了预设套餐 (先付款)
        if (!card.isPostPay && card.presetPlan.isNotEmpty() && card.presetPlan != "none" && card.presetPlan != "custom") {
            val basePlanName: String
            val basePlanFee: Double
            val baseMinutes: Int

            if (card.presetPlan == "3h") {
                basePlanName = "3小时套餐"
                basePlanFee = 29.9
                baseMinutes = 180
            } else {
                basePlanName = "1小时套餐"
                basePlanFee = 12.9
                baseMinutes = 60
            }

            val extraMin = Math.max(0, totalPlayMinutes - baseMinutes)
            val overtimeFee = calculatePlayOvertimeFee(extraMin)
            val totalPrice = Math.round((basePlanFee + overtimeFee + doubanFee) * 10.0) / 10.0
            val needToPay = Math.round((overtimeFee + doubanFee) * 10.0) / 10.0 // 先付款需补收差价

            val breakdown = mutableListOf<String>()
            breakdown.add("📦 已选套餐: $basePlanName (已付基价 ¥${String.format(Locale.US, "%.1f", basePlanFee)})")
            if (overtimeFee > 0) {
                val otText = if (extraMin <= 30) "超时${extraMin}分" else "超时${extraMin / 60}小时${extraMin % 60}分"
                breakdown.add("⏳ 游玩超时加时: +¥${String.format(Locale.US, "%.1f", overtimeFee)} ($otText)")
            } else {
                breakdown.add("⏳ 游玩时长: 正常未超时")
            }

            if (card.useDouban) {
                breakdown.add("📟 智能豆板: +¥${String.format(Locale.US, "%.1f", doubanFee)} ($doubanDetail)")
            }

            val formula = if (needToPay > 0) {
                "已付¥${String.format(Locale.US, "%.1f", basePlanFee)} + 需补收¥${String.format(Locale.US, "%.1f", needToPay)}" +
                        (if (overtimeFee > 0) " [加时¥${String.format(Locale.US, "%.1f", overtimeFee)}]" else "") +
                        (if (doubanFee > 0) " [豆板¥${String.format(Locale.US, "%.1f", doubanFee)}]" else "") +
                        " = 总价¥${String.format(Locale.US, "%.1f", totalPrice)}"
            } else {
                "已付¥${String.format(Locale.US, "%.1f", basePlanFee)} (未超时/无补收)"
            }

            return PricingResult(
                totalPrice = totalPrice,
                needToPay = needToPay,
                bestPlanName = basePlanName,
                playFee = basePlanFee,
                playOvertimeFee = overtimeFee,
                doubanFee = doubanFee,
                doubanOvertimeFee = 0.0,
                formula = formula,
                breakdownItems = breakdown
            )
        }

        // 3. 场景 B: 玩完再付 (后付款) -> 全场智能推荐最优解 (比对所有套餐与组合)
        data class Cand(val planName: String, val playFee: Double, val playOt: Double, val otText: String, val total: Double)
        val candidates = mutableListOf<Cand>()

        // 方案 1: 单买 1小时套餐 (12.9) + 超时
        val ex1 = Math.max(0, totalPlayMinutes - 60)
        val ot1 = calculatePlayOvertimeFee(ex1)
        candidates.add(Cand("1小时套餐", 12.9, ot1, if (ex1 > 0) "超时${ex1}分" else "未超时", 12.9 + ot1))

        // 方案 2: 单买 3小时套餐 (29.9) + 超时
        val ex3 = Math.max(0, totalPlayMinutes - 180)
        val ot3 = calculatePlayOvertimeFee(ex3)
        candidates.add(Cand("3小时套餐", 29.9, ot3, if (ex3 > 0) "超时${ex3}分" else "未超时", 29.9 + ot3))

        // 方案 3: 拼套餐 3小时(29.9) + 1小时(12.9) = 42.8元 (240分钟) + 超时
        val ex3_1 = Math.max(0, totalPlayMinutes - 240)
        val ot3_1 = calculatePlayOvertimeFee(ex3_1)
        candidates.add(Cand("3小时+1小时组合(4h)", 42.8, ot3_1, if (ex3_1 > 0) "超时${ex3_1}分" else "未超时", 42.8 + ot3_1))

        // 方案 4: 拼套餐 3小时(29.9) + 1小时(12.9) + 1小时(12.9) = 55.7元 (300分钟) + 超时
        val ex3_2 = Math.max(0, totalPlayMinutes - 300)
        val ot3_2 = calculatePlayOvertimeFee(ex3_2)
        candidates.add(Cand("3小时+2小时组合(5h)", 55.7, ot3_2, if (ex3_2 > 0) "超时${ex3_2}分" else "未超时", 55.7 + ot3_2))

        // 方案 5: 下午场套餐 (43.9元，330分钟即 14:00-19:30 5.5小时)
        val exAft = Math.max(0, totalPlayMinutes - 330)
        val otAft = calculatePlayOvertimeFee(exAft)
        candidates.add(Cand("下午场套餐(¥43.9)", 43.9, otAft, if (exAft > 0) "超时${exAft}分" else "场次内", 43.9 + otAft))

        // 方案 6: 全天不限时套餐 (59.9元)
        candidates.add(Cand("全天不限时套餐", 59.9, 0.0, "不限时", 59.9))

        val best = candidates.minByOrNull { it.total } ?: candidates[0]
        val finalTotalPrice = Math.round((best.total + doubanFee) * 10.0) / 10.0

        val bestBreakdown = mutableListOf<String>()
        bestBreakdown.add("💡 自动推荐最优: ${best.planName} (¥${String.format(Locale.US, "%.1f", best.playFee)})")
        if (best.playOt > 0) {
            bestBreakdown.add("⏳ 游玩超时加时: +¥${String.format(Locale.US, "%.1f", best.playOt)} (${best.otText})")
        }
        if (card.useDouban) {
            bestBreakdown.add("📟 智能豆板: +¥${String.format(Locale.US, "%.1f", doubanFee)} ($doubanDetail)")
        }

        val bestFormula = "游玩¥${String.format(Locale.US, "%.1f", best.total)}(${best.planName})" +
                (if (doubanFee > 0) " + 豆板¥${String.format(Locale.US, "%.1f", doubanFee)}" else "") +
                " = 应收¥${String.format(Locale.US, "%.1f", finalTotalPrice)}"

        return PricingResult(
            totalPrice = finalTotalPrice,
            needToPay = finalTotalPrice,
            bestPlanName = best.planName,
            playFee = best.playFee,
            playOvertimeFee = best.playOt,
            doubanFee = doubanFee,
            doubanOvertimeFee = 0.0,
            formula = bestFormula,
            breakdownItems = bestBreakdown
        )
    }
}
