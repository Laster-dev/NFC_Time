Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
vbsPath = scriptDir & "\start_voice_service.vbs"

startupFolder = ws.SpecialFolders("Startup")
shortcutPath = startupFolder & "\NFC_Time_NEO_VoiceService.lnk"

Set shortcut = ws.CreateShortcut(shortcutPath)
shortcut.TargetPath = "wscript.exe"
shortcut.Arguments = """" & vbsPath & """"
shortcut.WorkingDirectory = scriptDir
shortcut.Description = "NFC_Time_NEO 语音播报服务 (开机自启)"
shortcut.Save

MsgBox "星野手作语音播报服务开机自启已成功安装！" & vbCrLf & vbCrLf & "已添加快捷方式至 Windows 启动文件夹：" & vbCrLf & shortcutPath, 64, "安装开机自启成功"
