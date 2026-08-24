using System.Diagnostics;
using Microsoft.Extensions.FileProviders;
using NfcServer;
using NfcServer.Endpoints;
using NfcServer.Services;

var builder = WebApplication.CreateSlimBuilder(args);

// Initialize custom AppLogger with file & console output
AppLogger.Init(builder.Environment.ContentRootPath);

// Configure System.Text.Json with Native AOT Source Generator
builder.Services.ConfigureHttpJsonOptions(options =>
{
    options.SerializerOptions.TypeInfoResolverChain.Insert(0, AppJsonSerializerContext.Default);
});

builder.Services.AddSingleton<CardManager>();
builder.Services.AddSingleton<AuthService>();

// CORS configuration
builder.Services.AddCors(options =>
{
    options.AddPolicy("AllowAll", policy =>
    {
        policy.AllowAnyOrigin()
              .AllowAnyMethod()
              .AllowAnyHeader();
    });
});

var app = builder.Build();

app.UseCors("AllowAll");

// Detailed HTTP Request Logging Middleware
app.Use(async (context, next) =>
{
    var sw = Stopwatch.StartNew();
    var path = context.Request.Path.Value ?? "";
    var method = context.Request.Method;
    var remoteIp = context.Connection.RemoteIpAddress?.ToString() ?? "127.0.0.1";

    await next();

    sw.Stop();
    // Skip excessive logging for frequent static files polling
    if (!path.EndsWith(".css") && !path.EndsWith(".js") && !path.EndsWith(".ico"))
    {
        AppLogger.Http(method, path, context.Response.StatusCode, sw.Elapsed.TotalMilliseconds, remoteIp);
    }
});

// Serve static files from wwwroot
var wwwrootPath = Path.Combine(builder.Environment.ContentRootPath, "wwwroot");
if (!Directory.Exists(wwwrootPath))
{
    wwwrootPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "wwwroot");
}
if (!Directory.Exists(wwwrootPath))
{
    Directory.CreateDirectory(wwwrootPath);
}

var fileProvider = new PhysicalFileProvider(wwwrootPath);
app.UseDefaultFiles(new DefaultFilesOptions { FileProvider = fileProvider });
app.UseStaticFiles(new StaticFileOptions { FileProvider = fileProvider });

// Map all Minimal API endpoints (100% Native AOT Request Delegate Generator compatible)
app.MapAllEndpoints();

AppLogger.Success("STARTUP", "==================================================");
AppLogger.Success("STARTUP", "  🚀 NFC_Time_NEO Native AOT 服务已启动 (端口: 5000) ");
AppLogger.Success("STARTUP", "  Web 看板: http://localhost:5000                   ");
AppLogger.Success("STARTUP", "  日志目录: server/logs/                             ");
AppLogger.Success("STARTUP", "==================================================");

app.Run("http://0.0.0.0:5000");
