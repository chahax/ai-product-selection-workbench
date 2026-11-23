import { useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import PddMobileCollector from './PddMobileCollector';
import libraryStyles from '../styles/modules/Products.module.css';
import { cancelMobileJob, createMobileJob, createMobilePairing, fetchMobileDevices, fetchMobileJobs } from '../api/mobileCollector';
import type { MobileDevice, MobileJob, MobileJobStatus, MobilePeriod } from '../api/mobileCollector';
import { useConfirm } from '../components/Confirm';
import { useToast } from '../components/Toast';
import s from '../styles/modules/MobileCollector.module.css';

const jobsKey = ['mobile-collector-jobs'];
const refreshInterval = 3000;
const gatewayUrl = (import.meta.env.VITE_MOBILE_GATEWAY_URL as string | undefined)?.trim();
const gatewayFingerprint = (import.meta.env.VITE_MOBILE_GATEWAY_FINGERPRINT as string | undefined)?.trim();
const categoryPath = '3C数码家电-电器-个护健康电器';
const categoryOptions = ['美容仪', '电动剃须刀/修须刀/修胡子造型器', '电吹风', '按摩椅/沙发'];
const jobStatuses: Record<MobileJobStatus, string> = {
  queued: '排队中',
  claimed: '手机已领取',
  waiting_phone: '等待手机操作',
  running: '采集中',
  paused: '已暂停',
  uploading: '回传中',
  completed: '已完成',
  failed: '任务失败',
  cancelled: '已取消',
};
const periods: Record<MobilePeriod, string> = { yesterday: '昨日', '7d': '7日', '30d': '30日' };
const dateTime = new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
});

function Time({ value }: { value?: string | null }) {
  if (!value || !Number.isFinite(Date.parse(value))) return <>暂无</>;
  return <time dateTime={value}>{dateTime.format(new Date(value))}</time>;
}

function CopyField({ label, value, code = false }: { label: string; value: string; code?: boolean }) {
  const toast = useToast();
  async function copyValue() {
    try {
      await navigator.clipboard.writeText(value);
      toast.success(`已复制${label}`);
    } catch {
      toast.info('请选中文本后复制');
    }
  }

  return (
    <div className={s.copyField}>
      <label>
        {label}
        <input type="text" className={code ? s.code : undefined} value={value} readOnly onFocus={event => event.currentTarget.select()} />
      </label>
      <button className="btn btn-outline btn-sm" type="button" onClick={copyValue} aria-label={`复制${label}`}>复制</button>
    </div>
  );
}

function JobCard({ job, device, cancel, cancelling }: {
  job: MobileJob; device?: MobileDevice; cancel: () => void; cancelling: boolean;
}) {
  const processed = job.complete + job.partial + job.failed + job.invalid;
  const total = job.total || job.limit;
  const pending = job.expected_records == null ? '—' : Math.max(0, job.expected_records - job.received);
  const stats = [
    ['完整', job.complete], ['部分完成', job.partial], ['采集失败', job.failed],
    ['商品失效', job.invalid], ['已回传', job.received], ['待回传', pending],
  ] as const;
  const statusTone = job.status === 'completed' ? s.good
    : job.status === 'failed' ? s.bad
    : job.status === 'running' || job.status === 'uploading' ? s.active
    : '';

  return (
    <article className={s.job}>
      <div className={s.jobHeading}>
        <div>
          <h4>{job.category.replaceAll('-', ' › ')}</h4>
          <p className={s.muted}>
            {periods[job.period]} · 计划 {job.limit} 件{device && ` · ${device.name}`} · 创建于 <Time value={job.created_at} />
          </p>
        </div>
        <span className={`${s.badge} ${statusTone}`}>{jobStatuses[job.status] ?? '状态待确认'}</span>
        {job.status === 'queued' && <button type="button" className="btn btn-outline btn-sm" disabled={cancelling}
          onClick={cancel}>取消任务</button>}
      </div>
      {(job.stage || job.current_title) && <dl className={s.jobContext}>
        {job.stage && <div><dt>当前阶段</dt><dd>{job.stage}</dd></div>}
        {job.current_title && <div><dt>当前商品</dt><dd>{job.current_title}</dd></div>}
      </dl>}
      <div className={s.progress}>
        <progress aria-label="商品处理进度" max={Math.max(total, 1)} value={Math.min(processed, total)} />
        <span>{processed} / {total} 件</span>
      </div>
      <dl className={s.stats}>
        {stats.map(([label, count]) => (
          <div key={label}><dt>{label}</dt><dd>{count}</dd></div>
        ))}
      </dl>
      {job.error && <p className={s.error} role="alert">{job.error}</p>}
      <p className={s.muted}>更新于 <Time value={job.updated_at} /></p>
    </article>
  );
}

