import { promisify } from 'node:util';
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import { State } from '../bridge/src/state.mjs';
import { DemoAdapter } from '../bridge/src/demo.mjs';
import { createServer } from '../bridge/src/server.mjs';
import { PairingInvitations } from '../bridge/src/pairing.mjs';
import selfsigned from 'selfsigned';
const execute=promisify(execFile);
const adb=process.env.ADB||'adb';
const state=new State(),adapter=new DemoAdapter(state);await adapter.start();
const demoLoad=adapter.load.bind(adapter);
adapter.load=async(...args)=>{
  await demoLoad(...args);
  state.addText('assistant','## Formatting check\n\n**Bold answer** and *italic answer*.\n\n`2 * 3`');
  state.finish();
};
const server=createServer({state,adapter,token:'d'.repeat(43)});
await new Promise(resolve=>server.listen(8877,'127.0.0.1',resolve));
const identity=await selfsigned.generate([{name:'commonName',value:'Kiro smoke test'}],{algorithm:'sha256',notBeforeDate:new Date(Date.now()-5*60000)});
const tls={key:identity.private,cert:identity.cert};
const pairing=new PairingInvitations({token:'d'.repeat(43),cert:tls.cert,port:8878});
const tlsServer=createServer({state,adapter,token:'d'.repeat(43),tls,pairing});
await new Promise(resolve=>tlsServer.listen(8878,'127.0.0.1',resolve));
const invitation=pairing.issue(['10.0.2.2']);
async function run(...args){return (await execute(adb,args,{timeout:300000,maxBuffer:1024*1024})).stdout;}
try{
  console.log((await run('install','-r','app/build/outputs/apk/debug/app-debug.apk')).trim());
  console.log((await run('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')).trim());
  await run('shell','pm','grant','dev.kiromobile.app','android.permission.POST_NOTIFICATIONS');
  await run('shell','pm','grant','dev.kiromobile.app','android.permission.CAMERA');
  const payload=JSON.stringify(invitation);
  const result=await run('shell','am','instrument','-w','-e','qrPairing',`'${payload}'`,'dev.kiromobile.app.test/androidx.test.runner.AndroidJUnitRunner');
  console.log(result);
  if(!result.includes('OK (2 tests)'))throw new Error('Android integration test failed');
  await fs.mkdir('dist/screenshots',{recursive:true});
  for(const name of ['setup','welcome','chat','agents','permission','summary'])await run('pull',`/sdcard/Android/data/dev.kiromobile.app/files/${name}.png`,`dist/screenshots/${name}.png`);
  console.log('Pairing, session controls, chat, background notification and rejection: PASS');
} catch(e){console.error(e.stdout||e.stderr||e.message);process.exitCode=1;}finally{adapter.close();server.closeAllConnections();tlsServer.closeAllConnections();await Promise.all([new Promise(resolve=>server.close(resolve)),new Promise(resolve=>tlsServer.close(resolve))]);}
