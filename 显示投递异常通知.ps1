param([Parameter(Mandatory=$true)][string]$EventFile,[switch]$ValidateOnly)
$ErrorActionPreference = 'Stop'
$event = Get-Content -LiteralPath $EventFile -Encoding UTF8 -Raw | ConvertFrom-Json
$beijing = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTime]::Parse($event.time).ToUniversalTime(),'China Standard Time')
$platformNames=@{boss='BOSS直聘';shixiseng='实习僧';zhilian='智联招聘';job51='应届生实习';lagou='拉勾'}
$label=if($event.platform){$platformNames[$event.platform]}else{'实习投递'}
$title = if ($event.type -eq 'daily_limit') { "$label：今天已停止投递" } elseif ($event.type -eq 'round_expired') { "$label：排队轮次已超时" } else { "$label：运行失败" }
$pauseText=if($event.paused -eq $false){'该轮未执行；平台未因此暂停。'}else{'该平台后续投递已暂停，请查看工作台记录。'}
$message = $beijing.ToString('MM-dd HH:mm:ss') + '（北京时间）' + "`r`n轮次：" + $event.round + "`r`n" + $event.message + "`r`n"+$pauseText
if ($ValidateOnly) { @{title=$title;message=$message} | ConvertTo-Json -Compress; return }
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$icon = New-Object System.Windows.Forms.NotifyIcon
try {
    $icon.Icon = [System.Drawing.SystemIcons]::Warning
    $icon.Text = '实习投递工作台'
    $icon.Visible = $true
    $icon.ShowBalloonTip(10000,$title,$message,[System.Windows.Forms.ToolTipIcon]::Warning)
    Start-Sleep -Seconds 15
    Write-Output ('已触发Windows通知：' + $event.round)
} finally { $icon.Dispose() }
