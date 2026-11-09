[CmdletBinding()]
param([string]$HostIp = '', [switch]$Simular)
$ErrorActionPreference = 'Stop'
# Sem -HostIp, detecta o IPv4 atual da rede de casa. Ele e usado so para o
# certificado; o agente escuta em 0.0.0.0 e o firewall restringe a sub-rede local,
# entao o comando continua valendo mesmo que o DHCP troque o IP do PC depois.
if (-not $HostIp) {
    $HostIp = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway }).IPv4Address.IPAddress | Select-Object -First 1
    if (-not $HostIp) { throw 'Nao foi possivel detectar o IP da rede local. Informe com -HostIp.' }
}
$destinoCasa = Join-Path $env:LOCALAPPDATA 'ZeyhCasa'
$identidadeCasa = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$adminCasa = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $adminCasa) { throw 'Execute este instalador como administrador para criar a regra local do firewall.' }
$pythonCasa = (Get-Command python.exe -ErrorAction Stop).Source
$pythonwCasa = Join-Path (Split-Path $pythonCasa) 'pythonw.exe'
if (-not (Test-Path -LiteralPath $pythonwCasa)) { throw 'pythonw.exe não encontrado ao lado do Python.' }
$ipCasa = [Net.IPAddress]::Parse($HostIp)
if ($ipCasa.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Informe um endereço IPv4 da rede de casa.' }
if (-not (Get-NetIPAddress -AddressFamily IPv4 -IPAddress $HostIp -ErrorAction SilentlyContinue)) { throw "Este PC não está usando o IP $HostIp. Confira o endereço antes de instalar." }
New-Item -ItemType Directory -Force -Path $destinoCasa | Out-Null
# Protect the certificate key, pairing code and token hashes before creating them.
& icacls.exe $destinoCasa /inheritance:r /grant:r "${identidadeCasa}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Não foi possível proteger a pasta do agente.' }
$agenteCasa = Join-Path $destinoCasa 'agente.py'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'agente.py') -Destination $agenteCasa -Force
foreach ($moduloCasa in @('anydesk_control.py')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $moduloCasa) -Destination (Join-Path $destinoCasa $moduloCasa) -Force
}
& $pythonCasa $agenteCasa --init --data $destinoCasa --host $HostIp
if ($LASTEXITCODE -ne 0) { throw 'Falha ao preparar o certificado do agente.' }
$argumentosCasa = '"' + $agenteCasa + '" --serve --data "' + $destinoCasa + '" --host 0.0.0.0'
if ($Simular) { $argumentosCasa += ' --dry-run' }
$acaoCasa = New-ScheduledTaskAction -Execute $pythonwCasa -Argument $argumentosCasa -WorkingDirectory $destinoCasa
$gatilhoCasa = New-ScheduledTaskTrigger -AtLogOn -User $identidadeCasa
$principalCasa = New-ScheduledTaskPrincipal -UserId $identidadeCasa -LogonType Interactive -RunLevel Limited
$opcoesCasa = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
if (Get-ScheduledTask -TaskName 'ZeyhCasa' -ErrorAction SilentlyContinue) { Stop-ScheduledTask -TaskName 'ZeyhCasa' -ErrorAction SilentlyContinue }
Register-ScheduledTask -TaskName 'ZeyhCasa' -Description 'Recebe comandos autenticados do app Casa na rede local.' -Action $acaoCasa -Trigger $gatilhoCasa -Principal $principalCasa -Settings $opcoesCasa -Force | Out-Null
Get-NetFirewallRule -Name 'ZeyhCasa-LAN' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -Name 'ZeyhCasa-LAN' -DisplayName 'Zeyh Casa - somente rede local' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 -LocalAddress Any -RemoteAddress LocalSubnet -Profile Any -Program $pythonwCasa | Out-Null
Start-ScheduledTask -TaskName 'ZeyhCasa'
& $pythonCasa $agenteCasa --pair --data $destinoCasa
Write-Host 'No celular: Casa > Conectar computador > digite o código acima.'
if ($Simular) { Write-Host 'MODO DE SIMULAÇÃO: pedidos de desligamento não desligam o PC.' }
