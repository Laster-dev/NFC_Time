@echo off
title NFC_Time_NEO - Android APK Build
echo ===================================================
echo   NFC_Time_NEO Android Build Script
echo ===================================================
echo.

if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    ) else if exist "C:\Program Files\Android\openjdk\jdk-21.0.8" (
        set "JAVA_HOME=C:\Program Files\Android\openjdk\jdk-21.0.8"
    )
)

if defined JAVA_HOME (
    echo [1/2] JAVA_HOME: %JAVA_HOME%
)

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%android"

echo [2/2] Compiling Debug & Signed Release APKs...
call gradlew.bat assembleDebug assembleRelease

if %errorlevel% neq 0 (
    echo.
    echo ===================================================
    echo [Build Failed] Please check build errors above.
    echo ===================================================
    pause
    exit /b %errorlevel%
)

echo.
echo ===================================================
echo [Build Success] Both APKs generated successfully!
echo.
echo  - Debug APK:   android\app\build\outputs\apk\debug\app-debug.apk
echo  - Release APK: android\app\build\outputs\apk\release\app-release.apk
echo ===================================================
echo.

if exist "app\build\outputs\apk\release\app-release.apk" (
    explorer /select,"app\build\outputs\apk\release\app-release.apk"
)

pause
