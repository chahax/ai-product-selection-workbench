import { useState } from 'react';
import type { FormEvent } from 'react';
import { isAxiosError } from 'axios';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import {
  createSelectionTask, fetchSelectionTasks, fetchSelectionTask, pauseSelectionTask, reviewSelectionTask, updateSelectionCost,
} from '../api';
import { useCaptureToggle, useSetCaptureToggle } from '../hooks/useApi';
import type {
  SelectionAiReview, SelectionCandidate, SelectionCosts, SelectionInput, SelectionJob, SelectionStatus, SelectionTask,
} from '../types/selection';
import s from '../styles/modules/Selection.module.css';

const taskStatus = { ready: '待启动', running: '采集中', paused: '已暂停', complete: '已完成' };
const collectionStatus = {
  pending: '待采集', running: '采集中', done: '已采集', partial: '部分采集', failed: '待核实', not_selected: '未入围',
};
const priceNames: Record<string, string> = {
  page: '商品页价', page_price: '商品页价', group: '拼团价', coupon: '券后价', first_item: '首件价',
  subsidy: '补贴价', flash_sale: '秒杀价', promotion: '大促价', displayed: '弹窗价',
};
const stageNames: Record<SelectionJob['stage'], string> = { discovery: '发现候选', overview: '商品初筛', spec: '补采规格' };
const costFields = [
  ['expected_receipt', '实收（扣下列费用前）', 0.01],
  ['cost', '进货成本（同规格）', 0.01],
  ['shipping_cost', '运费', 0],
  ['platform_fee', '平台费用', 0],
  ['promotion_cost', '推广费用', 0],
  ['after_sales_cost', '售后预留', 0],
] as const;
const money = new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' });

function priceDisplay(value: number | null | undefined) {
  return typeof value === 'number' && Number.isFinite(value) && value > 0 ? `¥${value.toFixed(2)}` : '待核实';
}

function displayTime(value?: string | null) {
  if (!value) return '';
  return value.slice(0, 16).replace('T', ' ');
}

function active(status?: SelectionStatus) {
  return status === 'ready' || status === 'running';
}

function messageFor(error: unknown, fallback: string) {
  if (isAxiosError(error) && typeof error.response?.data?.error === 'string') return error.response.data.error;
  return fallback;
}

function workerLink(taskId: string) {
  return `/api/selection/tasks/${encodeURIComponent(taskId)}/worker?resume=1`;
}

function pddLink(source: string) {
  try {
    const url = new URL(source);
    return url.protocol === 'https:' && !url.username && !url.password
      && /^(?:[\w-]+\.)*(?:yangkeduo|pinduoduo)\.com$/.test(url.hostname) ? url.href : '';
  } catch {
    return '';
  }
}

type SaveCost = (goodsId: string, costs: Partial<SelectionCosts>) => void;

function CostEditor({ item, saveCost, pending }: { item: SelectionCandidate; saveCost: SaveCost; pending: boolean }) {
  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const costs: Partial<SelectionCosts> = {};
    for (const [name] of costFields) {
      const value = String(form.get(name) ?? '').trim();
      costs[name] = value ? Number(value) : null;
    }
    saveCost(item.goods_id, costs);
  }

  return <details className={s.costDetails}>
    <summary>利润试算</summary>
    <form className={s.costEditor} key={costFields.map(([name]) => item[name] ?? '').join('/')} onSubmit={handleSubmit}>
      <div className={s.costFields}>
        {costFields.map(([name, label, min]) => <label key={name}>{label}
          <input type="number" name={name} min={min} step="0.01" defaultValue={item[name] ?? ''} placeholder={name === 'cost' ? '待询价' : '元'} />
        </label>)}
      </div>
      <p className={s.muted}>填写每单金额。未知留空，确定没有的费用填 0。</p>
      <button type="submit" className="btn btn-outline btn-sm" disabled={pending}>保存试算数据</button>
    </form>
  </details>;
}

