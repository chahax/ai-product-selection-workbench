import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  batchUpdate, createGroup, createTag, deleteGroup, deleteProducts, deleteTag, exportGoods,
  fetchDuplicates, fetchGroups, fetchShops, fetchTags,
} from '../api';
import type { DuplicateGroup, GroupInfo, TagInfo } from '../types';
import type { DouyinRecord } from '../types/douyin';
import { useProducts } from './useApi';

export type LibraryPlatform = 'pdd' | 'douyin';
export type LibraryFilters = { min_price: string; max_price: string; min_sales: string; max_sales: string; shop: string };
export const DOUYIN_LIBRARY_API = '/api/workbench/douyin';

type DouyinLibraryPage = { records: DouyinRecord[]; total: number; page: number; page_size: number };
type BatchResult = { ok: boolean; updated: number; skipped: number };
type DeleteResult = { ok: boolean; deleted_count: number };
type DuplicatesResult = { duplicates: DuplicateGroup[]; total_groups: number };

export async function requestDouyinLibrary<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${DOUYIN_LIBRARY_API}${path}`, options);
  const data = await response.json().catch(() => null);
  if (!response.ok || data === null) throw new Error(data?.error || '无法读取抖音入库服务');
  return data as T;
}

function jsonBody(body: unknown): RequestInit {
  return { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
}

export async function exportLibraryProducts(platform: LibraryPlatform, ids: string[]) {
  if (platform === 'pdd') return { blob: await exportGoods(ids, 'excel'), extension: 'xlsx' };
  const data = await requestDouyinLibrary<{ records: DouyinRecord[] }>('/export', jsonBody({ ids }));
  return { blob: new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }), extension: 'json' };
}

export function useProductLibrary(
  platform: LibraryPlatform, page: number, keyword: string,
  tagId: number | undefined, groupId: number | undefined, filters: LibraryFilters,
) {
  const client = useQueryClient();
  const isDouyin = platform === 'douyin';
  const listKey = isDouyin ? 'douyin-records' : 'products';
  const tagsKey = isDouyin ? 'douyin-tags' : 'tags';
  const groupsKey = isDouyin ? 'douyin-groups' : 'groups';

  const pdd = useProducts(page, keyword, tagId, groupId, filters, !isDouyin);
  const douyin = useQuery({
    queryKey: ['douyin-records', page, keyword, tagId, groupId, filters],
    enabled: isDouyin,
    queryFn: ({ signal }) => {
      const params = new URLSearchParams({ page: String(page), keyword });
      if (tagId) params.set('tag', String(tagId));
      if (groupId) params.set('group', String(groupId));
      for (const [key, value] of Object.entries(filters)) {
        if (value) params.set(key, value);
      }
      return requestDouyinLibrary<DouyinLibraryPage>(`?${params}`, { signal });
    },
    refetchInterval: query => query.state.data?.records.some(row => row.web_image_status === 'pending') ? 2000 : false,
  });
  const shops = useQuery({
    queryKey: [isDouyin ? 'douyin-shops' : 'shops'],
    queryFn: isDouyin ? () => requestDouyinLibrary<{ shops: string[] }>('/shops') : fetchShops,
    staleTime: 60_000,
  });
  const tags = useQuery({
    queryKey: [tagsKey], queryFn: isDouyin ? () => requestDouyinLibrary<TagInfo[]>('/tags') : fetchTags,
  });
  const groups = useQuery({
    queryKey: [groupsKey], queryFn: isDouyin ? () => requestDouyinLibrary<GroupInfo[]>('/groups') : fetchGroups,
  });
  const duplicates = useQuery({
    queryKey: [isDouyin ? 'douyin-duplicates' : 'duplicates'],
    queryFn: isDouyin ? () => requestDouyinLibrary<DuplicatesResult>('/duplicates') : fetchDuplicates,
    enabled: false,
  });

  const refresh = () => client.invalidateQueries({ queryKey: [listKey] });
  const refreshAfterChange = () => {
    void refresh();
    if (isDouyin) void client.invalidateQueries({ queryKey: ['douyin-detail'] });
    else void client.invalidateQueries({ queryKey: ['dashboard'] });
  };
  const deleteSelection = useMutation({
    mutationFn: (ids: string[]) => isDouyin ? requestDouyinLibrary<DeleteResult>('/delete', jsonBody({ ids })) : deleteProducts(ids),
    onSuccess: () => {
      refreshAfterChange();
      void client.invalidateQueries({ queryKey: [isDouyin ? 'douyin-shops' : 'shops'] });
    },
  });
  const deleteInvalid = useMutation({
    mutationFn: () => requestDouyinLibrary<DeleteResult>('/delete-invalid', jsonBody({})),
    onSuccess: () => {
      refreshAfterChange();
      void client.invalidateQueries({ queryKey: ['douyin-shops'] });
    },
  });
  const batch = useMutation({
    mutationFn: ({ ids, updates }: { ids: string[]; updates: Record<string, unknown> }) => isDouyin
      ? requestDouyinLibrary<BatchResult>('/batch-update', jsonBody({ ids, updates })) : batchUpdate(ids, updates),
    onSuccess: refreshAfterChange,
  });
  const addTag = useMutation({
    mutationFn: ({ name, color }: { name: string; color: string }) => isDouyin
      ? requestDouyinLibrary<TagInfo>('/tags', jsonBody({ name, color })) : createTag(name, color),
    onSuccess: () => client.invalidateQueries({ queryKey: [tagsKey] }),
  });
  const removeTag = useMutation({
    mutationFn: (id: number) => isDouyin ? requestDouyinLibrary<{ ok: boolean }>(`/tags/${id}`, { method: 'DELETE' }) : deleteTag(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: [tagsKey] });
      refreshAfterChange();
    },
  });
  const addGroup = useMutation({
    mutationFn: (name: string) => isDouyin ? requestDouyinLibrary<GroupInfo>('/groups', jsonBody({ name })) : createGroup(name),
    onSuccess: () => client.invalidateQueries({ queryKey: [groupsKey] }),
  });
  const removeGroup = useMutation({
    mutationFn: (id: number) => isDouyin ? requestDouyinLibrary<{ ok: boolean }>(`/groups/${id}`, { method: 'DELETE' }) : deleteGroup(id),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: [groupsKey] });
      refreshAfterChange();
    },
  });

  return { pdd, douyin, shops, tags, groups, duplicates, refresh, deleteSelection, deleteInvalid, batch, addTag, removeTag, addGroup, removeGroup };
}
