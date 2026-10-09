import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { State } from '../src/state.mjs';
import { KiroAdapter } from '../src/kiro.mjs';

function setup(){
  const state=new State(),transport=new EventEmitter(),replies=[];
  transport.reply=(id,result)=>replies.push({id,result});transport.reject=()=>{};
  const adapter=new KiroAdapter(state,{transport});state.change({selectedSession:{sessionId:'s'}});
  const update=(update,sessionId='s')=>adapter.message({method:'session/update',params:{sessionId,update}});
  return {state,adapter,update,replies};
}
test('thinking chunks stream in order and stop when tools or answers begin',()=>{
  const {state,update}=setup();
  update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'Inspect '}});
  update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'the file.'}});
  assert.equal(state.data.transcript.length,1);assert.equal(state.data.transcript[0].text,'Inspect the file.');assert.equal(state.data.transcript[0].streaming,true);
  update({sessionUpdate:'tool_call',toolCallId:'read',title:'Read file',status:'in_progress'});
  assert.equal(state.data.transcript[0].streaming,false);
  update({sessionUpdate:'agent_message_chunk',content:{type:'text',text:'The result.'}});
  assert.deepEqual(state.data.transcript.map(m=>m.role),['thinking','tool','assistant']);
  state.finish();assert.ok(state.data.transcript.every(m=>!m.streaming));assert.equal(state.data.transcript[1].tool.status,'interrupted');
});
test('partial tool updates preserve inputs and update a single card by id',()=>{
  const {state,update}=setup();
  update({sessionUpdate:'tool_call',toolCallId:'a',title:'Read config',status:'pending',rawInput:{path:'config.json'}});
  update({sessionUpdate:'tool_call_update',toolCallId:'a',status:'in_progress',content:[{type:'content',content:{type:'text',text:'First'}}]});
  update({sessionUpdate:'tool_call_update',toolCallId:'a',status:'completed',rawInput:null,content:[{type:'content',content:{type:'text',text:'Full result'}}]});
  assert.equal(state.data.transcript.length,1);const tool=state.data.transcript[0];
  assert.equal(tool.tool.title,'Read config');assert.match(tool.tool.input,/config.json/);assert.equal(tool.tool.content,'Full result');assert.equal(tool.streaming,false);
  update({sessionUpdate:'tool_call',toolCallId:'b',title:'Read config',status:'completed'});assert.equal(state.data.transcript.length,2);
});
test('late tool updates leave concurrent thinking in order; failed calls retain reason and details',()=>{
  const {state,update}=setup();
  update({sessionUpdate:'tool_call_update',toolCallId:'a',status:'in_progress'});
  update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'Reviewing '}});
  update({sessionUpdate:'tool_call_update',toolCallId:'a',status:'failed',rawOutput:'Access denied',_meta:{kiro:{failureReason:'denied'}}});
  update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'another option.'}});
  assert.equal(state.data.transcript.length,2);assert.equal(state.data.transcript[1].text,'Reviewing another option.');assert.equal(state.data.transcript[0].tool.failureReason,'denied');
});
test('replay thinking and tools are not live and notifications stay in their session',()=>{
  const {state,update,adapter,replies}=setup();
  update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text:'Other session'}},'other');
  update({sessionUpdate:'tool_call',toolCallId:'other',title:'Other tool'},'other');
  adapter.message({id:1,method:'session/request_permission',params:{sessionId:'other',toolCall:{toolCallId:'other'}}});
  assert.equal(state.data.transcript.length,0);assert.equal(state.data.permissions.length,0);assert.equal(replies[0].result.outcome.outcome,'cancelled');
  for(const text of ['Saved ','thinking'])update({sessionUpdate:'agent_thought_chunk',content:{type:'text',text},_meta:{kiro:{replay:true}}});
  assert.equal(state.data.transcript[0].text,'Saved thinking');assert.equal(state.data.transcript[0].streaming,false);
  update({sessionUpdate:'tool_call',toolCallId:'old',status:'completed',_meta:{kiro:{replay:true}}});state.finish();assert.equal(state.data.transcript[1].tool.status,'completed');
});
test('permission events update the tool card without approving it',()=>{
  const {state,adapter,update,replies}=setup();
  update({sessionUpdate:'tool_call',toolCallId:'a',title:'Edit file',status:'pending'});
  adapter.message({id:1,method:'session/request_permission',params:{sessionId:'s',toolCall:{toolCallId:'a'},options:[{optionId:'deny',name:'Deny'}]}});
  assert.equal(state.data.transcript.length,1);assert.equal(state.data.transcript[0].tool.waitingForPermission,true);assert.equal(replies.length,0);
  adapter.resolvePermission(state.data.permissions[0].id,'deny');assert.equal(state.data.transcript[0].tool.waitingForPermission,false);assert.equal(replies[0].result.outcome.optionId,'deny');
});
test('large tool output and diffs are bounded without including binary content',()=>{
  const state=new State();
  for(let i=0;i<70;i++)state.updateTool({toolCallId:String(i),rawOutput:'x'.repeat(100000),content:[{type:'diff',path:'file',oldText:'a'.repeat(20000),newText:'b'.repeat(20000)},{type:'content',content:{type:'image',data:'DO_NOT_RENDER_BASE64'}}]});
  assert.ok(JSON.stringify(state.data.transcript).length<1100000);
  const details=state.data.transcript.at(-1).tool;assert.match(details.content,/Before:/);assert.match(details.content,/After:/);assert.match(details.content,/image output/);assert.ok(!details.content.includes('DO_NOT_RENDER_BASE64'));
});
