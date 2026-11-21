// @vitest-environment node
import { afterAll, beforeAll, expect, it, vi } from 'vitest';
import { createServer } from 'vite';
import type { ViteDevServer } from 'vite';
import { douyinDatabase } from '../../server/douyin';

let server: ViteDevServer;
let address: string;
const admin = '/api/workbench/mobile';
const phone = '/api/mobile';

beforeAll(async () => {
  vi.stubEnv('WORKBENCH_DOUYIN_DB', ':memory:');
  server = await createServer({
    configFile: false,
    plugins: [douyinDatabase()],
    server: { host: '127.0.0.1', port: 0, strictPort: true, watch: null, ws: false },
    logLevel: 'silent',
  });
  await server.listen();
  const listener = server.httpServer?.address();
  if (!listener || typeof listener === 'string') throw new Error('Missing HTTP listener');
  address = `http://127.0.0.1:${listener.port}`;
});

afterAll(async () => {
  await server?.close();
  vi.unstubAllEnvs();
});

function post(path: string, body: unknown, token?: string, headers = {}) {
  return fetch(`${address}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: JSON.stringify(body),
  });
}

async function getJobs() {
  return (await (await fetch(`${address}${admin}/jobs`)).json()).jobs;
}

async function pair(deviceId: string) {
  const { code } = await (await post(`${admin}/pairing`, {})).json();
  const input = { code, device_id: deviceId, name: '测试手机' };
  const paired = await post(`${phone}/pair`, input);
  expect(paired.status).toBe(200);
  const { token } = await paired.json();
  expect((await post(`${phone}/pair`, input)).status).toBe(410);
  return String(token);
}

function jobInput(deviceId: string) {
  return { device_id: deviceId, period: '30d', limit: 3, category: '3C数码家电-电器-个护健康电器' };
}

function envelope(recordId: string, jobId?: string) {
  return {
    schema_version: 1, record_id: recordId, revision: 1,
    record: {
      platform: 'douyin_mall', record_id: recordId, revision: 1, workbench_job_id: jobId,
      captured_at: '2026-11-20T00:00:00.000Z', skus: [],
    },
    images: [],
  };
}

it('cleans only explicitly invalid Douyin products and keeps single-price products', async () => {
  const library = '/api/workbench/douyin';
  const save = async (id: string, fields: Record<string, unknown>) => {
    const input = envelope(id);
    expect((await post(`${library}/import`, { records: [{ ...input.record, ...fields }] })).status).toBe(200);
  };
  await save('cleanup-invalid', { status: 'failed', product_state: 'invalid' });
  await save('cleanup-ordinary-failure', { status: 'failed' });
  await save('cleanup-single-price', {
    status: 'complete',
    source_detail: { core_data_period_days: 30, core_data: { 商品卡销量: '30' } },
    skus: [{ name: '标准款', price_before_coupon_fen: 3990, price_before_label: '展示价' }],
  });
  expect(await (await post(`${library}/delete-invalid`, {})).json()).toEqual({ ok: true, deleted_count: 1 });
  expect((await fetch(`${address}${library}?id=cleanup-invalid`)).status).toBe(404);
  expect((await fetch(`${address}${library}?id=cleanup-ordinary-failure`)).status).toBe(200);
  const kept = await (await fetch(`${address}${library}?id=cleanup-single-price`)).json();
  expect(kept).toMatchObject({
    library_price: 39.9, library_price_label: '展示价', selection_labels: ['商品卡测算未达标'],
  });
});

it('claims each job once and persists progress and distinct receipts across repeated requests', async () => {
  const alpha = await pair('test-phone-alpha');
  const beta = await pair('test-phone-beta');
  const create = await post(`${admin}/jobs`, jobInput('test-phone-alpha'));
  expect(create.status).toBe(201);
  const { job: first } = await create.json();
  const { job: second } = await (await post(`${admin}/jobs`, jobInput('test-phone-alpha'))).json();

  expect(await (await post(`${phone}/jobs/claim`, { ready: false }, alpha)).json()).toEqual({ job: null });
  expect(await (await post(`${phone}/jobs/claim`, { ready: true }, beta)).json()).toEqual({ job: null });
  const claim = () => post(`${phone}/jobs/claim`, { ready: true }, alpha).then(response => response.json());
  const claims = await Promise.all([claim(), claim()]);
  for (const result of claims) {
    expect(result).toEqual({ job: expect.objectContaining({ id: first.id, status: 'claimed' }) });
  }
  expect(await (await post(`${phone}/jobs/claim`, { ready: false }, alpha)).json())
    .toEqual({ job: expect.objectContaining({ id: first.id }) });

  const progress = `${phone}/jobs/${first.id}/progress`;
  expect((await post(progress, { sequence: 1, status: 'running' }, beta)).status).toBe(404);
  expect((await post(progress, { sequence: 1, status: 'running', total: 2 }, alpha)).status).toBe(200);
  const finish = { sequence: 3, status: 'completed', total: 2, complete: 1, partial: 1, expected_records: 2 };
  expect((await post(progress, finish, alpha)).status).toBe(200);
  expect((await post(progress, { sequence: 2, status: 'paused' }, alpha)).status).toBe(200);
  expect((await getJobs()).find((job: { id: string }) => job.id === first.id))
    .toMatchObject({ status: 'uploading', expected_records: 2, received: 0 });

  const rejected = await post(`${phone}/records`, envelope('test-foreign-record', first.id), beta);
  expect(rejected.status).toBe(404);
  expect((await fetch(`${address}/api/workbench/douyin?id=test-foreign-record`)).status).toBe(404);

  const record = envelope('test-first-record', first.id);
  const reply = { acknowledged: true, record_id: 'test-first-record', revision: 1 };
  expect(await (await post(`${phone}/records`, record, alpha)).json()).toEqual(reply);
  expect(await (await post(`${phone}/records`, record, alpha)).json()).toEqual(reply);
  expect((await getJobs()).find((job: { id: string }) => job.id === first.id)).toMatchObject({ status: 'uploading', received: 1 });
  expect((await post(`${phone}/records`, envelope('test-second-record', first.id), alpha)).status).toBe(200);
  expect((await post(progress, finish, alpha)).status).toBe(200);
  expect((await getJobs()).find((job: { id: string }) => job.id === first.id)).toMatchObject({ status: 'completed', received: 2 });
  expect(await (await post(`${phone}/jobs/claim`, { ready: true }, alpha)).json())
    .toEqual({ job: expect.objectContaining({ id: second.id, status: 'claimed' }) });
});

it('rejects unauthenticated gateway uploads and cross-site pairing requests while allowing local legacy records', async () => {
  const record = envelope('test-legacy-record');
  expect((await post(`${phone}/records`, record, undefined, { 'x-workbench-mobile-gateway': '1' })).status).toBe(401);
  expect((await post(`${phone}/records`, record, 'unknown')).status).toBe(401);
  expect((await post(`${admin}/pairing`, {}, undefined, { Origin: 'https://example.org' })).status).toBe(403);
  expect((await post(`${phone}/records`, record)).status).toBe(200);
});
