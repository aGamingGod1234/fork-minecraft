param(
    [Parameter(Mandatory=$true)][string]$QueueRoot,
    [string]$TaskName='FORK-Singapore-Full-Queue'
)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$QueueRoot=(Resolve-Path -LiteralPath $QueueRoot).Path
$source=(Resolve-Path -LiteralPath $PSScriptRoot).Path
$script=Join-Path $source 'queue.py'
$runner=Join-Path $source 'Run-Queue.ps1'
if (-not (Test-Path -LiteralPath (Join-Path $QueueRoot 'seam-gate.json'))) { throw 'Coordinator seam gate must exist first.' }
$arguments='-NoProfile -NonInteractive -WindowStyle Hidden -File "'+$runner+'" -QueueRoot "'+$QueueRoot+'" -QueueScript "'+$script+'"'
$action=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings=New-ScheduledTaskSettingsSet -Hidden -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
$trigger=New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
$task=New-ScheduledTask -Action $action -Principal $principal -Settings $settings -Trigger $trigger
Register-ScheduledTask -TaskName $TaskName -InputObject $task -Force | Out-Null
Start-ScheduledTask -TaskName $TaskName
Get-ScheduledTask -TaskName $TaskName | Select-Object TaskName,State
