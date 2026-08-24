using System.Text.Json.Serialization;
using NfcServer.Models;
using NfcServer.Services;

namespace NfcServer;

public class ApiResponse
{
    public bool Success { get; set; }
    public string Message { get; set; } = string.Empty;
    public string? CardId { get; set; }
}

public class SystemInfoDto
{
    public string AppName { get; set; } = string.Empty;
    public string Version { get; set; } = string.Empty;
    public string Status { get; set; } = string.Empty;
    public DateTime ServerTimeUtc { get; set; }
    public string ServerLocalTime { get; set; } = string.Empty;
    public List<string> LocalIps { get; set; } = new();
    public string RecommendedApiUrl { get; set; } = string.Empty;
}

public class ConfigDto
{
    public int PollIntervalSeconds { get; set; }
    public string ExpiredTemplate { get; set; } = string.Empty;
    public bool EnableVoiceAlert { get; set; }
}

[JsonSourceGenerationOptions(WriteIndented = true, PropertyNamingPolicy = JsonKnownNamingPolicy.CamelCase)]
[JsonSerializable(typeof(CardInfo))]
[JsonSerializable(typeof(List<CardInfo>))]
[JsonSerializable(typeof(IEnumerable<CardInfo>))]
[JsonSerializable(typeof(CardStatus))]
[JsonSerializable(typeof(PricingResult))]
[JsonSerializable(typeof(SwipeRequest))]
[JsonSerializable(typeof(SetTimerRequest))]
[JsonSerializable(typeof(UpdateCardConfigRequest))]
[JsonSerializable(typeof(AddTimeRequest))]
[JsonSerializable(typeof(AuthRequest))]
[JsonSerializable(typeof(ChangePasswordRequest))]
[JsonSerializable(typeof(AppConfig))]
[JsonSerializable(typeof(ApiResponse))]
[JsonSerializable(typeof(SystemInfoDto))]
[JsonSerializable(typeof(ConfigDto))]
[JsonSerializable(typeof(Dictionary<string, object>))]
[JsonSerializable(typeof(Dictionary<string, string>))]
public partial class AppJsonSerializerContext : JsonSerializerContext
{
}