export default function MobileCollector() {
  const [params, setParams] = useSearchParams();
  const isPdd = params.get('platform') === 'pdd';
  return <div className={s.page}>
    <header className={`page-header ${s.heading}`}>
      <h2>手机采集</h2>
      <label className={libraryStyles.platformField}>平台
        <select value={isPdd ? 'pdd' : 'douyin'} onChange={event => setParams(event.target.value === 'pdd' ? { platform: 'pdd' } : {})}>
          <option value="douyin">抖音</option><option value="pdd">拼多多</option>
        </select>
      </label>
    </header>
    {isPdd ? <PddMobileCollector /> : <DouyinMobileCollector />}
  </div>;
}

function DouyinMobileCollector() {
  const client = useQueryClient();
  const { confirm } = useConfirm();
  const toast = useToast();
  const [deviceId, setDeviceId] = useState('');
  const [category, setCategory] = useState('');
  const devicesQuery = useQuery({
    queryKey: ['mobile-collector-devices'],
    queryFn: ({ signal }) => fetchMobileDevices(signal),
    refetchInterval: refreshInterval,
  });
  const jobsQuery = useQuery({
    queryKey: jobsKey,
    queryFn: ({ signal }) => fetchMobileJobs(signal),
    refetchInterval: refreshInterval,
  });
  const pairing = useMutation({ mutationFn: createMobilePairing });
  const createJob = useMutation({
    mutationFn: createMobileJob,
    onSuccess: () => {
      toast.success('采集任务已下发');
      void client.invalidateQueries({ queryKey: jobsKey });
    },
  });
  const cancelJob = useMutation({
    mutationFn: cancelMobileJob,
    onSuccess: () => {
      toast.success('已取消排队任务');
      void client.invalidateQueries({ queryKey: jobsKey });
    },
    onError: error => {
      toast.error(error.message);
      void client.invalidateQueries({ queryKey: jobsKey });
    },
  });

  const devices = devicesQuery.data?.devices ?? [];
  const selected = devices.find(device => device.id === deviceId) ?? devices.find(device => device.ready) ?? devices[0];
  const jobs = jobsQuery.data?.jobs ?? [];

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected || devicesQuery.isError || createJob.isPending) return;
    const data = new FormData(event.currentTarget);
    const period = data.get('period') as MobilePeriod;
    const limit = Number(data.get('limit'));
    const child = category === 'custom' ? String(data.get('subcategory') ?? '').trim() : category;
    if (!Number.isInteger(limit) || limit < 1 || limit > 200 || (category === 'custom' && !child)) return;
    createJob.mutate({ device_id: selected.id, period, limit, category: child ? `${categoryPath}-${child}` : categoryPath });
  }

  async function confirmCancel(job: MobileJob) {
    if (cancelJob.isPending) return;
    if (await confirm({ title: '取消采集任务', message: '取消这个排队中的任务？', confirmText: '取消任务', cancelText: '返回' })) {
      cancelJob.mutate(job.id);
    }
  }

  return (
    <div className={s.page}>
      <header className={`page-header ${s.heading}`}>
        <div>
          <p className={s.muted}>创建抖音商品采集任务，查看手机进度和回传结果。</p>
        </div>
        <Link className="btn btn-outline" to="/products?platform=douyin">查看抖音商品库</Link>
      </header>

      <div className={s.setupGrid}>
        <section className={`card card-body ${s.panel}`} aria-labelledby="mobile-devices-heading">
          <div className={s.panelHeading}>
            <h3 id="mobile-devices-heading">采集手机</h3>
            <button className="btn btn-outline btn-sm" type="button" disabled={pairing.isPending}
              onClick={() => pairing.mutate()}>
              {pairing.isPending ? '正在生成…' : '生成配对码'}
            </button>
          </div>
          {devicesQuery.isPending && <p className={s.muted} role="status">正在读取手机…</p>}
          {devicesQuery.isError && <p className={s.error} role="alert">{devicesQuery.error.message}</p>}
          {devicesQuery.isSuccess && !devices.length && <p className={s.muted}>还没有配对的手机。</p>}
          {!!devices.length && <ul className={s.devices}>
            {devices.map(device => (
              <li key={device.id} className={s.device}>
                <div>
                  <strong>{device.name}</strong>
                  <p className={s.muted}>最近上报 <Time value={device.last_seen} /></p>
                </div>
                <span className={`${s.badge} ${device.ready ? s.good : ''}`}>{device.ready ? '已就绪' : '待就绪'}</span>
              </li>
            ))}
          </ul>}
          <p className={s.muted}>在手机商品采集 App 的「连接工作台」填入网址和配对码。配对后点击「开启待命」。</p>
          {gatewayUrl ? <CopyField label="手机连接地址" value={gatewayUrl} />
            : <p className={s.muted}>手机连接地址以工作台网关启动时显示的 HTTPS 地址为准。</p>}
          {gatewayFingerprint && <CopyField label="服务器证书 SHA-256" value={gatewayFingerprint} />}
          {pairing.isSuccess && <div className={s.pairing}>
            <CopyField label="配对码" value={pairing.data.code} code />
            <p className={s.muted}>于 <Time value={pairing.data.expires_at} /> 失效。</p>
          </div>}
          {pairing.isError && <p className={s.error} role="alert">{pairing.error.message}</p>}
        </section>

        <section className={`card card-body ${s.panel}`} aria-labelledby="mobile-create-heading">
          <div className={s.panelHeading}>
            <h3 id="mobile-create-heading">新建采集任务</h3>
            <span className={s.badge}>抖音商城</span>
          </div>
          <form className={s.form} onSubmit={submit}>
            <label>执行手机
              <select value={selected?.id ?? ''} onChange={event => setDeviceId(event.currentTarget.value)} disabled={!devices.length}>
                {!devices.length && <option value="">等待手机配对</option>}
                {devices.map(device => <option key={device.id} value={device.id}>{device.name}{device.ready ? '' : '（待就绪）'}</option>)}
              </select>
            </label>
            <label>考古加商品类目
              <select value={category} onChange={event => setCategory(event.currentTarget.value)}>
                <option value="">3C数码家电 › 电器 › 个护健康电器</option>
                {categoryOptions.map(option => <option key={option} value={option}>个护健康电器 › {option}</option>)}
                <option value="custom">其他个护健康电器子类目</option>
              </select>
            </label>
            {category === 'custom' && <label>子类目名称
              <input type="text" name="subcategory" placeholder="填写考古加里的完整名称" maxLength={100 - categoryPath.length - 1} required />
            </label>}
            <div className={s.formRow}>
              <label>统计周期
                <select name="period" defaultValue="30d">
                  {Object.entries(periods).map(([key, label]) => <option key={key} value={key}>{label}</option>)}
                </select>
              </label>
              <label>采集数量
                <input type="number" name="limit" defaultValue={10} min={1} max={200} step={1} required />
              </label>
            </div>
            <div className={s.submit}>
              <p className={s.muted}>
                {selected?.ready ? '请让手机保持解锁，并开启采集 App 的无障碍服务。'
                  : selected ? '任务会排队，手机待命后自动领取。' : '先配对手机，再下发采集任务。'}
              </p>
              <button className="btn btn-primary" type="submit"
                disabled={!selected || devicesQuery.isError || createJob.isPending}>
                {createJob.isPending ? '正在下发…' : '下发任务'}
              </button>
            </div>
            {createJob.isError && <p className={s.error} role="alert">{createJob.error.message}</p>}
          </form>
        </section>
      </div>

      <section className={`card card-body ${s.panel}`} aria-labelledby="mobile-jobs-heading">
        <div className={s.panelHeading}>
          <h3 id="mobile-jobs-heading">采集任务</h3>
          <button className="btn btn-outline btn-sm" type="button" disabled={jobsQuery.isFetching || devicesQuery.isFetching}
            onClick={() => {
              void jobsQuery.refetch();
              void devicesQuery.refetch();
            }}>刷新</button>
        </div>
        {jobsQuery.isPending && <p className={s.muted} role="status">正在读取任务…</p>}
        {jobsQuery.isError && <p className={s.error} role="alert">{jobsQuery.error.message}</p>}
        {jobsQuery.isSuccess && !jobs.length && <p className={s.muted}>还没有采集任务。</p>}
        {!!jobs.length && <div className={s.jobs}>
          {jobs.map(job => (
            <JobCard key={job.id} job={job} device={devices.find(device => device.id === job.device_id)}
              cancel={() => void confirmCancel(job)} cancelling={cancelJob.isPending} />
          ))}
        </div>}
      </section>
    </div>
  );
}
