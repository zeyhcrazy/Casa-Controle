$ErrorActionPreference = 'Stop'
$caminhoInstaladorCasa = Join-Path $PSScriptRoot 'instalar.ps1'
$comandoInstaladorCasa = "& '" + $caminhoInstaladorCasa.Replace("'", "''") + "'"
$argumentoInstaladorCasa = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($comandoInstaladorCasa))
# This is the interactive installer. Keep its code visible after UAC approval;
# the installed background agent itself uses pythonw with no visible console.
Start-Process powershell.exe -WindowStyle Normal -Verb RunAs -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-NoExit', '-EncodedCommand', $argumentoInstaladorCasa)
