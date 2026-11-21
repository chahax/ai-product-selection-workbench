import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router-dom';
import type { DouyinRecord, DouyinSku, WebImageFetchResult } from '../types/douyin';
import styles from '../styles/modules/DouyinLibrary.module.css';
import { eligibleDouyinSkus } from '../utils/douyinSelection';
import { display, title, status, price, productLink } from '../utils/douyinProduct';

const API = '/api/workbench/douyin';
const salesFields = ['商品销量', '直播销量', '视频销量', '商品卡销量'];
type SelectionSettings = { ratio_percent: number; threshold_yuan: number };
const selectionSettingsKey = ['douyin-selection-settings'];
const calculationNumber = new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 });
const calculationAmount = new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 6 });
const skuSourceLabels = { manual: '手动指定', hot_price: '火爆价', hot: '热销标记', fallback: '首个有效券后价' };
const selectionTag = '商品卡测算达标';

async function request<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(url, options);
  const data = await response.json().catch(() => null);
  if (!response.ok || data === null) throw new Error(data?.error || '无法读取抖音入库服务');
  return data as T;
}

function skuName(sku: DouyinSku) {
  if (sku.specifications && typeof sku.specifications === 'object') {
    const specs = Object.entries(sku.specifications).filter(([, value]) => display(value));
    if (specs.length > 0) return specs.map(([key, value]) => `${key}：${value}`).join(' / ');
  }
  return display(sku.name) || '未采到规格名';
}

function useSelectionSettings() {
  return useQuery({
    queryKey: selectionSettingsKey,
    queryFn: ({ signal }) => request<SelectionSettings>(`${API}/selection-settings`, { signal }),
  });
}

export function DouyinSelectionSettings() {
  const client = useQueryClient();
  const settings = useSelectionSettings();
  const [ratio, setRatio] = useState<string | null>(null);
  const [threshold, setThreshold] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: (draft: SelectionSettings) => request<SelectionSettings>(`${API}/selection-settings`, {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(draft),
    }),
    onSuccess: async saved => {
      client.setQueryData(selectionSettingsKey, saved);
      setRatio(null);
      setThreshold(null);
      await Promise.all([
        client.invalidateQueries({ queryKey: ['douyin-records'] }),
        client.invalidateQueries({ queryKey: ['douyin-detail'] }),
      ]);
    },
  });
  const value = settings.data;
  const ratioValue = ratio ?? String(value?.ratio_percent ?? '');
  const thresholdValue = threshold ?? String(value?.threshold_yuan ?? '');
  const dirty = value && (ratioValue === '' || thresholdValue === ''
    || Number(ratioValue) !== value.ratio_percent || Number(thresholdValue) !== value.threshold_yuan);

  return <details className={`card card-body ${styles.section} ${styles.rules}`}>
    <summary>
      商品卡标签规则
      {value && <span className={styles.muted}>比例 {calculationNumber.format(value.ratio_percent)}% · 每日门槛 ¥{calculationNumber.format(value.threshold_yuan)}</span>}
    </summary>
    <p className={styles.muted}>商品卡销量下限 ÷ 统计天数 × SKU 单价 × 比例。结果严格大于门槛时标记。优先用火爆价，其次用热销规格的券后价，其余取第一条可售规格的有效券后价。</p>
    {settings.isPending && <p role="status">正在读取规则…</p>}
    {settings.isError && <p role="alert">{settings.error.message}</p>}
    {value && <form className={styles.ruleForm} onSubmit={event => {
      event.preventDefault();
      if (dirty) save.mutate({ ratio_percent: Number(ratioValue), threshold_yuan: Number(thresholdValue) });
    }}>
      <label>测算比例（%）
        <input type="number" required min={0} max={100} step="0.01" value={ratioValue}
          disabled={save.isPending} onChange={event => { save.reset(); setRatio(event.target.value); }} />
      </label>
      <label>达标门槛（元/日）
        <input type="number" required min={0} step="0.01" value={thresholdValue}
          disabled={save.isPending} onChange={event => { save.reset(); setThreshold(event.target.value); }} />
      </label>
      <button className="btn btn-primary btn-sm" disabled={!dirty || save.isPending}>
        {save.isPending ? '正在保存…' : '保存规则'}
      </button>
      {save.isSuccess && <span role="status" className={styles.muted}>规则已保存</span>}
      {save.isError && <span role="alert">{save.error.message}</span>}
    </form>}
  </details>;
}

