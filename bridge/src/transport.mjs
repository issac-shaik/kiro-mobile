import { spawn } from 'node:child_process';
import { EventEmitter } from 'node:events';
import readline from 'node:readline';

// Transport owns only framing. Adapters and the HTTP API never see Kiro credentials.
export class AcpTransport extends EventEmitter {
  constructor({binary='kiro-cli', cwd=process.cwd(), websocketUrl}={}) {
    super(); this.options={binary,cwd,websocketUrl}; this.pending=new Map(); this.next=0; this.closed=false;
  }
  async start() {
    if(this.options.websocketUrl) {
      const url=new URL(this.options.websocketUrl);
      if(!['ws:','wss:'].includes(url.protocol)) throw new Error('ACP endpoint must use ws or wss');
      if(url.protocol==='ws:' && !['localhost','127.0.0.1','[::1]'].includes(url.hostname)) throw new Error('Unencrypted ACP endpoints must be loopback');
      this.socket=new WebSocket(url);
      this.socket.addEventListener('message',e=>this.receive(String(e.data)));
      this.socket.addEventListener('close',()=>this.fail(new Error('Kiro connection closed')));
      await new Promise((resolve,reject)=>{this.socket.addEventListener('open',resolve,{once:true});this.socket.addEventListener('error',()=>reject(new Error('Cannot connect to the configured ACP WebSocket')),{once:true});});
    } else {
      this.child=spawn(this.options.binary,['acp','--agent-engine','v3','--auth-method','cli'],{cwd:this.options.cwd,windowsHide:true,stdio:['pipe','pipe','pipe']});
      this.child.on('error',()=>this.fail(new Error('Cannot launch Kiro CLI. Install it and check KIRO_CLI.')));
      this.child.on('exit',()=>this.fail(new Error('Kiro exited. Run kiro-cli login on the PC, then restart the companion.')));
      // Never forward stderr: it may contain account identifiers, paths or tokens.
      this.child.stderr.resume();
      readline.createInterface({input:this.child.stdout}).on('line',line=>this.receive(line));
    }
  }
  receive(line) {
    let m;try{m=JSON.parse(line);}catch{return;}
    if(m.id!=null && !m.method) {
      const p=this.pending.get(m.id);if(!p)return;
      this.pending.delete(m.id);clearTimeout(p.timer);
      m.error?p.reject(new Error(m.error.message||'Kiro rejected this operation')):p.resolve(m.result);
    } else this.emit('message',m);
  }
  send(message) {
    if(this.closed)throw new Error('Kiro connection is unavailable');
    const line=JSON.stringify(message);
    if(this.socket)this.socket.send(line);else this.child.stdin.write(line+'\n');
  }
  rpc(method,params={},timeout=30000) {
    return new Promise((resolve,reject)=>{
      const id=++this.next;
      const timer=setTimeout(()=>{this.pending.delete(id);reject(new Error('Kiro timed out: '+method));},timeout);
      this.pending.set(id,{resolve,reject,timer});
      try{this.send({jsonrpc:'2.0',id,method,params});}catch(e){clearTimeout(timer);this.pending.delete(id);reject(e);}
    });
  }
  reply(id,result){this.send({jsonrpc:'2.0',id,result});}
  reject(id,message='Unsupported client capability'){this.send({jsonrpc:'2.0',id,error:{code:-32601,message}});}
  fail(e){if(this.closed)return;this.closed=true;for(const p of this.pending.values()){clearTimeout(p.timer);p.reject(e);}this.pending.clear();this.emit('unavailable',e.message);}
  close(){this.socket?.close();this.child?.kill();this.fail(new Error('Companion stopped'));}
}
