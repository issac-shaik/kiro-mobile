import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';
const execute = promisify(execFile);

export function privateAddress(status) {
  if (status.BackendState !== 'Running') throw new Error('Connect Tailscale on this PC, then run npm run pair again.');
  const address = status.TailscaleIPs?.find(ip => {
    const parts = ip.split('.').map(Number);
    return parts.length === 4 && parts[0] === 100 && parts[1] >= 64 && parts[1] <= 127 && parts.every(n => Number.isInteger(n) && n >= 0 && n <= 255);
  });
  if (!address) throw new Error('No private Tailscale IPv4 address is available on this PC.');
  return address;
}

export async function tailscaleAddress() {
  const binary = process.env.KIRO_TAILSCALE || (process.platform === 'win32' ? path.join(process.env.ProgramFiles || 'C:\\Program Files', 'Tailscale', 'tailscale.exe') : 'tailscale');
  let output;
  try { output = await execute(binary, ['status','--json'], { timeout: 10000, windowsHide: true, maxBuffer: 4 * 1024 * 1024 }); }
  catch { throw new Error('Cannot query Tailscale. Install and connect it on this PC, or set KIRO_TAILSCALE to its executable.'); }
  return privateAddress(JSON.parse(output.stdout));
}
