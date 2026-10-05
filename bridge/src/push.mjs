import fs from 'node:fs/promises';
import { createSign } from 'node:crypto';

// Provider interface: send(deviceToken, permission). Add other providers here.
export class FirebasePush {
  constructor(accountPath){this.accountPath=accountPath;this.token=null;}
  async bearer(){
    if(this.token && this.expires>Date.now()+60000)return this.token;
    const account=JSON.parse(await fs.readFile(this.accountPath,'utf8'));this.projectId=account.project_id;
    const now=Math.floor(Date.now()/1000), enc=x=>Buffer.from(JSON.stringify(x)).toString('base64url');
    const payload=enc({alg:'RS256',typ:'JWT'})+'.'+enc({iss:account.client_email,scope:'https://www.googleapis.com/auth/firebase.messaging',aud:'https://oauth2.googleapis.com/token',iat:now,exp:now+3600});
    const signature=createSign('RSA-SHA256').update(payload).sign(account.private_key,'base64url');
    const response=await fetch('https://oauth2.googleapis.com/token',{method:'POST',body:new URLSearchParams({grant_type:'urn:ietf:params:oauth:grant-type:jwt-bearer',assertion:payload+'.'+signature}),signal:AbortSignal.timeout(15000)});
    if(!response.ok)throw new Error('Firebase authorization failed');const result=await response.json();this.token=result.access_token;this.expires=Date.now()+result.expires_in*1000;return this.token;
  }
  async send(deviceToken,permission){
    const bearer=await this.bearer();
    const result=await fetch(`https://fcm.googleapis.com/v1/projects/${encodeURIComponent(this.projectId)}/messages:send`,{method:'POST',headers:{Authorization:'Bearer '+bearer,'Content-Type':'application/json'},body:JSON.stringify({message:{token:deviceToken,data:{type:'permission',permissionId:permission.id,sessionId:permission.sessionId||''},android:{priority:'high',ttl:'300s'}}}),signal:AbortSignal.timeout(15000)});
    if(!result.ok)throw new Error('Firebase rejected permission notification');
  }
}
