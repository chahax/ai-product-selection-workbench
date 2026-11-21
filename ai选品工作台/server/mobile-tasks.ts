import { createHash, createHmac, randomBytes, randomInt, randomUUID, timingSafeEqual } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';
import type { DatabaseSync } from 'node:sqlite';
import { RequestError, isObject } from './douyin-library';

const ADMIN_PATH = '/api/workbench/mobile';
const PAIR_PATH = '/api/mobile/pair';
const CLAIM_PATH = '/api/mobile/jobs/claim';
const PROGRESS_PATH = /^\/api\/mobile\/jobs\/([\da-f-]{36})\/progress$/;
const CANCEL_PATH = /^\/api\/workbench\/mobile\/jobs\/([\da-f-]{36})\/cancel$/;
const CATEGORY = '3C数码家电-电器-个护健康电器';
const PERIODS = new Set(['yesterday', '7d', '30d']);
const PROGRESS_STATES = new Set(['waiting_phone', 'running', 'paused', 'uploading', 'completed', 'failed']);
const TERMINAL_STATES = new Set(['completed', 'failed', 'cancelled']);
const ACTIVE = "'claimed', 'waiting_phone', 'running', 'paused', 'uploading'";
const PAIR_LIFETIME_MS = 5 * 60_000;
const READY_LIFETIME_MS = 30_000;
const MAX_PAIR_ATTEMPTS = 8;
const pairingKey = randomBytes(32);

type MobileJob = {
  id: string;
  device_id: string;
  period: string;
  limit: number;
  category: string;
  status: string;
  sequence: number;
  stage: string | null;
  current_title: string | null;
  total: number;
  complete: number;
  partial: number;
  failed: number;
  invalid: number;
  received: number;
  expected_records: number | null;
  final_status: string | null;
  created_at: string;
  updated_at: string;
  error: string | null;
};

type SendJson = (response: ServerResponse, data: unknown, status?: number) => void;
type ReadJson = (request: IncomingMessage, maxBytes: number) => Promise<unknown>;

export function isLocalMobileRequest(request: IncomingMessage) {
  return ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(request.socket.remoteAddress ?? '');
}

export function fromMobileGateway(request: IncomingMessage) {
  return request.headers['x-workbench-mobile-gateway'] === '1';
}

function requireAdmin(request: IncomingMessage) {
  if (!isLocalMobileRequest(request) || fromMobileGateway(request) || request.headers['sec-fetch-site'] === 'cross-site') {
    throw new RequestError(403, '手机采集管理仅允许从工作台本机访问');
  }
  if (request.headers.origin) {
    let origin: URL;
    try { origin = new URL(request.headers.origin); }
    catch { throw new RequestError(403, '请求来源无效'); }
    if (!['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname) || origin.host !== request.headers.host) {
      throw new RequestError(403, '手机采集管理仅允许从工作台本机访问');
    }
  }
}

function requireMobileTransport(request: IncomingMessage) {
  if (!isLocalMobileRequest(request)) throw new RequestError(403, '请通过手机连接地址访问');
}

function inTransaction<T>(db: DatabaseSync, work: () => T): T {
  db.exec('BEGIN IMMEDIATE');
  try {
    const result = work();
    db.exec('COMMIT');
    return result;
  } catch (error) {
    db.exec('ROLLBACK');
    throw error;
  }
}

function now() {
  return new Date().toISOString();
}

function pairingHash(code: string) {
  return createHmac('sha256', pairingKey).update(code).digest('hex');
}

function tokenHash(token: string) {
  return createHash('sha256').update(token).digest('hex');
}

function object(input: unknown) {
  if (!isObject(input)) throw new RequestError(400, '请求需要 JSON 对象');
  return input;
}

function textField(value: unknown, maximum: number) {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (trimmed.length > maximum) return null;
  for (const character of trimmed) {
    const code = character.charCodeAt(0);
    if (code < 32 || code === 127) return null;
  }
  return trimmed;
}

function requiredText(value: unknown, maximum: number, message: string) {
  const result = textField(value, maximum);
  if (!result) throw new RequestError(400, message);
  return result;
}

function optionalText(input: Record<string, unknown>, name: string, previous: string | null, maximum: number) {
  if (input[name] === undefined) return previous;
  if (input[name] === null) return null;
  const value = input[name];
  const result = textField(typeof value === 'string' ? value.replace(/[\t\r\n]+/g, ' ') : value, maximum);
  if (result === null) throw new RequestError(400, '进度说明格式无效或过长');
  return result || null;
}

function numberField(value: unknown, minimum: number, maximum: number, message: string) {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < minimum || value > maximum) {
    throw new RequestError(400, message);
  }
  return value;
}

