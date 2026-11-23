import { apiBase } from './path';
import type { KaoguCategory } from '../data/kaoguCategories';

const base = `${apiBase}/workbench/mobile`;

export type MobilePeriod = 'yesterday' | '7d' | '30d';
export type MobileJobStatus = 'queued' | 'claimed' | 'waiting_phone' | 'running' | 'paused'
  | 'uploading' | 'completed' | 'failed' | 'cancelled';

export interface MobileDevice {
  id: string;
  name: string;
  last_seen: string | null;
  ready: boolean;
}

export interface MobileJobInput {
  device_id: string;
  category: string;
  period: MobilePeriod;
  limit: number;
}

export interface MobileJob extends MobileJobInput {
  id: string;
  status: MobileJobStatus;
  stage?: string | null;
  current_title?: string | null;
  total: number;
  complete: number;
  partial: number;
  failed: number;
  invalid: number;
  received: number;
  expected_records?: number | null;
  created_at: string;
  updated_at: string;
  error?: string | null;
}

type Pairing = { code: string; expires_at: string };

export interface MobileCategoryCatalog {
  version: string;
  updated_at: string | null;
  categories: KaoguCategory[];
  total: number;
  changed?: boolean;
}

async function readMobile<T>(path: string, options?: RequestInit): Promise<T> {
  return readCollector<T>(base + path, options);
}

export async function readCollector<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);
  const data = await response.json().catch(() => null);
  if (!response.ok || data === null) {
    throw new Error(typeof data?.error === 'string' ? data.error : '无法连接手机采集服务');
  }
  return data as T;
}

function post(body: Record<string, unknown> | MobileJobInput): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

export function fetchMobileDevices(signal: AbortSignal) {
  return readMobile<{ devices: MobileDevice[] }>('/devices', { signal });
}

export function createMobilePairing() {
  return readMobile<Pairing>('/pairing', post({}));
}

export function fetchMobileJobs(signal: AbortSignal) {
  return readMobile<{ jobs: MobileJob[] }>('/jobs', { signal });
}

export function fetchMobileCategories(signal: AbortSignal) {
  return readMobile<MobileCategoryCatalog>('/categories', { signal });
}

export function refreshMobileCategories() {
  return readMobile<MobileCategoryCatalog>('/categories/refresh', post({}));
}

export function createMobileJob(input: MobileJobInput) {
  return readMobile<{ job: MobileJob }>('/jobs', post(input));
}

export function cancelMobileJob(id: string) {
  return readMobile<{ job: MobileJob }>(`/jobs/${encodeURIComponent(id)}/cancel`, post({}));
}
