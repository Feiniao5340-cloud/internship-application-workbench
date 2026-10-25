$ErrorActionPreference='Stop'
$beijing=[TimeZoneInfo]::ConvertTimeBySystemTimeZoneId([DateTimeOffset]::UtcNow,'China Standard Time')
if($beijing.Hour -lt 6 -or ($beijing.Hour -eq 6 -and $beijing.Minute -lt 55) -or $beijing.Hour -ge 17){return}
if(Test-Path -LiteralPath (Join-Path $PSScriptRoot 'config/manual-stop.flag')){return}
$path=Join-Path $PSScriptRoot 'config/launcher.json'
if(!(Test-Path -LiteralPath $path)){return}
$config=Get-Content -LiteralPath $path -Raw -Encoding UTF8|ConvertFrom-Json
$port=[int]$config.port
if($port -lt 1024 -or $port -gt 65535 -or $port -in @(9527,9537)){throw '端口配置无效，未操作其他实例。'}
try{$state=Invoke-RestMethod -Uri "http://127.0.0.1:$port/api/v2/status" -TimeoutSec 8;if($state.distribution -ne 'portable-preview'){throw '端口上的程序不是本发布版。'};return}catch{
    if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw '服务无响应或身份不符，未强制重启。'}
}
New-Item -ItemType Directory -Path (Join-Path $PSScriptRoot 'logs') -Force|Out-Null
try{
    & (Join-Path $PSScriptRoot '启动工作台.ps1') -NoBrowser -NoSetup -FromGuard
    @{at=[DateTimeOffset]::UtcNow.ToString('o');action='service-restored';message='仅恢复服务；定时开关、平台暂停和未确认发送保护均未改变。'}|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $PSScriptRoot 'logs/service-guard.jsonl') -Encoding UTF8
}catch{
    @{at=[DateTimeOffset]::UtcNow.ToString('o');action='restore-failed';message=$_.Exception.Message}|ConvertTo-Json -Compress|Add-Content -LiteralPath (Join-Path $PSScriptRoot 'logs/service-guard.jsonl') -Encoding UTF8
    throw
}
