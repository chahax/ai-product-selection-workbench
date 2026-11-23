import type { IncomingMessage, ServerResponse } from 'node:http';
import { RequestError } from './douyin-library';
import { requireAdmin } from './mobile-tasks';

export const pddMobileBase = '/api/workbench/pdd-mobile';
const uuid = '[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}';
const taskPath = new RegExp('^/tasks/' + uuid + '(?:/(all|qualified)\\.csv|/images/' + uuid + '\\.jpg)?$');

export async function handlePddMobile(path: string, request: IncomingMessage, response: ServerResponse,
  readJson: (request: IncomingMessage, maxBytes: number) => Promise<unknown>) {
  requireAdmin(request);
  const post = path === '/pair' || path === '/tasks';
  if (!post && path !== '/status' && !taskPath.test(path)) throw new RequestError(404, '采集接口不存在');
  if (request.method !== (post ? 'POST' : 'GET')) throw new RequestError(405, '请求方式不支持');
  const password = process.env.PDD_ADMIN_PASSWORD;
  if (!password) throw new RequestError(503, '尚未配置拼多多采集服务口令');
  const target = new URL(process.env.PDD_MOBILE_API_TARGET || 'http://127.0.0.1:8765');
  if (target.protocol !== 'https:' && !(target.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(target.hostname))) {
    throw new RequestError(503, '拼多多采集服务需要使用本机 HTTP 或 HTTPS');
  }
  const upstream = await fetch(new URL('api/admin' + path, target.href.replace(/\/?$/, '/')), {
    method: request.method, redirect: 'error', signal: AbortSignal.timeout(10000),
    headers: { Authorization: 'Basic ' + Buffer.from('admin:' + password).toString('base64'), 'Content-Type': 'application/json' },
    body: post ? JSON.stringify(await readJson(request, 16 * 1024)) : undefined,
  });
  if ([401, 403].includes(upstream.status)) throw new RequestError(502, '拼多多采集服务认证失败');
  const headers: Record<string, string> = { 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' };
  for (const name of ['Content-Type', 'Content-Disposition']) {
    const value = upstream.headers.get(name);
    if (value) headers[name] = value;
  }
  const bytes = Buffer.from(await upstream.arrayBuffer());
  response.writeHead(upstream.status, headers).end(bytes);
}
