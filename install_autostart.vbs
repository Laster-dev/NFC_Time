Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
vbsPath = scriptDir & "\start_service.vbs"

startupFolder = ws.SpecialFolders("Startup")
shortcutPath = startupFolder & "\NFC_Time_Service.lnk"

Set shortcut = ws.CreateShortcut(shortcutPath)
shortcut.TargetPath = "wscript.exe"
shortcut.Arguments = """" & vbsPath & """"
shortcut.WorkingDirectory = scriptDir
shortcut.Description = "NFC Time Service"
shortcut.Save

MsgBox "开机自启已成功设置！" & vbCrLf & vbCrLf & "快捷方式已添加到 Windows 启动项文件夹中。", 64, "设置成功"
