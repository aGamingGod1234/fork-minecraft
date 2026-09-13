param(
    [Parameter(Mandatory=$true)][string]$QueueRoot,
    [Parameter(Mandatory=$true)][string]$QueueScript
)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$QueueRoot=(Resolve-Path -LiteralPath $QueueRoot).Path
$QueueScript=(Resolve-Path -LiteralPath $QueueScript).Path
$gate=Join-Path $QueueRoot 'seam-gate.json'
if (-not (Test-Path -LiteralPath $gate -PathType Leaf)) { throw 'Seam gate is missing; queue will not start.' }
$python=& py -3 -c "import sys; print(sys.executable)"
$taskArgs=@('"' + $QueueScript + '"','run','"' + $QueueRoot + '"','--gate','"' + $gate + '"')
$worker=Start-Process -FilePath $python.Trim() -ArgumentList $taskArgs -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput (Join-Path $QueueRoot 'scheduler.stdout.log') -RedirectStandardError (Join-Path $QueueRoot 'scheduler.stderr.log')
exit $worker.ExitCode
