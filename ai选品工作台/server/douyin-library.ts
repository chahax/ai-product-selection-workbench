import type { DatabaseSync, SQLInputValue } from 'node:sqlite';
import type { DuplicateGroup } from '../src/types';
import type { DouyinRecord } from '../src/types/douyin';
import { display, observedDouyinPrice } from '../src/utils/douyinProduct';
import { minimumDouyinCardSales } from '../src/utils/douyinSelection';

export class RequestError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}

export function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

type LabelKind = 'group' | 'tag';
type LabelRow = { id: number; name: string; color: string };
type ProductRow = { id: string; revision: number; payload: string; price_fen: number | null };

function shopName(record: DouyinRecord) {
  return display(record.douyin_shop_name, record.kaogujia_shop_name, record.source_detail?.shop_name, record.shop_name).trim();
}

function observedSales(record: DouyinRecord) {
  return minimumDouyinCardSales(record.source_detail?.core_data?.['商品卡销量']);
}

function recordFrom(payload: SQLInputValue): DouyinRecord {
  return JSON.parse(String(payload));
}

function labelData(kind: LabelKind, row: LabelRow) {
  const { id, name, color } = row;
  return kind === 'tag' ? { id, name, color } : { id, name, parent_id: null };
}

function idsFrom(payload: unknown) {
  if (!isObject(payload) || !Array.isArray(payload.ids) || !payload.ids.length || payload.ids.length > 5000
      || !payload.ids.every(id => typeof id === 'string' && id.trim() && id.length <= 240)) {
    throw new RequestError(400, '请选择有效的商品');
  }
  return [...new Set<string>(payload.ids)];
}

function numberIds(value: unknown): number[] {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.length > 500 || !value.every(id => Number.isSafeInteger(id) && id > 0)) {
    throw new RequestError(400, '请选择有效的分组或标签');
  }
  return [...new Set<number>(value)];
}

function numericFilter(params: URLSearchParams, name: string) {
  const raw = params.get(name)?.trim();
  if (raw) {
    const value = Number(raw);
    if (!Number.isFinite(value) || value < 0) throw new RequestError(400, '价格和销量筛选需填写非负数值');
    return value;
  }
  return null;
}

function transaction<T>(connection: DatabaseSync, operation: () => T): T {
  connection.exec('BEGIN IMMEDIATE');
  try {
    const result = operation();
    connection.exec('COMMIT');
    return result;
  } catch (error) {
    connection.exec('ROLLBACK');
    throw error;
  }
}

export function initializeDouyinLibrary(connection: DatabaseSync) {
  connection.exec(`
    PRAGMA foreign_keys = ON;
    CREATE TABLE IF NOT EXISTS douyin_library_labels (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      kind TEXT NOT NULL CHECK (kind IN ('group', 'tag')),
      name TEXT NOT NULL COLLATE NOCASE,
      color TEXT NOT NULL,
      UNIQUE (kind, name)
    );
    CREATE TABLE IF NOT EXISTS douyin_library_memberships (
      record_id TEXT NOT NULL REFERENCES douyin_records(id) ON DELETE CASCADE,
      label_id INTEGER NOT NULL REFERENCES douyin_library_labels(id) ON DELETE CASCADE,
      PRIMARY KEY (record_id, label_id)
    );
    CREATE INDEX IF NOT EXISTS douyin_library_memberships_by_label ON douyin_library_memberships(label_id);
    CREATE TABLE IF NOT EXISTS douyin_library_prices (
      record_id TEXT PRIMARY KEY REFERENCES douyin_records(id) ON DELETE CASCADE,
      price_fen INTEGER NOT NULL CHECK (price_fen >= 0)
    );
  `);
  const options = { deterministic: true };
  connection.function('library_shop', options, payload => shopName(recordFrom(payload)));
  connection.function('library_price_fen', options, payload => observedDouyinPrice(recordFrom(payload)).fen);
  connection.function('library_sales', options, payload => observedSales(recordFrom(payload)));
}

