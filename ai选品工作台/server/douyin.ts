import { mkdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import type { IncomingMessage, ServerResponse } from 'node:http';
import path from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import type { Plugin } from 'vite';
import type { DouyinRecord, WebImageFetchResult } from '../src/types/douyin';
import { productLink } from '../src/utils/douyinProduct';
import { calculateDouyinSelection, DEFAULT_DOUYIN_SELECTION_RULE, eligibleDouyinSkus } from '../src/utils/douyinSelection';
import type { DouyinSelectionRule } from '../src/utils/douyinSelection';
import { fetchWebProductImages } from './douyin-web-images';
import {
  RequestError, isObject, initializeDouyinLibrary, queryDouyinProducts, douyinProductMetadata,
  douyinShops, listDouyinLabels, createDouyinLabel, deleteDouyinLabel,
  updateDouyinProducts, deleteDouyinProducts, exportDouyinProducts, douyinDuplicates,
} from './douyin-library';
import {
  acknowledgeMobileRecord, authenticateMobile, fromMobileGateway, handleMobileTaskRequest, initializeMobileTasks,
  isLocalMobileRequest, isMobileTaskPath, mobileRecordJob,
} from './mobile-tasks';

const API = '/api/workbench/douyin';
const MOBILE_API = '/api/mobile/records';
const MAX_BYTES = 16 * 1024 * 1024;
const PNG = Buffer.from('89504e470d0a1a0a', 'hex');
const JPEG = Buffer.from('ffd8ff', 'hex');

type StoredImage = { kind: string; specIndex: number; imageIndex: number; mime: string; bytes: Buffer };

function imageUrl(id: string, kind: string, spec: number, index: number) {
  return `${API}/image?${new URLSearchParams({ id, kind, spec: String(spec), index: String(index) })}`;
}

function validateRecords(payload: unknown): DouyinRecord[] {
  const rows = isObject(payload) ? payload.records : payload;
  if (!Array.isArray(rows) || rows.length < 1 || rows.length > 5000) {
    throw new RequestError(400, '请选择包含 1 至 5000 条记录的手机采集 JSON');
  }
  for (const [index, row] of rows.entries()) {
    if (!isObject(row) || row.platform !== 'douyin_mall'
        || typeof row.record_id !== 'string' || !row.record_id.trim() || row.record_id.length > 240
        || typeof row.captured_at !== 'string' || !Number.isFinite(Date.parse(row.captured_at))
        || (row.revision !== undefined && (!Number.isSafeInteger(row.revision) || Number(row.revision) < 0))
        || !Array.isArray(row.skus) || !row.skus.every(isObject)) {
      throw new RequestError(400, `第 ${index + 1} 条不是有效的抖音手机采集记录`);
    }
  }
  return rows;
}

async function readJson(request: IncomingMessage, maxBytes = MAX_BYTES): Promise<unknown> {
  if (request.headers['content-type']?.split(';')[0].trim() !== 'application/json') {
    throw new RequestError(415, '需要上传 JSON');
  }
  const largeRequest = maxBytes === MAX_BYTES ? '文件超过 16 MB' : '请求内容过大';
  if (Number(request.headers['content-length']) > maxBytes) {
    request.resume();
    throw new RequestError(413, largeRequest);
  }
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request.iterator({ destroyOnReturn: false })) {
    size += chunk.length;
    if (size > maxBytes) {
      request.resume();
      throw new RequestError(413, largeRequest);
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8').replace(/^\uFEFF/, ''));
  } catch {
    throw new RequestError(400, '文件内容不是有效的 JSON');
  }
}

function validateMobile(payload: unknown) {
  if (!isObject(payload) || payload.schema_version !== 1 || !Array.isArray(payload.images) || payload.images.length > 200) {
    throw new RequestError(400, '无效的手机回传格式');
  }
  const [record] = validateRecords([payload.record]);
  if (payload.record_id !== record.record_id || payload.revision !== record.revision || !record.revision) {
    throw new RequestError(400, '手机回传编号或版本不一致');
  }
  const images: StoredImage[] = [];
  const seen = new Set<string>();
  for (const image of payload.images) {
    if (!isObject(image) || typeof image.kind !== 'string' || !['main_image', 'product_image', 'sku_image'].includes(image.kind)
        || typeof image.spec_index !== 'number' || !Number.isInteger(image.spec_index) || image.spec_index < -1
        || typeof image.image_index !== 'number' || !Number.isInteger(image.image_index) || image.image_index < 0
        || typeof image.data_base64 !== 'string' || !/^[A-Za-z0-9+/]+={0,2}$/.test(image.data_base64)
        || typeof image.sha256 !== 'string') throw new RequestError(400, '回传图片格式无效');
    const bytes = Buffer.from(image.data_base64, 'base64');
    const mime = bytes.subarray(0, PNG.length).equals(PNG) ? 'image/png'
      : bytes.subarray(0, JPEG.length).equals(JPEG) ? 'image/jpeg' : '';
    const key = `${image.kind}:${image.spec_index}:${image.image_index}`;
    if (!mime || mime !== image.mime_type || createHash('sha256').update(bytes).digest('hex') !== image.sha256 || seen.has(key)) {
      throw new RequestError(400, '图片类型、校验值或编号不一致');
    }
    seen.add(key);
    images.push({ kind: image.kind, specIndex: image.spec_index, imageIndex: image.image_index, mime, bytes });
  }
  if (record.product_images != null && (!Array.isArray(record.product_images)
      || record.product_images.some(image => !image || !Number.isSafeInteger(image.position) || image.position < 1
        || (!seen.has(`product_image:-1:${image.position - 1}`) && !(image.position === 1 && seen.has('main_image:-1:0')))))) {
    throw new RequestError(400, '有轮播图未随记录回传');
  }
  return { record, images };
}

export function douyinDatabase(): Plugin {
  let db: DatabaseSync | undefined;
  let databasePath: string;
  function database() {
    if (db) return db;
    mkdirSync(path.dirname(databasePath), { recursive: true });
    const connection = new DatabaseSync(databasePath);
    connection.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA busy_timeout = 5000;
      CREATE TABLE IF NOT EXISTS douyin_records (
        id TEXT PRIMARY KEY,
        revision INTEGER NOT NULL,
        capture_time TEXT NOT NULL,
        payload TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS douyin_images (
        record_id TEXT NOT NULL,
        revision INTEGER NOT NULL,
        kind TEXT NOT NULL,
        spec_index INTEGER NOT NULL,
        image_index INTEGER NOT NULL,
        mime TEXT NOT NULL,
        bytes BLOB NOT NULL,
        PRIMARY KEY (record_id, revision, kind, spec_index, image_index)
      );
      CREATE TABLE IF NOT EXISTS douyin_web_images (
        record_id TEXT NOT NULL,
        revision INTEGER NOT NULL,
        status TEXT NOT NULL,
        product_id TEXT NOT NULL,
        product_url TEXT NOT NULL,
        source_urls TEXT NOT NULL,
        PRIMARY KEY (record_id, revision)
      );
      CREATE TABLE IF NOT EXISTS douyin_selection_settings (
        id INTEGER PRIMARY KEY CHECK (id = 1),
        ratio_percent REAL NOT NULL,
        threshold_yuan REAL NOT NULL
      );
      CREATE TABLE IF NOT EXISTS douyin_selection_skus (
        record_id TEXT NOT NULL,
        revision INTEGER NOT NULL,
        sku_index INTEGER NOT NULL,
        PRIMARY KEY (record_id, revision)
      );
    `);
    initializeDouyinLibrary(connection);
    initializeMobileTasks(connection);
    return (db = connection);
  }

  function insert(rows: DouyinRecord[], images?: StoredImage[], job?: ReturnType<typeof mobileRecordJob>) {
    const connection = database();
    const current = connection.prepare('SELECT revision FROM douyin_records WHERE id = ?');
    const put = connection.prepare(`
      INSERT INTO douyin_records (id, revision, capture_time, payload) VALUES (?, ?, ?, ?)
      ON CONFLICT(id) DO UPDATE SET revision=excluded.revision, capture_time=excluded.capture_time, payload=excluded.payload
    `);
    const result = { inserted: 0, updated: 0, skipped: 0 };
    connection.exec('BEGIN IMMEDIATE');
    try {
      for (const row of rows) {
        const previous = current.get(row.record_id);
        const revision = row.revision ?? 0;
        if (previous && Number(previous.revision) >= revision) {
          result.skipped++;
          continue;
        }
        put.run(row.record_id, revision, new Date(row.captured_at).toISOString(), JSON.stringify(row));
        if (previous) result.updated++;
        else result.inserted++;
      }
      if (images && rows.length === 1) {
        const row = rows[0];
        const saved = current.get(row.record_id);
        if (Number(saved?.revision) === row.revision) {
          const putImage = connection.prepare('INSERT OR REPLACE INTO douyin_images VALUES (?, ?, ?, ?, ?, ?, ?)');
          for (const image of images) {
            putImage.run(row.record_id, row.revision, image.kind, image.specIndex, image.imageIndex, image.mime, image.bytes);
          }
        }
      }
      if (job) acknowledgeMobileRecord(connection, job, rows[0].record_id, rows[0].revision!);
      connection.exec('COMMIT');
      return result;
    } catch (error) {
      connection.exec('ROLLBACK');
      throw error;
    }
  }

  function send(response: ServerResponse, data: unknown, status = 200) {
    response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(data));
  }

  function webImageInfo(id: string, revision: number) {
    return database().prepare('SELECT status, product_id, product_url, source_urls FROM douyin_web_images WHERE record_id = ? AND revision = ?')
      .get(id, revision);
  }

  function selectionRule(): DouyinSelectionRule {
    const row = database().prepare('SELECT ratio_percent, threshold_yuan FROM douyin_selection_settings WHERE id = 1').get();
    return row ? { ratioPercent: Number(row.ratio_percent), thresholdYuan: Number(row.threshold_yuan) }
      : DEFAULT_DOUYIN_SELECTION_RULE;
  }

  function sendSelectionSettings(response: ServerResponse, rule: DouyinSelectionRule) {
    send(response, { ratio_percent: rule.ratioPercent, threshold_yuan: rule.thresholdYuan });
  }

  function selectionFor(record: DouyinRecord, revision: number, rule: DouyinSelectionRule) {
    const row = database().prepare('SELECT sku_index FROM douyin_selection_skus WHERE record_id = ? AND revision = ?')
      .get(record.record_id, revision);
    const signal = calculateDouyinSelection(record, rule, row ? Number(row.sku_index) : undefined);
    return { selection_signal: signal, selection_labels: signal.marked ? ['商品卡测算达标'] : [] };
  }

  const imageJobs = new Map<string, Promise<WebImageFetchResult>>();
  let imageQueue = Promise.resolve<unknown>(undefined);
  function collectWebImages(record: DouyinRecord, retry = false): Promise<WebImageFetchResult> {
    const link = productLink(record);
    if (!link || record.product_state === 'invalid') {
      return Promise.resolve({ status: 'no_link', outcome: 'no_link', image_count: 0 });
    }
    const revision = record.revision ?? 0;
    const key = `${record.record_id}:${revision}`;
    const pending = imageJobs.get(key);
    if (pending) return pending;
    const previous = webImageInfo(record.record_id, revision);
    const previousStatus = previous?.status === 'cover_only' || previous?.status === 'page_images'
      ? previous.status : 'unavailable';
    const previousUrls: string[] = previous ? JSON.parse(String(previous.source_urls)) : [];
    if (previous && !retry) return Promise.resolve({
      status: previousStatus,
      outcome: previousUrls.length ? 'unchanged' : 'unavailable',
      image_count: previousUrls.length,
    });
    const job = imageQueue.then(async (): Promise<WebImageFetchResult> => {
      let status: WebImageFetchResult['status'] = 'unavailable';
      let productId = '', productUrl = '';
      let images: Awaited<ReturnType<typeof fetchWebProductImages>>['images'] = [];
      try {
        const fetched = await fetchWebProductImages(link, retry);
        if (record.product_id && record.product_id !== fetched.productId) throw new Error('商品 ID 与入库记录不一致');
        productId = fetched.productId;
        productUrl = fetched.productUrl;
        images = fetched.images;
        status = images.length ? fetched.coverage : 'unavailable';
      } catch { /* Keep the record usable and expose retry on its details page. */ }
      const connection = database();
      const current = connection.prepare('SELECT payload FROM douyin_records WHERE id = ? AND revision = ?')
        .get(record.record_id, revision);
      if (!current || productLink(JSON.parse(String(current.payload))) !== link) {
        return { status: 'unavailable', outcome: 'unavailable', image_count: 0 };
      }
      if (previousUrls.length && status !== 'page_images'
          && (!images.length || previousStatus === 'page_images' || images.length < previousUrls.length)) {
        return { status: previousStatus, outcome: 'retained', image_count: previousUrls.length };
      }
      if (images.length && status === previousStatus && images.length === previousUrls.length) {
        const saved = connection.prepare(`
          SELECT mime, bytes FROM douyin_images
          WHERE record_id = ? AND revision = ? AND kind = 'web_product_image'
          ORDER BY image_index
        `).all(record.record_id, revision);
        if (images.length === saved.length && images.every((image, index) => {
          const existing = saved[index];
          return existing.mime === image.mime && existing.bytes instanceof Uint8Array
            && image.bytes.equals(existing.bytes) && image.sourceUrl === previousUrls[index];
        })) {
          return { status, outcome: 'unchanged', image_count: images.length, product_id: productId };
        }
      }
      connection.exec('BEGIN IMMEDIATE');
      try {
        connection.prepare('DELETE FROM douyin_images WHERE record_id = ? AND revision = ? AND kind = ?')
          .run(record.record_id, revision, 'web_product_image');
        const save = connection.prepare('INSERT INTO douyin_images VALUES (?, ?, ?, ?, ?, ?, ?)');
        images.forEach((image, index) => save.run(record.record_id, revision, 'web_product_image', -1, index, image.mime, image.bytes));
        connection.prepare('INSERT OR REPLACE INTO douyin_web_images VALUES (?, ?, ?, ?, ?, ?)')
          .run(record.record_id, revision, status, productId, productUrl, JSON.stringify(images.map(image => image.sourceUrl)));
        connection.exec('COMMIT');
      } catch (error) {
        connection.exec('ROLLBACK');
        throw error;
      }
      return { status, outcome: images.length ? 'updated' : 'unavailable', image_count: images.length, product_id: productId };
    }).finally(() => imageJobs.delete(key));
    imageJobs.set(key, job);
    imageQueue = job.catch(error => console.error('Douyin web images:', error));
    return job;
  }

  function middleware(request: IncomingMessage, response: ServerResponse, next: () => void) {
    const url = new URL(request.url ?? '/', 'http://localhost');
    const mobileTask = isMobileTaskPath(url.pathname);
    const labelPath = url.pathname.startsWith(`${API}/`)
      ? /^(tags|groups)(?:\/(\d+))?$/.exec(url.pathname.slice(API.length + 1)) : null;
    if (![API, `${API}/import`, `${API}/image`, `${API}/web-images`,
      `${API}/selection-settings`, `${API}/selection-sku`, `${API}/shops`, `${API}/duplicates`,
      `${API}/batch-update`, `${API}/delete`, `${API}/export`, MOBILE_API].includes(url.pathname)
      && !labelPath && !mobileTask) { next(); return; }
    void (async () => {
      if (mobileTask) {
        await handleMobileTaskRequest(url.pathname, request, response, database(), readJson, send);
      } else if (labelPath) {
        const kind = labelPath[1] === 'tags' ? 'tag' : 'group';
        const id = Number(labelPath[2]);
        if (!labelPath[2] && request.method === 'GET') send(response, listDouyinLabels(database(), kind));
        else if (!labelPath[2] && request.method === 'POST') send(response, createDouyinLabel(database(), kind, await readJson(request)), 201);
        else if (Number.isSafeInteger(id) && id > 0 && request.method === 'DELETE') send(response, deleteDouyinLabel(database(), kind, id));
        else throw new RequestError(405, '请求方式不支持');
      } else if (request.method === 'GET' && url.pathname === `${API}/shops`) {
        send(response, douyinShops(database()));
      } else if (request.method === 'GET' && url.pathname === `${API}/duplicates`) {
        send(response, douyinDuplicates(database()));
      } else if (request.method === 'POST' && url.pathname === `${API}/batch-update`) {
        send(response, updateDouyinProducts(database(), await readJson(request)));
      } else if (request.method === 'POST' && url.pathname === `${API}/delete`) {
        send(response, deleteDouyinProducts(database(), await readJson(request)));
      } else if (request.method === 'POST' && url.pathname === `${API}/export`) {
        send(response, exportDouyinProducts(database(), await readJson(request)));
      } else if (request.method === 'GET' && url.pathname === `${API}/selection-settings`) {
        sendSelectionSettings(response, selectionRule());
      } else if (request.method === 'PUT' && url.pathname === `${API}/selection-settings`) {
        const payload = await readJson(request);
        const ratioPercent = isObject(payload) ? payload.ratio_percent : undefined;
        const thresholdYuan = isObject(payload) ? payload.threshold_yuan : undefined;
        if (typeof ratioPercent !== 'number' || !Number.isFinite(ratioPercent) || ratioPercent < 0 || ratioPercent > 100
            || Math.round(ratioPercent * 100) / 100 !== ratioPercent
            || typeof thresholdYuan !== 'number' || !Number.isFinite(thresholdYuan) || thresholdYuan < 0
            || !Number.isSafeInteger(Math.round(thresholdYuan * 100))
            || Math.round(thresholdYuan * 100) / 100 !== thresholdYuan) {
          throw new RequestError(400, '请输入 0 至 100 的比例和有效的非负门槛，最多两位小数');
        }
        database().prepare('INSERT OR REPLACE INTO douyin_selection_settings (id, ratio_percent, threshold_yuan) VALUES (1, ?, ?)')
          .run(ratioPercent, thresholdYuan);
        sendSelectionSettings(response, { ratioPercent, thresholdYuan });
      } else if (request.method === 'PUT' && url.pathname === `${API}/selection-sku`) {
        const payload = await readJson(request);
        if (!isObject(payload) || typeof payload.id !== 'string'
            || (payload.sku_index !== null && (!Number.isSafeInteger(payload.sku_index) || Number(payload.sku_index) < 0))) {
          throw new RequestError(400, '请选择有效的规格');
        }
        const row = database().prepare('SELECT payload, revision FROM douyin_records WHERE id = ?').get(payload.id);
        if (!row) throw new RequestError(404, '找不到这条采集记录');
        const revision = Number(row.revision);
        if (payload.revision !== undefined && payload.revision !== revision) {
          throw new RequestError(409, '采集记录已更新，请刷新后重新选择规格');
        }
        const record: DouyinRecord = JSON.parse(String(row.payload));
        if (payload.sku_index === null) {
          database().prepare('DELETE FROM douyin_selection_skus WHERE record_id = ? AND revision = ?').run(payload.id, revision);
        } else {
          const index = Number(payload.sku_index);
          if (!eligibleDouyinSkus(record).some(sku => sku.index === index)) throw new RequestError(400, '该规格没有可用于测算的价格');
          database().prepare('INSERT OR REPLACE INTO douyin_selection_skus (record_id, revision, sku_index) VALUES (?, ?, ?)')
            .run(payload.id, revision, index);
        }
        send(response, selectionFor(record, revision, selectionRule()));
      } else if (request.method === 'POST' && url.pathname === `${API}/import`) {
        const records = validateRecords(await readJson(request));
        send(response, insert(records));
        records.forEach(record => { void collectWebImages(record).catch(() => undefined); });
      } else if (request.method === 'POST' && url.pathname === MOBILE_API) {
        if (!isLocalMobileRequest(request)) {
          throw new RequestError(403, '手机入库接口仅接受本机连接');
        }
        const deviceId = request.headers.authorization !== undefined || fromMobileGateway(request)
          ? authenticateMobile(database(), request) : undefined;
        const payload = await readJson(request);
        const job = mobileRecordJob(payload, deviceId);
        const { record, images } = validateMobile(payload);
        if (job) Object.assign(record, { workbench_job_id: job.id });
        insert([record], images, job);
        send(response, { acknowledged: true, record_id: record.record_id, revision: record.revision });
        void collectWebImages(record).catch(() => undefined);
      } else if (request.method === 'POST' && url.pathname === `${API}/web-images`) {
        const payload = await readJson(request);
        if (!isObject(payload) || typeof payload.id !== 'string') throw new RequestError(400, '缺少采集记录编号');
        const row = database().prepare('SELECT payload FROM douyin_records WHERE id = ?').get(payload.id);
        if (!row) throw new RequestError(404, '找不到这条采集记录');
        send(response, await collectWebImages(JSON.parse(String(row.payload)), true));
      } else if (request.method === 'GET' && url.pathname === `${API}/image`) {
        const image = database().prepare(`
          SELECT bytes, mime FROM douyin_images AS asset JOIN douyin_records AS record
          ON record.id = asset.record_id AND record.revision = asset.revision
          WHERE asset.record_id = ? AND kind = ? AND spec_index = ? AND image_index = ?
        `).get(url.searchParams.get('id'), url.searchParams.get('kind'), url.searchParams.get('spec'), url.searchParams.get('index'));
        if (!image || !(image.bytes instanceof Uint8Array)) throw new RequestError(404, '找不到采集图片');
        response.writeHead(200, { 'Content-Type': String(image.mime), 'X-Content-Type-Options': 'nosniff', 'Cache-Control': 'no-store' });
        response.end(image.bytes);
      } else if (request.method === 'GET' && url.pathname === API) {
        const id = url.searchParams.get('id');
        if (id !== null) {
          const row = database().prepare('SELECT payload, revision FROM douyin_records WHERE id = ?').get(id);
          if (!row) throw new RequestError(404, '找不到这条采集记录');
          const assets = database().prepare(`
            SELECT kind, spec_index, image_index FROM douyin_images
            WHERE record_id = ? AND revision = ? AND kind IN ('main_image', 'product_image', 'web_product_image') ORDER BY image_index
          `).all(id, row.revision);
          const fromWeb = assets.some(asset => asset.kind === 'web_product_image');
          const web = webImageInfo(id, Number(row.revision));
          const sourceUrls: string[] = web ? JSON.parse(String(web.source_urls)) : [];
          const images = assets.filter(asset => (asset.kind === 'web_product_image') === fromWeb).map(asset => ({
            position: Number(asset.image_index) + 1,
            url: imageUrl(id, String(asset.kind), Number(asset.spec_index), Number(asset.image_index)),
            source_url: fromWeb ? sourceUrls[Number(asset.image_index)] : undefined,
          }));
          const record: DouyinRecord = JSON.parse(String(row.payload));
          send(response, {
            ...record,
            ...selectionFor(record, Number(row.revision), selectionRule()),
            ...douyinProductMetadata(database(), record),
            product_id: record.product_id || web?.product_id || undefined,
            douyin_product_url: web?.product_url || record.douyin_product_url || undefined,
            web_image_status: imageJobs.has(`${id}:${row.revision}`) ? 'pending' : web?.status,
            stored_image_source: fromWeb ? 'web' : images.length ? 'phone' : undefined,
            stored_product_images: images,
          });
          return;
        }
        const connection = database();
        const { rows, ...pagination } = queryDouyinProducts(connection, url.searchParams);
        const cover = connection.prepare(`
          SELECT kind, spec_index, image_index FROM douyin_images
          WHERE record_id = ? AND revision = ? AND kind IN ('main_image', 'product_image', 'web_product_image')
          ORDER BY (kind = 'web_product_image') DESC, image_index, kind LIMIT 1
        `);
        const rule = selectionRule();
        send(response, { ...pagination, records: rows.map(row => {
          const asset = cover.get(row.id, row.revision);
          const coverUrl = asset ? imageUrl(String(row.id), String(asset.kind), Number(asset.spec_index), Number(asset.image_index)) : '';
          const record: DouyinRecord = JSON.parse(String(row.payload));
          return {
            ...record,
            ...selectionFor(record, Number(row.revision), rule),
            ...douyinProductMetadata(connection, record, row.price_fen),
            stored_cover_url: coverUrl,
            web_image_status: imageJobs.has(`${row.id}:${row.revision}`) ? 'pending' : undefined,
          };
        }) });
      } else {
        throw new RequestError(405, '请求方式不支持');
      }
    })().catch(error => {
      if (!(error instanceof RequestError)) console.error('Douyin database:', error);
      send(response, { error: error instanceof RequestError ? error.message : '抖音入库服务处理失败' },
        error instanceof RequestError ? error.status : 500);
    });
  }

  return {
    name: 'workbench-douyin-database',
    configResolved(config) {
      databasePath = process.env.WORKBENCH_DOUYIN_DB || path.join(config.root, 'data', 'douyin.sqlite');
    },
    configureServer(server) { server.middlewares.use(middleware); },
    configurePreviewServer(server) { server.middlewares.use(middleware); },
  };
}
