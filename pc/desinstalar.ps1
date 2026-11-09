$ErrorActionPreference = 'Stop'
Stop-ScheduledTask -TaskName 'ZeyhCasa' -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName 'ZeyhCasa' -Confirm:$false -ErrorAction SilentlyContinue
Get-NetFirewallRule -Name 'ZeyhCasa-LAN' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host 'Agente e acesso pelo firewall desativados. Os dados de pareamento foram preservados em LOCALAPPDATA/ZeyhCasa.'
