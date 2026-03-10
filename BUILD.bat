@echo off
setlocal enabledelayedexpansion
title RDP Pro Build

echo.
echo  ╔══════════════════════════════════════╗
echo  ║       RDP Pro - Kotlin Native        ║
echo  ║           Building APK...            ║
echo  ╚══════════════════════════════════════╝
echo.

set "PROJ=%~dp0"
cd /d "%PROJ%"

:: Find Gradle from Flutter cache
set "GRADLE="
for /r "%USERPROFILE%\.gradle\wrapper\dists" %%G in (gradle.bat) do (
    if "!GRADLE!"=="" set "GRADLE=%%G"
)

if "!GRADLE!"=="" (
    echo [ERROR] Gradle غير موجود. شغّل SETUP.ps1 اولاً
    pause
    exit /b 1
)

echo [+] Gradle: !GRADLE!
echo [+] SDK: C:\Android
echo [+] Building...
echo.

:: Write local.properties
echo sdk.dir=C\:\\Android> local.properties

:: Build
"!GRADLE!" assembleDebug --stacktrace

if %ERRORLEVEL% EQU 0 (
    echo.
    echo  ══════════════════════════════════════
    echo   SUCCESS - APK جاهز!
    echo  ══════════════════════════════════════
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "%USERPROFILE%\Desktop\RDPPro.apk"
    echo   نُسخ إلى Desktop: RDPPro.apk
    echo  ══════════════════════════════════════
) else (
    echo  [ERROR] فشل البناء
)
pause
