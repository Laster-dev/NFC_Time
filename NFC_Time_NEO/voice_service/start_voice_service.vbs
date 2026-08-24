Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")

scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
pyScript = scriptDir & "\voice_service.py"

pyExe = "C:\Users\admin\AppData\Local\Programs\Python\Python314\python.exe"
If Not fso.FileExists(pyExe) Then
    pyExe = "python.exe"
End If

ws.CurrentDirectory = scriptDir
ws.Run """" & pyExe & """ """ & pyScript & """", 0, False
