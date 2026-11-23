import { readCollector } from './mobileCollector';

const base = import.meta.env.BASE_URL + 'api/workbench/pdd-mobile';
export const pddStatusKey = ['pdd-mobile', 'status'];
export const pricingRules = ['火标规格最高价', '可见规格最高价', '单规格或火标', '仅火标', '人工核价'] as const;
export interface Settings {
  route: string; quantity: number; weekly: boolean; pricing: string; threshold: string; share: string;
}
interface Task {
  id: string; device: string; state: string; settings: Settings;
}
interface Device { id: string; model: string; online: boolean; available: boolean }
interface Summary extends Task { progress: {
  total: number; qualified: number; startedAt?: number; review?: number; step?: string; event?: string;
} }
export interface Item {
  id?: string; title?: string; daily?: number | string; dailyFloor?: boolean; dailyRaw?: string;
  weekly?: number | string; weeklyFloor?: boolean; weeklyRaw?: string; salesStatus?: string;
  sku?: string; priceKind?: string; price?: string; estimate?: string; qualifies?: boolean; evaluation?: string;
  imageState?: 'ready' | 'pending' | 'missing';
}
export interface Batch extends Task { items: Item[] }

export function pddStatus(signal: AbortSignal) {
  return readCollector<{ devices: Device[]; tasks: Summary[] }>(base + '/status', { signal });
}
export function batchUrl(id: string) { return base + '/tasks/' + encodeURIComponent(id); }
export function pddBatch(id: string, signal: AbortSignal) {
  return readCollector<Batch>(batchUrl(id), { signal });
}
function post<T>(path: string, body: unknown) {
  return readCollector<T>(base + path, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  });
}
export function pairPddDevice() { return post<{ key: string }>('/pair', {}); }
export function createPddJob(input: { device: string; settings: Settings }) {
  return post<{ id: string }>('/tasks', input);
}
export function batchState(task: Task, count: number) {
  return task.state === '已完成' && count < task.settings.quantity ? '已结束（未采满）' : task.state;
}
