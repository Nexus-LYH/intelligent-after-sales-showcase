// Fixed production dist server. No npm install, Vite dev server, or environment secrets.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

export function loadDist(config) {
  if (!['staff', 'customer'].includes(config.role) || config.port !== ({ staff: 4173, customer: 4174 })[config.role]) throw Error('Invalid frontend role/port');
  const root = fs.realpathSync(config.dist);
  const pins = new Map();
  for (const entry of config.files) {
    if (!entry.name || !/^[a-f0-9]{64}$/i.test(entry.sha256) || path.isAbsolute(entry.name)) throw Error('Invalid artifact pin');
    const target = path.resolve(root, entry.name);
    if (!target.startsWith(root + path.sep) || fs.realpathSync(target) !== target || !fs.statSync(target).isFile()) throw Error('Artifact escapes dist or uses link');
    const data = fs.readFileSync(target);
    if (crypto.createHash('sha256').update(data).digest('hex') !== entry.sha256.toLowerCase() || pins.has(target)) throw Error('Artifact mismatch/duplicate');
    pins.set(target, { data, name: entry.name });
  }
  if (!pins.has(path.join(root, 'index.html'))) throw Error('Pinned index missing');
  // Memory snapshot prevents a dist build overwrite from changing a running release.
  return { root, pins };
}

export function createDistServer(config) {
  const { root, pins } = loadDist(config);
  const types = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon', '.woff2': 'font/woff2' };
  return http.createServer((req, res) => {
    if (req.headers.host !== `127.0.0.1:${config.port}`) { res.writeHead(421).end(); return; }
    let pathname;
    try { pathname = decodeURIComponent(new URL(req.url, 'http://127.0.0.1').pathname); } catch { res.writeHead(400).end(); return; }
    if (pathname.includes('\\') || pathname.includes('\0') || pathname.split('/').includes('..')) { res.writeHead(400).end(); return; }
    if (pathname.startsWith('/api/')) {
      // Same-origin API bridge. Fixed target; no user-selected upstream and no internal API forwarding.
      const headers = { ...req.headers, host: '127.0.0.1:8080' };
      delete headers['proxy-authorization']; delete headers['proxy-connection']; delete headers['transfer-encoding'];
      const upstream = http.request({ hostname: '127.0.0.1', port: 8080, path: req.url, method: req.method, headers }, response => {
        const safeHeaders = { ...response.headers }; delete safeHeaders['connection']; delete safeHeaders['transfer-encoding'];
        res.writeHead(response.statusCode, safeHeaders); response.pipe(res);
      });
      upstream.setTimeout(35000, () => upstream.destroy());
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502).end(); else res.destroy(); });
      req.on('aborted', () => upstream.destroy()); req.pipe(upstream); return;
    }
    if (!['GET', 'HEAD'].includes(req.method)) { res.writeHead(405, { Allow: 'GET, HEAD' }).end(); return; }
    if (pathname.startsWith('/internal/') || pathname.startsWith('/.')) { res.writeHead(404).end(); return; }
    const ownBase = `/${config.role}/`;
    const otherRole = config.role === 'staff' ? 'customer' : 'staff';
    const otherBase = `/${otherRole}/`;
    if (pathname.startsWith(otherBase)) {
      // Keep both applications on the user's current origin, as the login links expect.
      const port = otherRole === 'staff' ? 4173 : 4174;
      const upstream = http.request({ hostname: '127.0.0.1', port, path: req.url, method: req.method, headers: { ...req.headers, host: `127.0.0.1:${port}` } }, response => {
        res.writeHead(response.statusCode, response.headers); response.pipe(res);
      });
      upstream.setTimeout(10000, () => upstream.destroy());
      upstream.on('error', () => { if (!res.headersSent) res.writeHead(502).end(); else res.destroy(); });
      req.on('aborted', () => upstream.destroy()); upstream.end(); return;
    }
    if (pathname === `/${config.role}`) { res.writeHead(308, { Location: ownBase }).end(); return; }
    if (!pathname.startsWith(ownBase)) { res.writeHead(404).end(); return; }
    const relative = pathname.slice(ownBase.length);
    const target = path.resolve(root, relative);
    if (target !== root && !target.startsWith(root + path.sep)) { res.writeHead(404).end(); return; }
    let artifact = pins.get(target);
    if (!artifact && !path.extname(pathname)) artifact = pins.get(path.join(root, 'index.html'));
    if (!artifact) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': types[path.extname(artifact.name)] || 'application/octet-stream', 'Content-Length': artifact.data.length, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
    res.end(req.method === 'HEAD' ? undefined : artifact.data);
  });
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8').replace(/^\uFEFF/, ''));
  createDistServer(config).listen(config.port, '127.0.0.1', () => console.log(`Pinned ${config.role} dist listening on 127.0.0.1:${config.port}`));
}
