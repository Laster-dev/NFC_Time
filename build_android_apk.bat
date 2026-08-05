@echo off
title NFC App Build Script
echo ===================================================
echo     NFC App Build Script (Debug and Release)
echo ===================================================
echo.

rem 1. Auto detect JAVA_HOME
if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    ) else if exist "C:\Program Files\Android\openjdk\jdk-21.0.8" (
        set "JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8"
    )
)

if defined JAVA_HOME (
    echo [1/3] JAVA_HOME Path: %JAVA_HOME%
) else (
    echo [1/3] Warning: JAVA_HOME not detected.
)

rem 2. Auto detect ANDROID_HOME / ANDROID_SDK_ROOT
if not defined ANDROID_HOME (
    if exist "%LOCALAPPDATA%\Android\Sdk" (
        set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
    ) else if exist "C:\Users\admin\AppData\Local\Android\Sdk" (
        set "ANDROID_HOME=C:\Users\admin\AppData\Local\Android\Sdk"
    )
)

if defined ANDROID_HOME (
    echo [2/3] ANDROID_HOME Path: %ANDROID_HOME%
) else (
    echo [2/3] Tip: ANDROID_HOME not set in system environment variables.
)

echo.
echo [3/3] Building Debug and Signed Release APKs...
echo.

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%android"

call gradlew.bat assembleDebug assembleRelease

if %errorlevel% neq 0 (
    echo.
    echo ===================================================
    echo [Build Failed] Please check build errors above.
    echo ===================================================
    echo.
    pause
    exit /b %errorlevel%
)

echo.
echo ===================================================
echo [Build Success] Both Debug and Signed Release APKs generated!
echo.
echo  - Debug APK:   android\app\build\outputs\apk\debug\app-debug.apk
echo  - Release APK: android\app\build\outputs\apk\release\app-release.apk
echo ===================================================
echo.

if exist "app\build\outputs\apk\release\app-release.apk" (
    explorer /select,"app\build\outputs\apk\release\app-release.apk"
) else if exist "app\build\outputs\apk\debug\app-debug.apk" (
    explorer /select,"app\build\outputs\apk\debug\app-debug.apk"
)
pause
