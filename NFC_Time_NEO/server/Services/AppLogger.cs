using System;
using System.IO;
using System.Text;

namespace NfcServer.Services;

public static class AppLogger
{
    private static readonly object _lock = new();
    private static string? _logDirPath;

    public static void Init(string contentRootPath)
    {
        _logDirPath = Path.Combine(contentRootPath, "logs");
        if (!Directory.Exists(_logDirPath))
        {
            Directory.CreateDirectory(_logDirPath);
        }
    }

    public static void Info(string category, string message) => Log("INFO", ConsoleColor.Cyan, category, message);
    public static void Success(string category, string message) => Log("SUCCESS", ConsoleColor.Green, category, message);
    public static void Warn(string category, string message) => Log("WARN", ConsoleColor.Yellow, category, message);
    public static void Error(string category, string message, Exception? ex = null) =>
        Log("ERROR", ConsoleColor.Red, category, ex != null ? $"{message}\n{ex}" : message);

    public static void Http(string method, string path, int statusCode, double elapsedMs, string remoteIp)
    {
        var color = statusCode < 400 ? ConsoleColor.DarkGreen : (statusCode < 500 ? ConsoleColor.DarkYellow : ConsoleColor.DarkRed);
        var msg = $"🌐 {method} {path} - {statusCode} ({elapsedMs:F1}ms) [IP: {remoteIp}]";
        Log("HTTP", color, "REQUEST", msg);
    }

    public static void Card(string action, string cardId, string details)
    {
        Log("CARD", ConsoleColor.Magenta, action, $"[UID: {cardId}] {details}");
    }

    public static void Timer(string action, string cardId, string details)
    {
        Log("TIMER", ConsoleColor.Cyan, action, $"[UID: {cardId}] {details}");
    }

    public static void Douban(string action, string cardId, string details)
    {
        Log("DOUBAN", ConsoleColor.Green, action, $"[UID: {cardId}] {details}");
    }

    public static void Auth(string action, bool success, string details)
    {
        var color = success ? ConsoleColor.Green : ConsoleColor.Red;
        Log("AUTH", color, action, $"{(success ? "✅ 验证通过" : "❌ 验证失败")} | {details}");
    }

    private static void Log(string level, ConsoleColor color, string category, string message)
    {
        var now = DateTime.Now;
        var timeStr = now.ToString("yyyy-MM-dd HH:mm:ss.fff");
        var logLine = $"[{timeStr}] [{level,-7}] [{category}] {message}";

        lock (_lock)
        {
            var oldColor = Console.ForegroundColor;
            Console.ForegroundColor = ConsoleColor.DarkGray;
            Console.Write($"[{timeStr}] ");
            Console.ForegroundColor = color;
            Console.Write($"[{level,-7}] ");
            Console.ForegroundColor = ConsoleColor.White;
            Console.Write($"[{category}] ");
            Console.ForegroundColor = color;
            Console.WriteLine(message);
            Console.ForegroundColor = oldColor;

            // Write to daily log file
            try
            {
                if (!string.IsNullOrEmpty(_logDirPath))
                {
                    var filePath = Path.Combine(_logDirPath, $"server_{now:yyyyMMdd}.log");
                    File.AppendAllText(filePath, logLine + Environment.NewLine, Encoding.UTF8);
                }
            }
            catch { }
        }
    }
}
