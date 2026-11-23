import { useState } from 'react';
import type { FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { batchState, createPddJob, pairPddDevice, pddStatus, pddStatusKey, pricingRules } from '../api/pddMobile';
import { useToast } from '../components/Toast';
import s from '../styles/modules/MobileCollector.module.css';

export default function PddMobileCollector() {
  const client = useQueryClient();
  const toast = useToast();
  const [deviceId, setDeviceId] = useState('');
  const status = useQuery({
    queryKey: pddStatusKey, queryFn: ({ signal }) => pddStatus(signal), staleTime: 0, refetchInterval: 5000,
  });
  const pair = useMutation({ mutationFn: pairPddDevice });
  const create = useMutation({ mutationFn: createPddJob, onSuccess: () => {
    toast.success('拼多多任务已下发');
    return client.invalidateQueries({ queryKey: pddStatusKey });
  }, onError: () => client.invalidateQueries({ queryKey: pddStatusKey }) });
  const devices = status.data?.devices ?? [];
  const tasks = status.data?.tasks ?? [];
  const device = devices.find(d => d.id === deviceId) ?? devices.find(d => d.online && d.available) ?? devices[0];
  const busy = tasks.some(task => task.device === device?.id && task.state !== '已完成');

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!device || busy || status.isError || create.isPending) return;
    const data = new FormData(event.currentTarget);
    create.mutate({ device: device.id, settings: {
      route: String(data.get('route')).trim(), quantity: Number(data.get('quantity')), pricing: String(data.get('pricing')),
      threshold: String(data.get('threshold')), share: String(data.get('share')), weekly: data.has('weekly'),
    } });
  }

  return <>
    {status.isError && <p className={s.error} role="alert">{status.error.message}</p>}
    <div className={s.setupGrid}>
      <section className={`card card-body ${s.panel}`}>
        <div className={s.panelHeading}>
          <h3>采集手机</h3>
          <button className="btn btn-outline btn-sm" disabled={pair.isPending} onClick={() => pair.mutate()}>生成设备密钥</button>
        </div>
        {status.isPending && <p role="status">正在读取手机…</p>}
        {status.isSuccess && !devices.length && <p className={s.muted}>还没有配对的拼多多手机。</p>}
        <ul className={s.devices}>{devices.map(d => <li key={d.id} className={s.device}>
          <div><strong>{d.model || '安卓手机'}</strong><small>{d.id}</small></div>
          <span className={s.badge}>{!d.online ? '离线' : d.available ? '可接单' : '已在线 · 待就绪'}</span>
        </li>)}</ul>
        <p className={s.muted}>在「百亿补贴采集」App 填入拼多多采集服务地址和设备密钥，开启「允许此手机自动接单」。</p>
        {pair.isSuccess && <label className={s.pairing}>新设备密钥
          <input aria-label="新设备密钥" readOnly value={pair.data.key} onFocus={e => e.currentTarget.select()} />
        </label>}
        {pair.isError && <p className={s.error} role="alert">{pair.error.message}</p>}
      </section>
      <section className={`card card-body ${s.panel}`}>
        <h3>新建拼多多任务</h3>
        <form className={s.form} onSubmit={submit}>
          <label>执行手机<select value={device?.id ?? ''} onChange={e => setDeviceId(e.target.value)} disabled={!devices.length}>
            {!devices.length && <option value="">等待手机配对</option>}
            {devices.map(d => <option key={d.id} value={d.id}>{d.model || '安卓手机'} · {d.id.slice(-6)}</option>)}
          </select></label>
          <label>类目路径<input name="route" defaultValue="百亿补贴 → 小家电 → 个人护理" required /></label>
          <div className={s.formRow}>
            <label>采集数量<input name="quantity" type="number" min="1" max="1000" defaultValue="50" required /></label>
            <label>计价规则<select name="pricing">{pricingRules.map(rule => <option key={rule}>{rule}</option>)}</select></label>
            <label>销售额门槛（元/天）<input name="threshold" type="number" min="0" max="1000000000" step="any" defaultValue="1500" required /></label>
            <label>折算系数（%）<input name="share" type="number" min="0.01" max="100" step="any" defaultValue="30" required /></label>
          </div>
          <label className={s.check}><input type="checkbox" name="weekly" defaultChecked />仅有周销时按周销 ÷ 7 折算</label>
          <div className={s.submit}>
            {busy && <p className={s.muted}>该手机已有未结束任务。</p>}
            <button className="btn btn-primary" type="submit" disabled={!device || busy || status.isError || create.isPending}>
              {create.isPending ? '正在下发…' : '下发任务'}
            </button>
          </div>
          {create.isError && <p className={s.error} role="alert">{create.error.message}</p>}
        </form>
      </section>
    </div>
    <section className={`card card-body ${s.panel}`}>
      <div className={s.panelHeading}><h3>采集任务</h3>
        <button className="btn btn-outline btn-sm" disabled={status.isFetching} onClick={() => void status.refetch()}>刷新</button>
      </div>
      {status.isSuccess && !tasks.length && <p className={s.muted}>还没有拼多多采集任务。</p>}
      {tasks.map(task => <article className={s.job} key={task.id}>
        <div className={s.jobHeading}>
          <div><h4>{task.settings.route}</h4>
            <p className={s.muted}>{devices.find(d => d.id === task.device)?.model || task.device} · 任务 {task.id}
              {task.progress.startedAt ? ' · 开始于 ' + new Date(task.progress.startedAt).toLocaleString('zh-CN') : ''}</p>
          </div>
          <span className={s.badge}>{batchState(task, task.progress.total)}</span>
          <Link className="btn btn-outline btn-sm" to={'/mobile-collector/pdd/' + task.id}>查看结果</Link>
        </div>
        <div className={s.progress}><progress aria-label="拼多多采集进度" value={task.progress.total} max={task.settings.quantity} />
          <span>已采 {task.progress.total} / {task.settings.quantity} 款 · 金额达标 {task.progress.qualified} 款
            {task.progress.review != null && ` · 待核价 ${task.progress.review} 款`}</span>
        </div>
        {(task.progress.event || task.progress.step) && <p>{task.progress.event || task.progress.step}</p>}
      </article>)}
    </section>
  </>;
}
