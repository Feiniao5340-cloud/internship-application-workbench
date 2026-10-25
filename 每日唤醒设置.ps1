param([switch]$Remove)
$ErrorActionPreference='Stop'
$env:PSModulePath=(Join-Path $PSHOME 'Modules')+';'+$env:PSModulePath
$config=Join-Path $PSScriptRoot 'config/launcher.json'
if(!(Test-Path -LiteralPath $config)){throw '请先完成模型和端口配置。'}
$c=Get-Content -LiteralPath $config -Raw -Encoding UTF8|ConvertFrom-Json
$port=[int]$c.port
if($port -lt 1024 -or $port -gt 65535 -or $port -in @(9527,9537)){throw '端口无效，未注册任务。'}
$taskName='InternshipWorkbench-Portable-'+$port
$guardName=$taskName+'-Guard'
if($Remove){foreach($name in @($taskName,$guardName)){Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue};Write-Output '已取消每日唤醒及本地服务守护。';return}
if((Get-TimeZone).Id -ne 'China Standard Time'){throw '请先将Windows时区设为北京时间后再启用默认每日任务。'}
$shell=Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe'
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
$settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -WakeToRun -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit (New-TimeSpan -Minutes 3) -MultipleInstances IgnoreNew
$script=Join-Path $PSScriptRoot '启动工作台.ps1'
$action=New-ScheduledTaskAction -Execute $shell -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$script+'" -NoBrowser -NoSetup -FromGuard') -WorkingDirectory $PSScriptRoot
$triggers=@((New-ScheduledTaskTrigger -Daily -At '06:55'),(New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)))
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $triggers -Settings $settings -Principal $principal -Force|Out-Null
$script=Join-Path $PSScriptRoot '服务在线守护.ps1'
$action=New-ScheduledTaskAction -Execute $shell -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$script+'"') -WorkingDirectory $PSScriptRoot
$trigger=New-ScheduledTaskTrigger -Daily -At '06:55'
$repeat=New-ScheduledTaskTrigger -Once -At ([DateTime]::Today.AddHours(6).AddMinutes(55)) -RepetitionInterval (New-TimeSpan -Minutes 1) -RepetitionDuration (New-TimeSpan -Hours 10 -Minutes 5)
$trigger.Repetition=$repeat.Repetition
Register-ScheduledTask -TaskName $guardName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force|Out-Null
Write-Output '已设置06:55唤醒和运行时段服务守护。请在网页自行开启每日定时；点击停止工作台后不会被守护重新打开，手动启动可恢复。'
