Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

startupFolder = ws.SpecialFolders("Startup")
shortcutPath = startupFolder & "\NFC_Time_NEO_VoiceService.lnk"

If fso.FileExists(shortcutPath) Then
    fso.DeleteFile(shortcutPath)
    MsgBox "已成功移除星野手作语音播报服务的开机自启快捷方式！", 64, "卸载开机自启成功"
Else
    MsgBox "未找到自启快捷方式，无需移除。", 48, "提示"
End If