export function queryDouyinProducts(connection: DatabaseSync, params: URLSearchParams) {
  const page = Math.max(1, Math.min(1_000_000, parseInt(params.get('page') ?? '', 10) || 1));
  const pageSize = Math.max(1, Math.min(100, parseInt(params.get('page_size') ?? '', 10) || 25));
  const query = (params.get('keyword') ?? params.get('q') ?? '').trim().toLowerCase().slice(0, 120);
  const where: string[] = [];
  const values: SQLInputValue[] = [];
  if (query) {
    where.push(`instr(lower(
      coalesce(json_extract(payload, '$.douyin_product_name'), '') || ' ' ||
      coalesce(json_extract(payload, '$.kaogujia_product_name'), '') || ' ' ||
      coalesce(json_extract(payload, '$.title'), '') || ' ' ||
      coalesce(json_extract(payload, '$.source_title'), '') || ' ' ||
      coalesce(json_extract(payload, '$.douyin_shop_name'), '') || ' ' ||
      coalesce(json_extract(payload, '$.kaogujia_shop_name'), '') || ' ' ||
      coalesce(json_extract(payload, '$.shop_name'), '') || ' ' ||
      coalesce(json_extract(payload, '$.source_detail.shop_name'), '')
    ), ?) > 0`);
    values.push(query);
  }
  const shop = params.get('shop')?.trim();
  if (shop) {
    where.push('library_shop(rec.payload) = ?');
    values.push(shop);
  }
  for (const kind of ['group', 'tag'] as const) {
    const labelId = numericFilter(params, kind);
    if (labelId) {
      where.push(`EXISTS (
        SELECT 1 FROM douyin_library_memberships AS membership
        JOIN douyin_library_labels AS label ON label.id = membership.label_id
        WHERE membership.record_id = rec.id AND label.kind = ? AND label.id = ?
      )`);
      values.push(kind, labelId);
    }
  }
  for (const [metric, expression] of [
    ['price', '(coalesce(quote.price_fen, library_price_fen(rec.payload)) / 100.0)'],
    ['sales', 'library_sales(rec.payload)'],
  ] as const) {
    for (const [bound, operator] of [['min', '>='], ['max', '<=']] as const) {
      const value = numericFilter(params, `${bound}_${metric}`);
      if (value !== null) {
        where.push(`${expression} ${operator} ?`);
        values.push(value);
      }
    }
  }
  const scope = 'FROM douyin_records AS rec LEFT JOIN douyin_library_prices AS quote ON quote.record_id = rec.id'
    + (where.length ? ` WHERE ${where.join(' AND ')}` : '');
  const total = Number(connection.prepare(`SELECT count(*) AS total ${scope}`).get(...values)?.total);
  const rows = connection.prepare(`
    SELECT rec.id, rec.revision, rec.payload, quote.price_fen ${scope}
    ORDER BY rec.capture_time DESC, rec.id LIMIT ? OFFSET ?
  `).all(...values, pageSize, (page - 1) * pageSize) as ProductRow[];
  return { total, page, page_size: pageSize, rows };
}

export function douyinProductMetadata(connection: DatabaseSync, record: DouyinRecord, calculatedPriceFen?: number | null) {
  const labels = connection.prepare(`
    SELECT label.id, label.kind, label.name, label.color FROM douyin_library_labels AS label
    JOIN douyin_library_memberships AS membership ON membership.label_id = label.id
    WHERE membership.record_id = ? ORDER BY label.id
  `).all(record.record_id) as (LabelRow & { kind: LabelKind })[];
  const currentPrice = calculatedPriceFen === undefined
    ? connection.prepare('SELECT price_fen FROM douyin_library_prices WHERE record_id = ?').get(record.record_id)?.price_fen
    : calculatedPriceFen;
  const observed = observedDouyinPrice(record);
  const priceFen = typeof currentPrice === 'number' ? currentPrice : observed.fen;
  const maxPriceFen = typeof currentPrice === 'number' ? currentPrice : observed.maxFen;
  return {
    library_original_price: observed.fen === null ? null : observed.fen / 100,
    library_price: priceFen === null ? null : priceFen / 100,
    library_price_max: maxPriceFen === null ? null : maxPriceFen / 100,
    library_price_modified: typeof currentPrice === 'number',
    library_price_label: typeof currentPrice === 'number' ? '工作台报价' : observed.label,
    library_sales: observedSales(record),
    library_shop: shopName(record),
    tags: labels.filter(label => label.kind === 'tag').map(({ id, name, color }) => ({ id, name, color })),
    groups: labels.filter(label => label.kind === 'group').map(({ id, name }) => ({ id, name, parent_id: null })),
  };
}

