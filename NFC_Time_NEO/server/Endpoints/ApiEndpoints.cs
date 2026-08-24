using System.Net;
using System.Net.Sockets;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using NfcServer.Models;
using NfcServer.Services;

namespace NfcServer.Endpoints;

public static class ApiEndpoints
{
    public static void MapAllEndpoints(this WebApplication app)
    {
        // ==========================================
        // System Info Endpoints
        // ==========================================
        app.MapGet("/api/system/ping", () => Results.Ok("pong"));

        app.MapGet("/api/system/info", () =>
        {
            var localIps = new List<string>();
            try
            {
                var host = Dns.GetHostEntry(Dns.GetHostName());
                foreach (var ip in host.AddressList)
                {
                    if (ip.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(ip))
                    {
                        localIps.Add(ip.ToString());
                    }
                }
            }
            catch { }

            return Results.Ok(new SystemInfoDto
            {
                AppName = "NFC_Time_NEO",
                Version = "2.0.0 (Native AOT)",
                Status = "Online",
                ServerTimeUtc = DateTime.UtcNow,
                ServerLocalTime = DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"),
                LocalIps = localIps,
                RecommendedApiUrl = localIps.Count > 0 ? $"http://{localIps[0]}:5000" : "http://localhost:5000"
            });
        });

        // ==========================================
        // Cards Management Endpoints (精准按需传输)
        // ==========================================
        var cardsGroup = app.MapGroup("/api/cards");

        // 仅活跃卡片 (减少 95% 传输量)
        cardsGroup.MapGet("/active", (CardManager cardManager) =>
        {
            var cards = cardManager.GetActiveCards();
            return Results.Ok(cards);
        });

        // 增量同步 (无变化传输 0KB)
        cardsGroup.MapGet("/delta", (string? since, CardManager cardManager) =>
        {
            var sinceUtc = DateTime.MinValue;
            if (!string.IsNullOrWhiteSpace(since) && DateTime.TryParse(since, null, System.Globalization.DateTimeStyles.AdjustToUniversal, out var dt))
            {
                sinceUtc = dt;
            }
            var delta = cardManager.GetDeltaCards(sinceUtc);
            return Results.Ok(delta);
        });

        // 仅未使用卡片 (打开档案库时按需加载)
        cardsGroup.MapGet("/unused", (CardManager cardManager) =>
        {
            var cards = cardManager.GetUnusedCards();
            return Results.Ok(cards);
        });

        cardsGroup.MapGet("/", (CardManager cardManager) =>
        {
            var cards = cardManager.GetAllCards();
            return Results.Ok(cards);
        });

        cardsGroup.MapGet("/{id}", (string id, CardManager cardManager) =>
        {
            var card = cardManager.GetCard(id);
            if (card == null)
            {
                AppLogger.Warn("CARD_GET", $"未找到卡片: UID={id}");
                return Results.NotFound(new ApiResponse { Success = false, Message = "Card not found" });
            }
            return Results.Ok(card);
        });

        cardsGroup.MapPost("/swipe", ([FromBody] SwipeRequest req, CardManager cardManager) =>
        {
            if (string.IsNullOrWhiteSpace(req.CardId))
                return Results.BadRequest(new ApiResponse { Success = false, Message = "CardId is required" });

            var card = cardManager.HandleSwipe(req.CardId, req.CardName);
            AppLogger.Card("SWIPE", req.CardId, $"🏷️ 刷卡感应成功 | 名称: {card.Name} | 状态: {card.Status}");
            return Results.Ok(card);
        });

        cardsGroup.MapPost("/{id}/timer", (string id, [FromBody] SetTimerRequest req, CardManager cardManager) =>
        {
            var card = cardManager.SetTimer(id, req);
            if (card == null)
            {
                AppLogger.Warn("TIMER", $"设置计时失败，未找到卡片: UID={id}");
                return Results.NotFound(new ApiResponse { Success = false, Message = "Card not found" });
            }

            var details = req.Action?.ToLowerInvariant() switch
            {
                "start" or "set_and_start" => $"🚀 开启全新会话 | 模式: {(req.TimerMode == 1 ? "正计时" : $"倒计时({req.DurationSeconds}s)")} | 付款: {(req.IsPostPay ? "玩完再付" : "先付款")} | 套餐: {req.PresetPlan} | 豆板: {(req.UseDouban ? $"开启({req.DoubanPlan})" : "未开启")}",
                "pause" => $"⏸️ 暂停计时 | 剩余: {card.RemainingSeconds:F0}s | 豆板已用: {card.DoubanElapsedSeconds:F0}s",
                "resume" => "▶️ 恢复计时",
                "stop" => $"🛑 结算停止 | 已玩: {card.ElapsedSeconds:F0}s | 总价: ¥{card.Pricing?.TotalPrice:F1}",
                _ => $"操作: {req.Action}"
            };
            AppLogger.Timer("SET_TIMER", id, details);

            return Results.Ok(card);
        });

        cardsGroup.MapPost("/{id}/config", (string id, [FromBody] UpdateCardConfigRequest req, CardManager cardManager) =>
        {
            var card = cardManager.UpdateCardConfig(id, req);
            if (card == null) return Results.NotFound(new ApiResponse { Success = false, Message = "Card not found" });

            AppLogger.Card("CONFIG_UPDATE", id, $"⚙️ 配置更新 | 名称: {card.Name} | 豆板: {(card.UseDouban ? "开启" : "关闭")} | 付款: {(card.IsPostPay ? "后付" : "先付")}");
            return Results.Ok(card);
        });

        cardsGroup.MapPost("/{id}/add-time", (string id, [FromBody] AddTimeRequest req, CardManager cardManager) =>
        {
            var card = cardManager.AddTime(id, req.AddSeconds);
            if (card == null) return Results.NotFound(new ApiResponse { Success = false, Message = "Card not found" });

            AppLogger.Timer("ADD_TIME", id, $"⚡ 快速加时: +{req.AddSeconds}秒 | 新剩余: {card.RemainingSeconds:F0}秒");
            return Results.Ok(card);
        });

        cardsGroup.MapPost("/{id}/reset-unused", (string id, CardManager cardManager) =>
        {
            var card = cardManager.ResetToUnused(id);
            if (card == null) return Results.NotFound(new ApiResponse { Success = false, Message = "Card not found" });

            AppLogger.Card("RESET_UNUSED", id, "🗂️ 已移至未使用卡片档案库");
            return Results.Ok(card);
        });

        cardsGroup.MapDelete("/{id}", (string id, CardManager cardManager) =>
        {
            var success = cardManager.DeleteCard(id);
            if (!success) return Results.NotFound(new ApiResponse { Success = false, Message = "Card not found" });

            AppLogger.Card("DELETE_CARD", id, "🗑️ 物理彻底删除卡片记录");
            return Results.Ok(new ApiResponse { Success = true, Message = "Card permanently deleted", CardId = id });
        });

        // ==========================================
        // Auth Endpoints
        // ==========================================
        var authGroup = app.MapGroup("/api/auth");

        authGroup.MapPost("/verify", (HttpContext httpContext, [FromBody] AuthRequest req, AuthService authService) =>
        {
            var isValid = authService.VerifyPassword(req.Password);
            var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "Unknown";
            AppLogger.Auth("VERIFY", isValid, $"IP={ip}");

            return Results.Ok(new ApiResponse
            {
                Success = isValid,
                Message = isValid ? "Password verified" : "Incorrect password"
            });
        });

        authGroup.MapPost("/change-password", (HttpContext httpContext, [FromBody] ChangePasswordRequest req, AuthService authService) =>
        {
            var success = authService.ChangePassword(req.OldPassword, req.NewPassword);
            var ip = httpContext.Connection.RemoteIpAddress?.ToString() ?? "Unknown";
            AppLogger.Auth("CHANGE_PASSWORD", success, $"IP={ip}");

            if (!success)
            {
                return Results.BadRequest(new ApiResponse { Success = false, Message = "原密码错误或新密码无效" });
            }
            return Results.Ok(new ApiResponse { Success = true, Message = "管理员密码修改成功" });
        });

        authGroup.MapGet("/config", (AuthService authService) =>
        {
            var cfg = authService.GetConfig();
            return Results.Ok(new ConfigDto
            {
                PollIntervalSeconds = cfg.PollIntervalSeconds,
                ExpiredTemplate = cfg.ExpiredTemplate,
                EnableVoiceAlert = cfg.EnableVoiceAlert
            });
        });
    }
}
