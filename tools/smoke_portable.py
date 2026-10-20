"""Windows-only clean-directory smoke test. Uses a local fake model; never logs into job sites."""
import argparse,base64,json,shutil,subprocess,threading,time,urllib.request,urllib.error
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def request(port,path,method='GET',body=None,headers=None):
    data=None if body is None else json.dumps(body).encode()
    req=urllib.request.Request(f'http://127.0.0.1:{port}/api/v2/{path}',data=data,method=method,headers={'Content-Type':'application/json',**(headers or {})})
    with urllib.request.urlopen(req,timeout=35) as r:return json.load(r)

class FakeModel(BaseHTTPRequestHandler):
    calls=0
    def do_POST(self):
        type(self).calls+=1
        data=json.loads(self.rfile.read(int(self.headers['Content-Length'])));prompt=data['messages'][0]['content'];legal='负责装卸搬运执行，不涉及数据分析。' in prompt
        result={'decision':'skip' if legal else 'apply','category':'搬运作业' if legal else '库存数据分析','confidence':.99,'matchScore':10 if legal else 90,'reason':'本机模拟接口验证，不代表真实模型判断','jdEvidence':['负责装卸搬运执行，不涉及数据分析。'] if legal else ['负责库存数据清洗与图表制作，招募数据分析实习生，不承担装卸搬运。'],'resumeEvidence':['完成过示例库存数据清洗与图表练习。'],'unknowns':[],'constraintViolations':['装卸搬运执行'] if legal else []}
        payload=json.dumps({'choices':[{'message':{'content':json.dumps(result,ensure_ascii=False)}}]},ensure_ascii=False).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(payload)
    def log_message(self,*args):pass

def main(package,browser):
    target=ROOT/'.work'/('空数据 试运行 '+str(time.time_ns()))
    if target.exists():raise ValueError('Smoke target exists; do not overwrite user data')
    shutil.copytree(package,target)
    model=ThreadingHTTPServer(('127.0.0.1',0),FakeModel);threading.Thread(target=model.serve_forever,daemon=True).start();port=9567
    config=target/'config';config.mkdir()
    setup=ROOT/'.work'/'seed-smoke-config.ps1'
    setup.write_text("param($Root,$Browser,$Base)\n$ErrorActionPreference='Stop'\n$env:PSModulePath=(Join-Path $PSHOME 'Modules')+';'+$env:PSModulePath\n$c=@{provider='api';model='local-fixture';apiBase=$Base;encryptedApiKey=(ConvertFrom-SecureString (ConvertTo-SecureString 'fixture-key-not-a-credential' -AsPlainText -Force));browserPath=$Browser;codexPath='';port=9567;fastMode=$false}\n$c|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $Root 'config/launcher.json') -Encoding UTF8\n",encoding='utf-8-sig')
    ps=['powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass']
    subprocess.run(ps+['-File',str(setup),str(target),str(browser),f'http://127.0.0.1:{model.server_port}/v1'],check=True,capture_output=True)
    try:
        # Windows children can retain pipe handles after the launcher exits; use files, not inherited pipes.
        with (target/'launcher-test.log').open('wb') as log:start=subprocess.run(ps+['-File',str(target/'启动工作台.ps1'),'-NoBrowser','-NoSetup'],stdout=log,stderr=log,timeout=85)
        if start.returncode:raise RuntimeError('Portable launcher failed; inspect private launcher-test.log')
        state=request(port,'status');profile=request(port,'profile')
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/parallel.html',timeout=10) as response:
            html=response.read().decode('utf-8');assert 'autoConfirmReview' in html and 'uploadResumeImage' in html and '首次使用' in html
        assert state['distribution']=='portable-preview' and not state['schedule']['enabled']
        assert not profile['confirmed'] and not profile['autoConfirmReview'] and profile['resumeText']==''
        assert profile['shixisengDays']==0 and profile['shixisengPeriod']=='' and profile['shixisengResumeType']==''
        assert all(not p['config']['enabled'] and not p['config']['sendEnabled'] and not p['config']['resumeReady'] and not p['liveVerified'] and p['effectiveMode']=='scan' for p in state['platforms'])
        before=json.loads((target/'logs/process.json').read_text(encoding='utf-8-sig'))['pid']
        subprocess.run(ps+['-File',str(target/'启动工作台.ps1'),'-NoBrowser','-NoSetup'],check=True,capture_output=True,timeout=15)
        assert json.loads((target/'logs/process.json').read_text(encoding='utf-8-sig'))['pid']==before
        model_result=request(port,'model-check','POST');assert model_result['connection']['ready'] and FakeModel.calls==2
        assert model_result['sample']['decision']=='apply' and model_result['negativeSample']['decision']=='skip'
        request(port,'profile','PUT',{'resumeText':'试用者测试资料','targetDirections':'产品实习','confirmed':True})
        assert request(port,'profile')['resumeText']=='试用者测试资料'
        try:request(port,'profile',headers={'Origin':'https://untrusted.example'})
        except urllib.error.HTTPError as e:assert e.code==403
        else:raise AssertionError('Cross-site profile access was not rejected')
        boundary='PortableSmokeBoundary';image=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a0x8AAAAASUVORK5CYII=')
        data=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fixture.png"\r\nContent-Type: image/png\r\n\r\n'.encode()+image+f'\r\n--{boundary}--\r\n'.encode())
        req=urllib.request.Request(f'http://127.0.0.1:{port}/api/v2/resume-image',data=data,headers={'Content-Type':f'multipart/form-data; boundary={boundary}'},method='POST')
        with urllib.request.urlopen(req,timeout=10) as response:assert json.load(response)['saved']
        assert request(port,'resume-image')['exists']
        request(port,'profile','PUT',{'autoConfirmReview':True});assert request(port,'profile')['autoConfirmReview']
        # Never start collection, login, native acceptance or submission in this test.
        report={'passed':True,'cleanProfile':True,'defaultSendingOff':True,'defaultScheduleOff':True,'defaultAutoReviewOff':True,'bundledRuntimeBoot':True,'duplicateLaunchReused':True,'fakeApiTransport':True,'resumeImageUpload':True,'profileSave':True,'crossOriginRejected':True,'jobSiteOperations':0,'realModelCalls':0}
        (ROOT/'.work/portable-smoke.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
    finally:
        try:subprocess.run(ps+['-File',str(target/'停止工作台.ps1')],capture_output=True,timeout=40)
        finally:model.shutdown()

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--package',type=Path,required=True);p.add_argument('--browser',type=Path,required=True);a=p.parse_args();main(a.package,a.browser)
