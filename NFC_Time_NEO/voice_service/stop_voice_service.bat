@echo off
chcp 65001 >nul
echo 正在停止 NFC_Time_NEO 语音播报服务...
wmic process where "commandline like '%%voice_service.py%%' and name like 'python%%'" call terminate >nul 2>&1
taskkill /F /FI "WINDOWTITLE eq NFC_Time_Voice" >nul 2>&1
echo [OK] 语音播报服务已停止。
timeout /t 2 /nobreak >nul
