import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, copyFileSync, chmodSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../..',import.meta.url));
const temp=mkdtempSync(join(tmpdir(),'audigo-single-api-'));
const data=join(temp,'users.json');
const users=Array.from({length:300},(_,i)=>({userId:i+1, accessToken:`fixture-${i+1}`, travelPlanId:i+1, itineraryItemId:i+1, generationJobId:i+1}));
writeFileSync(data, JSON.stringify({users}));
const calls=[]; const state=new Map(); const active=new Map(); let collision=false; let patchCount=0; let busy=false; let denyAuth=false;
const server=createServer(async(req,res)=>{
 const chunks=[]; for await(const b of req) chunks.push(b);
 const body=chunks.length?JSON.parse(Buffer.concat(chunks)):null;
 const id=Number(req.headers.authorization?.split('-').at(-1));
 calls.push({method:req.method,path:req.url,scenario:req.headers['x-test-scenario'],id,body});
 const send=(status,data)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(JSON.stringify({message:'fixture',data}));};
 if(denyAuth) return send(403,null);
 if(req.url==='/api/notifications/subscribe'){
  if(active.has(id)) collision=true; active.set(id,true);
  res.writeHead(200,{'Content-Type':'text/event-stream'});
  res.write('event: connected\ndata: {}\n\n');
  const timer=setInterval(()=>res.write('event: notification\ndata: {}\n\n'),100);
  res.on('close',()=>{clearInterval(timer);active.delete(id);});return;
 }
 if(req.url==='/api/travel-plans/me') return send(200,[{travel_plan_id:id,status:busy?'GENERATING':'COMPLETED'}]);
 if(req.url.endsWith('/itinerary')) return send(200,{days:[{items:[{itinerary_item_id:id,is_completed:state.get(id)||false}]}]});
 if(req.method==='PATCH'){
  patchCount++;state.set(id,body.is_completed);
  if(req.headers['x-test-scenario']==='API-COMPLETION' && patchCount===2) return send(503,null);
  return send(200,{itinerary_item_id:id,is_completed:body.is_completed,completed_at:body.is_completed?'fixture':null});
 }
 if(req.method==='POST')return send(202,{travel_plan_id:1000+id,generation_job_id:2000+id});
 return send(200,[]);
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
const base=`http://127.0.0.1:${server.address().port}`;
const env={...process.env,BASE_URL:base,CONFIRM_STAGING:'true',ALLOW_WRITE_TESTS:'true',CONFIRM_COMPLETION_RESET:'true',CONFIRM_AI_MOCK:'true',TEST_DATA_FILE:data,K6_WEB_DASHBOARD:'false',K6_NO_USAGE_REPORT:'true'};
const names=[['upcoming','UPCOMING_RAMP'],['recent','RECENT_RAMP'],['itinerary','ITINERARY_API_RAMP'],['regions','REGIONS_RAMP'],['generation-job','GENERATION_JOB_RAMP'],['unread-count','UNREAD_COUNT_RAMP'],['completion','COMPLETION_API_RAMP'],['travel-plan-create','CREATE_API_RAMP'],['sse','SSE_API_RAMP']];
function command(bin,args,extra={},cwd=root){return new Promise(resolve=>{
 const child=spawn(bin,args,{cwd,env:{...env,...extra}});let output='';child.stdout.on('data',b=>output+=b);child.stderr.on('data',b=>output+=b);child.on('close',code=>resolve({code,output}));child.on('error',e=>{throw e;});
});}
const values=(s,n)=>s.metrics[n]?.values||s.metrics[n]||{};
try{
 for(const[name,prefix]of (process.argv.includes('--wrapper-only') ? [] : names)){
  const inspected=await command('./.bin/k6-sse',['inspect','--include-system-env-vars',`scenarios/api/${name}-ramping-arrival.js`]);
  assert.equal(inspected.code,0,inspected.output);
  const parsed=JSON.parse(inspected.output);
  const scenario=Object.values(parsed.scenarios)[0];
  assert.equal(scenario.executor,'ramping-arrival-rate');assert.equal(scenario.timeUnit,'1s');assert.equal(scenario.stages.length,5);
  console.log(`inspect PASS: ${name}`);
  const dir=join(temp,name);mkdirSync(dir);
  const summary=join(dir,'summary.json');const extra={TEST_RUN_ID:`fixture-${name}`,SINGLE_API_ARTIFACT_DIR:dir,SSE_API_HOLD_DURATION:'1s'};
  for(const suffix of ['START_RPS','STAGE_1_RPS','STAGE_2_RPS','PEAK_RPS'])extra[`${prefix}_${suffix}`]='1';
  for(const suffix of ['STAGE_1_DURATION','STAGE_2_DURATION','UP_DURATION','PEAK_DURATION','DOWN_DURATION'])extra[`${prefix}_${suffix}`]='1s';
  extra[`${prefix}_PRE_ALLOCATED_VUS`]='1';extra[`${prefix}_MAX_VUS`]='2';
  const start=calls.length;
  const run=await command('./.bin/k6-sse',['run','--address=','--summary-export',summary,`scenarios/api/${name}-ramping-arrival.js`],extra);
  assert.equal(run.code,0,run.output);const s=JSON.parse(readFileSync(summary));const runCalls=calls.slice(start);
  const measured=runCalls.filter(c=>!c.scenario?.endsWith('PREPARE'));
  assert.ok(measured.length>=4,`${name} too few requests: ${JSON.stringify(runCalls)}`);
  const paths={upcoming:'/api/travel-plans/upcoming',recent:'/api/travel-plans/recent',itinerary:/^\/api\/travel-plans\/\d+\/itinerary$/,regions:'/api/regions','generation-job':/^\/api\/ai-generation-jobs\/\d+$/,'unread-count':'/api/notifications/unread-count',completion:/^\/api\/itinerary-items\/\d+\/completion$/,'travel-plan-create':'/api/travel-plans',sse:'/api/notifications/subscribe'};
  assert.ok(measured.every(c=>paths[name] instanceof RegExp?paths[name].test(c.path):c.path===paths[name]),JSON.stringify(runCalls));
  if(name==='completion'){
   const sequence=measured.map(c=>c.body.is_completed);
   assert.deepEqual(sequence.slice(0,4),[true,false,false,true]);
   assert.equal(values(s,'completion_state_reconfirmed').count,1);
   assert.equal(values(s,'completion_state_uncertain').count,1);
   assert.equal(values(s,'completion_false_to_true').count+values(s,'completion_true_to_false').count,measured.length-2);
   const manifest=JSON.parse(readFileSync(join(dir,'completion-items.json')));assert.equal(manifest.items.length,1);assert.equal(manifest.items[0].itineraryItemId,1);
   assert.equal(values(s,'completion_api_attempts').count,measured.length);
   const reset=await command('node',['scripts/reset-completions.mjs','--data',data,'--items',join(dir,'completion-items.json'),'--delay-ms','0']);
   assert.equal(reset.code,0,reset.output);assert.equal(state.get(1),false);
  }
  if(name==='travel-plan-create'){
   assert.equal(new Set(measured.map(c=>c.id)).size,measured.length);
   const results=JSON.parse(readFileSync(join(dir,'generation-results.json'))).results;
   assert.equal(results.length,measured.length);assert.ok(results.every(c=>c.travelPlanId===1000+c.userId&&c.generationJobId===2000+c.userId));
   assert.equal(values(s,'http_reqs{phase:api_measurement}').count,measured.length);
  }
  if(name==='sse'){
   assert.equal(collision,false);
   assert.equal(values(s,'sse_connection_errors').count,0,run.output);
   assert.equal(values(s,'sse_session_duration_expired').count,measured.length,run.output);
   assert.ok(values(s,'sse_events_received').count>measured.length);
  }
  console.log(`local smoke PASS: ${name} (${measured.length} measured requests)`);
 }
 // Fail closed before writing when creation stage budget or initial state is invalid.
 const small=join(temp,'small.json');writeFileSync(small,JSON.stringify({users:users.slice(0,5)}));
 const over=await command('./.bin/k6-sse',['inspect','--include-system-env-vars','scenarios/api/travel-plan-create-ramping-arrival.js'],{TEST_DATA_FILE:small});assert.notEqual(over.code,0);assert.match(over.output,/unique users/);
 const bad=await command('./.bin/k6-sse',['inspect','--include-system-env-vars','scenarios/api/upcoming-ramping-arrival.js'],{UPCOMING_RAMP_PRE_ALLOCATED_VUS:'10',UPCOMING_RAMP_MAX_VUS:'1'});assert.notEqual(bad.code,0);
 for(const name of ['upcoming','completion','travel-plan-create','sse']){
  for(const BASE_URL of ['https://api.audigo.kr','https://api.audigo.kr.']){
   const denied=await command('./.bin/k6-sse',['inspect','--include-system-env-vars',`scenarios/api/${name}-ramping-arrival.js`],{BASE_URL});assert.notEqual(denied.code,0);assert.match(denied.output,/production/);
  }
 }
 for(const script of ['scripts/test/preflight-auth.mjs','scripts/reset-completions.mjs']){
  const denied=await command('node',[script],{BASE_URL:'https://api.audigo.kr.'});assert.notEqual(denied.code,0);assert.match(denied.output,/production/);
 }
 const badManifest=join(temp,'bad-items.json');writeFileSync(badManifest,JSON.stringify({items:[{userId:1,itineraryItemId:2}]}));
 const mismatch=await command('node',['scripts/reset-completions.mjs','--data',data,'--items',badManifest]);assert.notEqual(mismatch.code,0);assert.match(mismatch.output,/does not match/);

 // Busy-user / authorization checks must stop before the first POST.
 const creationLow={CREATE_API_RAMP_START_RPS:'1',CREATE_API_RAMP_STAGE_1_RPS:'1',CREATE_API_RAMP_STAGE_2_RPS:'1',CREATE_API_RAMP_PEAK_RPS:'1',CREATE_API_RAMP_PRE_ALLOCATED_VUS:'1',CREATE_API_RAMP_MAX_VUS:'2'};
 for(const suffix of ['STAGE_1_DURATION','STAGE_2_DURATION','UP_DURATION','PEAK_DURATION','DOWN_DURATION'])creationLow[`CREATE_API_RAMP_${suffix}`]='1s';
 for(const mode of ['busy','auth']){
  busy=mode==='busy';denyAuth=mode==='auth';const start=calls.length;
  const dir=join(temp,mode);mkdirSync(dir);
  const run=await command('./.bin/k6-sse',['run','--address=','scenarios/api/travel-plan-create-ramping-arrival.js'],{...creationLow,SINGLE_API_ARTIFACT_DIR:dir});
  assert.notEqual(run.code,0,run.output);assert.equal(calls.slice(start).filter(c=>c.method==='POST').length,0);
 }
 busy=false;denyAuth=false;
 // Existing scripts still load; inspect never makes API calls.
 for(const file of ['my-travel-plans','user-me','notifications','notification-settings','travel-plan-status','nickname']){
  const run=await command('./.bin/k6-sse',['inspect','--include-system-env-vars',`scenarios/api/${file}-ramping-arrival.js`]);assert.equal(run.code,0,run.output);
 }
 for(const file of ['smoke.js','itinerary/constant-arrival.js','itinerary/ramping-arrival.js','completion/constant-arrival.js','completion/ramping-arrival.js','completion/toggle.js','generation/polling.js','sse/handshake-smoke.js','sse/constant-vus.js','sse/ramping-vus.js','p01/constant-arrival.js','p01/ramping-arrival.js','p01/ramping-vus.js','p01/notification-delivery.js','p02/constant-mix.js','p02/ramping-spike.js']){
  const run=await command('./.bin/k6-sse',['inspect','--include-system-env-vars',`scenarios/${file}`]);assert.equal(run.code,0,run.output);
 }
 // Test failure + automatic reset/fallback without reading the real .env.
 for(const withManifest of [true,false]){
  const isolated=join(temp,withManifest?'wrapper-manifest':'wrapper-fallback');
  mkdirSync(join(isolated,'scripts/test'),{recursive:true});
  copyFileSync(join(root,'scripts/test/run-completion-with-reset.sh'),join(isolated,'scripts/test/run-completion-with-reset.sh'));
  for(const filename of ['reset-completions.sh','reset-completions.mjs'])copyFileSync(join(root,'scripts',filename),join(isolated,'scripts',filename));
  const resetData=join(isolated,'users.json');writeFileSync(resetData,JSON.stringify({users:users.slice(0,3)}));
  writeFileSync(join(isolated,'.env'),`BASE_URL=${base}\nTEST_DATA_FILE=${resetData}\nRESET_COMPLETION_DELAY_MS=0\n`);
  const stub=`#!/usr/bin/env bash\nmkdir -p "results/$TEST_RUN_ID"\n${withManifest?`printf '%s' '{"items":[{"userId":1,"itineraryItemId":1}]}' > "results/$TEST_RUN_ID/completion-items.json"`:''}\nexit 17\n`;
  writeFileSync(join(isolated,'scripts/test/run-k6.sh'),stub);
  for(const file of ['scripts/test/run-k6.sh','scripts/test/run-completion-with-reset.sh','scripts/reset-completions.sh'])chmodSync(join(isolated,file),0o755);
  state.set(1,true);const start=calls.length;
  const run=await command('bash',['scripts/test/run-completion-with-reset.sh','scenarios/api/completion-ramping-arrival.js'],{TEST_RUN_ID:'wrapper-fixture'},isolated);
  assert.equal(run.code,17,run.output);assert.equal(state.get(1),false);
  assert.equal(calls.slice(start).filter(c=>c.method==='PATCH').length,withManifest?1:3,run.output);
 }
 // Terminal-like process-group SIGINT: wrapper must reset before returning 130.
 {
  const isolated=join(temp,'wrapper-interrupt');mkdirSync(join(isolated,'scripts/test'),{recursive:true});
  copyFileSync(join(root,'scripts/test/run-completion-with-reset.sh'),join(isolated,'scripts/test/run-completion-with-reset.sh'));
  for(const filename of ['reset-completions.sh','reset-completions.mjs'])copyFileSync(join(root,'scripts',filename),join(isolated,'scripts',filename));
  const resetData=join(isolated,'users.json');writeFileSync(resetData,JSON.stringify({users:users.slice(0,3)}));
  writeFileSync(join(isolated,'.env'),`BASE_URL=${base}\nTEST_DATA_FILE=${resetData}\nRESET_COMPLETION_DELAY_MS=0\n`);
  writeFileSync(join(isolated,'scripts/test/run-k6.sh'),`#!/usr/bin/env bash\nmkdir -p "results/$TEST_RUN_ID"\nprintf '%s' '{"items":[{"userId":1,"itineraryItemId":1}]}' > "results/$TEST_RUN_ID/completion-items.json"\necho signal-ready\nsleep 20\n`);
  for(const file of ['scripts/test/run-k6.sh','scripts/test/run-completion-with-reset.sh','scripts/reset-completions.sh'])chmodSync(join(isolated,file),0o755);
  state.set(1,true);
  const result=await new Promise((resolve,reject)=>{
   const child=spawn('bash',['scripts/test/run-completion-with-reset.sh','scenarios/api/completion-ramping-arrival.js'],{cwd:isolated,env:{...env,TEST_RUN_ID:'interrupt-fixture'},detached:true});
   let output='';let signaled=false;
   const watchdog=setTimeout(()=>{process.kill(-child.pid,'SIGKILL');reject(new Error(`interrupt test stalled: ${output}`));},10000);
   child.stdout.on('data',b=>{output+=b;if(!signaled&&output.includes('signal-ready')){signaled=true;setTimeout(()=>process.kill(-child.pid,'SIGINT'),100);}});
   child.stderr.on('data',b=>output+=b);
   child.on('error',reject);child.on('close',(code,signal)=>{clearTimeout(watchdog);resolve({code,signal,output});});
  });
  assert.equal(result.code,130,result.output);assert.equal(state.get(1),false,result.output);
  console.log('interrupt PASS: SIGINT -> manifest reset -> exit 130');
 }
 console.log('regressions PASS: existing scenarios, busy/auth preflight, failed-run manifest reset and fallback');
 console.log('guards PASS: generation budget, VU limits, production block, manifest ownership');
 console.log(`artifacts=${temp}`);
}finally{server.closeAllConnections();await new Promise(r=>server.close(r));}