function CandidateRow({ item, review, saveCost, costPending, canEditCost }: {
  item: SelectionCandidate;
  review?: SelectionAiReview;
  saveCost: SaveCost;
  costPending: boolean;
  canEditCost: boolean;
}) {
  const source = pddLink(item.source_url);
  const sku = item.recommended_sku;
  const labels = [...new Set([
    item.heat?.buyers?.label || item.buyers_text, item.heat?.sold?.label, item.heat?.views?.label,
    ...(item.title_labels ?? []),
  ].filter(Boolean))] as string[];
  const reasons = item.reasons ?? [];
  const missing = item.missing ?? [];
  const capturedAt = item.overview_captured_at || item.list_captured_at;
  const productIdentity = [review?.brand, review?.model, review?.specification].filter(Boolean).join(' · ');
  const needsOverview = item.overview_state !== 'done' && item.overview_state !== 'partial';
  return (
    <tr>
      <td className={s.rankCell}>
        <span className={s.rank}>{item.rank ?? '—'}</span>
        {typeof item.score === 'number' && <small>{item.score} 分</small>}
      </td>
      <td className={s.productCell}>
        {needsOverview ? <strong>{item.title || item.goods_id}</strong> : (
          <Link className={s.productName} to={`/product/${encodeURIComponent(item.goods_id)}`}>{item.title || item.goods_id}</Link>
        )}
        <small>{item.shop_name || item.keyword || '店铺待核实'}</small>
        {(source || capturedAt) && <div className={s.sources}>
          {source && <a href={source} target="_blank" rel="noreferrer">原商品</a>}
          {capturedAt && <time dateTime={capturedAt}>{displayTime(capturedAt)}</time>}
        </div>}
      </td>
      <td className={s.priceCell}>
        <strong>{priceDisplay(item.page_price ?? item.price)}</strong>
        <small>{item.overview_captured_at ? '商品页价' : '候选记录价'}</small>
      </td>
      <td className={s.evidenceCell}>
        {labels.length > 0 ? <div className={s.labelList}>{labels.slice(0, 3).map(label => <span key={label}>{label}</span>)}</div> : <span className={s.muted}>暂无热度证据</span>}
        {labels.length > 3 && <details className={s.details}>
          <summary>更多标签</summary>
          {labels.slice(3).map(label => <p key={label}>{label}</p>)}
        </details>}
      </td>
      <td className={s.skuCell}>
        {sku ? <>
          <strong>{priceDisplay(sku.price)}</strong>
          <small>{priceNames[sku.price_type] || '规格报价'} · {sku.name}</small>
          {sku.captured_at && <time dateTime={sku.captured_at}>{displayTime(sku.captured_at)}</time>}
        </> : <span className={s.muted}>{item.recommended_variant || '暂无报价'}</span>}
        {item.offer_count > 0 && <Link className={s.quoteLink} to={`/product/${encodeURIComponent(item.goods_id)}`}>{item.offer_count} 个规格报价</Link>}
      </td>
      <td className={s.stateCell}>
        <span className={item.deep_selected ? s.selectedBadge : s.statusBadge}>
          {item.deep_selected ? '已入围' : item.score_status === 'out_of_range' ? '超出价带'
            : item.score_status === 'excluded' ? '已排除' : collectionStatus[item.overview_state]}
        </span>
        {item.deep_selected && <small>规格{collectionStatus[item.spec_state] || '待核实'}</small>}
        {typeof item.estimated_profit === 'number' && Number.isFinite(item.estimated_profit) && <div className={s.profit}>
          <small>预计每单利润</small>
          <strong>{money.format(item.estimated_profit)}</strong>
          {typeof item.estimated_profit_rate === 'number' && Number.isFinite(item.estimated_profit_rate) && <span> · {(item.estimated_profit_rate * 100).toFixed(1)}%</span>}
        </div>}
        {(reasons.length > 0 || missing.length > 0 || review || sku) && <details className={s.details}>
          <summary>{review ? '选品依据与 AI 意见' : '查看依据'}</summary>
          {reasons.map(reason => <p key={reason}>{reason}</p>)}
          {missing.length > 0 && <p className={s.muted}>待核实 · {missing.join('、')}</p>}
          {review?.normalized_title && <p>标准品名 · {review.normalized_title}</p>}
          {productIdentity && <p>品牌与规格 · {productIdentity}</p>}
          {review?.duplicate_hint && <p>同款提示 · {review.duplicate_hint}</p>}
          {review?.reason && <p>AI 意见 · {review.reason}</p>}
          {review?.risk && <p>风险 · {review.risk}</p>}
          {sku && canEditCost && <CostEditor item={item} saveCost={saveCost} pending={costPending} />}
        </details>}
      </td>
    </tr>
  );
}

