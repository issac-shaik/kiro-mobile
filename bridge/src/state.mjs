import { EventEmitter } from 'node:events';
export class State extends EventEmitter {
  constructor(){super();this.data={revision:1,status:'connecting',error:null,sessions:[],selectedSession:null,transcript:[],permissions:[],models:[],reasoning:[],modes:[],currentModel:null,currentReasoning:null,currentMode:null,usage:null,autopilot:null,autopilotSupported:false,contextUsagePercent:null,push:{configured:false},imageSupported:false,busy:false,source:'local',connectionKind:'resume'};}
  change(values={}){Object.assign(this.data,values);this.data.revision++;this.emit('change',this.data);}
  addText(role,text,{replay=false}={}) {
    if(!text)return;
    const messages=this.data.transcript;const last=messages.at(-1);
    if(role==='assistant' && last?.role===role && last.streaming && last.replay===replay) last.text+=text;
    else messages.push({id:crypto.randomUUID(),role,text,streaming:role==='assistant',replay});
    // Bound the in-memory transcript; Kiro remains the owner of complete history.
    if(messages.length>500)messages.splice(0,messages.length-500);
    if(messages.at(-1)?.text.length>200000)messages.at(-1).text=messages.at(-1).text.slice(-200000);
    let total=messages.reduce((sum,m)=>sum+m.text.length,0);
    while(total>1000000&&messages.length>1){total-=messages[0].text.length;messages.shift();}
    this.change();
  }
  addTurnSummary(summary){
    if(summary.creditsUsed==null&&summary.elapsedMs==null)return;
    const id=summary.requestId?'turn:'+summary.requestId:crypto.randomUUID();
    const existing=this.data.transcript.find(m=>m.id===id);
    if(existing)Object.assign(existing,{summary});
    else this.data.transcript.push({id,role:'summary',text:'',streaming:false,summary});
    if(this.data.transcript.length>500)this.data.transcript.splice(0,this.data.transcript.length-500);
    this.change();
  }
  finish(){for(const m of this.data.transcript)m.streaming=false;this.change({busy:false});}
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
