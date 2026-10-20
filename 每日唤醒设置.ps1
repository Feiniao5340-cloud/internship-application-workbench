param([switch]$Remove)
$ErrorActionPreference='Stop'
$env:PSModulePath=(Join-Path $PSHOME 'Modules')+';'+$env:PSModulePath
$taskName='InternshipWorkbench-Portable-9567'
if($Remove){Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue;Write-Output '已取消Windows每日唤醒；工作台内的定时开关请按需自行关闭。';return}
$script=Join-Path $PSScriptRoot '启动工作台.ps1'
$action=New-ScheduledTaskAction -Execute (Join-Path $env:WINDIR 'System32/WindowsPowerShell/v1.0/powershell.exe') -Argument ('-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$script+'" -NoBrowser -NoSetup') -WorkingDirectory $PSScriptRoot
$triggers=@((New-ScheduledTaskTrigger -Daily -At '06:59'),(New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)))
$settings=New-ScheduledTaskSettingsSet -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
$principal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $triggers -Settings $settings -Principal $principal -Force|Out-Null
Write-Output '已设置登录及每日06:59恢复服务。请保持Windows已登录且电脑唤醒，并在工作台中自行启用每日定时。'
