import { PRESETS } from './kiro.mjs';
export class DemoAdapter {
  constructor(state,{onPermission=()=>{}}={}){this.state=state;this.onPermission=onPermission;}
  async start(){this.state.change({status:'online',source:'demo',autopilot:true,autopilotSupported:true,contextUsagePercent:24,imageSupported:true,sessions:[{sessionId:'demo-session',title:'Explore your mobile workspace',cwd:'Demo workspace'}],models:[{id:'demo-balanced',name:'Demo · Balanced'},{id:'demo-fast',name:'Demo · Fast'}],reasoning:[{id:'low',name:'Low'},{id:'high',name:'High'}],currentModel:'demo-balanced',currentReasoning:'low',usage:{available:true,remaining:123.5,plan:'Demo data',pools:[]},connectionKind:'demo'});}
  async list(){}
  async refresh(sessionId){await this.list();if(this.state.data.selectedSession||sessionId==='demo-session')await this.load();await this.usage();}
  async create(){await this.load('demo-session',true);}
  async load(){this.state.change({selectedSession:{sessionId:'demo-session',cwd:'Demo workspace'},transcript:[]});this.state.addText('assistant','Welcome to Kiro Mobile. This is a local demo, with no connection to your account. Send a message to try streaming and a permission request.');this.state.finish();}
  async usage(){}
  async select({kind,value}){if(kind==='autopilot'){if(!['on','off'].includes(value))throw new Error('Unknown Autopilot value');this.state.change({autopilot:value==='on'});return;}if(kind==='agent'&&!PRESETS[value])throw new Error('Unknown preset');this.state.change(kind==='model'?{currentModel:value}:kind==='reasoning'?{currentReasoning:value}:{agentPreset:value,agentPresetNative:false});}
  async prompt({text,attachments=[]}){
    if(this.state.data.busy)throw new Error('Answer the pending permission first');
    if(!text?.trim()&&!attachments.length)throw new Error('Enter a message');
    this.state.addText('user',text||'[Image]');this.state.change({busy:true});
    this.timer=setTimeout(()=>{
      if(this.state.data.autopilot){this.state.addText('assistant','Demo Autopilot completed the turn. No real files were changed.');this.complete();return;}
      this.state.addText('assistant','I’m ready to continue. Before changing files, I need your permission.');
      const p={id:crypto.randomUUID(),sessionId:'demo-session',title:'Demo: allow editing README.md?',toolCall:{title:'Edit README.md',rawInput:{path:'README.md',change:'Add a setup example'}},options:[{optionId:'allow',name:'Allow once',kind:'allow_once'},{optionId:'deny',name:'Reject',kind:'reject_once'}]};
      this.state.change({permissions:[p]});this.onPermission(p);
    },500);
  }
  resolvePermission(id,optionId){const p=this.state.data.permissions.find(p=>p.id===id);if(!p)throw new Error('Permission request expired');this.state.change({permissions:[]});this.state.addText('assistant',optionId==='allow'?'\nPermission received. In a real session, Kiro would continue its work on your PC.':'\nPermission declined. Your files remain unchanged.');this.complete();}
  complete(){this.state.addTurnSummary({creditsUsed:0.02,elapsedMs:1250});this.state.finish();}
  async cancel(){clearTimeout(this.timer);this.state.change({permissions:[]});this.state.finish();}
  close(){clearTimeout(this.timer);}
}
