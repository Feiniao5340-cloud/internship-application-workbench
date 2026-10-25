const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require(process.env.PLAYWRIGHT_PACKAGE||'playwright');
(async()=>{const browser=await chromium.launch({executablePath:process.env.TEST_BROWSER,headless:true});try{
const page=await browser.newPage({viewport:{width:1440,height:1050}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
const profile={resumeText:'',targetDirections:'',excludedDirections:'',constraintsText:'',greeting:'',cities:[],confirmed:false,autoConfirmReview:false,minDailySalary:0,shixisengArrival:'',shixisengDays:0,shixisengPeriod:'',shixisengResumeType:''};
const state={version:'2.1.0',liveEnabled:true,modelEnabled:true,sendingEnabled:true,notificationsEnabled:true,deadline:'',model:{model:'test',active:0,waiting:0},schedule:{enabled:false,active:{},pending:[]},platforms:[['boss','BOSS直聘'],['shixiseng','实习僧'],['zhilian','智联招聘'],['job51','应届生实习']].map(([id,label])=>({id,label,config:{enabled:false,sendEnabled:false,resumeReady:false,maxScan:20,maxSend:3,refreshRounds:1,intervalSeconds:15,keywords:[]},progress:'就绪',session:{message:'尚未登录'},round:{},liveVerified:false,effectiveMode:'scan'}))};let uploads=0,saves=0;
const html=fs.readFileSync(path.resolve(__dirname,'../get_jobs/src/main/resources/static/parallel.html'),'utf8');
await page.route('**/*',r=>{let body;const req=r.request(),url=new URL(req.url()),api=url.pathname.replace('/api/v2/','');
if(url.pathname==='/parallel.html')return r.fulfill({contentType:'text/html; charset=utf-8',body:html});
if(api==='status')body=state;else if(api==='profile'){if(req.method()==='PUT'){Object.assign(profile,req.postDataJSON());saves++;body={saved:true}}else body=profile}
else if(api==='resume-image'){if(req.method()==='POST'){uploads++;body={saved:true,exists:true}}else body={exists:false}}
else if(api==='model-check')body={connection:{ready:true}};else if(api==='events'||api.startsWith('rounds'))body=[];else if(api.endsWith('/records'))body={};else throw Error('Unexpected mutation/request '+api);
return r.fulfill({contentType:'application/json',body:JSON.stringify(body)});});
await page.goto('http://127.0.0.1:9567/parallel.html');await page.locator('#card-job51').waitFor();
assert.equal(await page.locator('#autoConfirmReview').isChecked(),false);assert.equal(await page.locator('#confirmed').isChecked(),false);
assert.equal(await page.locator('#shixisengDays').inputValue(),'');assert.equal(await page.locator('#shixisengArrival').inputValue(),'');
await page.locator('#resumeText').fill('测试简历');await page.locator('#targetDirections').fill('产品实习');await page.locator('#confirmed').check();await page.locator('#shixisengArrival').selectOption('两周内');await page.locator('#shixisengDays').fill('3');await page.locator('#saveProfile').click();await page.waitForFunction(()=>document.querySelector('#profileStatus').textContent.includes('已保存'));
assert.equal(saves,1);assert.equal(profile.autoConfirmReview,false);assert.equal(profile.shixisengArrival,'两周内');assert.equal(profile.shixisengDays,3);
await page.locator('#testModel').click();await page.waitForFunction(()=>document.querySelector('#modelCheckResult').textContent.includes('连接正常'));
await page.locator('#resumeImageFile').setInputFiles({name:'fixture.png',mimeType:'image/png',buffer:Buffer.from('fixture')});await page.locator('#uploadResumeImage').click();await page.waitForFunction(()=>document.querySelector('#resumeImageStatus').textContent.includes('已保存'));assert.equal(uploads,1);
await page.setViewportSize({width:390,height:900});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);assert.deepEqual(errors,[]);
console.log(JSON.stringify({passed:true,initialReviewOff:true,profileSave:true,arrivalSettings:true,modelCheckUi:true,imageUploadUi:true,mobileNoOverflow:true}));
}finally{await browser.close()}})().catch(e=>{console.error(e);process.exitCode=1});
