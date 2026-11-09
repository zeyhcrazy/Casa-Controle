$ErrorActionPreference = 'Stop'
$origemCasa = $PSScriptRoot
$destinoCasa = Join-Path $env:LOCALAPPDATA 'ZeyhCasa'
$raizCasa = Split-Path $PSScriptRoot -Parent
$resultadoCasa = Join-Path $raizCasa 'local/agente-atualizado-0.4.json'
$arquivosCasa = @('agente.py','anydesk_control.py')
$obsoletosCasa = @('remote_win32.py','remote_service.py')
$tarefaParadaCasa = $false
$backupCasa = Join-Path $destinoCasa ('backup-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
try {
    $identidadeCasa = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]$identidadeCasa).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'O Windows exige administrador para reiniciar a tarefa do agente.'
    }
    $tarefaCasa = Get-ScheduledTask -TaskName ZeyhCasa -ErrorAction Stop
    if (-not (Test-Path -LiteralPath (Join-Path $destinoCasa 'cert.pem'))) { throw 'O agente existente nao foi identificado. Use o instalador inicial.' }
    foreach ($arquivoCasa in $arquivosCasa) {
        if (-not (Test-Path -LiteralPath (Join-Path $origemCasa $arquivoCasa))) { throw "Arquivo ausente: $arquivoCasa" }
    }
    New-Item -ItemType Directory -Path $backupCasa | Out-Null
    foreach ($arquivoCasa in ($arquivosCasa + $obsoletosCasa)) {
        $atualCasa = Join-Path $destinoCasa $arquivoCasa
        if (Test-Path -LiteralPath $atualCasa) { Copy-Item -LiteralPath $atualCasa -Destination (Join-Path $backupCasa $arquivoCasa) }
    }
    $certAntesCasa = (Get-FileHash -LiteralPath (Join-Path $destinoCasa 'cert.pem')).Hash
    Stop-ScheduledTask -TaskName ZeyhCasa
    $tarefaParadaCasa = $true
    foreach ($arquivoCasa in $arquivosCasa) { Copy-Item -LiteralPath (Join-Path $origemCasa $arquivoCasa) -Destination (Join-Path $destinoCasa $arquivoCasa) -Force }
    foreach ($arquivoCasa in $obsoletosCasa) {
        $alvoCasa = Join-Path $destinoCasa $arquivoCasa
        if (Test-Path -LiteralPath $alvoCasa) { Remove-Item -LiteralPath $alvoCasa -Force }
        $cacheCasa = Join-Path $destinoCasa '__pycache__'
        if (Test-Path -LiteralPath $cacheCasa) {
            $nomeModuloCasa = [IO.Path]::GetFileNameWithoutExtension($arquivoCasa)
            Get-ChildItem -LiteralPath $cacheCasa -Filter ($nomeModuloCasa + '.*.pyc') -File | ForEach-Object { Remove-Item -LiteralPath $_.FullName -Force }
        }
    }
    Start-ScheduledTask -TaskName ZeyhCasa
    $tarefaParadaCasa = $false
    $certDepoisCasa = (Get-FileHash -LiteralPath (Join-Path $destinoCasa 'cert.pem')).Hash
    if ($certAntesCasa -ne $certDepoisCasa) { throw 'O certificado foi alterado inesperadamente.' }
    [ordered]@{sucesso=$true;data=(Get-Date).ToString('o');backup=$backupCasa;certificadoPreservado=$true;pareamentosPreservados=$true;reiniciouPc=$false;capturaRemovida=$true;versao='0.4.0'} |
        ConvertTo-Json | Set-Content -LiteralPath $resultadoCasa -Encoding UTF8
} catch {
    $erroCasa = $_.Exception.Message
    if (Test-Path -LiteralPath $backupCasa) {
        Stop-ScheduledTask -TaskName ZeyhCasa -ErrorAction SilentlyContinue
        foreach ($arquivoCasa in ($arquivosCasa + $obsoletosCasa)) {
            $antigoCasa = Join-Path $backupCasa $arquivoCasa
            if (Test-Path -LiteralPath $antigoCasa) { Copy-Item -LiteralPath $antigoCasa -Destination (Join-Path $destinoCasa $arquivoCasa) -Force }
        }
        Start-ScheduledTask -TaskName ZeyhCasa -ErrorAction SilentlyContinue
    } elseif ($tarefaParadaCasa) { Start-ScheduledTask -TaskName ZeyhCasa -ErrorAction SilentlyContinue }
    [ordered]@{sucesso=$false;data=(Get-Date).ToString('o');erro=$erroCasa} | ConvertTo-Json | Set-Content -LiteralPath $resultadoCasa -Encoding UTF8
    Write-Error $erroCasa
    exit 1
}
