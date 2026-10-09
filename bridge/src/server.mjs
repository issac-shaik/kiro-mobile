import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes, timingSafeEqual, createHash } from 'node:crypto';
import { State } from './state.mjs';
import { AcpTransport } from './transport.mjs';
import { KiroAdapter } from './kiro.mjs';
import { DemoAdapter } from './demo.mjs';
import { FirebasePush } from './push.mjs';
import { localIdentity, PairingInvitations, isLocalPairingPage, lanAddresses } from './pairing.mjs';
import { tailscaleAddress } from './tailscale.mjs';

export function authorized(header,token){if(typeof header!=='string'||!header.startsWith('Bearer '))return false;const given=Buffer.from(header.slice(7));const wanted=Buffer.from(token);return given.length===wanted.length&&timingSafeEqual(given,wanted);}
export async function body(req,maxBytes=44*1024*1024){let size=0;const chunks=[];for await(const chunk of req){size+=chunk.length;if(size>maxBytes)throw new Error('Request is too large');chunks.push(chunk);}try{return JSON.parse(Buffer.concat(chunks).toString()||'{}');}catch{throw new Error('Invalid JSON');}}
export function createServer({state,adapter,token,push,tls,pairing,devices=new Map()}){
  const requests=new Map();const limits=new Map();
  let commandQueue=Promise.resolve();
  const onPermission=async permission=>{if(!push)return;await Promise.allSettled([...devices.values()].map(t=>push.send(t,permission))).then(results=>{if(results.some(r=>r.status==='rejected'))state.change({push:{configured:true,error:'Push delivery failed. Check Firebase configuration on the PC.'}});});};
  adapter.onPermission=onPermission;
  const handler=async(req,res)=>{
    res.setHeader('Content-Type','application/json');res.setHeader('Cache-Control','no-store');res.setHeader('X-Content-Type-Options','nosniff');
    const reply=(code,value)=>{if(!res.destroyed){res.writeHead(code);res.end(JSON.stringify(value));}};
    // Native clients have no Origin; browser requests are intentionally rejected.
    if(req.headers.origin)return reply(403,{error:'Browser origins are not permitted'});
    const ip=req.socket.remoteAddress;const bucket=limits.get(ip)||{at:Date.now(),count:0};if(Date.now()-bucket.at>60000){bucket.at=Date.now();bucket.count=0;}bucket.count++;limits.set(ip,bucket);
    if(limits.size>1000)for(const [key,b] of limits)if(Date.now()-b.at>60000)limits.delete(key);
    if(bucket.count>240)return reply(429,{error:'Too many requests'});
    if(pairing && req.method==='GET' && req.url==='/pair' && !tls && isLocalPairingPage(req)) {
      try { const html=await pairing.page();res.setHeader('Content-Type','text/html; charset=utf-8');res.setHeader('Content-Security-Policy',"default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'none'");res.setHeader('Referrer-Policy','no-referrer');res.end(html); }
      catch(e) { reply(503,{error:e.message}); } return;
    }
    if(pairing && tls && req.method==='POST' && req.url==='/v1/pair') {
      try { const b=await body(req,2048);reply(200,pairing.redeem(b.code)); } catch(e) { reply(400,{error:e.message}); } return;
    }
    if(!authorized(req.headers.authorization,token))return reply(401,{error:'Pairing key is invalid'});
    try{
      const url=new URL(req.url,'http://localhost');
      if(req.method==='GET'&&url.pathname==='/v1/state'){
        const revision=Number(url.searchParams.get('after')||0);
        if(revision<state.data.revision)return reply(200,state.data);
        const done=()=>{clearTimeout(timer);state.off('change',done);reply(200,state.data);};
        const timer=setTimeout(done,20000);state.once('change',done);res.on('close',()=>{clearTimeout(timer);state.off('change',done);});return;
      }
      if(req.method==='POST'&&url.pathname==='/v1/push/register'){
        const b=await body(req);if(typeof b.deviceId!=='string'||b.deviceId.length>128||typeof b.token!=='string'||b.token.length>4096)throw new Error('Invalid push registration');if(devices.size>=10&&!devices.has(b.deviceId))throw new Error('Too many devices');devices.set(b.deviceId,b.token);return reply(200,{configured:!!push});
      }
      if(req.method!=='POST'||url.pathname!=='/v1/command')return reply(404,{error:'Unknown endpoint'});
      const b=await body(req);
      if(typeof b.requestId!=='string'||b.requestId.length>128)throw new Error('A requestId is required');
      const digest=createHash('sha256').update(JSON.stringify(b)).digest('hex');
      if(requests.has(b.requestId)){const prior=requests.get(b.requestId);if(prior.digest!==digest)throw new Error('requestId was reused for a different command');return reply(200,await prior.promise);}
      const run=async()=>{
        switch(b.type){
          case 'refresh': await adapter.refresh(b.sessionId);break;
          case 'create':await adapter.create(b.cwd);break;
          case 'load':await adapter.load(b.sessionId,b.handoffConfirmed===true);break;
          case 'select':await adapter.select(b);break;
          case 'prompt':await adapter.prompt(b);break;
          case 'cancel':await adapter.cancel();break;
          case 'permission':adapter.resolvePermission(b.permissionId,b.optionId??null);break;
          default:throw new Error('Unknown command');
        }
        return {ok:true};
      };
      // Keep configuration and handoffs ordered; permission/cancel must remain responsive.
      const immediate=['permission','cancel'].includes(b.type);
      const promise=immediate?run():commandQueue.then(run);
      if(!immediate)commandQueue=promise.catch(()=>{});
      requests.set(b.requestId,{digest,promise});if(requests.size>500)requests.delete(requests.keys().next().value);
      try{reply(200,await promise);}catch(e){requests.delete(b.requestId);throw e;}
    }catch(e){reply(400,{error:e.message||'Operation failed'});}
  };
  const server=tls?https.createServer({...tls,minVersion:'TLSv1.2'},handler):http.createServer(handler);
  server.requestTimeout=30000;server.headersTimeout=15000;return server;
}
export async function main(){
  const base=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../.local');await fs.mkdir(base,{recursive:true});
  let token=process.env.KIRO_MOBILE_TOKEN;
  if(!token){try{token=JSON.parse(await fs.readFile(path.join(base,'pairing.json'),'utf8')).token;}catch{token=randomBytes(32).toString('base64url');await fs.writeFile(path.join(base,'pairing.json'),JSON.stringify({token},null,2),{mode:0o600});}}
  if(token.length<32)throw new Error('Pairing key must contain at least 32 characters');
  const port=Number(process.env.KIRO_MOBILE_PORT||8787);const host=process.env.KIRO_MOBILE_HOST||'127.0.0.1';
  if(host!=='127.0.0.1'&&host!=='::1')throw new Error('Companion must bind to loopback. Use Tailscale Serve for authenticated private HTTPS access.');
  const state=new State();const push=process.env.KIRO_FIREBASE_SERVICE_ACCOUNT?new FirebasePush(process.env.KIRO_FIREBASE_SERVICE_ACCOUNT):null;state.change({push:{configured:!!push}});
  let localConfig={};
  try{localConfig=JSON.parse(await fs.readFile(path.resolve(base,'../config.local.json'),'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}
  const roots=process.env.KIRO_WORKSPACES?JSON.parse(process.env.KIRO_WORKSPACES):(localConfig.workspaces||[process.cwd()]);
  if(!Array.isArray(roots)||roots.length===0||roots.some(root=>typeof root!=='string'||!path.isAbsolute(root)))throw new Error('Allowed workspaces must be a non-empty array of absolute paths.');
  const wifi=process.argv.includes('--wifi');
  const tailnet=process.argv.includes('--tailscale');
  if(process.argv.includes('--remote')||process.env.KIRO_PUBLIC_URL)throw new Error('Public relay mode was removed. Use npm run pair with Tailscale connected.');
  if(wifi&&tailnet)throw new Error('Choose Tailscale pairing or direct Wi-Fi pairing.');
  const lanPort=Number(process.env.KIRO_PRIVATE_PORT||process.env.KIRO_WIFI_PORT||8788);
  const privateHost=tailnet?await tailscaleAddress():wifi?'0.0.0.0':null;
  const tls=privateHost?await localIdentity(base):null;
  const pairing=privateHost?new PairingInvitations({token,cert:tls.cert,port:lanPort,addresses:tailnet?[privateHost]:null,network:tailnet?'tailscale':'wifi'}):null;
  const adapter=process.argv.includes('--demo')?new DemoAdapter(state):new KiroAdapter(state,{roots,transport:new AcpTransport({binary:process.env.KIRO_CLI||'kiro-cli',cwd:roots[0],websocketUrl:process.env.KIRO_ACP_URL})});
  const devices=new Map();
  const server=createServer({state,adapter,token,push,pairing,devices});server.listen(port,host,()=>{
    console.log(pairing?`Open http://127.0.0.1:${port}/pair on this PC and scan with Kiro Mobile.`:`Kiro Mobile companion: http://${host}:${port}\nPairing key stored in ${path.join(base,'pairing.json')}\nPublish privately with: tailscale serve --bg http://127.0.0.1:${port}`);
  });
  const lanServer=privateHost?createServer({state,adapter,token,push,tls,pairing,devices}):null;
  if(lanServer)lanServer.listen(lanPort,privateHost,()=>console.log(tailnet?`Certificate-pinned HTTPS bound only to Tailscale: https://${privateHost}:${lanPort}. Connect Tailscale on your phone, then scan the PC QR code.`:`Encrypted Wi-Fi companion on port ${lanPort}. Addresses: ${lanAddresses().join(', ')}`));
  for(const listener of [server,lanServer].filter(Boolean))listener.on('error',e=>{console.error(`Cannot start companion listener: ${e.message}`);adapter.close();server.close();lanServer?.close();process.exit(1);});
  adapter.start().catch(e=>state.change({status:'offline',error:e.message}));
  const stop=()=>{adapter.close();server.close();server.closeAllConnections();lanServer?.close();lanServer?.closeAllConnections();process.exit(0);};process.on('SIGINT',stop);process.on('SIGTERM',stop);
}
if(process.argv[1]&&path.resolve(process.argv[1])===fileURLToPath(import.meta.url))main().catch(e=>{console.error(e.message);process.exitCode=1;});
