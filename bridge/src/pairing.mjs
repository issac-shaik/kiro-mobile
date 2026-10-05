import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { randomBytes, X509Certificate } from 'node:crypto';
import selfsigned from 'selfsigned';
import QRCode from 'qrcode';

export function lanAddresses(interfaces = os.networkInterfaces()) {
  return [...new Set(Object.values(interfaces).flat().filter(a => a && !a.internal && a.family === 'IPv4' && /^(10\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(a.address)).map(a => a.address))];
}

export async function localIdentity(base) {
  const file = path.join(base, 'tls.json');
  try { return JSON.parse(await fs.readFile(file, 'utf8')); }
  catch (e) { if (e.code !== 'ENOENT') throw e; }
  const identity = await selfsigned.generate([{ name: 'commonName', value: 'Kiro Mobile Companion' }], {
    keySize: 2048, algorithm: 'sha256',
    notBeforeDate: new Date(Date.now() - 5 * 60000),
    notAfterDate: new Date(Date.now() + 5 * 365 * 86400000),
    extensions: [{ name: 'basicConstraints', cA: false }, { name: 'keyUsage', digitalSignature: true, keyEncipherment: true }, { name: 'extKeyUsage', serverAuth: true }]
  });
  const tls = { key: identity.private, cert: identity.cert };
  await fs.writeFile(file, JSON.stringify(tls), { mode: 0o600, flag: 'wx' });
  return tls;
}

export class PairingInvitations {
  constructor({ token, cert, port, clock = Date.now, addresses = null, network = 'wifi' }) {
    this.token = token; this.port = port; this.clock = clock; this.codes = new Map();
    this.addresses = addresses; this.network = network;
    this.certSha256 = new X509Certificate(cert).fingerprint256.replaceAll(':', '').toLowerCase();
  }
  issue(addresses = this.addresses ?? lanAddresses()) {
    if (!addresses.length) throw new Error('Connect the PC to the network, then refresh this page.');
    const code = randomBytes(32).toString('base64url');
    const expiresAt = this.clock() + 5 * 60000;
    for (const [key, expiry] of this.codes) if (expiry <= this.clock()) this.codes.delete(key);
    if (this.codes.size >= 10) this.codes.delete(this.codes.keys().next().value);
    this.codes.set(code, expiresAt);
    return { kind: 'kiro-mobile-pair', version: 1, network: this.network, endpoints: addresses.slice(0,8).map(ip => `https://${ip}:${this.port}`), certSha256: this.certSha256, code, expiresAt };
  }
  redeem(code) {
    const expiresAt = this.codes.get(code);
    if (!expiresAt || expiresAt <= this.clock()) { this.codes.delete(code); throw new Error('QR code expired or already used. Refresh the pairing page on your PC.'); }
    this.codes.delete(code);
    return { token: this.token };
  }
  async page() {
    const invitation = this.issue();
    const image = await QRCode.toDataURL(JSON.stringify(invitation), { errorCorrectionLevel: 'M', margin: 3, width: 440 });
    return `<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Pair Kiro Mobile</title></head><body style="background:#111018;color:#eee;font:17px system-ui;text-align:center;padding:32px"><h1>Connect Kiro Mobile</h1><p>${this.network === 'tailscale' ? 'Connect Tailscale on your phone using the same tailnet as this PC.' : 'Connect your phone to the same Wi-Fi as this PC.'}</p><p>Open Kiro Mobile and tap <b>Scan PC QR code</b>.</p><img width="440" style="max-width:100%;border-radius:16px" alt="One-time Kiro Mobile pairing QR code" src="${image}"><p>Valid for 5 minutes and one phone. <a style="color:#bd9aff" href="/pair">Refresh QR code</a></p><p>Encrypted directly to this PC. Your Kiro login stays here. Keep this QR code private.</p><p style="color:#aaa">${this.network === 'tailscale' ? 'Works on Wi-Fi or mobile data while Tailscale and the PC companion are running. No public API or HTTPS proxy.' : 'If the phone cannot connect, check the PC firewall and avoid guest Wi-Fi.'}</p></body></html>`;
  }
}

export function isLocalPairingPage(req) {
  return ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(req.socket.remoteAddress) && /^127\.0\.0\.1:\d+$/.test(req.headers.host || '') && !['origin','cf-ray','cf-connecting-ip','x-forwarded-for','forwarded'].some(header => req.headers[header]);
}
