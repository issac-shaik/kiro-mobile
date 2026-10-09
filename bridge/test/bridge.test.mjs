import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { State, normalizeUsage } from '../src/state.mjs';
import { KiroAdapter } from '../src/kiro.mjs';
import { AcpTransport } from '../src/transport.mjs';
import { createServer, authorized } from '../src/server.mjs';
import { DemoAdapter } from '../src/demo.mjs';

class FakeTransport extends EventEmitter {
  constructor(){super();this.calls=[];this.replies=[];this.options={};}
  async start(){}
  rpc(method,params){this.calls.push({method,params});return Promise.resolve(method==='session/new'?{sessionId:'s',modes:{currentModeId:'vibe',availableModes:[{id:'vibe'},{id:'plan'}]},configOptions:[]}:method==='session/list'?{sessions:[]}:{});}
  reply(id,result){this.replies.push({id,result});}
  reject(id){this.replies.push({id,rejected:true});}
  send(m){this.calls.push(m);}
  close(){}
}
test('authentication rejects absent, malformed, and partial keys',()=>{
  const token='a'.repeat(43);assert.equal(authorized(undefined,token),false);assert.equal(authorized('Basic '+token,token),false);assert.equal(authorized('Bearer '+token.slice(1),token),false);assert.equal(authorized('Bearer '+token,token),true);
});
test('remaining credits use account pools and clamp overages',()=>{
  assert.deepEqual(normalizeUsage({data:{usageBreakdowns:[{hasLimit:true,limit:100,used:125} ]}}).remaining,0);
  assert.equal(normalizeUsage({data:{usageBreakdowns:[{hasLimit:true,limit:100,used:20},{hasLimit:true,limit:50,used:5}]}}).remaining,125);
  assert.equal(normalizeUsage({data:{}}).remaining,null);
  assert.equal(normalizeUsage({data:{usageBreakdowns:[{hasLimit:true,resourceType:'CREDIT',limit:100,used:20}],bonusCredits:[{name:'Trial',total:50,used:10,daysUntilExpiry:2}],addOnCredits:[{total:25,used:5}]}}).remaining,140);
});
test('streamed history is bounded in characters and messages',()=>{
  const s=new State();for(let i=0;i<30;i++){s.addText('user','u'.repeat(100000));s.addText('assistant','a'.repeat(250000));s.finish();}
  assert.ok(s.data.transcript.length<=500);assert.ok(s.data.transcript.reduce((sum,m)=>sum+m.text.length,0)<=1000000);assert.ok(s.data.transcript.every(m=>m.text.length<=200000));
});
test('permission is explicit, validated and resolved exactly once',()=>{
  const state=new State(),transport=new FakeTransport();let alerts=0;const adapter=new KiroAdapter(state,{transport,onPermission:()=>alerts++});
  state.change({selectedSession:{sessionId:'s'}});
  adapter.message({id:10,method:'session/request_permission',params:{sessionId:'s',toolCall:{title:'Edit file'},options:[{optionId:'allow',name:'Allow once'}]}});
  assert.equal(alerts,1);assert.equal(transport.replies.length,0);
  const id=state.data.permissions[0].id;assert.throws(()=>adapter.resolvePermission(id,'invalid'));
  adapter.resolvePermission(id,'allow');assert.deepEqual(transport.replies[0],{id:10,result:{outcome:{outcome:'selected',optionId:'allow'}}});assert.throws(()=>adapter.resolvePermission(id,'allow'));
});
test('unknown client requests are rejected, never auto approved',()=>{
  const t=new FakeTransport(),a=new KiroAdapter(new State(),{transport:t});a.message({id:1,method:'terminal/create',params:{}});assert.equal(t.replies[0].rejected,true);
});
test('workspace traversal and sibling-prefix bypass are blocked',async()=>{
  const tmp=await fs.mkdtemp(path.join(os.tmpdir(),'kiro-mobile-test-'));const root=path.join(tmp,'allowed'),sibling=path.join(tmp,'allowed-other');await fs.mkdir(root);await fs.mkdir(sibling);
  try{const a=new KiroAdapter(new State(),{roots:[root],transport:new FakeTransport()});assert.equal(await a.validateCwd(root),await fs.realpath(root));await assert.rejects(a.validateCwd(sibling),/outside/);await assert.rejects(a.validateCwd('relative'),/absolute/);}finally{await fs.rm(tmp,{recursive:true,force:true});}
});
test('session load requires explicit handoff consent',async()=>{const a=new KiroAdapter(new State(),{transport:new FakeTransport()});await assert.rejects(a.load('s',false),/Confirm desktop handoff/);});
test('sending is blocked until Kiro confirms Autopilot configuration',async()=>{const s=new State();s.change({selectedSession:{sessionId:'s'}});const a=new KiroAdapter(s,{transport:new FakeTransport()});await assert.rejects(a.prompt({text:'edit a file'}),/Autopilot configuration/);await assert.rejects(a.supervise('s'),/did not confirm/);});
test('media is sent as ACP prompt blocks; malformed data is rejected',async()=>{
  const state=new State(),t=new FakeTransport(),a=new KiroAdapter(state,{transport:t});a.configurationConfirmed=true;state.change({selectedSession:{sessionId:'s'},imageSupported:true});
  await assert.rejects(a.prompt({text:'x',attachments:[{mimeType:'text/html',data:'eA=='}]}),/Unsupported/);
  await a.prompt({text:'Describe it',attachments:[{mimeType:'image/png',data:'eA=='}]});
  const call=t.calls.find(c=>c.method==='session/prompt');assert.equal(call.params.prompt[0].type,'text');assert.equal(call.params.prompt[1].type,'image');assert.equal(call.params.content,undefined);
});
test('Autopilot defaults on, acknowledges native configuration and respects idle state',async()=>{
  const s=new State(),t=new FakeTransport(),a=new KiroAdapter(s,{transport:t});
  t.rpc=async(method,params)=>{t.calls.push({method,params});return method==='session/set_config_option'?{configOptions:[{id:'autopilot',currentValue:params.value}]}:{};};
  assert.equal(a.autopilot,true);s.change({selectedSession:{sessionId:'s'}});
  await a.setAutopilot('s',a.autopilot);assert.equal(s.data.autopilot,true);
  await a.select({kind:'autopilot',value:'off'});assert.equal(s.data.autopilot,false);assert.equal(a.supervised,true);
  await assert.rejects(a.select({kind:'autopilot',value:'invalid'}),/Unknown/);
  s.change({busy:true});await assert.rejects(a.select({kind:'autopilot',value:'on'}),/current turn/);
  s.change({busy:false});t.rpc=async()=>({});await assert.rejects(a.select({kind:'autopilot',value:'on'}),/did not confirm/);
  assert.equal(s.data.autopilot,null);await assert.rejects(a.prompt({text:'edit'}),/not been confirmed/);
});
test('demo Autopilot completes with metering and off mode requests permission',async()=>{
  const s=new State(),a=new DemoAdapter(s);await a.start();await a.load();
  try{await a.prompt({text:'demo'});await new Promise(r=>setTimeout(r,600));assert.equal(s.data.busy,false);assert.equal(s.data.permissions.length,0);assert.equal(s.data.transcript.at(-1).summary.creditsUsed,0.02);
    await a.select({kind:'autopilot',value:'off'});await a.prompt({text:'demo'});await new Promise(r=>setTimeout(r,600));assert.equal(s.data.permissions.length,1);a.resolvePermission(s.data.permissions[0].id,'deny');assert.equal(s.data.busy,false);
  }finally{a.close();}
});
test('context and turn telemetry use reported values, isolate sessions and deduplicate summaries',()=>{
  const s=new State(),a=new KiroAdapter(s,{transport:new FakeTransport()});s.change({selectedSession:{sessionId:'s'}});
  const event=(sessionId,meta)=>a.message({method:'session/update',params:{sessionId,update:{sessionUpdate:'session_info_update',_meta:{kiro:meta}}}});
  event('other',{kind:'context_usage',usagePercentage:50});assert.equal(s.data.contextUsagePercent,null);
  event('s',{kind:'context_usage',usagePercentage:24});assert.equal(s.data.contextUsagePercent,24);
  event('s',{kind:'context_usage',usagePercentage:NaN});assert.equal(s.data.contextUsagePercent,24);
  const meta={kind:'turn_completion',requestId:'turn1',elapsedTime:1250,promptTurnSummaries:[{usage:0.1,unit:'credit'},{usage:0.2,unitPlural:'credits'},{usage:123,unit:'tokens'}]};
  event('s',meta);event('s',meta);assert.equal(s.data.transcript.length,1);assert.ok(Math.abs(s.data.transcript[0].summary.creditsUsed-0.3)<1e-9);assert.equal(s.data.transcript[0].summary.elapsedMs,1250);
  event('s',{kind:'turn_completion',requestId:'turn2',elapsedTime:0});assert.equal(s.data.transcript[1].summary.creditsUsed,null);assert.equal(s.data.transcript[1].summary.elapsedMs,0);
  event('s',{kind:'turn_completion',requestId:'turn3'});assert.equal(s.data.transcript.length,2);
});
test('turn completion and cancellation do not answer stale requests',async()=>{
  const s=new State(),t=new FakeTransport(),a=new KiroAdapter(s,{transport:t});s.change({selectedSession:{sessionId:'s'},busy:true});a.message({id:4,method:'session/request_permission',params:{sessionId:'s',options:[]}});await a.cancel();assert.deepEqual(t.replies[0].result,{outcome:{outcome:'cancelled'}});assert.equal(s.data.permissions.length,0);
});
test('transport matches out-of-order RPC replies and clears pending requests',async()=>{
  const t=new AcpTransport();const sent=[];t.send=m=>sent.push(m);const p1=t.rpc('one'),p2=t.rpc('two');t.receive(JSON.stringify({id:sent[1].id,result:{second:true}}));t.receive(JSON.stringify({id:sent[0].id,result:{first:true}}));assert.deepEqual(await p1,{first:true});assert.deepEqual(await p2,{second:true});assert.equal(t.pending.size,0);t.close();
});
test('HTTP API protects state, refuses browser origins, and deduplicates prompts',async()=>{
  const state=new State();const adapter=new DemoAdapter(state);await adapter.start();const token='t'.repeat(43);const server=createServer({state,adapter,token});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));const url=`http://127.0.0.1:${server.address().port}`;
  const headers={Authorization:'Bearer '+token,'Content-Type':'application/json'};
  try {
    assert.equal((await fetch(url+'/v1/state')).status,401);
    assert.equal((await fetch(url+'/v1/state',{headers:{...headers,Origin:'https://example.com'}})).status,403);
    await adapter.load();const body=JSON.stringify({type:'prompt',requestId:'unique',text:'hello'});
    assert.equal((await fetch(url+'/v1/command',{method:'POST',headers,body})).status,200);assert.equal((await fetch(url+'/v1/command',{method:'POST',headers,body})).status,200);assert.equal(state.data.transcript.filter(m=>m.role==='user').length,1);
    assert.equal((await fetch(url+'/v1/command',{method:'POST',headers,body:JSON.stringify({type:'prompt',requestId:'unique',text:'changed'})})).status,400);
    const waiting=fetch(url+'/v1/state?after='+state.data.revision,{headers});state.change({activity:'Test change'});const result=await waiting;assert.equal(result.status,200);assert.equal((await result.json()).activity,'Test change');
  } finally {adapter.close();await new Promise(resolve=>server.close(resolve));server.closeAllConnections();}
});
test('registered push devices receive permission ids without conversation content',async()=>{
  const state=new State(),adapter=new DemoAdapter(state),sent=[];
  const push={send:async(token,p)=>sent.push({token,id:p.id})},token='p'.repeat(43);
  const server=createServer({state,adapter,token,push});await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  try {
    const result=await fetch(`http://127.0.0.1:${server.address().port}/v1/push/register`,{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json'},body:JSON.stringify({deviceId:'test-device',token:'device-token'})});
    assert.equal((await result.json()).configured,true);
    await adapter.onPermission({id:'permission-id',sessionId:'s'});assert.deepEqual(sent,[{token:'device-token',id:'permission-id'}]);
  }finally{adapter.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
});
