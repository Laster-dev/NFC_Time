@echo off
title NFC_Time_NEO - Publish Server Native AOT
echo ===================================================
echo   NFC_Time_NEO Server Native AOT Release 编译发布
echo ===================================================
echo.

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%server"

echo [1/2] 正在初始化 Visual Studio MSVC 编译环境...
if exist "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" (
    call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat"
) else if exist "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" (
    call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat"
)

echo.
echo [2/2] 正在执行 Native AOT 发布编译 (win-x64 Release)...
dotnet publish NfcServer.csproj -c Release -r win-x64 --self-contained -o "%SCRIPT_DIR%server\publish_aot"

if %errorlevel% neq 0 (
    echo.
    echo ===================================================
    echo [Publish Failed] 请检查上方编译错误。
    echo ===================================================
    pause
    exit /b %errorlevel%
)

echo.
echo ===================================================
echo [Publish Success] Native AOT 发布成功！
echo 输出路径: server\publish_aot\NfcServer.exe
echo ===================================================
echo.

pause