function DouyinSelectionBreakdown({ record }: { record: DouyinRecord }) {
  const client = useQueryClient();
  const settings = useSelectionSettings();
  const signal = record.selection_signal;
  const eligible = eligibleDouyinSkus(record);
  const override = useMutation({
    mutationFn: (index: number | null) => request<unknown>(`${API}/selection-sku`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: record.record_id, sku_index: index, revision: record.revision ?? 0 }),
    }),
    onSuccess: async () => {
      await Promise.all([
        client.invalidateQueries({ queryKey: ['douyin-records'] }),
        client.invalidateQueries({ queryKey: ['douyin-detail', record.record_id] }),
      ]);
    },
    onError: () => { void client.invalidateQueries({ queryKey: ['douyin-detail', record.record_id] }); },
  });
  const result = signal?.estimatedYuan;
  const selection = signal?.skuSource === 'manual' ? signal.skuIndex : null;
  const selectedIndex = override.isPending ? override.variables : selection;
  const rule = settings.data;

  return <section className={`card card-body ${styles.section}`}>
    <div className={styles.selectionHeading}>
      <h3>商品卡测算</h3>
      {signal?.marked && <span className={styles.selectionTag}>{selectionTag}</span>}
    </div>
    <dl className={styles.facts}>
      <div><dt>考古加商品卡销量下限</dt><dd>{signal?.salesMinimum == null ? '未采到' : `${calculationNumber.format(signal.salesMinimum)} 件`}</dd></div>
      <div><dt>统计天数</dt><dd>{signal?.periodDays == null ? '未确认' : `${calculationNumber.format(signal.periodDays)} 天`}</dd></div>
      <div><dt>折算日销下限</dt><dd>{signal?.dailySalesMinimum == null ? '无法折算' : `${calculationNumber.format(signal.dailySalesMinimum)} 件/日`}</dd></div>
      <div><dt>测算单价</dt><dd>{price(signal?.priceFen)}</dd></div>
      <div><dt>日销测算金额</dt><dd>{result == null ? '暂无结果' : `¥${calculationAmount.format(result)} / 日`}</dd></div>
      <div><dt>选品标签</dt><dd>{signal?.marked ? selectionTag : record.product_state === 'invalid'
        ? '商品已失效' : result == null ? '无法测算' : '未达标'}</dd></div>
    </dl>
    {signal?.salesMinimum != null && signal.periodDays == null && <p className={styles.selectionFormula}>统计周期未确认，暂无法折算日销。</p>}
    {rule && <p className={styles.selectionFormula}>测算比例 {calculationNumber.format(rule.ratio_percent)}% · 每日测算金额严格大于 ¥{calculationNumber.format(rule.threshold_yuan)} 时达标</p>}
    {signal?.skuSource && <p className={styles.selectionFormula}>当前规格 {signal.skuName || '未命名规格'} · {skuSourceLabels[signal.skuSource]}</p>}
    {record.product_state !== 'invalid' && eligible.length > 0 && <label className={styles.skuPicker}>
      测算规格
      <select value={selectedIndex == null ? '' : String(selectedIndex)} disabled={override.isPending}
        onChange={event => override.mutate(event.target.value === '' ? null : Number(event.target.value))}>
        <option value="">自动选择</option>
        {eligible.map(sku => <option key={sku.index} value={sku.index}>{sku.name || `规格 ${sku.index + 1}`} · {price(sku.priceFen)}</option>)}
      </select>
    </label>}
    {override.isPending && <p role="status" className={styles.muted}>正在更新测算规格…</p>}
    {override.isError && <p role="alert">{override.error.message}</p>}
  </section>;
}

function webImageFeedback(result: WebImageFetchResult) {
  switch (result.outcome) {
    case 'updated':
      return result.status === 'cover_only' && result.image_count === 1
        ? '已更新网页封面 1 张，暂未取得其他轮播图。'
        : `已更新 ${result.image_count} 张网页图片。`;
    case 'unchanged':
      return result.status === 'cover_only' && result.image_count === 1
        ? '已重新检查网页。仍只找到这 1 张封面，暂无新增轮播图。'
        : `已重新检查网页。已保存的 ${result.image_count} 张图片没有变化。`;
    case 'retained':
      return `本次未取得更多图片，已保留此前保存的 ${result.image_count} 张。`;
    case 'no_link':
      return '这条记录缺少可用的商品链接。';
    case 'unavailable':
      return '本次未能从商品网页获取图片，可以稍后重试。';
  }
}

