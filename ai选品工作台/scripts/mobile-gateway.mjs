import { readFile } from 'node:fs/promises';
import { X509Certificate } from 'node:crypto';
import { request as forward } from 'node:http';
import { createServer } from 'node:https';
import { isIPv4 } from 'node:net';
import { parseArgs } from 'node:util';

const { values } = parseArgs({
  options: {
    bind: { type: 'string' },
    cert: { type: 'string' },
    key: { type: 'string' },
    port: { type: 'string', default: '3443' },
    'workbench-port': { type: 'string', default: '3000' },
  },
});

const { bind, cert, key } = values;
if (!bind || !isIPv4(bind) || !/^(?:127\.0\.0\.1$|10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)/.test(bind) || !cert || !key) {
  throw new Error('请通过 --bind 指定本机局域网 IPv4，并填写 --cert 与 --key');
}

function portNumber(value) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65_535) throw new Error('端口无效');
  return port;
}

const port = portNumber(values.port);
const workbenchPort = portNumber(values['workbench-port']);
const maxBodyBytes = 16 * 1024 * 1024;
const [certificate, privateKey] = await Promise.all([readFile(cert), readFile(key)]);
const identity = new X509Certificate(certificate);
if (!identity.checkIP(bind) || new Date(identity.validTo) <= new Date() || new Date(identity.validFrom) > new Date()) {
  throw new Error('证书需要包含监听 IP，且必须在有效期内');
}

function send(response, status, error) {
  response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  response.end(JSON.stringify({ error }));
}

const server = createServer({
  cert: certificate, key: privateKey, minVersion: 'TLSv1.2',
  requestTimeout: 45_000, headersTimeout: 10_000,
}, (request, response) => {
  const path = request.url ?? '';
  if (request.method !== 'POST' ||
      !/^\/api\/mobile\/(?:pair|records|categories|jobs\/claim|jobs\/[a-z0-9-]{1,80}\/progress)$/.test(path)) {
    request.resume();
    send(response, 404, '接口不存在');
    return;
  }
  const authorization = request.headers.authorization;
  if (path !== '/api/mobile/pair' && !/^Bearer [A-Za-z0-9_-]{16,256}$/.test(authorization ?? '')) {
    request.resume();
    send(response, 401, '需要手机配对凭证');
    return;
  }
  const contentType = request.headers['content-type'];
  if (contentType?.split(';', 1)[0].trim().toLowerCase() !== 'application/json') {
    request.resume();
    send(response, 415, '需要上传 JSON');
    return;
  }
  const contentLength = request.headers['content-length'];
  if (Number(contentLength) > maxBodyBytes) {
    request.resume();
    send(response, 413, '请求内容超过大小限制');
    return;
  }
  const headers = {
    'content-type': contentType,
    'x-workbench-mobile-gateway': '1',
  };
  if (authorization) headers.authorization = authorization;
  if (contentLength) headers['content-length'] = contentLength;
  if (request.headers['idempotency-key']) headers['idempotency-key'] = request.headers['idempotency-key'];

  const upstream = forward({ hostname: '127.0.0.1', port: workbenchPort, method: 'POST', path, headers }, result => {
    if (response.writableEnded) { result.resume(); return; }
    response.writeHead(result.statusCode ?? 502, {
      'Content-Type': result.headers['content-type'] ?? 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    });
    result.on('error', () => response.destroy());
    result.pipe(response);
  });
  upstream.setTimeout(40_000, () => upstream.destroy());
  upstream.on('error', () => {
    if (!response.headersSent) send(response, 502, '工作台接口暂不可用');
    else if (!response.writableEnded) response.destroy();
  });
  request.on('aborted', () => upstream.destroy());
  request.on('error', () => upstream.destroy());
  response.on('close', () => { if (!response.writableEnded) upstream.destroy(); });
  request.pipe(upstream);
});

server.listen(port, bind, () => {
  console.log(`手机采集入口 https://${bind}:${port}`);
  console.log(`服务器证书 SHA-256  ${identity.fingerprint256.replaceAll(':', '')}`);
});
