import { createHash } from 'node:crypto';
import type { DatabaseSync } from 'node:sqlite';
import { indexKaoguCategories, kaoguCategories } from '../src/data/kaoguCategories';
import type { KaoguCategory } from '../src/data/kaoguCategories';
import { RequestError } from './douyin-library';
import { fetchKaoguCategories } from './kaogu-catalog-source.mjs';

type CatalogSnapshot = {
  version: string;
  updated_at: string | null;
  categories: KaoguCategory[];
  total: number;
};

function makeState(categories: KaoguCategory[], updatedAt: string | null, json = JSON.stringify(categories)) {
  const paths = indexKaoguCategories(categories);
  const version = createHash('sha256').update(json).digest('hex');
  const snapshot: CatalogSnapshot = { version, updated_at: updatedAt, categories, total: paths.size };
  return { snapshot, paths };
}

const bundled = makeState(kaoguCategories, null);
const states = new WeakMap<DatabaseSync, ReturnType<typeof makeState>>();
const refreshing = new WeakMap<DatabaseSync, Promise<CatalogSnapshot & { changed: boolean }>>();

export function initializeKaoguCatalog(db: DatabaseSync) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS kaogu_catalog (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      payload TEXT NOT NULL,
      updated_at TEXT NOT NULL
    )
  `);
}

function catalogState(db: DatabaseSync) {
  const cached = states.get(db);
  if (cached) return cached;
  const row = db.prepare('SELECT payload, updated_at FROM kaogu_catalog WHERE id = 1').get();
  let state = bundled;
  if (typeof row?.payload === 'string' && typeof row.updated_at === 'string') {
    try {
      const saved = makeState(JSON.parse(row.payload), row.updated_at, row.payload);
      if (saved.snapshot.total > 0) state = saved;
    } catch { /* A damaged snapshot leaves the built-in catalog available. */ }
  }
  states.set(db, state);
  return state;
}

export function kaoguCatalog(db: DatabaseSync, knownVersion?: string) {
  const snapshot = catalogState(db).snapshot;
  if (knownVersion === snapshot.version) return {
    version: snapshot.version, updated_at: snapshot.updated_at, total: snapshot.total, unchanged: true,
  };
  return snapshot;
}

export function currentKaoguPath(db: DatabaseSync, category: string) {
  return catalogState(db).paths.get(category);
}

export function refreshKaoguCatalog(db: DatabaseSync) {
  const pending = refreshing.get(db);
  if (pending) return pending;
  const work = (async () => {
    let categories: KaoguCategory[];
    try { ({ categories } = await fetchKaoguCategories()); }
    catch (error) {
      const message = error instanceof Error && error.message.startsWith('考古加')
        ? error.message : '暂时无法从考古加获取类目';
      throw new RequestError(502, `${message}，已保留现有类目`);
    }
    const before = catalogState(db).snapshot;
    const payload = JSON.stringify(categories);
    const state = makeState(categories, new Date().toISOString(), payload);
    db.prepare(`
      INSERT INTO kaogu_catalog (id, payload, updated_at) VALUES (1, ?, ?)
      ON CONFLICT(id) DO UPDATE SET payload=excluded.payload, updated_at=excluded.updated_at
    `).run(payload, state.snapshot.updated_at);
    states.set(db, state);
    return { ...state.snapshot, changed: state.snapshot.version !== before.version };
  })().finally(() => refreshing.delete(db));
  refreshing.set(db, work);
  return work;
}