function ResultTable({ task, review, saveCost, costPending }: {
  task: SelectionTask;
  review: SelectionAiReview[];
  saveCost: SaveCost;
  costPending: boolean;
}) {
  const [filter, setFilter] = useState<'all' | 'shortlist' | 'review'>('all');
  const candidates = task.candidates ?? [];
  const rows = candidates.filter(item => filter === 'all'
    || (filter === 'shortlist' ? item.deep_selected : item.score_status === 'review' || item.missing?.length));
  const reviewById = new Map(review.map(item => [item.goods_id, item]));
  return (
    <section className="card" aria-labelledby="selection-results-title">
      <div className={`card-header ${s.resultHeader}`}>
        <h3 id="selection-results-title">候选商品</h3>
        <div className={s.filters} role="group" aria-label="筛选候选商品">
          <button type="button" aria-pressed={filter === 'all'} onClick={() => setFilter('all')}>全部 {candidates.length}</button>
          <button type="button" aria-pressed={filter === 'shortlist'} onClick={() => setFilter('shortlist')}>入围 {task.summary.deep_selected}</button>
          <button type="button" aria-pressed={filter === 'review'} onClick={() => setFilter('review')}>待核实</button>
        </div>
      </div>
      {rows.length > 0 ? <div className={s.tableScroll} tabIndex={0} aria-label="候选商品表格">
        <table className={s.table}>
          <thead><tr>
            <th scope="col">排名</th><th scope="col">商品</th><th scope="col">页面参考价</th>
            <th scope="col">热度证据</th><th scope="col">对标规格报价</th><th scope="col">状态和依据</th>
          </tr></thead>
          <tbody>{rows.map(item => <CandidateRow key={item.goods_id} item={item} review={reviewById.get(item.goods_id)}
            saveCost={saveCost} costPending={costPending} canEditCost={task.status === 'complete'} />)}</tbody>
        </table>
      </div> : <p className={s.empty}>
        {candidates.length ? '当前筛选没有商品。' : active(task.status) ? '采集到的候选商品会显示在这里。' : '这次没有发现候选商品。'}
      </p>}
    </section>
  );
}