export function douyinShops(connection: DatabaseSync) {
  const rows = connection.prepare(`
    SELECT DISTINCT library_shop(payload) AS shop FROM douyin_records ORDER BY shop
  `).all();
  return { shops: rows.map(row => String(row.shop)).filter(Boolean) };
}

export function listDouyinLabels(connection: DatabaseSync, kind: LabelKind) {
  return (connection.prepare('SELECT id, name, color FROM douyin_library_labels WHERE kind = ? ORDER BY id')
    .all(kind) as LabelRow[]).map(label => labelData(kind, label));
}

export function createDouyinLabel(connection: DatabaseSync, kind: LabelKind, payload: unknown) {
  const name = isObject(payload) && typeof payload.name === 'string' ? payload.name.trim() : '';
  const color = isObject(payload) && typeof payload.color === 'string' ? payload.color : '';
  if (!name || name.length > 100 || (kind === 'tag' && !/^#[a-f0-9]{6}$/i.test(color))) {
    throw new RequestError(400, '请输入有效的名称和颜色');
  }
  const insert = connection.prepare('INSERT OR IGNORE INTO douyin_library_labels(kind, name, color) VALUES (?, ?, ?)')
    .run(kind, name, color);
  if (!insert.changes) throw new RequestError(409, '已有同名的分组或标签');
  return labelData(kind, { id: Number(insert.lastInsertRowid), name, color });
}

export function deleteDouyinLabel(connection: DatabaseSync, kind: LabelKind, id: number) {
  connection.prepare('DELETE FROM douyin_library_labels WHERE kind = ? AND id = ?').run(kind, id);
  return { ok: true };
}

export function updateDouyinProducts(connection: DatabaseSync, payload: unknown) {
  const ids = idsFrom(payload);
  const updates = isObject(payload) && isObject(payload.updates) ? payload.updates : {};
  const groupsToAdd = numberIds(updates.groups_add);
  if (updates.group_id !== undefined) groupsToAdd.push(...numberIds([updates.group_id]));
  const changes = [
    { kind: 'tag', add: numberIds(updates.tags_add), remove: numberIds(updates.tags_remove) },
    { kind: 'group', add: groupsToAdd, remove: numberIds(updates.groups_remove) },
  ];
  const markUp = updates.price_multiply !== undefined || updates.price_add !== undefined;
  const multiplier = updates.price_multiply ?? 1;
  const fixed = updates.price_add ?? 0;
  if (typeof multiplier !== 'number' || !Number.isFinite(multiplier) || multiplier < 0
      || typeof fixed !== 'number' || !Number.isFinite(fixed)) throw new RequestError(400, '改价公式无效');
  const lookup = connection.prepare('SELECT payload, price_fen FROM douyin_records LEFT JOIN douyin_library_prices ON record_id = id WHERE id = ?');
  const labelLookup = connection.prepare('SELECT id FROM douyin_library_labels WHERE kind = ? AND id = ?');
  const link = connection.prepare('INSERT OR IGNORE INTO douyin_library_memberships VALUES (?, ?)');
  const unlink = connection.prepare('DELETE FROM douyin_library_memberships WHERE record_id = ? AND label_id = ?');
  const putPrice = connection.prepare('INSERT INTO douyin_library_prices VALUES (?, ?)');
  return transaction(connection, () => {
    let updated = 0;
    let skipped = 0;
    for (const change of changes) {
      if ([...change.add, ...change.remove].some(labelId => !labelLookup.get(change.kind, labelId))) {
        throw new RequestError(400, '分组或标签已不存在，请刷新后重试');
      }
    }
    for (const id of ids) {
      const row = lookup.get(id);
      if (!row) { skipped++; continue; }
      if (markUp) {
        const observed = observedDouyinPrice(recordFrom(row.payload)).fen;
        if (row.price_fen !== null || observed === null) { skipped++; continue; }
        const nextPrice = Math.round(observed * multiplier + fixed * 100);
        if (!Number.isSafeInteger(nextPrice) || nextPrice < 0) {
          throw new RequestError(400, '调整后的价格超出有效范围');
        }
        putPrice.run(id, nextPrice);
      }
      for (const change of changes) {
        for (const labelId of change.add) link.run(id, labelId);
        for (const labelId of change.remove) unlink.run(id, labelId);
      }
      updated++;
    }
    return { ok: true, updated, skipped };
  });
}

export function deleteDouyinProducts(connection: DatabaseSync, payload: unknown) {
  const ids = idsFrom(payload);
  return transaction(connection, () => removeDouyinRecords(connection, ids));
}

export function deleteInvalidDouyinProducts(connection: DatabaseSync) {
  return transaction(connection, () => {
    const records = connection.prepare("SELECT id FROM douyin_records WHERE json_extract(payload, '$.product_state') = 'invalid'").all();
    return removeDouyinRecords(connection, records.map(record => String(record.id)));
  });
}

function removeDouyinRecords(connection: DatabaseSync, ids: string[]) {
  const removeRecord = connection.prepare('DELETE FROM douyin_records WHERE id = ?');
  const related = ['douyin_images', 'douyin_web_images', 'douyin_selection_skus']
    .map(table => connection.prepare(`DELETE FROM ${table} WHERE record_id = ?`));
  let deletedCount = 0;
  for (const id of ids) {
    deletedCount += Number(removeRecord.run(id).changes);
    for (const remove of related) remove.run(id);
  }
  return { ok: true, deleted_count: deletedCount };
}

export function exportDouyinProducts(connection: DatabaseSync, payload: unknown) {
  const rows = idsFrom(payload);
  const lookup = connection.prepare('SELECT payload FROM douyin_records WHERE id = ?');
  const records = rows.flatMap(id => {
    const row = lookup.get(id);
    return row ? [recordFrom(row.payload)] : [];
  });
  return { records };
}

export function douyinDuplicates(connection: DatabaseSync) {
  const rows = connection.prepare(`
    SELECT rec.id, rec.payload, web.product_id FROM douyin_records AS rec
    LEFT JOIN douyin_web_images AS web ON web.record_id = rec.id AND web.revision = rec.revision
    ORDER BY rec.capture_time DESC, rec.id
  `).all();
  type Item = DuplicateGroup['items'][number];
  const exact = new Map<string, Item[]>();
  const titles = new Map<string, Item[]>();
  function add(index: Map<string, Item[]>, key: string, item: Item) {
    if (!key) return;
    const items = index.get(key);
    if (items) items.push(item);
    else index.set(key, [item]);
  }
  for (const row of rows) {
    const record = recordFrom(row.payload);
    const name = display(record.douyin_product_name, record.kaogujia_product_name, record.source_title, record.title).trim();
    const item = { goods_id: String(row.id), title: name || '未采到商品名' };
    add(exact, display(record.product_id, row.product_id), item);
    add(titles, name.slice(0, 20), item);
  }
  const duplicates: DuplicateGroup[] = [];
  const exactIds = new Map<string, string>();
  for (const [goods_id, items] of exact) {
    if (items.length < 2) continue;
    duplicates.push({ type: 'exact_id', goods_id, items });
    items.forEach(item => exactIds.set(item.goods_id, goods_id));
  }
  for (const [title_prefix, items] of titles) {
    if (items.length < 2) continue;
    const firstId = exactIds.get(items[0].goods_id);
    if (firstId && items.every(item => exactIds.get(item.goods_id) === firstId)) continue;
    duplicates.push({ type: 'similar_title', title_prefix, items });
  }
  return { duplicates, total_groups: duplicates.length };
}
