[CmdletBinding()]
param([string]$Resultado)
# Corrige o agente ZeyhCasa depois que o IP da rede local mudou (ex.: apos mexer no
# roteador). Em vez de amarrar num IP fixo, o agente passa a escutar em 0.0.0.0 e o
# firewall passa a usar LocalAddress Any, mantendo RemoteAddress LocalSubnet. Assim o
# comando continua valendo mesmo que o DHCP troque o IP do PC de novo.
$ErrorActionPreference = 'Stop'
if (-not $Resultado) {
    $Resultado = Join-Path (Split-Path $PSScriptRoot -Parent) 'local/rede-correcao-resultado.json'
}
New-Item -ItemType Directory -Force -Path (Split-Path $Resultado -Parent) | Out-Null
try {
    $identidadeCasa = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]$identidadeCasa).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'O Windows exige administrador para ajustar a tarefa e a regra de firewall.'
    }
    $tarefaCasa = Get-ScheduledTask -TaskName 'ZeyhCasa' -ErrorAction Stop
    $acaoAtualCasa = $tarefaCasa.Actions[0]
    $argsAntesCasa = $acaoAtualCasa.Arguments
    if ($argsAntesCasa -match '--host\s+\S+') {
        $argsNovosCasa = $argsAntesCasa -replace '--host\s+\S+', '--host 0.0.0.0'
    } else {
        $argsNovosCasa = $argsAntesCasa.TrimEnd() + ' --host 0.0.0.0'
    }
    $acaoNovaCasa = New-ScheduledTaskAction -Execute $acaoAtualCasa.Execute -Argument $argsNovosCasa -WorkingDirectory $acaoAtualCasa.WorkingDirectory
    Set-ScheduledTask -TaskName 'ZeyhCasa' -Action $acaoNovaCasa | Out-Null

    $pythonwCasa = $acaoAtualCasa.Execute
    $regraCasa = Get-NetFirewallRule -Name 'ZeyhCasa-LAN' -ErrorAction SilentlyContinue
    if ($regraCasa) {
        $regraCasa | Set-NetFirewallRule -LocalAddress Any -RemoteAddress LocalSubnet | Out-Null
    } else {
        New-NetFirewallRule -Name 'ZeyhCasa-LAN' -DisplayName 'Zeyh Casa - somente rede local' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 -LocalAddress Any -RemoteAddress LocalSubnet -Profile Any -Program $pythonwCasa | Out-Null
    }

    Stop-ScheduledTask -TaskName 'ZeyhCasa' -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
    Start-ScheduledTask -TaskName 'ZeyhCasa'

    $escutandoCasa = $false
    for ($i = 0; $i -lt 20 -and -not $escutandoCasa; $i++) {
        Start-Sleep -Milliseconds 500
        if (Get-NetTCPConnection -State Listen -LocalPort 8765 -ErrorAction SilentlyContinue) { $escutandoCasa = $true }
    }
    $ipAtualCasa = (Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway }).IPv4Address.IPAddress

    [ordered]@{
        sucesso = $escutandoCasa
        data = (Get-Date).ToString('o')
        argumentosAntes = $argsAntesCasa
        argumentosDepois = $argsNovosCasa
        firewallLocalAddress = 'Any'
        firewallRemoteAddress = 'LocalSubnet'
        porta8765Escutando = $escutandoCasa
        ipAtualDoPc = $ipAtualCasa
    } | ConvertTo-Json | Set-Content -LiteralPath $Resultado -Encoding UTF8
    if (-not $escutandoCasa) { throw 'A tarefa foi ajustada, mas a porta 8765 nao subiu. Veja o agente.log.' }
} catch {
    [ordered]@{sucesso=$false; data=(Get-Date).ToString('o'); erro=$_.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath $Resultado -Encoding UTF8
    Write-Error $_
    exit 1
}
