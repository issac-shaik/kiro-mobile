import { AcpTransport } from './transport.mjs';
import { normalizeUsage } from './state.mjs';
import path from 'node:path';
import fs from 'node:fs/promises';

export const PRESETS={
  default:{modes:['default','vibe'],prompt:''},
  spec:{modes:['spec'],prompt:'Use a spec workflow: establish requirements, design, then tasks. Ask before implementing.'},
  'quick-spec':{modes:['quick-spec'],prompt:'Create a concise quick spec covering the objective, requirements and implementation tasks, then ask before implementing.'},
  'bug-fix':{modes:['bug-fix','bugfix'],prompt:'Use a bug-fix workflow: reproduce the issue, identify the root cause, propose the smallest fix, and verify with a regression check.'},
  plan:{modes:['plan','planner'],prompt:'Plan the work. Investigate and explain the approach, risks and tests. Do not edit files or execute the implementation until I explicitly ask.'}
};

export class KiroAdapter {
  constructor(state,{roots=[process.cwd()],transport,onPermission=()=>{}}={}) {
    this.state=state;this.roots=roots.map(r=>path.resolve(r));this.transport=transport||new AcpTransport();this.onPermission=onPermission;this.extensions=[];this.preset='default';this.presetPending=false;this.supervised=false;
    this.configurationConfirmed=false;this.autopilot=true;
    this.transport.on('message',m=>this.message(m));
    this.transport.on('unavailable',error=>{this.state.data.permissions=[];this.state.finish();this.state.change({status:'offline',error});});
  }
  async start(){
    await this.transport.start();
    const init=await this.transport.rpc('initialize',{protocolVersion:1,clientCapabilities:{fs:{readTextFile:false,writeTextFile:false},terminal:false},clientInfo:{name:'kiro-mobile',version:'0.1.0'}});
    this.extensions=init.agentCapabilities?._meta?.kiro?.extensionMethods||[];
    this.state.change({status:'online',error:null,imageSupported:init.agentCapabilities?.promptCapabilities?.image===true,agentInfo:init.agentInfo,connectionKind:this.transport.options?.websocketUrl?'shared-endpoint':'resume'});
    await this.list();await this.usage();
  }
  async validateCwd(cwd){
    if(typeof cwd!=='string'||!path.isAbsolute(cwd))throw new Error('Choose an absolute workspace path');
    const actual=await fs.realpath(cwd);
    let allowed=false;
    for(const root of this.roots){const canonical=await fs.realpath(root);const rel=path.relative(canonical,actual);if(rel===''||(!rel.startsWith('..'+path.sep)&&rel!=='..'&&!path.isAbsolute(rel)))allowed=true;}
    if(!allowed)throw new Error('Workspace is outside the companion’s allowed roots. Add it to KIRO_WORKSPACES on the PC.');
    if(!(await fs.stat(actual)).isDirectory())throw new Error('Workspace must be a directory');return actual;
  }
  async list(){
    const reply=await this.transport.rpc('session/list',{});
    const sessions=(reply.sessions||[]).map(s=>({...s,owned:s.sessionId===this.state.data.selectedSession?.sessionId}));
    const selected=sessions.find(s=>s.sessionId===this.state.data.selectedSession?.sessionId);
    const metadata=selected?._meta?.kiro||{};
    this.state.change({sessions,sessionRunning:metadata.isProcessing===true||metadata.status==='running'});
    return sessions;
  }
  async refresh(sessionId){
    this.requireIdle();
    await this.list();
    let selected=this.state.data.selectedSession;
    if(!selected&&sessionId){
      const session=this.state.data.sessions.find(s=>s.sessionId===sessionId);
      if(!session)throw new Error('The selected session is no longer available. Choose it again from Sessions.');
      const metadata=session._meta?.kiro||{};
      this.state.change({selectedSession:{sessionId:session.sessionId,cwd:session.cwd},sessionRunning:metadata.isProcessing===true||metadata.status==='running'});
      selected=this.state.data.selectedSession;
    }
    if(selected){
      const session=this.state.data.sessions.find(s=>s.sessionId===selected.sessionId);
      if(!session)throw new Error('The selected session is no longer available. Choose it again from Sessions.');
      await this.restore(session,{configureAutopilot:false});
    }
    await this.usage();
  }
  adopt(id,cwd,result){this.state.change({selectedSession:{sessionId:id,cwd},error:null});this.configure(result);}
  configure(result={}) {
    const options=result.configOptions||[];
    const values=id=>{const raw=options.find(o=>o.id===id)?.options||[];return raw.flatMap(o=>o.options||[o]).map(o=>({id:o.value,name:o.name||o.value}));};
    const mode=result.modes?.currentModeId||options.find(o=>o.id==='mode')?.currentValue||this.state.data.currentMode;
    const preset=Object.keys(PRESETS).find(key=>PRESETS[key].modes.includes(mode));
    const autopilot=options.find(o=>o.id==='autopilot');
    if(autopilot)this.state.change({autopilotSupported:true,autopilot:autopilot.currentValue==='on'?true:autopilot.currentValue==='off'?false:null});
    this.state.change({models:values('model'),reasoning:values('effortLevel'),modes:result.modes?.availableModes||this.state.data.modes,currentModel:options.find(o=>o.id==='model')?.currentValue||this.state.data.currentModel,currentReasoning:options.find(o=>o.id==='effortLevel')?.currentValue||null,currentMode:mode,...preset?{agentPreset:preset,agentPresetNative:true}:{}});
  }
  requireIdle(){if(this.state.data.busy||this.state.data.permissions.length)throw new Error('Wait for the current turn or cancel it first');}
  async setAutopilot(sessionId,enabled){const value=enabled?'on':'off';this.configurationConfirmed=false;this.state.change({autopilot:null});const reply=await this.transport.rpc('session/set_config_option',{sessionId,configId:'autopilot',value});if(reply.configOptions?.find(o=>o.id==='autopilot')?.currentValue!==value)throw new Error('Kiro did not confirm Autopilot configuration. Reopen the session or update the CLI.');this.configurationConfirmed=true;this.autopilot=enabled;this.supervised=!enabled;this.configure(reply);}
  async supervise(sessionId){await this.setAutopilot(sessionId,false);}
  async create(cwd){this.requireIdle();cwd=await this.validateCwd(cwd);this.configurationConfirmed=false;this.supervised=false;this.preset='default';this.presetPending=false;this.state.change({transcript:[],contextUsagePercent:null,autopilot:null,autopilotSupported:false,selectedSession:null,models:[],reasoning:[],modes:[],currentModel:null,currentReasoning:null,currentMode:null});const result=await this.transport.rpc('session/new',{cwd,mcpServers:[]});this.adopt(result.sessionId,cwd,result);try{await this.setAutopilot(result.sessionId,this.autopilot);}catch(e){this.state.change({selectedSession:null});throw e;}await this.list();}
  async load(sessionId,confirmed){
    this.requireIdle();if(!confirmed)throw new Error('Confirm desktop handoff before restoring a session');
    const session=this.state.data.sessions.find(s=>s.sessionId===sessionId);if(!session)throw new Error('Session no longer exists. Refresh the list.');
    const metadata=session._meta?.kiro||{};
    if(metadata.isProcessing===true||metadata.status==='running')throw new Error('This session is running on another client. Finish or stop its turn first.');
    return this.restore(session,{configureAutopilot:true});
  }
  async restore(session,{configureAutopilot}){
    const cwd=await this.validateCwd(session.cwd);
    const prior={selectedSession:this.state.data.selectedSession,transcript:this.state.data.transcript,contextUsagePercent:this.state.data.contextUsagePercent,sessionRunning:this.state.data.sessionRunning};
    if(configureAutopilot){this.configurationConfirmed=false;this.supervised=false;this.preset='default';this.presetPending=false;}
    this.state.change({selectedSession:{sessionId:session.sessionId,cwd},transcript:[],contextUsagePercent:null,...configureAutopilot?{autopilot:null,autopilotSupported:false,models:[],reasoning:[],modes:[],currentModel:null,currentReasoning:null,currentMode:null}:{}});
    this.loading=true;
    try{
      const reply=await this.transport.rpc('session/load',{sessionId:session.sessionId,cwd,mcpServers:[]});
      if(configureAutopilot){this.state.finish();this.adopt(session.sessionId,cwd,reply);await this.setAutopilot(session.sessionId,this.autopilot);}
      else this.adopt(session.sessionId,cwd,reply);
    }catch(e){this.state.change(prior);throw e;}finally{this.loading=false;}
  }
  async select({kind,value}){
    this.requireIdle();const sessionId=this.state.data.selectedSession?.sessionId;if(!sessionId)throw new Error('Open a session first');
    if(!this.configurationConfirmed)throw new Error('Autopilot configuration has not been confirmed. Reopen the session.');
    if(kind==='autopilot'){if(!['on','off'].includes(value))throw new Error('Unknown Autopilot value');await this.setAutopilot(sessionId,value==='on');return;}
    if(kind==='agent') {
      const preset=PRESETS[value];if(!preset)throw new Error('Unknown agent preset');
      const mode=this.state.data.modes.find(m=>preset.modes.includes(m.id));
      if(mode)await this.transport.rpc('session/set_mode',{sessionId,modeId:mode.id});
      else {
        // Leave an earlier native workflow before using a prompt preset.
        const base=this.state.data.modes.find(m=>['default','vibe'].includes(m.id));
        if(base)await this.transport.rpc('session/set_mode',{sessionId,modeId:base.id});
      }
      this.preset=value;this.presetPending=!mode&&!!preset.prompt;this.state.change({agentPreset:value,agentPresetNative:!!mode});return;
    }
    const configId=kind==='model'?'model':kind==='reasoning'?'effortLevel':null;
    if(!configId)throw new Error('Unknown configuration option');
    const choices=kind==='model'?this.state.data.models:this.state.data.reasoning;
    if(!choices.some(o=>o.id===value))throw new Error('Choice is not advertised by Kiro');
    const reply=await this.transport.rpc('session/set_config_option',{sessionId,configId,value});this.configure(reply);
    this.state.change(kind==='model'?{currentModel:value}:{currentReasoning:value});
  }
  async prompt({text='',attachments=[]}) {
    this.requireIdle();const sessionId=this.state.data.selectedSession?.sessionId;if(!sessionId)throw new Error('Open a session first');
    if(!this.configurationConfirmed)throw new Error('Autopilot configuration has not been confirmed. Reopen the session.');
    if(typeof text!=='string'||text.length>100000)throw new Error('Message is too long');
    if(!text.trim()&&!attachments.length)throw new Error('Enter a message or attach media');
    if(!Array.isArray(attachments)||attachments.length>4)throw new Error('Attach at most four images');
    const content=[];
    for(const a of attachments){
      if(!this.state.data.imageSupported)throw new Error('This Kiro engine does not support images');
      if(!['image/jpeg','image/png','image/webp','image/gif'].includes(a.mimeType)||typeof a.data!=='string'||!/^[A-Za-z0-9+/]*={0,2}$/.test(a.data))throw new Error('Unsupported image attachment');
      const bytes=Buffer.from(a.data,'base64');if(!bytes.length||bytes.length>8*1024*1024)throw new Error('Each image must be under 8 MB');
      content.push({type:'image',mimeType:a.mimeType,data:a.data});
    }
    const instructions=this.presetPending?PRESETS[this.preset].prompt+'\n\n':'';
    if(text.trim()||instructions)content.unshift({type:'text',text:instructions+text});
    this.state.addText('user',text+(attachments.length?`\n[${attachments.length} image attachment(s)]`:''));this.state.change({busy:true,error:null,activity:null});this.presetPending=false;
    // The request runs in the background; the state stream carries tool and permission events.
    this.transport.rpc('session/prompt',{sessionId,prompt:content},24*60*60*1000).then(()=>this.state.finish()).catch(e=>{this.state.finish();this.state.change({error:e.message});}).finally(()=>this.usage().catch(()=>{}));
  }
  async cancel(){const sessionId=this.state.data.selectedSession?.sessionId;if(!sessionId)return;this.transport.send({jsonrpc:'2.0',method:'session/cancel',params:{sessionId}});for(const p of [...this.state.data.permissions])this.resolvePermission(p.id,null);}
  resolvePermission(id,optionId){const p=this.state.data.permissions.find(p=>p.id===id);if(!p)throw new Error('Permission request has expired or was already answered');if(optionId!=null&&!p.options.some(o=>o.optionId===optionId))throw new Error('Unknown permission choice');this.transport.reply(p.rpcId,{outcome:optionId==null?{outcome:'cancelled'}:{outcome:'selected',optionId}});if(p.toolCall)this.state.updateTool({toolCallId:p.toolCall.toolCallId},{waitingForPermission:false});this.state.change({permissions:this.state.data.permissions.filter(p=>p.id!==id)});}
  async usage(){
    // Current Kiro builds implement their own usage endpoint without advertising it.
    // This isolated read-only probe is allowed to fail; never infer balance from turn cost.
    try{this.state.change({usage:normalizeUsage(await this.transport.rpc('_kiro/account/getUsage',{}))});}catch{this.state.change({usage:{available:false,remaining:null,reason:'Could not fetch account usage'}});}
  }
  message(m){
    if(m.method==='session/request_permission'&&m.id!=null){
      const p=m.params||{};const permission={id:crypto.randomUUID(),rpcId:m.id,sessionId:p.sessionId,title:p.toolCall?.title||'Kiro needs permission',toolCall:p.toolCall,options:p.options||[],createdAt:new Date().toISOString()};
      if(p.sessionId&&p.sessionId!==this.state.data.selectedSession?.sessionId){this.transport.reply(m.id,{outcome:{outcome:'cancelled'}});return;}
      if(p.toolCall)this.state.updateTool(p.toolCall,{waitingForPermission:true});
      this.state.change({permissions:[...this.state.data.permissions,permission]});Promise.resolve(this.onPermission(permission)).catch(()=>{});return;
    }
    if(m.id!=null&&m.method){this.transport.reject(m.id);return;}
    if(m.method!=='session/update')return;
    const p=m.params||{};
    if(p.sessionId && p.sessionId!==this.state.data.selectedSession?.sessionId)return;
    const u=p.update||{};
    const replay=this.loading===true||p._meta?.kiro?.isReplay===true||u._meta?.kiro?.isReplay===true||p._meta?.kiro?.replay===true||u._meta?.kiro?.replay===true;
    if(['agent_message_chunk','user_message_chunk','agent_thought_chunk'].includes(u.sessionUpdate)&&u.content?.type==='text'){
      const role=u.sessionUpdate==='user_message_chunk'?'user':u.sessionUpdate==='agent_thought_chunk'?'thinking':'assistant';
      if(!replay&&(role==='assistant'||role==='thinking'))this.state.change({sessionRunning:true});
      if(!replay)this.state.data.activity=role==='thinking'?'Thinking…':null;
      this.state.addText(role,u.content.text,{replay});
    }
    else if(u.sessionUpdate==='session_info_update'){
      const meta=u._meta?.kiro||{};const percent=meta.usagePercentage??meta.contextUsage?.usagePercentage;
      if(Number.isFinite(percent))this.state.change({contextUsagePercent:Math.max(0,Math.min(100,percent))});
      if(meta.kind==='turn_completion'||meta.isProcessing===false||['idle','completed','failed'].includes(meta.status))this.state.change({sessionRunning:false});
      else if(meta.kind==='turn_start'||meta.isProcessing===true||meta.status==='running')this.state.change({sessionRunning:true});
      if(meta.kind==='turn_completion'){
        const amounts=(meta.promptTurnSummaries||[]).filter(v=>Number.isFinite(v.usage)&&v.usage>=0);
        const credits=amounts.filter(v=>/^credits?$/i.test(v.unit)||/^credits?$/i.test(v.unitPlural));
        const elapsed=Number.isFinite(meta.elapsedTime)&&meta.elapsedTime>=0?meta.elapsedTime:null;
        this.state.addTurnSummary({requestId:meta.requestId||meta.requestIds?.at(-1)||amounts.at(-1)?.requestId,creditsUsed:credits.length?credits.reduce((sum,v)=>sum+v.usage,0):null,elapsedMs:elapsed});
      }
    }
    else if(u.sessionUpdate==='config_option_update')this.configure({configOptions:u.configOptions});
    else if(u.sessionUpdate==='current_mode_update')this.state.change({currentMode:u.currentModeId});
    else if(['tool_call','tool_call_update'].includes(u.sessionUpdate)){
      if(['pending','in_progress'].includes(u.status))this.state.change({sessionRunning:true});
      if(!replay)this.state.data.activity=['completed','failed'].includes(u.status)?null:u.title||'Using a tool…';
      this.state.updateTool(u,{replay});
    }
  }
  close(){for(const p of [...this.state.data.permissions])this.resolvePermission(p.id,null);this.transport.close();}
}
