import { promisify } from 'node:util';
import { execFile } from 'node:child_process';
import fs from 'node:fs/promises';
import { State } from '../bridge/src/state.mjs';
import { DemoAdapter } from '../bridge/src/demo.mjs';
import { createServer } from '../bridge/src/server.mjs';
const execute=promisify(execFile);
const adb=process.env.ADB||'adb';
const state=new State(),adapter=new DemoAdapter(state);await adapter.start();
const server=createServer({state,adapter,token:'d'.repeat(43)});
await new Promise(resolve=>server.listen(8877,'127.0.0.1',resolve));
async function run(...args){return (await execute(adb,args,{timeout:300000,maxBuffer:1024*1024})).stdout;}
try{
  console.log((await run('install','-r','app/build/outputs/apk/debug/app-debug.apk')).trim());
  console.log((await run('install','-r','app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')).trim());
  await run('shell','pm','grant','dev.kiromobile.app','android.permission.POST_NOTIFICATIONS');
  const result=await run('shell','am','instrument','-w','dev.kiromobile.app.test/androidx.test.runner.AndroidJUnitRunner');
  console.log(result);
  if(!result.includes('OK (1 test)'))throw new Error('Android integration test failed');
  await fs.mkdir('dist/screenshots',{recursive:true});
  for(const name of ['setup','welcome','chat','agents','permission'])await run('pull',`/sdcard/Android/data/dev.kiromobile.app/files/${name}.png`,`dist/screenshots/${name}.png`);
  console.log('Pairing, session controls, chat, background notification and rejection: PASS');
} catch(e){console.error(e.stdout||e.stderr||e.message);process.exitCode=1;}finally{adapter.close();server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}
