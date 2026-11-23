import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import { batchState, batchUrl, pddBatch } from '../api/pddMobile';
import type { Item } from '../api/pddMobile';
import s from '../styles/modules/MobileCollector.module.css';
import table from '../styles/modules/Selection.module.css';

function hasValue(value?: string | number) {
  return value != null && String(value).trim() !== '' && Number.isFinite(Number(value));
}

function Sales({ item, weekly }: { item: Item; weekly: boolean }) {
  const dailyKnown = hasValue(item.daily);
  const weekKnown = hasValue(item.weekly);
  const day = item.dailyRaw || (dailyKnown ? `近1日 ${item.daily}${item.dailyFloor ? '+' : ''}` : '');
  const week = item.weeklyRaw || (weekKnown ? `近7日 ${item.weekly}${item.weeklyFloor ? '+' : ''}` : '');
  const basis = dailyKnown ? '采用页面日销' : weekKnown && weekly
    ? `周销 ÷ 7 ≈ ${Number((Number(item.weekly) / 7).toFixed(2))} 件/天` : '';
  return <>{[day, week].filter(Boolean).join(' · ') || item.salesStatus || '缺销量'}
    {basis && <small>{basis}</small>}</>;
}

export default function PddBatchResults() {
  const { taskId = '' } = useParams();
  const [group, setGroup] = useState<'all' | 'daily' | 'amount'>('all');
  const query = useQuery({
    queryKey: ['pdd-mobile', 'task', taskId], queryFn: ({ signal }) => pddBatch(taskId, signal),
    staleTime: 0,
    refetchInterval: q => q.state.data?.state === '已完成'
      && !q.state.data.items.some(item => item.imageState === 'pending') ? false : 5000,
  });
  const batch = query.data;
  const items = batch?.items ?? [];
  const daily = items.filter(i => hasValue(i.daily) && Number(i.daily) >= 100);
  const amount = items.filter(i => i.qualifies === true);
  const rows = { all: items, daily, amount };

  return <div className={s.page}>
    <header className={`page-header ${s.heading}`}><h2>拼多多批次结果</h2>
      <Link className="btn btn-outline" to="/mobile-collector?platform=pdd">返回采集任务</Link>
    </header>
    {query.isPending && <p role="status">正在读取批次结果…</p>}
    {query.isError && <p className={s.error} role="alert">{query.error.message}</p>}
    {batch && <section className={`card card-body ${s.panel}`}>
      <div className={s.jobHeading}><div><h3>{batch.settings.route}</h3>
        <p className={s.muted}>任务 {batch.id} · 已采 {items.length} / {batch.settings.quantity} 款</p></div>
        <span className={s.badge}>{batchState(batch, items.length)}</span>
        <button className="btn btn-outline btn-sm" disabled={query.isFetching} onClick={() => void query.refetch()}>刷新</button>
      </div>
      <p className={s.muted}>{batch.settings.pricing} · 预估日销售额 = 日销 × 计价 × {batch.settings.share}%
        {batch.settings.weekly ? ' · 仅有周销时按周销 ÷ 7 折算' : ''}</p>
      <div className={s.panelHeading}>
        <div className={table.filters} role="group" aria-label="商品筛选">
          {([['all', '全部商品'], ['daily', '页面日销 ≥ 100'], ['amount', `预估日销售额 > ${batch.settings.threshold} 元`]] as const)
            .map(([key, label]) => <button key={key} type="button" aria-pressed={group === key}
              onClick={() => setGroup(key)}>{label}（{rows[key].length}）</button>)}
        </div>
        {items.length > 0 && <div className={table.filters}>
          <a className="btn btn-outline btn-sm" download href={batchUrl(taskId) + '/all.csv'}>导出全部 CSV</a>
          <a className="btn btn-outline btn-sm" download href={batchUrl(taskId) + '/qualified.csv'}>导出金额达标 CSV</a>
        </div>}
      </div>
      {rows[group].length ? <div className={table.tableScroll} tabIndex={0} role="region" aria-label="商品明细">
        <table className={table.table}>
          <thead><tr>{['商品标题', '商品主图', '页面销量及依据', '计价规格', '采用价格（元）', '预估日销售额（元）'].map(label => <th key={label}>{label}</th>)}</tr></thead>
          <tbody>{rows[group].map((item, i) => <tr key={item.id || i}>
            <td className={table.productCell}>{item.title || '缺标题'}</td>
            <td>{item.imageState === 'ready' && item.id ? <ProductImage taskId={taskId} itemId={item.id} title={item.title || '商品'} />
              : item.imageState === 'pending' ? '等待上传' : '暂无主图'}</td>
            <td><Sales item={item} weekly={batch.settings.weekly} /></td>
            <td>{item.sku || '—'}<small>{item.priceKind}</small></td>
            <td className={table.priceCell}>{hasValue(item.price) ? item.price : '缺价'}</td>
            <td className={table.priceCell}>{hasValue(item.estimate) ? item.estimate : '无法计算'}<small>{item.evaluation}</small></td>
          </tr>)}</tbody>
        </table>
      </div> : <p className={s.muted}>{items.length ? '这一组暂无商品。' : '该任务暂无回传商品。'}</p>}
    </section>}
  </div>;
}

function ProductImage({ taskId, itemId, title }: { taskId: string; itemId: string; title: string }) {
  const url = batchUrl(taskId) + '/images/' + encodeURIComponent(itemId) + '.jpg';
  return <><a href={url} target="_blank" rel="noreferrer" aria-label={'查看' + title + '大图'}>
    <img src={url} width="72" height="72" loading="lazy" alt={title} />
  </a><a href={url} download={'商品-' + itemId + '.jpg'}>下载图片</a></>;
}
