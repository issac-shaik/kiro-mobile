import { EventEmitter } from 'node:events';
const clip=(value,limit=24000)=>{const text=typeof value==='string'?value:JSON.stringify(value,null,2)??'';return text.length>limit?text.slice(0,limit)+'\n[Output shortened on mobile]':text;};
function toolContent(content){
  return clip(content.slice(0,40).map(block=>{
    if(block.type==='diff')return `${block.path||'File change'}\nBefore:\n${clip(block.oldText??'',12000)}\nAfter:\n${clip(block.newText??'',12000)}`;
    if(block.type==='terminal')return 'Terminal output: '+(block.terminalId||'');
    const value=block.content;
    if(value?.type==='text')return clip(value.text||'');
    if(value?.type==='resource')return clip(value.resource?.text||value.resource?.uri||'Resource');
    if(value)return `[${value.type||'Media'} output]`;
    return '';
  }).filter(Boolean).join('\n\n'),40000);
}
export class State extends EventEmitter {
  constructor(){super();this.data={revision:1,status:'connecting',error:null,sessions:[],selectedSession:null,transcript:[],permissions:[],models:[],reasoning:[],modes:[],currentModel:null,currentReasoning:null,currentMode:null,usage:null,autopilot:null,autopilotSupported:false,contextUsagePercent:null,push:{configured:false},imageSupported:false,busy:false,source:'local',connectionKind:'resume'};}
  change(values={}){Object.assign(this.data,values);this.data.revision++;this.emit('change',this.data);}
  addText(role,text,{replay=false}={}) {
    if(typeof text!=='string'||!text)return;
    const messages=this.data.transcript;const last=messages.at(-1);
    const streams=role==='assistant'||role==='thinking';
    if(streams && last?.role===role && (last.streaming||replay) && last.replay===replay) last.text+=text;
    else {this.endText();messages.push({id:crypto.randomUUID(),role,text,streaming:streams&&!replay,replay});}
    this.trim();this.change();
  }
  endText(){for(const m of this.data.transcript)if(m.role==='assistant'||m.role==='thinking')m.streaming=false;}
  trim(){
    // Bound text and tool details together; Kiro owns the complete history.
    const messages=this.data.transcript;
    if(messages.length>500)messages.splice(0,messages.length-500);
    if(messages.at(-1)?.text.length>200000)messages.at(-1).text=messages.at(-1).text.slice(-200000);
    const size=m=>m.text.length+(m.tool?Object.values(m.tool).reduce((sum,v)=>sum+(typeof v==='string'?v.length:0),0):0);
    let total=messages.reduce((sum,m)=>sum+size(m),0);
    while(total>1000000&&messages.length>1){total-=size(messages[0]);messages.shift();}
  }
  updateTool(update,{replay=false,waitingForPermission}={}){
    if(typeof update.toolCallId!=='string'||!update.toolCallId||update.toolCallId.length>512)return;
    const id='tool:'+update.toolCallId;
    let entry=this.data.transcript.find(m=>m.id===id);
    if(!entry){this.endText();entry={id,role:'tool',text:'',streaming:false,replay,tool:{title:'Tool call',kind:'other',status:'pending'}};this.data.transcript.push(entry);}
    const tool=entry.tool;
    for(const field of ['title','kind','status'])if(typeof update[field]==='string')tool[field]=clip(update[field],1000);
    if(update.rawInput!=null)tool.input=clip(update.rawInput);
    if(update.rawOutput!=null)tool.output=clip(update.rawOutput);
    if(Array.isArray(update.content))tool.content=toolContent(update.content);
    if(Array.isArray(update.locations))tool.locations=clip(update.locations.slice(0,40).map(l=>l.path+(l.line!=null?':'+l.line:'')).join('\n'),4000);
    const failure=update._meta?.kiro?.failureReason;
    if(typeof failure==='string')tool.failureReason=clip(failure,100);
    if(waitingForPermission!==undefined)tool.waitingForPermission=waitingForPermission;
    if(['completed','failed'].includes(tool.status))tool.waitingForPermission=false;
    entry.streaming=!replay&&['pending','in_progress'].includes(tool.status);
    this.trim();
    this.change();
  }
  addTurnSummary(summary){
    if(summary.creditsUsed==null&&summary.elapsedMs==null)return;
    const id=summary.requestId?'turn:'+summary.requestId:crypto.randomUUID();
    const existing=this.data.transcript.find(m=>m.id===id);
    if(existing)Object.assign(existing,{summary});
    else this.data.transcript.push({id,role:'summary',text:'',streaming:false,summary});
    this.trim();
    this.change();
  }
  finish(){for(const m of this.data.transcript){m.streaming=false;if(m.tool&&['pending','in_progress'].includes(m.tool.status)){m.tool.status='interrupted';m.tool.waitingForPermission=false;}}this.change({busy:false,activity:null});}
}
export function normalizeUsage(reply) {
  let data=reply;
  for(let i=0;i<4 && data?.data;i++)data=data.data;
  const pools=(data?.usageBreakdowns||[]).filter(p=>p.hasLimit && (!p.resourceType||p.resourceType==='CREDIT') && Number.isFinite(p.limit) && Number.isFinite(p.used));
  for(const p of [...data?.bonusCredits||[],...data?.addOnCredits||[]]) {
    if(Number.isFinite(p.total)&&Number.isFinite(p.used) && (p.daysUntilExpiry==null||p.daysUntilExpiry>=0))pools.push({displayName:p.name||'Additional credits',limit:p.total,used:p.used});
  }
  return {available:pools.length>0,plan:data?.planName||null,reset:data?.billingCycleReset||null,remaining:pools.length?pools.reduce((sum,p)=>sum+Math.max(0,p.limit-Math.max(0,p.used)),0):null,pools:pools.map(p=>({name:p.displayName||p.resourceType||'Credits',limit:p.limit,used:p.used,remaining:Math.max(0,p.limit-Math.max(0,p.used))})),updatedAt:new Date().toISOString()};
}
