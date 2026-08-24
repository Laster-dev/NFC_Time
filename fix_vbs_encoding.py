vbs_start = '''Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
pyScript = scriptDir & "\\nfc_time_service.py"
pyExe = "C:\\Users\\admin\\AppData\\Local\\Programs\\Python\\Python314\\python.exe"

If Not fso.FileExists(pyExe) Then
    pyExe = "python.exe"
End If

ws.Run """" & pyExe & """ """ & pyScript & """", 0, False
'''

with open(r"c:\Users\admin\Desktop\NFC_Time\start_service.vbs", "w", encoding="gbk") as f:
    f.write(vbs_start)

vbs_install = '''Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
vbsPath = scriptDir & "\\start_service.vbs"

startupFolder = ws.SpecialFolders("Startup")
shortcutPath = startupFolder & "\\NFC_Time_Service.lnk"

Set shortcut = ws.CreateShortcut(shortcutPath)
shortcut.TargetPath = "wscript.exe"
shortcut.Arguments = """" & vbsPath & """"
shortcut.WorkingDirectory = scriptDir
shortcut.Description = "NFC Time Service"
shortcut.Save

MsgBox "开机自启已成功设置！" & vbCrLf & vbCrLf & "快捷方式已添加到 Windows 启动项文件夹中。", 64, "设置成功"
'''

with open(r"c:\Users\admin\Desktop\NFC_Time\install_autostart.vbs", "w", encoding="gbk") as f:
    f.write(vbs_install)

print("VBScript files converted to GBK/ANSI encoding successfully!")
