param([switch]$Aplicar, [switch]$Restaurar)
$ErrorActionPreference = 'Stop'
$pastaDiagnosticoCasa = Join-Path (Split-Path $PSScriptRoot -Parent) 'local'
$arquivoBackupCasa = Join-Path $pastaDiagnosticoCasa 'wol-pme-antes.json'
$arquivoResultadoCasa = Join-Path $pastaDiagnosticoCasa 'wol-correcao-resultado.json'
New-Item -ItemType Directory -Force -Path $pastaDiagnosticoCasa | Out-Null
try {
    if ($Aplicar -and $Restaurar) { throw 'Escolha aplicar ou restaurar.' }
    $placaCasa = @(Get-NetAdapter | Where-Object { $_.MacAddress -eq '04-D9-F5-80-CB-AA' })
    if ($placaCasa.Count -ne 1 -or $placaCasa[0].InterfaceDescription -notlike '*I211*') {
        throw 'A placa Intel I211 esperada nao foi identificada. Nenhuma alteracao feita.'
    }
    $placaCasa = $placaCasa[0]
    $antesCasa = Get-NetAdapterAdvancedProperty -Name $placaCasa.Name -RegistryKeyword EnablePME
    if (-not ($Aplicar -or $Restaurar)) {
        $antesCasa | Select-Object Name, DisplayName, DisplayValue, RegistryValue
        return
    }
    $identidadeCasa = [Security.Principal.WindowsIdentity]::GetCurrent()
    if (-not ([Security.Principal.WindowsPrincipal]$identidadeCasa).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'O Windows exige permissao de administrador para alterar a placa de rede.'
    }
    if ($Aplicar) {
        if (-not (Test-Path -LiteralPath $arquivoBackupCasa)) {
            [ordered]@{
                data = (Get-Date).ToString('o')
                mac = $placaCasa.MacAddress
                interfaceGuid = [string]$placaCasa.InterfaceGuid
                keyword = 'EnablePME'
                valor = @($antesCasa.RegistryValue)
            } | ConvertTo-Json | Set-Content -LiteralPath $arquivoBackupCasa -Encoding UTF8
        }
        $valorCasa = '1'
    } else {
        $backupCasa = Get-Content -LiteralPath $arquivoBackupCasa -Raw | ConvertFrom-Json
        if ($backupCasa.mac -ne $placaCasa.MacAddress -or $backupCasa.interfaceGuid -ne [string]$placaCasa.InterfaceGuid -or $backupCasa.keyword -ne 'EnablePME') {
            throw 'O backup nao corresponde a esta placa.'
        }
        $valorCasa = [string]@($backupCasa.valor)[0]
        if ($valorCasa -notin @('0','1')) { throw 'Valor invalido no backup.' }
    }
    # Persist only the PME setting. Do not restart the adapter or the computer.
    Set-NetAdapterAdvancedProperty -Name $placaCasa.Name -RegistryKeyword EnablePME -RegistryValue $valorCasa -NoRestart
    $depoisCasa = Get-NetAdapterAdvancedProperty -Name $placaCasa.Name -RegistryKeyword EnablePME
    if ([string]@($depoisCasa.RegistryValue)[0] -ne $valorCasa) { throw 'A leitura de retorno nao confirmou a configuracao.' }
    [ordered]@{
        sucesso = $true
        data = (Get-Date).ToString('o')
        acao = $(if ($Restaurar) {'restaurar'} else {'aplicar'})
        placa = $placaCasa.InterfaceDescription
        mac = $placaCasa.MacAddress
        antes = @($antesCasa.RegistryValue)
        depois = @($depoisCasa.RegistryValue)
        reinicioAutomatico = $false
        reiniciarWindowsDepois = $true
        testeRealDeDespertar = $false
    } | ConvertTo-Json | Set-Content -LiteralPath $arquivoResultadoCasa -Encoding UTF8
} catch {
    [ordered]@{sucesso=$false; data=(Get-Date).ToString('o'); erro=$_.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath $arquivoResultadoCasa -Encoding UTF8
    Write-Error $_
    exit 1
}
