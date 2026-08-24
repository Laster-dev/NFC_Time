package com.nfctime.app.api

import java.util.Date
import java.util.Locale

data class PricingResult(
    val price: Double,
    val planName: String,
    val details: String,
    val extraMinutes: Int = 0,
    val overtimeFee: Double = 0.0
)

object PriceCalculator {

    /**
     * 计算超时加时费（规则1：超时15分钟内免费，超出15分钟后每超出半小时收8元，超出1小时收15元）
     */
    fun calculateOvertimeFee(extraMinutes: Int): Double {
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

    /**
     * 自动评估所有方案，返回最划算（最低价）方案
     * @param elapsedSeconds 已玩总秒数
     * @param startDate 开始时间（用于判断时段场）
     * @param isDouble 是否双人模式
     */
    fun calculateBestPrice(elapsedSeconds: Double, startDate: Date? = null, isDouble: Boolean = false): PricingResult {
        val totalMinutes = Math.max(1, Math.ceil(elapsedSeconds / 60.0).toInt())
        val candidates = mutableListOf<PricingResult>()

        if (isDouble) {
            // 方案 1: 双人 3 小时套餐 (56.9元) + 加时
            val d3Overtime = calculateOvertimeFee(Math.max(0, totalMinutes - 180))
            val d3TotalPrice = 56.9 + d3Overtime
            val d3Detail = if (d3Overtime > 0) {
                "双人3小时(¥56.9) + 超时加时(¥${String.format(Locale.US, "%.1f", d3Overtime)})"
            } else {
                "双人3小时套餐(¥56.9)"
            }
            candidates.add(PricingResult(d3TotalPrice, "双人3小时套餐", d3Detail, Math.max(0, totalMinutes - 180), d3Overtime))

            // 方案 2: 2 * 单人最优方案组合
            val singleBest = calculateBestPrice(elapsedSeconds, startDate, isDouble = false)
            val doubleSinglePrice = singleBest.price * 2
            val doubleSingleDetail = "2人单买[${singleBest.planName}]组合 (2 × ¥${String.format(Locale.US, "%.1f", singleBest.price)})"
            candidates.add(PricingResult(doubleSinglePrice, "双人单买组合", doubleSingleDetail))

            return candidates.minByOrNull { it.price } ?: candidates[0]
        }

        // --- 单人模式所有方案比价 ---

        // 1. 单人 1 小时套餐 (12.9元) + 加时
        val h1Extra = Math.max(0, totalMinutes - 60)
        val h1Overtime = calculateOvertimeFee(h1Extra)
        val h1Price = 12.9 + h1Overtime
        val h1Detail = if (h1Overtime > 0) {
            "单人1小时(¥12.9) + 超时加时(¥${String.format(Locale.US, "%.1f", h1Overtime)})"
        } else {
            "单人1小时套餐(¥12.9)"
        }
        candidates.add(PricingResult(h1Price, "单人1小时套餐", h1Detail, h1Extra, h1Overtime))

        // 2. 单人 3 小时套餐 (29.9元) + 加时
        val h3Extra = Math.max(0, totalMinutes - 180)
        val h3Overtime = calculateOvertimeFee(h3Extra)
        val h3Price = 29.9 + h3Overtime
        val h3Detail = if (h3Overtime > 0) {
            "单人3小时(¥29.9) + 超时加时(¥${String.format(Locale.US, "%.1f", h3Overtime)})"
        } else {
            "单人3小时套餐(¥29.9)"
        }
        candidates.add(PricingResult(h3Price, "单人3小时套餐", h3Detail, h3Extra, h3Overtime))

        // 3. 上午场 (10:00 - 14:00, 36.9元, 240分钟)
        val morningExtra = Math.max(0, totalMinutes - 240)
        val morningOvertime = calculateOvertimeFee(morningExtra)
        val morningPrice = 36.9 + morningOvertime
        val morningDetail = if (morningOvertime > 0) {
            "上午场(¥36.9) + 超出时段加时(¥${String.format(Locale.US, "%.1f", morningOvertime)})"
        } else {
            "单人上午场(10:00-14:00)"
        }
        candidates.add(PricingResult(morningPrice, "单人上午场", morningDetail, morningExtra, morningOvertime))

        // 4. 下午场 (14:00 - 19:30, 43.9元, 330分钟)
        val afternoonExtra = Math.max(0, totalMinutes - 330)
        val afternoonOvertime = calculateOvertimeFee(afternoonExtra)
        val afternoonPrice = 43.9 + afternoonOvertime
        val afternoonDetail = if (afternoonOvertime > 0) {
            "下午场(¥43.9) + 超出时段加时(¥${String.format(Locale.US, "%.1f", afternoonOvertime)})"
        } else {
            "单人下午场(14:00-19:30)"
        }
        candidates.add(PricingResult(afternoonPrice, "单人下午场", afternoonDetail, afternoonExtra, afternoonOvertime))

        // 5. 全天不限时 (10:00 - 20:30, 59.9元)
        candidates.add(PricingResult(59.9, "单人全天不限时", "全天不限时套餐(10:00-20:30 封顶 ¥59.9)"))

        // 选出价格最低且最实惠的方案
        return candidates.minByOrNull { it.price } ?: candidates[0]
    }
}
