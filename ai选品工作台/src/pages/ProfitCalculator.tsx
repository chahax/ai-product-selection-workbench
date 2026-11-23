import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import type { DouyinRecord } from '../types/douyin';
import { eligibleDouyinSkus } from '../utils/douyinSelection';
import styles from '../styles/modules/ProfitCalculator.module.css';
import { apiBase } from '../api/path';

const API = `${apiBase}/workbench/douyin`;
const PAGE_SIZE = 25;
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });
const number = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });

type RecordPage = { records: DouyinRecord[]; total: number };
type FieldName = 'selling' | 'purchase' | 'shipping' | 'fee' | 'other' | 'daily';
type Draft = Partial<Record<FieldName, string>>;

const fields = [
  { name: 'selling', label: '计划售价（元）', placeholder: '填写预计成交单价' },
  { name: 'purchase', label: '拿货价（元）', placeholder: '填写每件拿货成本' },
  { name: 'shipping', label: '每单运费（元）' },
  { name: 'fee', label: '平台费率（%）', max: 100 },
  { name: 'other', label: '其他每单成本（元）' },
  { name: 'daily', label: '预计日销（件）', step: 'any', placeholder: '可填写日销量' },
] as const;

async function get<T>(parameters: Record<string, string>, signal: AbortSignal): Promise<T> {
  const response = await fetch(`${API}?${new URLSearchParams(parameters)}`, { signal });
  if (!response.ok) throw new Error('暂时无法读取商品库，请重试。');
  return response.json();
}

function productName(record: DouyinRecord) {
  return record.douyin_product_name || record.kaogujia_product_name || record.source_title || record.title || '未命名商品';
}

function decimal(value: string, max = Number.MAX_SAFE_INTEGER): number | null {
  if (!value.trim()) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed >= 0 && parsed <= max ? parsed : null;
}

function inCents(value: string): number | null {
  const parsed = decimal(value);
  if (parsed === null) return null;
  const cents = Math.round(parsed * 100);
  return Number.isSafeInteger(cents) && cents / 100 === parsed ? cents : null;
}

function amount(cents: number | null) {
  return cents === null ? '—' : money.format(cents / 100);
}

function profitTone(result: number | null) {
  if (result === null || result === 0) return '';
  return result > 0 ? styles.positive : styles.negative;
}

function ProfitInputs({ record, hasSource }: { record: DouyinRecord | undefined; hasSource: boolean }) {
  const [draft, setDraft] = useState<Draft>({});
  const [skuIndex, setSkuIndex] = useState<number>();
  const skus = record ? eligibleDouyinSkus(record) : [];
  const reference = record?.selection_signal;
  const referenceDaily = reference?.dailySalesMinimum;
  const sku = skus.find(item => item.index === skuIndex)
    ?? skus.find(item => item.index === reference?.skuIndex)
    ?? skus[0];
  const values: Record<FieldName, string> = {
    selling: draft.selling ?? (sku ? (sku.priceFen / 100).toFixed(2) : ''),
    purchase: draft.purchase ?? '',
    shipping: draft.shipping ?? '0',
    fee: draft.fee ?? '0',
    other: draft.other ?? '0',
    daily: draft.daily ?? (referenceDaily == null ? '' : String(Number(referenceDaily.toFixed(2)))),
  };

  const selling = inCents(values.selling);
  const purchase = inCents(values.purchase);
  const shipping = inCents(values.shipping || '0');
  const fee = decimal(values.fee || '0', 100);
  const other = inCents(values.other || '0');
  const daily = draft.daily == null ? referenceDaily ?? null : decimal(values.daily);
  const platformCost = selling === null || fee === null ? null : Math.round(selling * fee / 100);
  const profit = selling === null || purchase === null || shipping === null || platformCost === null || other === null
    ? null : selling - purchase - shipping - platformCost - other;
  const margin = profit === null || selling === null || selling === 0 ? null : profit / selling * 100;
  const dailyProfit = profit === null || daily === null ? null : profit * daily;

  function reset() {
    setSkuIndex(undefined);
    setDraft({});
  }

  return <div className={styles.columns}>
    <section className={`card card-body ${styles.section}`} aria-labelledby="profit-inputs">
      <div className={styles.sectionHeading}>
        <h3 id="profit-inputs">售价与成本</h3>
        <button type="button" className="btn btn-outline btn-sm" onClick={reset}
          disabled={Object.keys(draft).length === 0 && skuIndex === undefined}>重置输入</button>
      </div>
      {skus.length > 0 && <label className={styles.sku}>
        参考规格
        <select value={sku?.index ?? ''} onChange={event => {
          setSkuIndex(Number(event.target.value));
          setDraft(current => ({ ...current, selling: undefined }));
        }}>
          {skus.map(item => <option key={item.index} value={item.index}>{item.name} · {amount(item.priceFen)}</option>)}
        </select>
      </label>}
      <div className={styles.fields}>
        {fields.map(field => <label key={field.name}>
          {field.label}
          <input type="number" inputMode="decimal" min={0} max={'max' in field ? field.max : undefined}
            step={'step' in field ? field.step : '0.01'}
            placeholder={'placeholder' in field ? field.placeholder : undefined} value={values[field.name]}
            onChange={event => setDraft({ ...draft, [field.name]: event.target.value })} />
          {field.name === 'selling' && sku && <small>参考规格单价 {amount(sku.priceFen)}，可按计划成交价调整。</small>}
          {field.name === 'daily' && hasSource && <small>{referenceDaily == null
            ? '暂无可用的商品卡日销下限，可手动填写。'
            : `考古加商品卡日销下限约 ${number.format(referenceDaily)} 件，可按预期销量调整。`}</small>}
        </label>)}
      </div>
      <p className={styles.hint}>运费、平台费率和其他成本默认按 0 计。拿货价需要填写。</p>
    </section>
    <section className={`card card-body ${styles.section}`} aria-labelledby="profit-results">
      <h3 id="profit-results">测算结果</h3>
      <dl className={styles.metrics} aria-live="polite">
        <div className={styles.daily}>
          <dt>每日预估利润</dt>
          <dd className={profitTone(dailyProfit)}>{amount(dailyProfit)}</dd>
        </div>
        <div>
          <dt>单件预估利润</dt>
          <dd className={profitTone(profit)}>{amount(profit)}</dd>
        </div>
        <div>
          <dt>预估利润率</dt>
          <dd className={profitTone(margin)}>{margin === null ? '—' : `${number.format(margin)}%`}</dd>
        </div>
      </dl>
      <dl className={styles.breakdown}>
        <div><dt>每单平台费</dt><dd>{amount(platformCost)}</dd></div>
        <div><dt>预计日销</dt><dd>{daily === null ? '—' : `${number.format(daily)} 件`}</dd></div>
      </dl>
      <p className={styles.hint}>单件利润 = 计划售价 − 拿货价 − 运费 − 平台费 − 其他每单成本。每日利润 = 单件利润 × 预计日销。</p>
    </section>
  </div>;
}

