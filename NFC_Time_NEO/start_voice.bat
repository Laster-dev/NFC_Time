@echo off
title NFC_Time_NEO - Voice Broadcast Service
echo ===================================================
echo   NFC_Time_NEO 智能语音播报服务
echo   (仅播报先付款倒计时，免打扰场次套餐与后付款)
echo ===================================================
echo.

cd /d "%~dp0voice_service"
python voice_service.py
pause
