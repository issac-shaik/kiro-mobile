import { State } from '../bridge/src/state.mjs';
import { KiroAdapter } from '../bridge/src/kiro.mjs';
import path from 'node:path';
const root=path.resolve('bridge/.local/smoke-workspace');
const state=new State();const adapter=new KiroAdapter(state,{roots:[root]});
try {
  await adapter.start();
  const candidate=state.data.sessions.filter(s=>path.resolve(s.cwd)===root).sort((a,b)=>(b.updatedAt||'').localeCompare(a.updatedAt||''))[0];
  if(!candidate)throw new Error('Run the live permission smoke test first');
  await adapter.load(candidate.sessionId,true);
  await new Promise(resolve=>setTimeout(resolve,2000));
  if(state.data.transcript.length===0)throw new Error('Session history was not replayed');
  console.log('Saved session resume: PASS');
  console.log('Replayed transcript messages:',state.data.transcript.length);
  console.log('Replayed roles:',state.data.transcript.map(m=>m.role).join(', '));
  console.log('Autopilot confirmed:',state.data.autopilot===true);
  console.log('Model catalog after resume:',state.data.models.length);
  if(process.argv.includes('--stream')) {
    adapter.onPermission=p=>adapter.resolvePermission(p.id,null);
    await adapter.prompt({text:'Reply with exactly: Mobile stream verified. Do not use tools or change any files.'});
    const deadline=Date.now()+90000;
    while(state.data.busy&&Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,200));
    if(!state.data.transcript.some(m=>m.role==='assistant'&&m.text.includes('Mobile stream verified')))throw new Error('Live assistant text was not received');
    console.log('Live assistant streaming: PASS');
  }
  if(process.argv.includes('--inspect-history')) {
    const history=await adapter.transport.rpc('_kiro/session/history',{sessionId:candidate.sessionId});
    console.log('History response shape:',JSON.stringify(Object.fromEntries(Object.entries(history).map(([key,value])=>[key,Array.isArray(value)?{count:value.length,firstKeys:Object.keys(value[0]||{})}:typeof value]))));
  }
}catch(e){console.error(e.message);process.exitCode=1;}finally{adapter.close();}
