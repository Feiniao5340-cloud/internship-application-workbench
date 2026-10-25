param([string]$Date='')
$ErrorActionPreference='Stop'
$python=Get-Command python.exe -ErrorAction SilentlyContinue
if(!$python){throw '日报脚本需要Python 3.10或更新版本；投递工作台本身不依赖Python。请安装后再生成日报。'}
$arguments=@((Join-Path $PSScriptRoot 'scripts/daily_report.py'),'--database',(Join-Path $PSScriptRoot 'app/data/parallel/workbench.db'),'--output',(Join-Path $PSScriptRoot 'reports'))
if($Date){$arguments+=@('--date',$Date)}
$env:PYTHONIOENCODING='utf-8'
& $python.Source @arguments
if($LASTEXITCODE -ne 0){throw '日报生成失败，未将缺失数据视为零。'}
