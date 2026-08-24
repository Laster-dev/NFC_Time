Set ws = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
scriptDir = fso.GetParentFolderName(WScript.ScriptFullName)
pyScript = scriptDir & "\nfc_time_service.py"
pyExe = "C:\Users\admin\AppData\Local\Programs\Python\Python314\python.exe"

If Not fso.FileExists(pyExe) Then
    pyExe = "python.exe"
End If

ws.Run """" & pyExe & """ """ & pyScript & """", 0, False
