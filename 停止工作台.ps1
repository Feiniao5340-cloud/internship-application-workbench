$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'config') -Force|Out-Null
Set-Content -LiteralPath (Join-Path $PSScriptRoot 'config/manual-stop.flag') -Value ([DateTimeOffset]::UtcNow.ToString('o')) -Encoding UTF8
$env:PSModulePath=(Join-Path $PSHOME 'Modules')+';'+$env:PSModulePath
$registry=Join-Path $PSScriptRoot 'logs/process.json';if(!(Test-Path $registry)){Write-Output '工作台没有运行记录。';return}
$record=Get-Content $registry -Raw -Encoding UTF8|ConvertFrom-Json;$process=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$record.pid)
if(!$process){Write-Output '工作台已经关闭。';return}
$expected=Join-Path $PSScriptRoot 'app/workbench.jar'
if(!$process.CommandLine.Contains($expected)){throw '进程身份已变化，未关闭其他程序。'}
Invoke-RestMethod -Method Post "http://127.0.0.1:$($record.port)/api/v2/shutdown" -TimeoutSec 6|Out-Null
$until=[DateTime]::UtcNow.AddSeconds(30);while((Get-Process -Id $record.pid -ErrorAction SilentlyContinue) -and [DateTime]::UtcNow -lt $until){Start-Sleep -Milliseconds 300}
if(Get-Process -Id $record.pid -ErrorAction SilentlyContinue){throw '仍在安全关闭中，请等待，不要重复启动。'}
Write-Output '工作台已关闭，定时已停用，个人资料和登录记录保留。'
