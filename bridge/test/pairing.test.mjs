import test from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import selfsigned from 'selfsigned';
import { X509Certificate } from 'node:crypto';
import { PairingInvitations, lanAddresses, isLocalPairingPage } from '../src/pairing.mjs';
import { createServer } from '../src/server.mjs';
import { State } from '../src/state.mjs';
import { DemoAdapter } from '../src/demo.mjs';
import { privateAddress } from '../src/tailscale.mjs';

test('QR invitations expire, are consumed once, and never include the durable key', async () => {
  let now = 1000;
  const identity=await selfsigned.generate([{name:'commonName',value:'localhost'}],{algorithm:'sha256'});
  const pairing = new PairingInvitations({token:'secret'.repeat(8),cert:identity.cert,port:8788,addresses:['100.92.51.95'],network:'tailscale',clock:()=>now});
  const qr = pairing.issue();
  assert.equal(qr.endpoints[0],'https://100.92.51.95:8788');
  assert.equal(qr.network,'tailscale');
  assert.match(qr.certSha256,/^[a-f0-9]{64}$/);
  assert.ok(!JSON.stringify(qr).includes(pairing.token));
  assert.throws(()=>pairing.redeem('wrong'));
  assert.equal(pairing.redeem(qr.code).token,pairing.token);
  assert.throws(()=>pairing.redeem(qr.code));
  const expired=pairing.issue();now+=300001;
  assert.throws(()=>pairing.redeem(expired.code));
});

test('Tailscale pairing requires a running tailnet and its private IPv4 address', () => {
  assert.equal(privateAddress({BackendState:'Running',TailscaleIPs:['fd7a::1','100.92.51.95']}),'100.92.51.95');
  assert.throws(()=>privateAddress({BackendState:'Stopped',TailscaleIPs:['100.92.51.95']}));
  for(const address of ['192.168.1.4','100.128.1.2','100.92.999.2','8.8.8.8'])assert.throws(()=>privateAddress({BackendState:'Running',TailscaleIPs:[address]}));
});

test('pairing page rejects public hosts, browser origins and non-loopback clients', () => {
  const req={socket:{remoteAddress:'127.0.0.1'},headers:{host:'127.0.0.1:8787'}};
  assert.equal(isLocalPairingPage(req),true);
  assert.equal(isLocalPairingPage({...req,headers:{host:'relay.trycloudflare.com'}}),false);
  assert.equal(isLocalPairingPage({...req,headers:{...req.headers,origin:'https://attacker.example'}}),false);
  assert.equal(isLocalPairingPage({...req,headers:{...req.headers,'cf-connecting-ip':'203.0.113.4'}}),false);
  assert.equal(isLocalPairingPage({...req,socket:{remoteAddress:'192.168.1.2'}}),false);
});

test('Wi-Fi QR includes private IPv4 addresses only', () => {
  assert.deepEqual(lanAddresses({nic:[{family:'IPv4',internal:false,address:'192.168.1.4'},{family:'IPv4',internal:false,address:'100.92.51.95'},{family:'IPv4',internal:true,address:'127.0.0.1'},{family:'IPv6',internal:false,address:'::1'}]}),['192.168.1.4']);
});

test('encrypted pairing authenticates subsequent state requests; replays and origins fail', async () => {
  const pems=await selfsigned.generate([{name:'commonName',value:'localhost'}],{algorithm:'sha256'});
  const tls={key:pems.private,cert:pems.cert};
  const token='t'.repeat(43),state=new State(),adapter=new DemoAdapter(state);
  const pairing=new PairingInvitations({token,cert:tls.cert,port:8788});
  const qr=pairing.issue(['192.168.1.4']);
  assert.equal(qr.certSha256,new X509Certificate(tls.cert).fingerprint256.replaceAll(':','').toLowerCase());
  const server=createServer({state,adapter,token,tls,pairing});
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const request=(url,body,headers={})=>new Promise((resolve,reject)=>{
    const req=https.request({hostname:'localhost',port:server.address().port,path:url,method:body?'POST':'GET',ca:tls.cert,headers:{...headers,...(body?{'Content-Type':'application/json'}:{})}},res=>{let data='';res.on('data',c=>data+=c);res.on('end',()=>resolve({status:res.statusCode,body:JSON.parse(data)}));});
    req.on('error',reject);req.end(body?JSON.stringify(body):undefined);
  });
  try {
    assert.equal((await request('/v1/state')).status,401);
    assert.equal((await request('/pair')).status,401);
    assert.equal((await request('/v1/pair',{code:qr.code},{Origin:'https://attacker.example'})).status,403);
    const result=await request('/v1/pair',{code:qr.code});
    assert.equal(result.status,200);assert.equal(result.body.token,token);
    assert.equal((await request('/v1/pair',{code:qr.code})).status,400);
    assert.equal((await request('/v1/state',null,{Authorization:`Bearer ${result.body.token}`})).status,200);
  } finally { adapter.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve)); }
});
