param([switch]$NoBrowser,[switch]$NoSetup,[switch]$FromGuard)
$ErrorActionPreference='Stop'
$env:PSModulePath=(Join-Path $PSHOME 'Modules')+';'+$env:PSModulePath
function Read-State($Port){try{$r=Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/api/v2/status" -TimeoutSec 3;[Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())|ConvertFrom-Json}catch{return $null}}
function Find-Browser {
    $candidates=@();foreach($name in @('chrome.exe','msedge.exe')){$found=Get-Command $name -ErrorAction SilentlyContinue;if($found){$candidates+=$found.Source};foreach($hive in @('HKCU:','HKLM:')){$key="$hive\Software\Microsoft\Windows\CurrentVersion\App Paths\$name";if(Test-Path $key){$candidates+=(Get-Item $key).GetValue('')}}}
    foreach($prefix in @($env:ProgramFiles,${env:ProgramFiles(x86)},$env:LOCALAPPDATA)){if($prefix){$candidates+=Join-Path $prefix 'Google/Chrome/Application/chrome.exe';$candidates+=Join-Path $prefix 'Microsoft/Edge/Application/msedge.exe'}}
    $candidates|Where-Object {$_ -and (Test-Path -LiteralPath $_ -PathType Leaf)}|Select-Object -First 1
}
function Find-Codex {
    $found=Get-Command codex.exe -ErrorAction SilentlyContinue;if($found){return $found.Source}
    $shim=Get-Command codex.cmd -ErrorAction SilentlyContinue
    if($shim){$scope=Join-Path (Split-Path $shim.Source -Parent) 'node_modules/@openai';if(Test-Path $scope){$binary=Get-ChildItem -LiteralPath $scope -Filter codex.exe -File -Recurse -ErrorAction SilentlyContinue|Select-Object -First 1;if($binary){return $binary.FullName}}}
    $folder=Join-Path $env:LOCALAPPDATA 'OpenAI/Codex/bin';if(Test-Path $folder){$candidate=Get-ChildItem -LiteralPath $folder -Filter codex.exe -File -Recurse|Sort-Object LastWriteTime -Descending|Select-Object -First 1;if($candidate){return $candidate.FullName}}
    return ''
}
$root=[IO.Path]::GetFullPath($PSScriptRoot);$config=Join-Path $root 'config/launcher.json'
$stopMarker=Join-Path $root 'config/manual-stop.flag'
if($FromGuard -and (Test-Path -LiteralPath $stopMarker)){Write-Output '用户已明确停止工作台，未自动恢复。';return}
if(!$FromGuard -and (Test-Path -LiteralPath $stopMarker)){Remove-Item -LiteralPath $stopMarker}
if(!(Test-Path -LiteralPath $config)){if($NoSetup){throw '尚未完成首次配置，请双击“配置模型”。'};& (Join-Path $root '配置模型.ps1')}
$c=Get-Content -LiteralPath $config -Raw -Encoding UTF8|ConvertFrom-Json
$port=[int]$c.port;if($port -lt 1024 -or $port -gt 65535 -or $port -in @(9527,9537)){throw '端口配置无效或保留给现有工作台，请使用9567等其他端口。'}
$url="http://127.0.0.1:$port/parallel.html";$registry=Join-Path $root 'logs/process.json'
$mutex=New-Object Threading.Mutex($false,('Local\InternshipWorkbench-'+$port));$locked=$mutex.WaitOne(0)
if(!$locked){$mutex.Dispose();throw '工作台正在启动，请稍候。'}
try{
    $state=Read-State $port
    if($state){
        if(!(Test-Path $registry)){throw '该端口已有未登记的服务，未启动第二个实例。'}
        $record=Get-Content $registry -Raw -Encoding UTF8|ConvertFrom-Json;$process=Get-CimInstance Win32_Process -Filter ('ProcessId='+[int]$record.pid)
        if($state.distribution -ne 'portable-preview' -or !$process -or !$process.CommandLine.Contains((Join-Path $root 'app/workbench.jar'))){throw '端口由另一个工作台使用，请先关闭它。'}
        if(!$NoBrowser){Start-Process $url};Write-Output '工作台已在运行，保留原启停状态。';return
    }
    if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw '端口被其他程序占用，未启动。'}
    $java=Join-Path $root 'runtime/java/bin/java.exe';$node=Join-Path $root 'runtime/node/node.exe';$jar=Join-Path $root 'app/workbench.jar';$driver=Join-Path $root 'driver'
    foreach($file in @($java,$node,$jar,(Join-Path $driver 'package/cli.js'))){if(!(Test-Path -LiteralPath $file -PathType Leaf)){throw '发布包不完整，请完整解压下载的Windows便携包。'}}
    $manifest=Get-Content (Join-Path $root 'release-manifest.json') -Raw -Encoding UTF8|ConvertFrom-Json
    if((Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $manifest.jarSha256){throw '程序校验失败，请重新下载发布包。'}
    $browser=$c.browserPath;if(!$browser){$browser=Find-Browser};if(!$browser){throw '未找到Chrome或Edge。请安装浏览器，或在“配置模型”中填写浏览器程序路径。'}
    $codex=$c.codexPath;if(!$codex){$codex=Find-Codex}
    foreach($name in @('logs','app/db')){New-Item -ItemType Directory -Path (Join-Path $root $name) -Force|Out-Null}
    $arguments=@('-Dfile.encoding=UTF-8',('-Dplaywright.cli.dir="'+$driver+'"'),'-jar',('"'+$jar+'"'),'--server.address=127.0.0.1',('--server.port='+$port),'--internship.browser-enabled=false','--internship.live-enabled=false','--internship.sending-enabled=false','--internship.model-enabled=false','--internship.v2.enabled=true','--internship.v2.protect-production-accounts=false','--internship.v2.live-enabled=true','--internship.v2.model-enabled=true','--internship.v2.sending-enabled=true','--internship.v2.notifications-enabled=true',('--internship.browser-executable="'+$browser+'"'),('--internship.model-provider='+$c.provider),('--internship.codex-model='+$c.model),('--internship.codex-executable="'+$codex+'"'),('--internship.codex-fast-mode='+([bool]$c.fastMode).ToString().ToLowerInvariant()))
    $envNames=@('PLAYWRIGHT_NODEJS_PATH','WORKBENCH_API_KEY','WORKBENCH_API_BASE','WORKBENCH_API_MODEL');$saved=@{};foreach($name in $envNames){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
    try{
        $env:PLAYWRIGHT_NODEJS_PATH=$node;$env:WORKBENCH_API_KEY='';$env:WORKBENCH_API_BASE='';$env:WORKBENCH_API_MODEL=''
        if($c.provider -eq 'api'){$secure=ConvertTo-SecureString $c.encryptedApiKey;$credential=New-Object Net.NetworkCredential('', $secure);$env:WORKBENCH_API_KEY=$credential.Password;$env:WORKBENCH_API_BASE=$c.apiBase;$env:WORKBENCH_API_MODEL=$c.model}
        $stamp=[DateTime]::Now.ToString('yyyyMMdd-HHmmss-fff')
        $process=Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory (Join-Path $root 'app') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root ('logs/service-'+$stamp+'.log')) -RedirectStandardError (Join-Path $root ('logs/error-'+$stamp+'.log'))
    }finally{foreach($name in $envNames){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
    @{pid=$process.Id;startedAt=$process.StartTime.ToUniversalTime().ToString('o');root=$root;port=$port}|ConvertTo-Json|Set-Content -LiteralPath $registry -Encoding UTF8
    $until=[DateTime]::UtcNow.AddSeconds(60);$state=$null
    do{Start-Sleep -Seconds 1;$state=Read-State $port;if(!(Get-Process -Id $process.Id -ErrorAction SilentlyContinue)){throw '工作台启动失败，详情在logs文件夹。'}}while(!$state -and [DateTime]::UtcNow -lt $until)
    if(!$state){throw '工作台仍未就绪，请查看logs文件夹；没有启动第二份。'}
    if(!$NoBrowser){Start-Process $url};Write-Output ('工作台已就绪：'+$url+'；定时状态：'+[bool]$state.schedule.enabled)
}finally{if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()}
