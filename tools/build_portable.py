"""Assemble a fresh Windows package from code and explicitly supplied runtimes, never user data."""
import argparse,hashlib,json,shutil,subprocess,urllib.request,zipfile
from pathlib import Path

VERSION='2.1.0'
ROOT=Path(__file__).resolve().parents[1]
SCRIPTS=['配置模型.ps1','启动工作台.ps1','停止工作台.ps1','每日唤醒设置.ps1','显示投递异常通知.ps1','服务在线守护.ps1','生成投递日报.ps1']
DOCS=['README.md','使用说明.md','PRIVACY.md','THIRD_PARTY.md','RELEASE_CHECKLIST.md','get_jobs/LICENSE']

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def build(java_home,node,driver,output):
    java_home=java_home.resolve();node=node.resolve();driver=driver.resolve();output=output.resolve()
    if output.exists():raise ValueError('Output already exists; use a new directory. No files were overwritten.')
    jar=ROOT/f'get_jobs/build/libs/get_jobs-{VERSION}.jar'
    for item in [java_home/'bin/jlink.exe',node,driver/'package/cli.js',jar]:
        if not item.is_file():raise ValueError('Missing dependency: '+str(item))
    output.mkdir(parents=True);(output/'app').mkdir();(output/'runtime').mkdir();(output/'runtime/node').mkdir()
    (output/'scripts').mkdir();shutil.copy2(ROOT/'scripts/daily_report.py',output/'scripts/daily_report.py')
    shutil.copy2(jar,output/'app/workbench.jar');shutil.copy2(node,output/'runtime/node/node.exe')
    subprocess.run([str(java_home/'bin/jlink.exe'),'--module-path',str(java_home/'jmods'),'--add-modules','ALL-MODULE-PATH','--strip-debug','--no-header-files','--no-man-pages','--output',str(output/'runtime/java')],check=True)
    if (java_home/'NOTICE').is_file():shutil.copy2(java_home/'NOTICE',output/'runtime/java/NOTICE')
    # Generated package-manager shims can embed the build machine's absolute paths.
    # The launcher starts package/cli.js directly, so these .bin helpers are unused.
    shutil.copytree(driver/'package',output/'driver/package',ignore=shutil.ignore_patterns('.bin'))
    node_version=subprocess.check_output([str(node),'--version'],text=True).strip()
    with urllib.request.urlopen(f'https://raw.githubusercontent.com/nodejs/node/{node_version}/LICENSE',timeout=30) as response:(output/'runtime/node/LICENSE').write_bytes(response.read())
    for name in SCRIPTS:
        # Windows PowerShell 5.1 requires a BOM for these Chinese scripts.
        (output/name).write_text((ROOT/name).read_text(encoding='utf-8-sig'),encoding='utf-8-sig')
    for name in DOCS:
        target=output/('LICENSE' if name=='get_jobs/LICENSE' else name);shutil.copy2(ROOT/name,target)
    shutil.copytree(ROOT/'get_jobs/third-party',output/'third-party')
    commands={'打开工作台.cmd':'启动工作台.ps1','配置模型.cmd':'配置模型.ps1','停止工作台.cmd':'停止工作台.ps1','启用每日唤醒.cmd':'每日唤醒设置.ps1','取消每日唤醒.cmd':'每日唤醒设置.ps1 -Remove'}
    for name,target in commands.items():
        script,_,arguments=target.partition(' ')
        body=f'@echo off\r\nchcp 65001 >nul\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0{script}" {arguments}\r\nif errorlevel 1 pause\r\n'
        (output/name).write_text(body,encoding='utf-8')
    manifest={'version':VERSION,'platform':'windows-x64','jarSha256':digest(output/'app/workbench.jar'),'nodeVersion':node_version,'includesUserData':False,'initialScheduleEnabled':False,'initialPlatformSendingEnabled':False}
    (output/'release-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    subprocess.run([__import__('sys').executable,str(ROOT/'tools/audit_release.py'),str(output)],check=True)
    archive=Path(str(output)+'.zip')
    if archive.exists():raise ValueError('Archive already exists, not overwritten')
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for file in sorted(output.rglob('*')):
            if file.is_file():z.write(file,Path(output.name)/file.relative_to(output))
    checksum=digest(archive);Path(str(archive)+'.sha256').write_text(checksum+'  '+archive.name+'\n',encoding='ascii')
    return {'archive':str(archive),'sha256':checksum,'bytes':archive.stat().st_size,'version':VERSION}

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--node',type=Path,required=True);p.add_argument('--driver',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    print(json.dumps(build(a.java_home,a.node,a.driver,a.output),ensure_ascii=False))
