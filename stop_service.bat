@echo off
chcp 65001 >nul
echo 正在停止 NFC 卡片倒计时语音提醒后台服务...
wmic process where "commandline like '%%nfc_time_service%%'" call terminate >nul 2>&1
taskkill /f /fi "WINDOWTITLE eq NFC_Time_Service*" >nul 2>&1
echo 广播监控后台服务已停止。
pause
