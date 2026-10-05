import { spawn } from 'node:child_process';
import readline from 'node:readline';
const child = spawn(process.env.KIRO_CLI || 'kiro-cli', ['acp', '--agent-engine', 'v3', '--auth-method', 'cli'], { windowsHide: true, stdio: ['pipe','pipe','pipe'] });
let next = 0;
const pending = new Map();
readline.createInterface({input: child.stdout}).on('line', line => {
  try {
    const m = JSON.parse(line);
    if (m.id != null && !m.method) { const p = pending.get(m.id); if(p) { pending.delete(m.id); clearTimeout(p.timer); m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result); } }
    else if(m.id != null && m.method) child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:m.id,error:{code:-32601,message:'Probe does not handle client requests'}})+'\n');
    else if(m.method==='session/update' && m.params?.update?.sessionUpdate==='config_option_update') console.log(JSON.stringify({configUpdate:m.params.update.configOptions?.map(o=>({id:o.id,currentValue:o.currentValue,options:o.options?.map(x=>({value:x.value,name:x.name}))}))},null,2));
  } catch {}
});
function rpc(method, params={}) { return new Promise((resolve,reject) => {const id=++next; const timer=setTimeout(()=>{pending.delete(id);reject(new Error('RPC timeout: '+method));},30000);pending.set(id,{resolve,reject,timer});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');}); }
try {
  const init = await rpc('initialize', {protocolVersion:1,clientCapabilities:{fs:{readTextFile:false,writeTextFile:false},terminal:false},clientInfo:{name:'kiro-mobile-probe',version:'0.1.0'}});
  console.log(JSON.stringify({agentInfo:init.agentInfo, capabilities:init.agentCapabilities},null,2));
  const sessions=await rpc('session/list',{});
  console.log(JSON.stringify({sessionCount:sessions.sessions?.length,firstSessionShape:Object.keys(sessions.sessions?.[0]||{}),listMeta:sessions._meta},null,2));
  if(process.argv.includes('--usage')) {
    const usage=await rpc('_kiro/account/getUsage',{}); console.log(JSON.stringify({usage},null,2));
  }
  if(process.argv.includes('--new')) {
    const result=await rpc('session/new',{cwd:process.cwd(),mcpServers:[]});
    console.log(JSON.stringify({newSessionShape:Object.keys(result),modes:result.modes,configOptions:result.configOptions},null,2));
    const config=await rpc('session/set_config_option',{sessionId:result.sessionId,configId:'autopilot',value:'off'});
    console.log(JSON.stringify({supervisedConfig:config.configOptions},null,2));
    await new Promise(resolve=>setTimeout(resolve,5000));
  }
} catch(e) { console.error(e.message); process.exitCode=1; } finally {child.kill();}