export default function ProfitCalculator() {
  const [params, setParams] = useSearchParams();
  const recordId = params.get('record') ?? '';
  const [searchDraft, setSearchDraft] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(1);
  const products = useQuery({
    queryKey: ['profit-douyin-records', search, page],
    queryFn: ({ signal }) => get<RecordPage>({ q: search, page: String(page) }, signal),
  });
  const detail = useQuery({
    queryKey: ['douyin-detail', recordId],
    queryFn: ({ signal }) => get<DouyinRecord>({ id: recordId }, signal),
    enabled: !!recordId,
  });
  const records = products.data?.records ?? [];
  const selectedRecord = detail.data ?? records.find(record => record.record_id === recordId);
  const pageCount = Math.max(1, Math.ceil((products.data?.total ?? 0) / PAGE_SIZE));

  return <>
    <div className="page-header"><h2>利润测算</h2></div>
    <section className={`card card-body ${styles.section} ${styles.source}`} aria-labelledby="profit-source">
      <h3 id="profit-source">选取测算商品</h3>
      <div className={styles.sourceControls}>
        <form role="search" onSubmit={event => {
          event.preventDefault();
          setSearch(searchDraft.trim());
          setPage(1);
        }}>
          <label>
            搜索抖音商品
            <input type="search" placeholder="输入商品名或店铺名" value={searchDraft}
              onChange={event => setSearchDraft(event.target.value)} />
          </label>
          <button type="submit" className="btn btn-outline">搜索</button>
        </form>
        <label>
          测算商品
          <select value={recordId} onChange={event => {
            const id = event.target.value;
            setParams(id ? { record: id } : {});
          }}>
            <option value="">手动输入，不选商品</option>
            {recordId && !records.some(record => record.record_id === recordId)
              && <option value={recordId}>{selectedRecord ? productName(selectedRecord) : '当前商品'}</option>}
            {records.map(record => <option key={record.record_id} value={record.record_id}>
              {productName(record)}{record.douyin_shop_name ? ` · ${record.douyin_shop_name}` : ''}
            </option>)}
          </select>
        </label>
      </div>
      <div className={styles.sourceFooter}>
        {recordId && selectedRecord && <Link to={`/products/douyin/${encodeURIComponent(recordId)}`}>查看商品详情</Link>}
        {products.isFetching && <span className={styles.hint} role="status">正在读取商品…</span>}
        {!products.isPending && !products.isError && records.length === 0
          && <span className={styles.hint}>{search ? '没有匹配的商品' : '商品库暂无抖音记录'}</span>}
        {pageCount > 1 && <div className={styles.paging}>
          <button type="button" className="btn btn-outline btn-sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>上一页</button>
          <span>{page} / {pageCount}</span>
          <button type="button" className="btn btn-outline btn-sm" disabled={page >= pageCount} onClick={() => setPage(page + 1)}>下一页</button>
        </div>}
      </div>
      {products.isError && <p className={styles.error} role="alert">{products.error.message}</p>}
      {recordId && detail.isError && <p className={styles.error} role="alert">{detail.error.message}</p>}
    </section>
    <ProfitInputs key={recordId} record={selectedRecord} hasSource={!!recordId} />
  </>;
}
