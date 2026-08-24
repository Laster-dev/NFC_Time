using System.Text.Json;

namespace NfcServer.Services;

public class AppConfig
{
    public string AdminPassword { get; set; } = "888888";
    public int PollIntervalSeconds { get; set; } = 60;
    public string ExpiredTemplate { get; set; } = "{卡片列表}即将超时";
    public bool EnableVoiceAlert { get; set; } = true;
}

public class AuthService
{
    private readonly string _configFilePath;
    private AppConfig _config = new();
    private readonly object _lock = new();

    public AuthService(IWebHostEnvironment env)
    {
        var dataDir = Path.Combine(env.ContentRootPath, "data");
        if (!Directory.Exists(dataDir)) Directory.CreateDirectory(dataDir);
        _configFilePath = Path.Combine(dataDir, "config.json");
        LoadConfig();
    }

    private void LoadConfig()
    {
        lock (_lock)
        {
            if (File.Exists(_configFilePath))
            {
                try
                {
                    var json = File.ReadAllText(_configFilePath);
                    var cfg = JsonSerializer.Deserialize(json, AppJsonSerializerContext.Default.AppConfig);
                    if (cfg != null) _config = cfg;
                }
                catch (Exception ex)
                {
                    AppLogger.Error("AUTH_CONFIG", "加载配置文件失败", ex);
                }
            }
            else
            {
                SaveConfig();
            }
        }
    }

    public void SaveConfig()
    {
        lock (_lock)
        {
            try
            {
                var json = JsonSerializer.Serialize(_config, AppJsonSerializerContext.Default.AppConfig);
                File.WriteAllText(_configFilePath, json);
            }
            catch (Exception ex)
            {
                AppLogger.Error("AUTH_CONFIG", "保存配置文件失败", ex);
            }
        }
    }

    public bool VerifyPassword(string password)
    {
        return string.Equals(_config.AdminPassword?.Trim(), password?.Trim(), StringComparison.Ordinal);
    }

    public bool ChangePassword(string oldPassword, string newPassword)
    {
        if (!VerifyPassword(oldPassword)) return false;
        if (string.IsNullOrWhiteSpace(newPassword)) return false;

        _config.AdminPassword = newPassword.Trim();
        SaveConfig();
        return true;
    }

    public AppConfig GetConfig() => _config;
}