function findJob(db: DatabaseSync, id: string) {
  return db.prepare('SELECT * FROM mobile_jobs WHERE id = ?').get(id) as MobileJob | undefined;
}

function ownedJob(db: DatabaseSync, deviceId: string, id: string) {
  const job = findJob(db, id);
  if (!job || job.device_id !== deviceId) throw new RequestError(404, '找不到这台设备的采集任务');
  return job;
}

function publicJob(job: MobileJob) {
  return {
    id: job.id, device_id: job.device_id, period: job.period, limit: job.limit, category: job.category,
    status: job.status, sequence: job.sequence, stage: job.stage ?? undefined,
    current_title: job.current_title ?? undefined, total: job.total, complete: job.complete,
    partial: job.partial, failed: job.failed, invalid: job.invalid, received: job.received,
    expected_records: job.expected_records ?? undefined, created_at: job.created_at,
    updated_at: job.updated_at, error: job.error ?? undefined,
  };
}

export function initializeMobileTasks(db: DatabaseSync) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS mobile_pairing (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      hash TEXT NOT NULL,
      expires_at INTEGER NOT NULL,
      attempts INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS mobile_devices (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      token_hash TEXT NOT NULL UNIQUE,
      last_seen TEXT NOT NULL,
      ready INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS mobile_jobs (
      id TEXT PRIMARY KEY,
      device_id TEXT NOT NULL REFERENCES mobile_devices(id),
      period TEXT NOT NULL,
      "limit" INTEGER NOT NULL,
      category TEXT NOT NULL,
      status TEXT NOT NULL,
      sequence INTEGER NOT NULL DEFAULT 0,
      stage TEXT,
      current_title TEXT,
      total INTEGER NOT NULL DEFAULT 0,
      complete INTEGER NOT NULL DEFAULT 0,
      partial INTEGER NOT NULL DEFAULT 0,
      failed INTEGER NOT NULL DEFAULT 0,
      invalid INTEGER NOT NULL DEFAULT 0,
      received INTEGER NOT NULL DEFAULT 0,
      expected_records INTEGER,
      final_status TEXT,
      created_at TEXT NOT NULL,
      updated_at TEXT NOT NULL,
      error TEXT
    );
    CREATE UNIQUE INDEX IF NOT EXISTS mobile_active_job ON mobile_jobs(device_id) WHERE status IN (${ACTIVE});
    CREATE INDEX IF NOT EXISTS mobile_pending_jobs ON mobile_jobs(device_id, status, created_at);
    CREATE TABLE IF NOT EXISTS mobile_job_receipts (
      record_id TEXT PRIMARY KEY,
      job_id TEXT NOT NULL REFERENCES mobile_jobs(id),
      revision INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS mobile_receipts_by_job ON mobile_job_receipts(job_id);
  `);
}

function issuePairing(db: DatabaseSync) {
  const code = String(randomInt(0, 1_000_000)).padStart(6, '0');
  const expires = Date.now() + PAIR_LIFETIME_MS;
  db.prepare('INSERT OR REPLACE INTO mobile_pairing(id, hash, expires_at) VALUES (1, ?, ?)').run(pairingHash(code), expires);
  return { code, expires_at: new Date(expires).toISOString() };
}

function pairDevice(db: DatabaseSync, raw: unknown) {
  const input = object(raw);
  const deviceId = requiredText(input.device_id, 128, '缺少手机设备编号');
  const name = requiredText(input.name, 80, '缺少手机名称');
  if (!/^[A-Za-z0-9_-]+$/.test(deviceId) || typeof input.code !== 'string' || !/^\d{6}$/.test(input.code)) {
    throw new RequestError(400, '设备编号或六位配对码格式无效');
  }
  const code = input.code;
  const result = inTransaction(db, () => {
    const pairing = db.prepare('SELECT hash, expires_at, attempts FROM mobile_pairing WHERE id = 1').get();
    if (!pairing || Date.now() >= Number(pairing.expires_at)) return 410;
    if (Number(pairing.attempts) >= MAX_PAIR_ATTEMPTS) return 429;
    if (!timingSafeEqual(Buffer.from(pairingHash(code)), Buffer.from(String(pairing.hash)))) {
      db.prepare('UPDATE mobile_pairing SET attempts = attempts + 1 WHERE id = 1').run();
      return 401;
    }
    const token = randomBytes(32).toString('base64url');
    db.prepare(`
      INSERT INTO mobile_devices(id, name, token_hash, last_seen) VALUES (?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET name = excluded.name, token_hash = excluded.token_hash, last_seen = excluded.last_seen, ready = 0
    `).run(deviceId, name, tokenHash(token), now());
    db.prepare('DELETE FROM mobile_pairing WHERE id = 1').run();
    return { token, device_id: deviceId };
  });
  if (result === 410) throw new RequestError(410, '配对码已失效，请在工作台重新生成');
  if (result === 429) throw new RequestError(429, '配对尝试过多，请在工作台重新生成配对码');
  if (result === 401) throw new RequestError(401, '配对码不正确');
  return result;
}

export function authenticateMobile(db: DatabaseSync, request: IncomingMessage) {
  requireMobileTransport(request);
  const token = /^Bearer ([\w-]{43})$/i.exec(request.headers.authorization ?? '')?.[1];
  const device = token && db.prepare('SELECT id FROM mobile_devices WHERE token_hash = ?').get(tokenHash(token));
  if (!device) throw new RequestError(401, '设备配对已失效，请重新配对');
  return String(device.id);
}

function listDevices(db: DatabaseSync) {
  const devices = db.prepare('SELECT id, name, last_seen, ready FROM mobile_devices ORDER BY last_seen DESC').all();
  return { devices: devices.map(device => ({
    id: String(device.id), name: String(device.name), last_seen: String(device.last_seen),
    ready: !!device.ready && Date.now() - Date.parse(String(device.last_seen)) < READY_LIFETIME_MS,
  })) };
}

function createJob(db: DatabaseSync, raw: unknown) {
  const input = object(raw);
  const deviceId = requiredText(input.device_id, 128, '请选择已配对手机');
  const period = requiredText(input.period, 10, '请选择采集周期');
  if (!PERIODS.has(period)) throw new RequestError(400, '请选择昨日、7 日或 30 日');
  const limit = numberField(input.limit, 1, 200, '商品数量需为 1 至 200 件');
  const category = requiredText(input.category, 100, '请选择考古加类目');
  const child = category.startsWith(`${CATEGORY}-`) ? category.slice(CATEGORY.length + 1).trim() : '';
  if (category !== CATEGORY && (!child || child.includes('-'))) {
    throw new RequestError(400, '请选择个护健康电器或它的子类目');
  }
  const id = randomUUID();
  const timestamp = now();
  return inTransaction(db, () => {
    if (!db.prepare('SELECT 1 FROM mobile_devices WHERE id = ?').get(deviceId)) {
      throw new RequestError(404, '找不到已配对手机');
    }
    const queued = db.prepare("SELECT count(*) AS count FROM mobile_jobs WHERE device_id = ? AND status = 'queued'").get(deviceId);
    if (Number(queued?.count) >= 25) throw new RequestError(409, '这台手机的待采任务已满，请先处理已有任务');
    db.prepare(`
      INSERT INTO mobile_jobs(id, device_id, period, "limit", category, status, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, 'queued', ?, ?)
    `).run(id, deviceId, period, limit, child ? `${CATEGORY}-${child}` : CATEGORY, timestamp, timestamp);
    return { job: publicJob(findJob(db, id)!) };
  });
}

function cancelJob(db: DatabaseSync, id: string) {
  const changed = db.prepare("UPDATE mobile_jobs SET status = 'cancelled', updated_at = ? WHERE id = ? AND status = 'queued'")
    .run(now(), id);
  const job = findJob(db, id);
  if (!job) throw new RequestError(404, '找不到采集任务');
  if (!changed.changes && job.status !== 'cancelled') throw new RequestError(409, '手机已接单，请在手机上结束任务');
  return { job: publicJob(job) };
}

function claimJob(db: DatabaseSync, deviceId: string, raw: unknown) {
  const input = object(raw);
  if (typeof input.ready !== 'boolean') throw new RequestError(400, '缺少手机待命状态');
  const ready = input.ready;
  const timestamp = now();
  return inTransaction(db, () => {
    db.prepare('UPDATE mobile_devices SET last_seen = ?, ready = ? WHERE id = ?')
      .run(timestamp, ready ? 1 : 0, deviceId);
    const active = db.prepare(`SELECT * FROM mobile_jobs WHERE device_id = ? AND status IN (${ACTIVE})`)
      .get(deviceId) as MobileJob | undefined;
    if (active) return { job: publicJob(active) };
    if (!ready) return { job: null };
    const queued = db.prepare(`
      SELECT id FROM mobile_jobs WHERE device_id = ? AND status = 'queued' ORDER BY created_at, rowid LIMIT 1
    `).get(deviceId);
    if (!queued) return { job: null };
    const id = String(queued.id);
    db.prepare("UPDATE mobile_jobs SET status = 'claimed', updated_at = ? WHERE id = ?").run(timestamp, id);
    return { job: publicJob(findJob(db, id)!) };
  });
}

function reportProgress(db: DatabaseSync, deviceId: string, id: string, raw: unknown) {
  const input = object(raw);
  const sequence = numberField(input.sequence, 1, Number.MAX_SAFE_INTEGER, '进度版本无效');
  const requested = requiredText(input.status, 24, '采集状态无效');
  if (!PROGRESS_STATES.has(requested)) throw new RequestError(400, '采集状态无效');
  const timestamp = now();
  return inTransaction(db, () => {
    const job = ownedJob(db, deviceId, id);
    db.prepare('UPDATE mobile_devices SET last_seen = ? WHERE id = ?').run(timestamp, deviceId);
    if (sequence <= job.sequence) return { ok: true };
    if (job.status === 'queued' || TERMINAL_STATES.has(job.status)) {
      throw new RequestError(409, '采集任务尚未领取或已结束');
    }
    if (job.final_status && requested !== job.final_status) return { ok: true };
    const metric = (name: 'total' | 'complete' | 'partial' | 'failed' | 'invalid') => input[name] === undefined
      ? job[name] : numberField(input[name], 0, job.limit, '进度数量超出任务上限');
    const stage = optionalText(input, 'stage', job.stage, 300);
    const title = optionalText(input, 'current_title', job.current_title, 500);
    const error = optionalText(input, 'error', job.error, 500);
    const total = metric('total');
    const complete = metric('complete');
    const partial = metric('partial');
    const failed = metric('failed');
    const invalid = metric('invalid');
    if (complete + partial + failed + invalid > total) throw new RequestError(400, '分类进度超过商品总数');
    const expected = input.expected_records === undefined ? job.expected_records
      : numberField(input.expected_records, 0, job.limit, '待入库记录数超出任务上限');
    const finalStatus = TERMINAL_STATES.has(requested) ? requested : null;
    if (finalStatus === 'completed' && (expected === null || expected < complete + partial)) {
      throw new RequestError(400, '完成任务时需要提供预期入库记录数');
    }
    const status = finalStatus && expected !== null && job.received < expected ? 'uploading' : requested;
    db.prepare(`
      UPDATE mobile_jobs SET status = ?, sequence = ?, stage = ?, current_title = ?, total = ?, complete = ?,
        partial = ?, failed = ?, invalid = ?, expected_records = ?, final_status = ?, updated_at = ?, error = ?
      WHERE id = ?
    `).run(status, sequence, stage, title, total, complete, partial, failed, invalid,
      expected, finalStatus, timestamp, error, id);
    return { ok: true };
  });
}

export function mobileRecordJob(payload: unknown, deviceId?: string) {
  const input = object(payload);
  if (deviceId && input.device_id !== undefined && input.device_id !== deviceId) {
    throw new RequestError(403, '回传设备编号与配对设备不一致');
  }
  const inside = isObject(input.record) ? input.record.workbench_job_id : undefined;
  if (inside !== undefined && input.job_id !== undefined && inside !== input.job_id) {
    throw new RequestError(400, '回传的采集任务编号不一致');
  }
  const candidate = inside ?? input.job_id;
  if (candidate === undefined) return null;
  if (!deviceId) throw new RequestError(401, '采集任务回传需要设备配对');
  const id = requiredText(candidate, 36, '回传采集任务编号无效');
  if (!/^[\da-f-]{36}$/.test(id)) throw new RequestError(400, '回传采集任务编号无效');
  return { deviceId, id };
}

/** Called within the existing record write transaction. A rejected receipt rolls back the record as well. */
export function acknowledgeMobileRecord(db: DatabaseSync, target: { id: string; deviceId: string }, recordId: string, revision: number) {
  const job = ownedJob(db, target.deviceId, target.id);
  if (job.status === 'queued' || job.status === 'cancelled') {
    throw new RequestError(409, '采集任务尚未领取或已取消');
  }
  const previous = db.prepare('SELECT job_id FROM mobile_job_receipts WHERE record_id = ?').get(recordId);
  if (previous && previous.job_id !== job.id) throw new RequestError(409, '此采集记录已关联其他任务');
  if (!previous && job.received >= job.limit) throw new RequestError(409, '任务入库数已达到商品上限');
  db.prepare(`
    INSERT INTO mobile_job_receipts(record_id, job_id, revision) VALUES (?, ?, ?)
    ON CONFLICT(record_id) DO UPDATE SET revision = max(revision, excluded.revision)
  `).run(recordId, job.id, revision);
  if (!previous) {
    const count = job.received + 1;
    const status = job.final_status && (job.expected_records === null || count >= job.expected_records)
      ? job.final_status : job.status;
    db.prepare('UPDATE mobile_jobs SET received = ?, status = ?, updated_at = ? WHERE id = ?')
      .run(count, status, now(), job.id);
  }
}

export function isMobileTaskPath(path: string) {
  return path === PAIR_PATH || path === CLAIM_PATH || PROGRESS_PATH.test(path)
    || path === `${ADMIN_PATH}/pairing` || path === `${ADMIN_PATH}/devices` || path === `${ADMIN_PATH}/jobs`
    || CANCEL_PATH.test(path);
}

export async function handleMobileTaskRequest(
  path: string, request: IncomingMessage, response: ServerResponse, db: DatabaseSync, readJson: ReadJson, send: SendJson,
) {
  const read = () => readJson(request, 16 * 1024);
  if (path.startsWith(ADMIN_PATH)) {
    requireAdmin(request);
    if (path === `${ADMIN_PATH}/devices` && request.method === 'GET') return send(response, listDevices(db));
    if (path === `${ADMIN_PATH}/jobs` && request.method === 'GET') {
      const jobs = db.prepare(`
        SELECT * FROM mobile_jobs
        ORDER BY (status IN (${ACTIVE}, 'queued')) DESC, created_at DESC, rowid DESC LIMIT 100
      `).all() as MobileJob[];
      return send(response, { jobs: jobs.map(publicJob) });
    }
    if (request.method === 'POST') {
      if (path === `${ADMIN_PATH}/pairing`) { object(await read()); return send(response, issuePairing(db)); }
      if (path === `${ADMIN_PATH}/jobs`) return send(response, createJob(db, await read()), 201);
      const cancel = CANCEL_PATH.exec(path);
      if (cancel) { object(await read()); return send(response, cancelJob(db, cancel[1])); }
    }
  } else if (request.method === 'POST') {
    requireMobileTransport(request);
    if (path === PAIR_PATH) return send(response, pairDevice(db, await read()));
    const deviceId = authenticateMobile(db, request);
    if (path === CLAIM_PATH) return send(response, claimJob(db, deviceId, await read()));
    const progress = PROGRESS_PATH.exec(path);
    if (progress) return send(response, reportProgress(db, deviceId, progress[1], await read()));
  }
  throw new RequestError(405, '请求方式不支持');
}
