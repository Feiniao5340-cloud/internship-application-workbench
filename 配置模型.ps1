param([switch]$Quiet)
$ErrorActionPreference='Stop'
$env:PSModulePath=(Join-Path $PSHOME 'Modules')+';'+$env:PSModulePath
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$file=Join-Path $PSScriptRoot 'config/launcher.json'
$settings=[ordered]@{provider='codex';model='gpt-6-luna';apiBase='https://api.openai.com/v1';encryptedApiKey='';browserPath='';codexPath='';port=9567;fastMode=$false}
if(Test-Path -LiteralPath $file){$old=Get-Content -LiteralPath $file -Raw -Encoding UTF8|ConvertFrom-Json;foreach($key in @($settings.Keys)){if($old.PSObject.Properties.Name -contains $key){$settings[$key]=$old.$key}}}
$form=New-Object Windows.Forms.Form
$form.Text='实习投递工作台 · 首次配置 / 模型设置';$form.Size=New-Object Drawing.Size(660,615);$form.StartPosition='CenterScreen';$form.FormBorderStyle='FixedDialog';$form.MaximizeBox=$false
function Add-Field($name,$title,$top,$value){$label=New-Object Windows.Forms.Label;$label.Text=$title;$label.Location=New-Object Drawing.Point(22,$top);$label.Size=New-Object Drawing.Size(610,24);$form.Controls.Add($label);$box=New-Object Windows.Forms.TextBox;$box.Name=$name;$box.Text=$value;$box.Location=New-Object Drawing.Point(22,($top+25));$box.Size=New-Object Drawing.Size(600,26);$form.Controls.Add($box);return $box}
$label=New-Object Windows.Forms.Label;$label.Text='每位使用者配置自己的模型账号，程序不会附带或共享作者账号。';$label.Location=New-Object Drawing.Point(22,15);$label.Size=New-Object Drawing.Size(610,32);$form.Controls.Add($label)
$provider=New-Object Windows.Forms.ComboBox;$provider.DropDownStyle='DropDownList';[void]$provider.Items.AddRange(@('自己的Codex登录','自己的模型API'));$provider.SelectedIndex=if($settings.provider -eq 'api'){1}else{0};$provider.Location=New-Object Drawing.Point(22,54);$provider.Size=New-Object Drawing.Size(600,28);$form.Controls.Add($provider)
$model=Add-Field 'model' '模型名称（须为自己账号可用的模型）' 95 $settings.model
$apiBase=Add-Field 'apiBase' 'API基础地址，或完整的 /responses、/chat/completions 地址' 160 $settings.apiBase
$apiKey=Add-Field 'apiKey' 'API密钥（Windows本机用户加密保存；已保存时可留空保留）' 225 '';$apiKey.UseSystemPasswordChar=$true
$browser=Add-Field 'browser' 'Chrome / Edge程序路径（一般留空自动检测，非用户资料目录）' 290 $settings.browserPath
$codex=Add-Field 'codex' 'Codex程序路径（一般留空自动检测，API模式无需Codex）' 355 $settings.codexPath
$hint=New-Object Windows.Forms.Label;$hint.Text='初始只筛选：首次打开后填写个人资料、上传简历并手动登录招聘网站。修改模型配置后须停止并重新启动工作台。';$hint.Location=New-Object Drawing.Point(22,422);$hint.Size=New-Object Drawing.Size(600,50);$form.Controls.Add($hint)
$save=New-Object Windows.Forms.Button;$save.Text='保存配置';$save.Location=New-Object Drawing.Point(22,490);$save.Size=New-Object Drawing.Size(130,36);$form.Controls.Add($save)
$help=New-Object Windows.Forms.Button;$help.Text='Codex安装说明';$help.Location=New-Object Drawing.Point(170,490);$help.Size=New-Object Drawing.Size(150,36);$form.Controls.Add($help);$help.Add_Click({Start-Process 'https://learn.chatgpt.com/docs/codex/cli'})
$save.Add_Click({try{
    if($model.Text.Trim() -notmatch '^[A-Za-z0-9_.:/-]+$'){throw '请填写有效的模型名称。'}
    $settings.provider=if($provider.SelectedIndex -eq 1){'api'}else{'codex'};$settings.model=$model.Text.Trim();$settings.apiBase=$apiBase.Text.Trim();$settings.browserPath=$browser.Text.Trim();$settings.codexPath=$codex.Text.Trim()
    if($settings.provider -eq 'api'){
        $uri=[Uri]$settings.apiBase
        if(!$uri.IsAbsoluteUri -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or !($uri.Scheme -eq 'https' -or ($uri.Scheme -eq 'http' -and $uri.Host -in @('127.0.0.1','localhost')))){throw 'API地址须为HTTPS，或本机HTTP地址。'}
        if($apiKey.Text){$settings.encryptedApiKey=ConvertFrom-SecureString (ConvertTo-SecureString $apiKey.Text -AsPlainText -Force)}
        if(!$settings.encryptedApiKey){throw '请填写自己的API密钥。'}
    }
    foreach($name in @('browserPath','codexPath')){if($settings[$name] -and !(Test-Path -LiteralPath $settings[$name] -PathType Leaf)){throw '指定的程序不存在，请重新选择或留空自动检测。'}}
    New-Item -ItemType Directory -Path (Split-Path $file -Parent) -Force|Out-Null
    $temp=$file+'.tmp';$settings|ConvertTo-Json|Set-Content -LiteralPath $temp -Encoding UTF8;Move-Item -LiteralPath $temp -Destination $file -Force
    $form.DialogResult=[Windows.Forms.DialogResult]::OK;$form.Close()
}catch{[Windows.Forms.MessageBox]::Show($_.Exception.Message,'配置未保存')|Out-Null}})
$result=$form.ShowDialog();$form.Dispose();if($result -ne [Windows.Forms.DialogResult]::OK){throw '配置已取消，未启动。'}
