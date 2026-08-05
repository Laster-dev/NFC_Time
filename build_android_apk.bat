@echo off
title NFC App Build Script
echo ===================================================
echo             NFC App Build Script
echo ===================================================
echo.
set BUILD_DIR=%~dp0android
if not exist \ %BUILD_DIR%\ set BUILD_DIR=.\android
if not exist \%BUILD_DIR%\ set BUILD_DIR=android
if not exist \%BUILD_DIR%\ (
    echo [Error] android directory not found!
    pause
    exit /b 1
)
cd /d \%BUILD_DIR%\
echo [1/2] Checking Android SDK environment...
if defined ANDROID_HOME (
    echo  - Found ANDROID_HOME: %ANDROID_HOME%
) else if defined ANDROID_SDK_ROOT (
    echo  - Found ANDROID_SDK_ROOT: %ANDROID_SDK_ROOT%
) else (
    echo  - Tip: ANDROID_HOME not set in system environment variables.
)
echo.
echo [2/2] Building Android Debug APK...
echo.
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo.
    echo [Build Failed] Please check if Android SDK / JDK or Android Studio is configured.
    echo.
    pause
    exit /b %errorlevel%
)
echo.
echo ===================================================
echo [Build Success] APK generated successfully!
echo File path: android\\app\\build\\outputs\\apk\\debug\\app-debug.apk
echo ===================================================
echo.
if exist \app\\build\\outputs\\apk\\debug\\app-debug.apk\ (
    explorer /select,\app\\build\\outputs\\apk\\debug\\app-debug.apk\
)
pause
