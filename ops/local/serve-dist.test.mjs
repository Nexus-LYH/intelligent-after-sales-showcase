import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { createDistServer } from './serve-dist.mjs';

for (const [role, port] of [['staff', 4173], ['customer', 4174]]) {
  test(`${role}: built assets and deep links resolve under the application base`, async () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ias-dist-test-'));
    fs.mkdirSync(path.join(root, 'assets'));
    const files = [['index.html', '<!doctype html><script src="/' + role + '/assets/app.js"></script>'], ['assets/app.js', 'window.iasLoaded=true;']];
    for (const [name, content] of files) fs.writeFileSync(path.join(root, name), content);
    const config = { role, port, dist: root, files: files.map(([name, content]) => ({ name, sha256: crypto.createHash('sha256').update(content).digest('hex') })) };
    const server = createDistServer(config);
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const request = url => new Promise((resolve, reject) => {
      http.get({ hostname: '127.0.0.1', port: server.address().port, path: url, headers: { host: `127.0.0.1:${port}` } }, response => {
        let body = ''; response.setEncoding('utf8'); response.on('data', chunk => body += chunk); response.on('end', () => resolve({ status: response.statusCode, body }));
      }).on('error', reject);
    });
    try {
      assert.equal((await request(`/${role}/`)).status, 200);
      assert.equal((await request(`/${role}/tickets/example`)).status, 200);
      assert.equal((await request(`/${role}/assets/app.js`)).body, 'window.iasLoaded=true;');
      assert.equal((await request(`/${role}/assets/missing.js`)).status, 404);
      assert.equal((await request('/assets/app.js')).status, 404);
    } finally {
      await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
      fs.rmSync(root, { recursive: true, force: true });
    }
  });
}

test('both real dist directories serve their assets through both local entry ports', async () => {
  const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
  const configs = [['staff', 4173], ['customer', 4174]].map(([role, port]) => {
    const dist = path.join(repository, 'apps', role, 'dist');
    const files = [];
    const visit = (directory, prefix = '') => {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const name = path.posix.join(prefix, entry.name), full = path.join(directory, entry.name);
        if (entry.isDirectory()) visit(full, name);
        else if (entry.isFile()) files.push({ name, sha256: crypto.createHash('sha256').update(fs.readFileSync(full)).digest('hex') });
        else throw Error('Unexpected dist entry');
      }
    };
    visit(dist);
    return { role, port, dist, files };
  });
  const servers = [];
  const request = (port, url) => new Promise((resolve, reject) => {
    http.get({ hostname: '127.0.0.1', port, path: url }, response => {
      const chunks = []; response.on('data', chunk => chunks.push(chunk));
      response.on('end', () => resolve({ status: response.statusCode, body: Buffer.concat(chunks) }));
    }).on('error', reject);
  });
  try {
    for (const config of configs) {
      const server = createDistServer(config);
      await new Promise((resolve, reject) => { server.once('error', reject); server.listen(config.port, '127.0.0.1', resolve); });
      servers.push(server);
    }
    for (const entryPort of [4173, 4174]) for (const config of configs) {
      const html = await request(entryPort, `/${config.role}/login`);
      assert.equal(html.status, 200);
      const text = html.body.toString('utf8');
      const assets = [...text.matchAll(/(?:src|href)="(\/(?:staff|customer)\/assets\/[^"]+)"/g)].map(match => match[1]);
      assert.ok(assets.length >= 2);
      for (const url of assets) {
        const response = await request(entryPort, url);
        const relative = url.slice(`/${config.role}/`.length);
        const pinned = config.files.find(file => file.name === relative);
        assert.equal(response.status, 200);
        assert.ok(pinned);
        assert.equal(crypto.createHash('sha256').update(response.body).digest('hex'), pinned.sha256);
      }
    }
  } finally {
    for (const server of servers) await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
