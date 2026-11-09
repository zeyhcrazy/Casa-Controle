$ErrorActionPreference = 'Stop'
$destinoCasa = Join-Path $env:LOCALAPPDATA 'ZeyhCasa'
& python.exe (Join-Path $destinoCasa 'agente.py') --pair --data $destinoCasa
