using Microsoft.Extensions.FileProviders;
using NfcServer.Services;

var builder = WebApplication.CreateBuilder(args);

// Add services
builder.Services.AddControllers();
builder.Services.AddSingleton<CardManager>();

// Allow CORS for mobile app access
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

// Ensure wwwroot directory exists in current content root or base directory
var wwwrootPath = Path.Combine(builder.Environment.ContentRootPath, "wwwroot");
if (!Directory.Exists(wwwrootPath))
{
    wwwrootPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "wwwroot");
}

if (Directory.Exists(wwwrootPath))
{
    var fileProvider = new PhysicalFileProvider(wwwrootPath);
    app.UseDefaultFiles(new DefaultFilesOptions { FileProvider = fileProvider });
    app.UseStaticFiles(new StaticFileOptions { FileProvider = fileProvider });
}
else
{
    app.UseDefaultFiles();
    app.UseStaticFiles();
}

app.MapControllers();

// Bind to 0.0.0.0 so local network (mobile device) can connect
app.Run("http://0.0.0.0:5000");
