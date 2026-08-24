@echo off
chcp 65001 >nul
echo 正在重启 NFC_Time_NEO 语音播报服务...
wmic process where "commandline like '%%voice_service.py%%' and name like 'python%%'" call terminate >nul 2>&1
timeout /t 1 /nobreak >nul
wscript "%~dp0start_voice_service.vbs"
echo [OK] 语音播报服务已在后台静默启动！
echo 浏览器管理后台: http://127.0.0.1:5050
timeout /t 2 /nobreak >nul
