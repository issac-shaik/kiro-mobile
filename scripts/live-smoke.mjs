import { State } from '../bridge/src/state.mjs';
import { KiroAdapter } from '../bridge/src/kiro.mjs';
import fs from 'node:fs/promises';
import path from 'node:path';
const root=path.resolve('bridge/.local/smoke-workspace');await fs.mkdir(root,{recursive:true});
const state=new State();const adapter=new KiroAdapter(state,{roots:[root]});
try {
  await adapter.start();console.log('Kiro login and session discovery: OK');
  console.log('Account usage:',state.data.usage?.available?'available':'unavailable');
  await adapter.create(root);
  console.log('Supervised session:',adapter.supervised?'confirmed':'FAILED');
  console.log('Native modes:',state.data.modes.map(m=>m.id).join(', '));
  await new Promise(resolve=>setTimeout(resolve,2000));
  console.log('Advertised models:',state.data.models.length);
  const model=state.data.models.find(m=>m.id==='claude-sonnet-4.6');
  if(model)await adapter.select({kind:'model',value:model.id});
  console.log('Reasoning choices:',state.data.reasoning.map(m=>m.id).join(', ')||'not available');
  if(state.data.reasoning.some(m=>m.id==='low'))await adapter.select({kind:'reasoning',value:'low'});
  await adapter.select({kind:'agent',value:'plan'});
  if(state.data.currentMode!=='plan')throw new Error('Native mode did not switch to plan');
  await adapter.select({kind:'agent',value:'default'});
  console.log('Model, effort and native agent controls: OK');
  if(process.argv.includes('--permission')) {
    let permissionSeen=false;
    adapter.onPermission=p=>{permissionSeen=true;adapter.resolvePermission(p.id,p.options.find(o=>o.kind==='reject_once')?.optionId??null);};
    await adapter.prompt({text:'For a mobile integration test, write a file named denied-permission-test.txt containing just test. This is a test of the approval prompt. If the write is denied, stop immediately and explain that permission was declined. Do not run shell commands.'});
    const deadline=Date.now()+90000;
    while(state.data.busy&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,500));
    if(!permissionSeen)throw new Error('No permission request was observed');
    try{await fs.access(path.join(root,'denied-permission-test.txt'));throw new Error('Denied write unexpectedly created a file');}catch(e){if(e.code!=='ENOENT')throw e;}
    console.log('Live permission request and denial without a file write: OK');
  }
} catch(e){console.error(e.message);process.exitCode=1;}finally{adapter.close();}