export default function Selection() {
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [formError, setFormError] = useState('');
  const [blockedId, setBlockedId] = useState<string>();
  const [actionMessage, setActionMessage] = useState<{ id: string; text: string }>();
  const [aiByTask, setAiByTask] = useState<Record<string, SelectionAiReview[]>>({});
  const capture = useCaptureToggle();
  const setCapture = useSetCaptureToggle();
  const captureOff = capture.data?.enabled === false;
  const list = useQuery({ queryKey: ['selectionTasks'], queryFn: fetchSelectionTasks });
  const tasks = list.data?.tasks ?? [];
  const selectedId = params.get('task') || tasks[0]?.id || '';
  const detail = useQuery({
    queryKey: ['selectionTask', selectedId], enabled: Boolean(selectedId), queryFn: () => fetchSelectionTask(selectedId),
    refetchInterval: query => query.state.data?.status === 'paused' ? 4_000 : active(query.state.data?.status) ? 2_500 : false,
  });
  const task = detail.data;
  const issues = task?.jobs?.filter(job => job.status === 'failed' || job.status === 'partial').slice(-5) ?? [];
  const create = useMutation({ mutationFn: createSelectionTask });
  const pause = useMutation({
    mutationFn: pauseSelectionTask,
    onSuccess: result => {
      qc.setQueryData(['selectionTask', result.id], result);
      void qc.invalidateQueries({ queryKey: ['selectionTasks'] });
    },
    onError: (error, id) => setActionMessage({ id, text: messageFor(error, '暂停失败，请重试。') }),
  });
  const costUpdate = useMutation({
    mutationFn: ({ id, goodsId, costs }: { id: string; goodsId: string; costs: Partial<SelectionCosts> }) => updateSelectionCost(id, goodsId, costs),
    onSuccess: result => {
      qc.setQueryData(['selectionTask', result.id], result);
      setActionMessage({ id: result.id, text: '试算数据已保存。' });
    },
    onError: (error, { id }) => setActionMessage({ id, text: messageFor(error, '保存试算数据失败，请重试。') }),
  });
  const aiReview = useMutation({
    mutationFn: reviewSelectionTask,
    onSuccess: (result, id) => {
      setAiByTask(previous => ({ ...previous, [id]: result.items ?? [] }));
      const text = result.message || (!result.configured ? '请在 API 设置里配置文本模型，再运行 AI 复核。'
        : result.status === 'failed' ? '模型暂未返回可用意见，请检查文本模型设置后重试。'
        : result.items?.length ? 'AI 意见已更新，展开候选商品的「选品依据与 AI 意见」查看。' : '暂时没有可复核的候选。');
      setActionMessage({ id, text });
    },
    onError: (error, id) => setActionMessage({ id, text: messageFor(error, 'AI 复核失败，请检查文本模型设置。') }),
  });

  function selectTask(id: string) {
    setActionMessage(undefined);
    setParams({ task: id });
  }

  async function handleCreate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (create.isPending || captureOff) return;
    setFormError('');
    const fields = new FormData(event.currentTarget);
    const val = (key: string) => String(fields.get(key) || '').trim();
    const category = val('category');
    const source = val('source_url');
    const terms = (key: string) => [...new Set(val(key).split(/[\n,，、;；]+/).map(v => v.trim()).filter(Boolean))];
    const keywords = terms('keywords');
    const min = val('price_min') ? Number(val('price_min')) : undefined;
    const max = val('price_max') ? Number(val('price_max')) : undefined;
    const candidateLimit = Number(val('candidate_limit'));
    const deepLimit = Number(val('deep_limit'));
    if (!category || (!source && !keywords.length)) {
      setFormError('请填写类目，再填写关键词或活动原链接。');
      return;
    }
    if (min !== undefined && max !== undefined && min > max) {
      setFormError('最低价不能高于最高价。');
      return;
    }
    if (deepLimit > candidateLimit) {
      setFormError('补采数量不能超过候选数量。');
      return;
    }
    const input: SelectionInput = {
      category, keywords, exclude_words: terms('exclude_words'), source_url: source || undefined, price_min: min, price_max: max,
      candidate_limit: candidateLimit, deep_limit: deepLimit,
    };

    // Reserve the new Chrome tab in the submit gesture so it can open once the task ID arrives.
    const tab = window.open('about:blank', '_blank');
    if (tab) tab.opener = null;
    let result: SelectionTask;
    try {
      result = await create.mutateAsync(input);
    } catch (error) {
      if (tab && !tab.closed) tab.close();
      setFormError(messageFor(error, '创建任务失败，请重试。'));
      return;
    }
    qc.setQueryData(['selectionTask', result.id], result);
    void qc.invalidateQueries({ queryKey: ['selectionTasks'] });
    selectTask(result.id);
    try {
      if (tab && !tab.closed) {
        tab.location.replace(new URL(workerLink(result.id), window.location.href).href);
        setBlockedId(undefined);
        return;
      }
    } catch {
      if (tab && !tab.closed) tab.close();
    }
    setBlockedId(result.id);
  }

  return (
    <div className={s.page}>
      <header className={`page-header ${s.pageHeader}`}>
        <div><h2>AI 选品</h2><p>按类目和关键词找候选商品，补采入围款式与报价。</p></div>
      </header>
      <section className="card" aria-labelledby="selection-form-title">
        <div className="card-header"><h3 id="selection-form-title">新建选品任务</h3></div>
        <form className={s.form} onSubmit={handleCreate}>
          <div className={s.fieldGrid}>
            <label>类目<input name="category" required maxLength={80} placeholder="如 小家电" /></label>
            <label>关键词<input name="keywords" maxLength={280} placeholder="如 吹风机、便携吹风机" /></label>
            <label>排除词 <span className={s.muted}>选填</span><input name="exclude_words" maxLength={280} placeholder="如 二手、配件" /></label>
            <label className={s.sourceField}>百亿补贴或类目原链接 <span className={s.muted}>选填</span>
              <input name="source_url" type="url" placeholder="https://mobile.yangkeduo.com/…" />
            </label>
          </div>
          <div className={s.formBottom}>
            <fieldset className={s.priceRange}>
              <legend>目标价带（元）</legend>
              <input type="number" name="price_min" min={0} step="0.01" placeholder="不限" aria-label="最低价" />
              <span aria-hidden="true">–</span>
              <input type="number" name="price_max" min={0} step="0.01" placeholder="不限" aria-label="最高价" />
            </fieldset>
            <label>候选数量<input className={s.countInput} type="number" name="candidate_limit" defaultValue={50} required min={1} max={100} /></label>
            <label>补采规格数量<input className={s.countInput} type="number" name="deep_limit" defaultValue={15} required min={0} max={30} /></label>
            <button className="btn btn-primary" type="submit" disabled={create.isPending || captureOff}>{create.isPending ? '创建中…' : '开始选品'}</button>
          </div>
          {captureOff ? <div className={s.notice}>
            <span>采集已关闭。</span>{' '}
            <button className="btn btn-outline btn-sm" type="button" disabled={setCapture.isPending || capture.isFetching}
              onClick={() => setCapture.mutate(true)}>{setCapture.isPending ? '正在开启…' : '开启采集'}</button>
            {setCapture.isError && <p className={s.error} role="alert">{messageFor(setCapture.error, '开启采集失败，请重试。')}</p>}
          </div> : <p className={s.muted}>任务会在当前浏览器中打开一个采集标签页。采集期间保留该标签页。</p>}
          {formError && <p className={s.error} role="alert">{formError}</p>}
        </form>
      </section>

      <section className="card" aria-labelledby="selection-task-title">
        <div className={`card-header ${s.resultHeader}`}>
          <h3 id="selection-task-title">任务进度</h3>
          {tasks.length > 0 && <select className={s.taskSelect} value={selectedId} onChange={event => selectTask(event.target.value)} aria-label="选择选品任务">
            {!tasks.some(item => item.id === selectedId) && selectedId && <option value={selectedId}>当前任务</option>}
            {tasks.map(item => <option key={item.id} value={item.id}>{item.category} · {displayTime(item.created_at)}</option>)}
          </select>}
        </div>
        {!task ? <div className={s.taskBody}>
          {list.isError || detail.isError ? <>
            <p className={s.error} role="alert">{messageFor(detail.error || list.error, '加载任务失败。')}</p>
            <button className="btn btn-outline btn-sm" type="button" onClick={() => selectedId ? detail.refetch() : list.refetch()}>重试</button>
          </> : <p className={s.muted}>{list.isPending || (selectedId && detail.isPending) ? '正在加载任务…' : '暂无任务。'}</p>}
        </div> : <div className={s.taskBody}>
          <div className={s.taskTop}>
            <div className={s.taskIdentity}><strong>{task.category}</strong><span className={task.status === 'running' ? s.selectedBadge : s.statusBadge}>{taskStatus[task.status]}</span></div>
            <div className={s.taskActions}>
              {task.status !== 'complete' && !captureOff && <a className="btn btn-outline btn-sm" href={workerLink(task.id)} target="_blank" rel="noreferrer" onClick={() => setBlockedId(undefined)}>
                {task.status === 'ready' ? '打开采集页' : task.status === 'paused' ? '继续采集' : '重新连接采集页'}
              </a>}
              {task.status === 'running' && <button className="btn btn-outline btn-sm" type="button" disabled={pause.isPending} onClick={() => pause.mutate(task.id)}>暂停</button>}
              <button className="btn btn-outline btn-sm" type="button" onClick={() => void detail.refetch()} disabled={detail.isFetching}>刷新</button>
              {(task.candidates?.length ?? 0) > 0 && <>
                <button className="btn btn-outline btn-sm" type="button" disabled={aiReview.isPending} onClick={() => { setActionMessage(undefined); aiReview.mutate(task.id); }}>
                  {aiReview.isPending ? 'AI 复核中…' : 'AI 复核'}
                </button>
                <a className="btn btn-outline btn-sm" href={`/api/selection/tasks/${encodeURIComponent(task.id)}/export`} download>导出 CSV</a>
              </>}
            </div>
          </div>
          {blockedId === task.id && !captureOff && <p role="status" className={s.notice}>浏览器未打开采集页。点「打开采集页」启动任务。</p>}
          <dl className={s.stats}>
            <div><dt>已发现</dt><dd>{task.summary.discovered}</dd></div>
            <div><dt>通过初筛</dt><dd>{task.summary.ranked}</dd></div>
            <div><dt>已补采规格</dt><dd>{task.summary.deep_captured}<small> / {task.summary.deep_selected}</small></dd></div>
            <div><dt>待核实</dt><dd>{task.summary.pending_verification}</dd></div>
          </dl>
          {task.summary.jobs_total > 0 && <div className={s.progress}>
            <progress aria-label="采集步骤进度" max={task.summary.jobs_total} value={task.summary.jobs_done} />
            <span>{task.summary.jobs_done} / {task.summary.jobs_total} 步</span>
          </div>}
          {task.summary.message && <p className={s.muted}>{task.summary.message}</p>}
          {issues.length > 0 && <details className={s.details}>
            <summary>查看近期采集问题</summary>
            {issues.map(job => <p key={job.id}>{stageNames[job.stage]} · {job.keyword || job.goods_id || '活动页'} · {job.message || collectionStatus[job.status]}</p>)}
          </details>}
          {actionMessage?.id === task.id && <p className={s.notice} role="status">{actionMessage.text}</p>}
          {detail.isError && <p className={s.error} role="alert">刷新失败，仍显示最近一次加载的结果。</p>}
        </div>}
      </section>
      {task && <ResultTable key={task.id} task={task} review={aiByTask[task.id] ?? []}
        costPending={costUpdate.isPending} saveCost={(goodsId, costs) => costUpdate.mutate({ id: task.id, goodsId, costs })} />}
    </div>
  );
}