function DouyinDetail({ id }: { id: string }) {
  const client = useQueryClient();
  const detail = useQuery({
    queryKey: ['douyin-detail', id],
    queryFn: () => request<DouyinRecord>(`${API}?id=${encodeURIComponent(id)}`),
    refetchInterval: query => query.state.data?.web_image_status === 'pending' ? 2000 : false,
  });
  const fetchImages = useMutation({
    mutationFn: () => request<WebImageFetchResult>(`${API}/web-images`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ id }),
    }),
    onSuccess: () => {
      void detail.refetch();
      void client.invalidateQueries({ queryKey: ['douyin-records'] });
    },
  });
  const record = detail.data;
  const source = record?.source_detail;
  const shareUrl = record ? productLink(record) : '';
  const checkingImages = fetchImages.isPending || (record?.web_image_status === 'pending' && !fetchImages.data);
  const imageFeedback = checkingImages ? '正在检查商品网页并下载可用图片…'
    : fetchImages.data ? webImageFeedback(fetchImages.data) : '';
  return <>
    <div className={`page-header ${styles.detailHeader}`}>
      <Link className="btn btn-outline" to="/products?platform=douyin">返回商品库</Link>
      <h2>采集详情</h2>
      {record && <Link className="btn btn-outline btn-sm" to={`/profit?record=${encodeURIComponent(record.record_id)}`}>利润测算</Link>}
    </div>
    {detail.isPending && <p role="status">正在读取记录…</p>}
    {detail.isError && <p role="alert">{detail.error.message}</p>}
    {record && <>
      <section className={`card card-body ${styles.section}`}>
        <h3>{title(record)}</h3>
        <p className={styles.muted}>{status(record)} · {new Date(record.captured_at).toLocaleString('zh-CN')}</p>
        {display(record.invalid_reason, record.collection_error) && <p>{display(record.invalid_reason, record.collection_error)}</p>}
        <dl className={styles.facts}>
          <div><dt>考古加商品名</dt><dd>{display(record.kaogujia_product_name, record.source_title) || '未采到'}</dd></div>
          <div><dt>考古加店名</dt><dd>{display(record.kaogujia_shop_name, source?.shop_name, record.shop_name) || '未采到'}</dd></div>
          <div><dt>抖音商品名</dt><dd>{display(record.douyin_product_name, record.status === 'failed' ? '' : record.title) || '未采到'}</dd></div>
          <div><dt>抖音店名</dt><dd>{display(record.douyin_shop_name) || '未采到'}</dd></div>
          <div><dt>类目</dt><dd>{display(record.source_card?.category_text) || '未采到'}</dd></div>
          {display(record.product_id) && <div><dt>商品 ID</dt><dd>{record.product_id}</dd></div>}
          <div><dt>抖音商品链接</dt><dd>{shareUrl
            ? <a className={styles.productLink} href={shareUrl} target="_blank" rel="noreferrer">打开抖音商品</a>
            : '未采到'}</dd></div>
          {display(record.batch_id) && <div><dt>采集批次</dt><dd>{record.batch_id}</dd></div>}
          {Array.isArray(record.product_tags) && record.product_tags.some(tag => display(tag)) &&
            <div><dt>商品标签</dt><dd>{record.product_tags.filter(tag => display(tag)).join(' · ')}</dd></div>}
        </dl>
      </section>
      {(!!record.stored_product_images?.length || !!shareUrl) && <section className={`card card-body ${styles.section}`}>
        <div className={styles.controls}>
          <h3>商品图片</h3>
          {shareUrl && <button type="button" className="btn btn-outline btn-sm" disabled={checkingImages}
            onClick={() => fetchImages.mutate()}>
            {checkingImages ? '正在从网页获取…' : '从商品网页获取图片'}
          </button>}
        </div>
        {imageFeedback && <p role="status" className={styles.notice}>{imageFeedback}</p>}
        {!imageFeedback && record.web_image_status === 'cover_only' && <p className={styles.muted}>已下载网页图片，完整轮播尚未取得。</p>}
        {!imageFeedback && record.web_image_status === 'unavailable' && <p className={styles.muted}>当前网页未提供可下载的商品图片。</p>}
        {record.stored_image_source === 'phone' && <p className={styles.muted}>历史采集截图</p>}
        {fetchImages.isError && <p role="alert">{fetchImages.error.message}</p>}
        <div className={styles.carouselImages}>
          {record.stored_product_images?.map(image => <a key={image.url} href={image.url} target="_blank" rel="noreferrer">
            <img src={image.url} alt={`商品图片 ${image.position}`} loading="lazy" />
          </a>)}
        </div>
      </section>}
      <section className={`card card-body ${styles.section}`}>
        <h3>考古加销量</h3>
        <p className={styles.muted}>{[display(source?.core_data_period), display(source?.core_data_date_text)].filter(Boolean).join(' · ')}</p>
        <dl className={styles.facts}>
          {salesFields.map(field => <div key={field}><dt>{field}</dt><dd>{display(source?.core_data?.[field]) || '未采到'}</dd></div>)}
        </dl>
      </section>
      <DouyinSelectionBreakdown key={record.record_id} record={record} />
      <section className={`card card-body ${styles.section}`}>
        <h3>规格报价 <small className={styles.muted}>{record.skus.length} 条</small></h3>
        {record.skus.length ? <div className="table-wrap"><table className={styles.table}>
          <thead><tr><th>规格</th><th>券前价</th><th>券后价</th><th>标记</th></tr></thead>
          <tbody>{record.skus.map((sku, i) => <tr key={i}>
            <td>{skuName(sku)}</td>
            <td>{price(sku.price_before_coupon_fen)}<small>{display(sku.price_before_label)}</small></td>
            <td>{price(sku.price_after_coupon_fen)}<small>{display(sku.price_after_label)}</small></td>
            <td>{[sku.hot === true && '热销', sku.available === false && '不可选'].filter(Boolean).join(' · ') || '—'}</td>
          </tr>)}</tbody>
        </table></div> : <p className={styles.muted}>这条记录没有规格报价。</p>}
      </section>
    </>}
  </>;
}

export default function DouyinLibrary() {
  const { recordId } = useParams();
  return recordId ? <DouyinDetail key={recordId} id={recordId} /> : null;
}
