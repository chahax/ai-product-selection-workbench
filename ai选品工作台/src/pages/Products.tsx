import { useState, useCallback, useMemo, useRef, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useSettings } from '../hooks/useApi';
import { DOUYIN_LIBRARY_API, exportLibraryProducts, requestDouyinLibrary, useProductLibrary } from '../hooks/useProductLibrary';
import type { LibraryPlatform } from '../hooks/useProductLibrary';
import SafeImage from '../components/SafeImage';
import { useConfirm } from '../components/Confirm';
import { useToast } from '../components/Toast';
import { useKeyboard } from '../hooks/useKeyboard';
import { useSearchHistory } from '../hooks/useSearchHistory';
import { relativeTime } from '../utils/time';
import { display, observedDouyinPrice, productLink, status, title } from '../utils/douyinProduct';
import { douyinSelectionLabel } from '../utils/douyinSelection';
import type { GoodsInfo, GroupInfo, TagInfo } from '../types';
import type { DouyinRecord } from '../types/douyin';
import s from '../styles/modules/Products.module.css';

export default function Products() {
  const [params] = useSearchParams();
  const platform = params.get('platform') === 'douyin' ? 'douyin' : 'pdd';
  return <ProductList key={platform} platform={platform} />;
}

function ProductList({ platform }: { platform: LibraryPlatform }) {
  const isDouyin = platform === 'douyin';
  const toast = useToast();
  const [searchParams, setSearchParams] = useSearchParams();
  const { confirm } = useConfirm();
  const { history: searchHistory, add: addSearchHistory } = useSearchHistory();
  const [showHistory, setShowHistory] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  const [page, setPage] = useState(() => Number(searchParams.get('page')) || 1);
  const [keyword, setKeyword] = useState(() => searchParams.get('keyword') || '');
  const [searchInput, setSearchInput] = useState(() => searchParams.get('keyword') || '');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [selectedGroupId, setSelectedGroupId] = useState<number | undefined>(() => {
    const v = searchParams.get('group'); return v ? Number(v) : undefined;
  });
  const [selectedTagId, setSelectedTagId] = useState<number | undefined>(() => {
    const v = searchParams.get('tag'); return v ? Number(v) : undefined;
  });

  const [priceModal, setPriceModal] = useState(false);
  const [priceMultiply, setPriceMultiply] = useState('1.5');
  const [priceAdd, setPriceAdd] = useState('3');
  const [tagName, setTagName] = useState('');
  const [tagColor, setTagColor] = useState('#FF9500');
  const [groupName, setGroupName] = useState('');
  const [dupModal, setDupModal] = useState(false);
  const [priceMin, setPriceMin] = useState(() => searchParams.get('min_price') || '');
  const [priceMax, setPriceMax] = useState(() => searchParams.get('max_price') || '');
  const [salesMin, setSalesMin] = useState(() => searchParams.get('min_sales') || '');
  const [salesMax, setSalesMax] = useState(() => searchParams.get('max_sales') || '');
  const [shopFilter, setShopFilter] = useState(() => searchParams.get('shop') || '');
  const [batchTaxonomy, setBatchTaxonomy] = useState<{ kind: 'tag' | 'group'; id: string } | null>(null);
  const [density, setDensity] = useState<'compact' | 'standard' | 'relaxed'>(() => {
    return (localStorage.getItem('pdd_density') as 'compact' | 'standard' | 'relaxed') || 'standard';
  });

  const activeFilters = { min_price: priceMin, max_price: priceMax, min_sales: salesMin, max_sales: salesMax, shop: shopFilter };
  const library = useProductLibrary(platform, page, keyword, selectedTagId, selectedGroupId, activeFilters);
  const { pdd, douyin, shops: { data: shopsData }, tags: { data: tags }, groups: { data: groups } } = library;
  const current = isDouyin ? douyin : pdd;
  const { data, isFetching, error, isPending } = current;
  const shops = shopsData?.shops ?? [];
  const qc = useQueryClient();
  const searchRef = useRef<HTMLInputElement>(null);
  const handleSearchKey = useCallback(() => searchRef.current?.focus(), []);
  useKeyboard({ 'r': () => void library.refresh(), '/': handleSearchKey });
  const { data: settings } = useSettings();
  const priceMarkup = settings?.price_markup;
  const [prevPriceMarkup, setPrevPriceMarkup] = useState(priceMarkup);
  if (priceMarkup !== prevPriceMarkup) {
    setPrevPriceMarkup(priceMarkup);
    if (priceMarkup) { setPriceMultiply(String(priceMarkup.multiplier)); setPriceAdd(String(priceMarkup.fixed_amount)); }
  }
  const { data: dupData, error: dupError, refetch: refetchDups, isFetching: dupLoading } = library.duplicates;
  const importer = useMutation({
    mutationFn: (file: File) => {
      if (file.size > 16 * 1024 * 1024) throw new Error('文件超过 16 MB');
      return requestDouyinLibrary<{ inserted: number; updated: number; skipped: number }>('/import', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: file,
      });
    },
    onSuccess: () => {
      setKeyword('');
      setSearchInput('');
      setPage(1);
      void qc.invalidateQueries({ queryKey: ['douyin-records'] });
      void qc.invalidateQueries({ queryKey: ['douyin-detail'] });
      void qc.invalidateQueries({ queryKey: ['douyin-shops'] });
    },
  });

  const items = useMemo(() => isDouyin ? douyin.data?.records.map(douyinCard) ?? [] : pdd.data?.items.map(pddCard) ?? [], [isDouyin, douyin.data, pdd.data]);
  const allIds = useMemo(() => items.map(item => item.id), [items]);
  const allSelected = allIds.length > 0 && allIds.every(id => selected.has(id));
  const totalPages = data ? Math.ceil(data.total / data.page_size) : 1;

  useEffect(() => {
    const params: Record<string, string> = {};
    if (isDouyin) params.platform = 'douyin';
    if (keyword) params.keyword = keyword;
    if (page > 1) params.page = String(page);
    if (selectedTagId) params.tag = String(selectedTagId);
    if (selectedGroupId) params.group = String(selectedGroupId);
    if (priceMin) params.min_price = priceMin;
    if (priceMax) params.max_price = priceMax;
    if (salesMin) params.min_sales = salesMin;
    if (salesMax) params.max_sales = salesMax;
    if (shopFilter) params.shop = shopFilter;
    const next = new URLSearchParams(params);
    if (next.toString() !== searchParams.toString()) setSearchParams(next, { replace: true });
  }, [isDouyin, keyword, page, selectedTagId, selectedGroupId, priceMin, priceMax, salesMin, salesMax, shopFilter, searchParams, setSearchParams]);

  const handleDensityChange = useCallback((d: 'compact' | 'standard' | 'relaxed') => {
    setDensity(d);
    localStorage.setItem('pdd_density', d);
  }, []);

  const toggle = useCallback((id: string) => {
    setSelected(prev => { const next = new Set(prev); if (next.has(id)) next.delete(id); else next.add(id); return next; });
  }, []);

  const toggleAll = useCallback(() => {
    setSelected(prev => {
      const next = new Set(prev);
      if (allIds.every(id => next.has(id))) allIds.forEach(id => next.delete(id));
      else allIds.forEach(id => next.add(id));
      return next;
    });
  }, [allIds]);

  const handleSearch = useCallback(() => {
    addSearchHistory(searchInput);
    setKeyword(searchInput);
    setPage(1);
    setShowHistory(false);
  }, [searchInput, addSearchHistory]);

  const handleHistoryClick = useCallback((kw: string) => {
    setSearchInput(kw);
    setKeyword(kw);
    setPage(1);
    setShowHistory(false);
  }, []);

  const handleCopyLink = useCallback((url: string) => {
    navigator.clipboard.writeText(url).then(() => toast.success('链接已复制')).catch(() => toast.error('复制失败'));
  }, [toast]);

  const handleDeleteSelected = useCallback(async () => {
    if (selected.size === 0) return;
    const ok = await confirm({ title: '删除商品', message: `确定删除选中的 ${selected.size} 件商品？此操作不可恢复。`, confirmText: '删除', danger: true });
    if (!ok) return;
    try {
      await library.deleteSelection.mutateAsync([...selected]);
      setSelected(new Set());
      setPage(1);
    }
    catch { toast.error('删除失败'); }
  }, [selected, library.deleteSelection, confirm, toast]);

  const handleDeleteInvalid = async () => {
    const ok = await confirm({
      title: '清理失效商品',
      message: '将永久删除抖音商品库中所有已标记为「商品已失效」的记录，包括其他页面和当前筛选范围以外的记录。',
      confirmText: '清理失效商品',
      danger: true,
    });
    if (!ok) return;
    try {
      const result = await library.deleteInvalid.mutateAsync();
      if (result.deleted_count > 0) {
        setSelected(new Set());
        setPage(1);
        toast.success(`已清理 ${result.deleted_count} 件失效商品`);
      } else {
        toast.success('没有需要清理的失效商品');
      }
    } catch { toast.error('清理失效商品失败'); }
  };

  const handleBatchPrice = useCallback(async () => {
    const ids = [...selected]; if (ids.length === 0) return;
    try {
      const result = await library.batch.mutateAsync({ ids, updates: { price_multiply: parseFloat(priceMultiply) || 1, price_add: parseFloat(priceAdd) || 0 } });
      setPriceModal(false);
      const skipped = result.skipped || 0;
      if (skipped > 0) {
        toast.success(`已更新 ${ids.length - skipped} 件商品价格，跳过 ${skipped} 件已改价商品`);
      } else {
        toast.success(`已更新 ${ids.length} 件商品价格`);
      }
    } catch { toast.error('改价失败'); }
  }, [selected, priceMultiply, priceAdd, library.batch, toast]);

  const handleCreateTag = useCallback(async () => {
    if (!tagName.trim()) return;
    try { await library.addTag.mutateAsync({ name: tagName.trim(), color: tagColor }); setTagName(''); }
    catch { toast.error('创建失败'); }
  }, [tagName, tagColor, library.addTag, toast]);

  const handleCreateGroup = useCallback(async () => {
    if (!groupName.trim()) return;
    try { await library.addGroup.mutateAsync(groupName.trim()); setGroupName(''); }
    catch { toast.error('创建失败'); }
  }, [groupName, library.addGroup, toast]);

  const handleDetectDups = useCallback(async () => {
    setDupModal(true);
    try { await refetchDups(); } catch { /* errors surface via query state */ }
  }, [refetchDups]);

  const handleBatchExport = useCallback(async () => {
    try {
      const ids = [...selected];
      const { blob, extension } = await exportLibraryProducts(platform, ids);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `export_${selected.size}_items.${extension}`;
      document.body.appendChild(a); a.click(); document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch { toast.error('导出失败'); }
  }, [selected, platform, toast]);

  const handleBatchTaxonomy = useCallback(async () => {
    if (!batchTaxonomy?.id || selected.size === 0) return;
    const id = Number(batchTaxonomy.id);
    try {
      await library.batch.mutateAsync({
        ids: [...selected], updates: batchTaxonomy.kind === 'tag' ? { tags_add: [id] } : { group_id: id },
      });
      setBatchTaxonomy(null);
    } catch { toast.error('批量更新失败'); }
  }, [selected, batchTaxonomy, library.batch, toast]);

  const gridClass = `${s.productGrid} ${density === 'compact' ? s.productGridCompact : density === 'relaxed' ? s.productGridRelaxed : ''}`;

  return (
    <>
      <div className={`page-header ${s.libraryHeader}`}>
        <h2>商品库</h2>
        <label className={s.platformField}>
          平台
          <select value={platform} onChange={event => {
            setSearchParams(event.currentTarget.value === 'douyin' ? { platform: 'douyin' } : {});
          }}>
            <option value="pdd">拼多多</option>
            <option value="douyin">抖音</option>
          </select>
        </label>
        {isDouyin && <div className={s.platformActions}>
          {data && <span>{data.total} 条采集记录</span>}
          <button className="btn btn-primary btn-sm" type="button" disabled={importer.isPending} onClick={() => fileInput.current?.click()}>
            {importer.isPending ? '正在入库…' : '导入手机采集 JSON'}
          </button>
          <input ref={fileInput} type="file" hidden accept=".json,application/json" onChange={event => {
            const file = event.currentTarget.files?.[0];
            if (file) importer.mutate(file);
            event.currentTarget.value = '';
          }} />
        </div>}
      </div>
      <div className={s.toolbar}>
        <div className={s.toolbarSearch} style={{ position: 'relative' }}>
          <input ref={searchRef} value={searchInput} onChange={e => setSearchInput(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && handleSearch()}
            onFocus={() => searchHistory.length > 0 && setShowHistory(true)}
            onBlur={() => setTimeout(() => setShowHistory(false), 200)}
            placeholder="搜索商品..." aria-label="搜索商品" />
          <button className="btn btn-outline btn-sm" onClick={handleSearch}>搜索</button>
          {showHistory && searchHistory.length > 0 && (
            <div className={s.searchHistory}>
              {searchHistory.map(kw => (
                <div key={kw} className={s.searchHistoryItem} onMouseDown={() => handleHistoryClick(kw)}>
                  <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.5"/><path d="M8 5v3l2 2" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg>
                  {kw}
                </div>
              ))}
            </div>
          )}
        </div>
        <div style={{ flex: 1 }} />
        <div className={s.densityGroup}>
          <button className={`${s.densityBtn} ${density === 'compact' ? s.active : ''}`} onClick={() => handleDensityChange('compact')}>紧凑</button>
          <button className={`${s.densityBtn} ${density === 'standard' ? s.active : ''}`} onClick={() => handleDensityChange('standard')}>标准</button>
          <button className={`${s.densityBtn} ${density === 'relaxed' ? s.active : ''}`} onClick={() => handleDensityChange('relaxed')}>宽松</button>
        </div>
        <button className="btn btn-outline btn-sm" onClick={() => void library.refresh()} disabled={isFetching}>
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" style={{ width: 14, height: 14, verticalAlign: -2, marginRight: 4, animation: isFetching ? 'spin 0.8s linear infinite' : 'none' }}>
            <path d="M13.5 8a5.5 5.5 0 01-9.4 3.5M2.5 8a5.5 5.5 0 019.4-3.5" />
            <path d="M13.5 3.5v4h-4" />
            <path d="M2.5 12.5v-4h4" />
          </svg>
          {isFetching ? '刷新中' : '刷新'}
        </button>
        <button className="btn btn-outline btn-sm" onClick={handleDetectDups}>检测重复</button>
      </div>

      <div style={{ display: 'flex', gap: 8, marginBottom: 12, flexWrap: 'wrap', alignItems: 'center' }}>
        <span style={{ fontSize: 12, color: 'var(--brand-text-secondary)' }}>筛选:</span>
        <input placeholder="最低价" value={priceMin} onChange={e => { setPriceMin(e.target.value); setPage(1); }}
               style={{ width: 70, padding: '4px 8px', border: '1px solid var(--brand-border)', borderRadius: 4, fontSize: 12 }} aria-label="最低价格" />
        <span style={{ color: 'var(--brand-text-muted)', fontSize: 12 }}>—</span>
        <input placeholder="最高价" value={priceMax} onChange={e => { setPriceMax(e.target.value); setPage(1); }}
               style={{ width: 70, padding: '4px 8px', border: '1px solid var(--brand-border)', borderRadius: 4, fontSize: 12 }} aria-label="最高价格" />
        <input placeholder="最低销量" value={salesMin} onChange={e => { setSalesMin(e.target.value); setPage(1); }}
               style={{ width: 70, padding: '4px 8px', border: '1px solid var(--brand-border)', borderRadius: 4, fontSize: 12 }} aria-label="最低销量" />
        <span style={{ color: 'var(--brand-text-muted)', fontSize: 12 }}>—</span>
        <input placeholder="最高销量" value={salesMax} onChange={e => { setSalesMax(e.target.value); setPage(1); }}
               style={{ width: 70, padding: '4px 8px', border: '1px solid var(--brand-border)', borderRadius: 4, fontSize: 12 }} aria-label="最高销量" />
        <select value={shopFilter} onChange={e => { setShopFilter(e.target.value); setPage(1); }}
                style={{ width: 120, padding: '4px 8px', border: '1px solid var(--brand-border)', borderRadius: 4, fontSize: 12 }} aria-label="店铺名筛选">
          <option value="">全部店铺</option>
          {shops.map(name => <option key={name} value={name}>{name}</option>)}
        </select>
        <button className="btn btn-primary btn-sm" onClick={handleSearch}>筛选</button>
      </div>

      <div className={s.filterBar}>
        <div className={s.filterGroup}>
          <span className={s.filterLabel}>分组</span>
          <div className={s.filterPills}>
            <button className={`${s.filterPill} ${!selectedGroupId ? s.active : ''}`}
              onClick={() => { setSelectedGroupId(undefined); setPage(1); }}>全部</button>
            {groups?.map(g => (
              <span key={g.id} className={`${s.filterPill} ${selectedGroupId === g.id ? s.active : ''}`}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <span onClick={() => { setSelectedGroupId(g.id); setPage(1); }}>{g.name}</span>
                <button className={s.filterDel} title="删除分组"
                  onClick={async () => {
                    const ok = await confirm({ title: '删除分组', message: '确定删除该分组？分组内的商品不会被删除。', confirmText: '删除', danger: true });
                    if (!ok) return;
                    try {
                      await library.removeGroup.mutateAsync(g.id);
                      if (selectedGroupId === g.id) { setSelectedGroupId(undefined); setPage(1); }
                    } catch { toast.error('删除分组失败'); }
                  }}>x</button>
              </span>
            ))}
          </div>
          <div className={s.filterCreate}>
            <input value={groupName} onChange={e => setGroupName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleCreateGroup()} placeholder="新分组" />
            <button className="btn btn-outline btn-sm" style={{ padding: '2px 8px', fontSize: 12 }} onClick={handleCreateGroup}>+</button>
          </div>
        </div>
      </div>

      <div className={s.filterBar}>
        <div className={s.filterGroup}>
          <span className={s.filterLabel}>标签</span>
          <div className={s.filterPills}>
            <button className={`${s.filterPill} ${!selectedTagId ? s.active : ''}`}
              onClick={() => { setSelectedTagId(undefined); setPage(1); }}>全部</button>
            {tags?.map(t => (
              <span key={t.id} className={`${s.filterPill} ${selectedTagId === t.id ? s.active : ''}`}
                style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <span className={s.filterDot} style={{ background: t.color }} />
                <span onClick={() => { setSelectedTagId(t.id); setPage(1); }}>{t.name}</span>
                <button className={s.filterDel} title="删除标签"
                  onClick={async () => {
                    const ok = await confirm({ title: '删除标签', message: '确定删除该标签？商品会保留。', confirmText: '删除', danger: true });
                    if (!ok) return;
                    try {
                      await library.removeTag.mutateAsync(t.id);
                      if (selectedTagId === t.id) { setSelectedTagId(undefined); setPage(1); }
                    } catch { toast.error('删除标签失败'); }
                  }}>x</button>
              </span>
            ))}
          </div>
          <div className={s.filterCreate}>
            <input type="color" value={tagColor} onChange={e => setTagColor(e.target.value)}
              className={s.filterColorPick} />
            <input value={tagName} onChange={e => setTagName(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleCreateTag()} placeholder="新标签" />
            <button className="btn btn-outline btn-sm" style={{ padding: '2px 8px', fontSize: 12 }} onClick={handleCreateTag}>+</button>
          </div>
        </div>
      </div>

      {(items.length > 0 || selected.size > 0 || isDouyin && data) && (
        <div className={s.filterBar} role="group" aria-label="商品操作">
          <span className={s.filterLabel}>操作</span>
          <button type="button" className="btn btn-outline btn-sm" onClick={toggleAll} disabled={!allIds.length}>
            {allSelected ? '取消全选本页' : '全选本页'}
          </button>
          {isDouyin && <button type="button" className={`btn btn-outline btn-sm ${s.dangerAction}`}
            onClick={handleDeleteInvalid} disabled={library.deleteInvalid.isPending}>
            {library.deleteInvalid.isPending ? '正在清理…' : '清理失效商品'}
          </button>}
          {selected.size > 0 && <>
            <span className={s.filterLabel}>已选 {selected.size} 件</span>
            <button className="btn btn-outline btn-sm" onClick={() => setPriceModal(true)}>批量改价</button>
            <button className="btn btn-outline btn-sm" onClick={() => setBatchTaxonomy({ kind: 'tag', id: '' })}>批量标签</button>
            <button className="btn btn-outline btn-sm" onClick={() => setBatchTaxonomy({ kind: 'group', id: '' })}>批量分组</button>
            <button className="btn btn-outline btn-sm" onClick={handleBatchExport}>批量导出</button>
            <button className={`btn btn-outline btn-sm ${s.dangerAction}`} onClick={handleDeleteSelected}>批量删除</button>
            <button className="btn btn-outline btn-sm" onClick={() => setSelected(new Set())}>取消选择</button>
          </>}
        </div>
      )}

      {importer.isSuccess && <p role="status" className={s.listMessage}>
        导入完成 · 新增 {importer.data.inserted} 条 · 更新 {importer.data.updated} 条 · 跳过 {importer.data.skipped} 条
      </p>}
      {importer.isError && <p role="alert" className={s.listMessage}>{importer.error.message}</p>}

      {error ? <p role="alert" className={s.listMessage}>商品读取失败 · {error.message}</p> : isPending ? (
        <div className="empty" role="status" style={{ padding: 60 }}>正在读取商品…</div>
      ) : items.length === 0 ? (
        <div className="empty" style={{ padding: 60 }}>{keyword ? '未找到匹配商品' : '暂无商品'}</div>
      ) : (
        <div className={gridClass}>
          {items.map(item => (
            <article key={item.id} className={`card ${s.productCard}`}>
              <input type="checkbox" className={s.cardSelection} checked={selected.has(item.id)}
                aria-label={`选择 ${item.title}`} onChange={() => toggle(item.id)} />
              {item.link && <div className={s.cardActions}>
                <button className={s.actionBtn} type="button" title="复制链接" onClick={() => handleCopyLink(item.link)}>
                  <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><rect x="5" y="5" width="8" height="8" rx="2" stroke="currentColor" strokeWidth="1.5"/><path d="M3 11V4a2 2 0 012-2h7" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/></svg>
                </button>
              </div>}
              <Link className={s.cardLink} to={productDetailPath(platform, item.id)}>
                <div className={s.cardMedia}>
                  {item.stock && <span className={s.stockBadge}>仅剩 {item.stock}</span>}
                  {item.priceModified && <span className={s.modifiedBadge}>已改价</span>}
                  {item.image
                    ? <SafeImage src={item.image} alt="" className={s.productCardImg} />
                    : <div className={`${s.productCardImg} ${s.coverPlaceholder}`}>暂无主图</div>}
                </div>
                <div className={s.productCardBody}>
                  <div className={s.productCardTitle}>{item.title}</div>
                  <div className={s.productCardMeta}>
                    {item.price
                      ? <span className={s.productCardPrice}>{item.price}</span>
                      : <span className={s.productCardSales}>暂无价格</span>}
                    {item.sales && <span className={s.productCardSales}>{item.sales}</span>}
                  </div>
                  <div className={s.cardFacts}>
                    {item.shop && <span>{item.shop}</span>}
                    {item.details && <span>{item.details}</span>}
                    <span className={s.cardTime}>{relativeTime(item.capturedAt)}</span>
                  </div>
                  {(item.signal || !!item.tags?.length || !!item.groups?.length) && <div className={s.cardLabels}>
                    {item.signal && <span className={item.signal.marked ? s.selectionTag : s.selectionTagBelow}>{item.signal.label}</span>}
                    {item.tags?.map(tag => <span key={tag.id}><i className={s.filterDot} style={{ background: tag.color }} />{tag.name}</span>)}
                    {item.groups?.map(group => <span key={group.id}>{group.name}</span>)}
                  </div>}
                </div>
              </Link>
            </article>
          ))}
        </div>
      )}

      {data && data.total > data.page_size && (
        <div style={{ display: 'flex', justifyContent: 'center', gap: 8, padding: '16px 0' }}>
          <button className="btn btn-outline btn-sm" disabled={page <= 1} onClick={() => setPage(p => p - 1)}>上一页</button>
          <span style={{ lineHeight: '28px', fontSize: 13, color: 'var(--brand-text-secondary)' }}>{page} / {totalPages}</span>
          <button className="btn btn-outline btn-sm" disabled={page >= totalPages} onClick={() => setPage(p => p + 1)}>下一页</button>
        </div>
      )}

      {priceModal && (
        <div style={overlayStyle} onClick={() => setPriceModal(false)}>
          <div style={modalStyle} onClick={e => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 16px' }}>批量改价（{selected.size} 件商品）</h3>
            <p style={{ fontSize: 12, color: 'var(--brand-text-muted)', margin: '0 0 8px' }}>
              已改价的商品将自动跳过。公式默认值来自设置页。{isDouyin && '改价仅用于工作台报价，详情中的采集报价仍保留。'}
            </p>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
              <div>
                <label style={{ fontSize: 12, color: 'var(--brand-text-secondary)' }}>倍数（如 1.3 表示加价 30%）</label>
                <input type="number" step="0.01" value={priceMultiply} onChange={e => setPriceMultiply(e.target.value)}
                  style={{ width: '100%', padding: 8, border: '1px solid var(--brand-border)', borderRadius: 6, fontSize: 14 }} />
              </div>
              <div>
                <label style={{ fontSize: 12, color: 'var(--brand-text-secondary)' }}>固定加减（正数加价，负数减价）</label>
                <input type="number" step="0.01" value={priceAdd} onChange={e => setPriceAdd(e.target.value)}
                  style={{ width: '100%', padding: 8, border: '1px solid var(--brand-border)', borderRadius: 6, fontSize: 14 }} />
              </div>
              <p style={{ fontSize: 12, color: 'var(--brand-text-muted)' }}>新价格 = 原价 × 倍数 + 固定金额</p>
              <button className="btn btn-primary" disabled={library.batch.isPending} onClick={handleBatchPrice}>确认改价</button>
            </div>
            <button style={{ position: 'absolute', top: 12, right: 12, background: 'none', border: 'none', fontSize: 20, cursor: 'pointer' }}
              onClick={() => setPriceModal(false)}>×</button>
          </div>
        </div>
      )}

      {batchTaxonomy && (
        <div style={overlayStyle} onClick={() => setBatchTaxonomy(null)}>
          <div style={modalStyle} onClick={e => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 12px', fontSize: 15 }}>{batchTaxonomy.kind === 'tag' ? '批量添加标签' : '批量添加分组'}</h3>
            <select value={batchTaxonomy.id} onChange={e => setBatchTaxonomy({ ...batchTaxonomy, id: e.target.value })}
                    style={{ width: '100%', padding: 8, border: '1px solid var(--brand-border)', borderRadius: 6, fontSize: 13 }}
                    aria-label={batchTaxonomy.kind === 'tag' ? '选择标签' : '选择分组'}>
              <option value="">{batchTaxonomy.kind === 'tag' ? '选择标签...' : '选择分组...'}</option>
              {(batchTaxonomy.kind === 'tag' ? tags : groups)?.map(entry => (
                <option key={entry.id} value={entry.id}>{entry.name}</option>
              ))}
            </select>
            <div style={{ display: 'flex', gap: 8, marginTop: 16, justifyContent: 'flex-end' }}>
              <button className="btn btn-outline btn-sm" onClick={() => setBatchTaxonomy(null)}>取消</button>
              <button className="btn btn-primary btn-sm" disabled={!batchTaxonomy.id || library.batch.isPending} onClick={handleBatchTaxonomy}>确认</button>
            </div>
            <button style={{ position: 'absolute', top: 12, right: 12, background: 'none', border: 'none', fontSize: 20, cursor: 'pointer' }}
              onClick={() => setBatchTaxonomy(null)}>×</button>
          </div>
        </div>
      )}

      {dupModal && (
        <div style={overlayStyle} onClick={() => setDupModal(false)}>
          <div style={{ ...modalStyle, maxWidth: 700, maxHeight: '80vh', overflow: 'auto' }} onClick={e => e.stopPropagation()}>
            <h3 style={{ margin: '0 0 16px' }}>重复商品检测</h3>
            {dupLoading ? <p>检测中...</p> : dupError ? <p role="alert">检测失败 · {dupError.message}</p> : dupData && dupData.total_groups > 0 ? (
              <div>
                <p style={{ color: 'var(--brand-red)', marginBottom: 12 }}>发现 {dupData.total_groups} 组重复</p>
                {dupData.duplicates.map((group, i) => (
                  <div key={i} style={{ background: 'var(--brand-red-light)', border: '1px solid rgba(255, 59, 48, 0.2)', borderRadius: 8, padding: 12, marginBottom: 8 }}>
                    <div style={{ fontSize: 12, color: 'var(--brand-red)', marginBottom: 4 }}>
                      {group.type === 'exact_id' ? `ID 重复: ${group.goods_id}` : `标题相似: "${group.title_prefix}..."`}
                    </div>
                    {group.items.map((item, j) => (
                      <Link key={j} style={{ display: 'block', fontSize: 13, padding: '2px 0', color: 'var(--brand-accent)' }}
                        to={productDetailPath(platform, item.goods_id)} onClick={() => setDupModal(false)}>
                        {item.goods_id} - {item.title.substring(0, 40)}
                      </Link>
                    ))}
                  </div>
                ))}
              </div>
            ) : (
              <p style={{ color: 'var(--brand-green)' }}>未发现重复商品</p>
            )}
            <button style={{ position: 'absolute', top: 12, right: 12, background: 'none', border: 'none', fontSize: 20, cursor: 'pointer' }}
              onClick={() => setDupModal(false)}>×</button>
          </div>
        </div>
      )}
    </>
  );
}

type LibraryCard = {
  id: string;
  title: string;
  image: string;
  link: string;
  price: string;
  sales: string;
  shop: string;
  capturedAt: string;
  details?: string;
  stock?: number;
  priceModified?: boolean;
  signal?: { label: string; marked: boolean };
  tags?: TagInfo[];
  groups?: GroupInfo[];
};

function productDetailPath(platform: LibraryPlatform, id: string) {
  return `${platform === 'douyin' ? '/products/douyin' : '/product'}/${encodeURIComponent(id)}`;
}

function money(amount: number) {
  return `¥${amount.toFixed(2)}`;
}

function pddCard(product: GoodsInfo): LibraryCard {
  let min = Number(product.price_min ?? 0);
  let max = Number(product.price_max ?? 0);
  if (!min && !max && Array.isArray(product.skus)) {
    const prices = product.skus.map(sku => Number(sku.price)).filter(value => value > 0);
    if (prices.length) {
      min = Math.min(...prices);
      max = Math.max(...prices);
    }
  }
  const price = min && max && min !== max ? `${money(min)} ~ ${money(max)}`
    : max || min || Number(product.price || product.original_price) || 0;
  const stock = Number(product.total_stock ?? 0);
  return {
    id: product.goods_id,
    title: product.title,
    image: product.main_images?.[0] || '',
    link: `https://mobile.yangkeduo.com/goods.html?goods_id=${encodeURIComponent(product.goods_id)}`,
    price: typeof price === 'number' ? (price > 0 ? money(price) : '') : price,
    sales: product.sales > 0 ? `已拼 ${product.sales.toLocaleString()}` : '',
    shop: product.shop_name,
    capturedAt: product.crawl_time,
    stock: stock > 0 && stock <= 5 ? stock : undefined,
    priceModified: product.price_modified === 1,
    tags: product.tags,
    groups: product.groups,
  };
}

function douyinCard(record: DouyinRecord): LibraryCard {
  const observed = observedDouyinPrice(record);
  const min = record.library_price ?? (observed.fen === null ? null : observed.fen / 100);
  const max = record.library_price_max ?? (observed.maxFen === null ? min : observed.maxFen / 100);
  const amount = min === null ? '' : max === null || min === max ? money(min) : `${money(min)} ~ ${money(max)}`;
  const cardSales = display(record.source_detail?.core_data?.['商品卡销量']);
  const priceLabel = record.library_price_label ?? (record.library_price_modified ? '工作台报价' : observed.label);
  const label = douyinSelectionLabel(record.selection_signal);
  return {
    id: record.record_id,
    title: title(record),
    image: record.stored_cover_url?.startsWith(`${DOUYIN_LIBRARY_API}/image?`) ? record.stored_cover_url : '',
    link: productLink(record),
    price: amount,
    sales: cardSales ? `商品卡 ${cardSales}` : amount && priceLabel ? priceLabel : status(record),
    shop: display(record.library_shop, record.douyin_shop_name),
    capturedAt: record.captured_at,
    details: [cardSales && amount ? priceLabel : '', `${record.skus.length} 款`, status(record)].filter(Boolean).join(' · '),
    priceModified: record.library_price_modified,
    signal: label ? { label, marked: record.selection_signal?.marked === true } : undefined,
    tags: record.tags,
    groups: record.groups,
  };
}

const overlayStyle: React.CSSProperties = {
  position: 'fixed', top: 0, left: 0, right: 0, bottom: 0,
  background: 'rgba(0,0,0,0.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000,
};
const modalStyle: React.CSSProperties = {
  background: 'var(--brand-surface)', borderRadius: 12, padding: 24, maxWidth: 500, width: '90%', position: 'relative',
};
