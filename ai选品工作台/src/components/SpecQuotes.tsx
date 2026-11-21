import { useState } from 'react';
import type { GoodsInfo } from '../types';
import s from '../styles/modules/ProductDetail.module.css';

type Offer = NonNullable<NonNullable<GoodsInfo['spec_capture']>['offers']>[number];
type PriceKind = keyof Offer['prices'];

const priceColumns: { key: PriceKind; label: string }[] = [
  { key: 'coupon', label: '券后价' },
  { key: 'first_item', label: '首件价' },
  { key: 'subsidy', label: '补贴价' },
  { key: 'flash_sale', label: '秒杀价' },
  { key: 'promotion', label: '大促价' },
  { key: 'group', label: '拼团价' },
  { key: 'displayed', label: '弹窗价' },
];

function hasPrice(value: number | null | undefined): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0;
}

interface Props {
  capture: GoodsInfo['spec_capture'];
  specs: GoodsInfo['specs'];
  captureUrl: string;
  onPreview: (url: string) => void;
}

export default function SpecQuotes({ capture, specs, captureUrl, onPreview }: Props) {
  const [sort, setSort] = useState<{ key: PriceKind; descending: boolean }>();
  const offers = capture?.offers ?? [];
  const columns = priceColumns.filter(({ key }) => offers.some(offer => hasPrice(offer.prices[key])));

  const quotedOptions = new Map<string, Set<string>>();
  for (const { selected } of offers) {
    for (const [name, value] of Object.entries(selected)) {
      const options = quotedOptions.get(name) ?? new Set<string>();
      options.add(value);
      quotedOptions.set(name, options);
    }
  }
  const optionGroups = [...new Set([...Object.keys(specs ?? {}), ...Object.keys(capture?.groups ?? {})])];
  const remainingOptions = optionGroups.map(name => {
    const productOptions = specs?.[name];
    const capturedOptions = capture?.groups?.[name];
    const values = new Set([
      ...(Array.isArray(productOptions) ? productOptions : []),
      ...(Array.isArray(capturedOptions) ? capturedOptions : []),
    ]);
    return { name, values: [...values].filter(value => value && !quotedOptions.get(name)?.has(value)) };
  }).filter(({ values }) => values.length);
  if (!offers.length && !captureUrl && !remainingOptions.length) return null;

  const groups = [...quotedOptions.keys()];
  const common = groups.filter(name => offers.length > 1 && offers[0].selected[name]
    && offers.every(offer => offer.selected[name] === offers[0].selected[name]));
  const varying = groups.filter(name => !common.includes(name));
  const rowGroups = varying.length ? varying : groups;
  const sharedGroups = varying.length ? common : [];
  const sortKey = sort?.key;
  const rows = sortKey && sort && columns.some(({ key }) => key === sortKey)
    ? [...offers].sort((a, b) => {
      const left = a.prices[sortKey];
      const right = b.prices[sortKey];
      if (!hasPrice(left)) return hasPrice(right) ? 1 : 0;
      if (!hasPrice(right)) return -1;
      return sort.descending ? right - left : left - right;
    }) : offers;
  const pricedCount = offers.filter(offer => columns.some(({ key }) => hasPrice(offer.prices[key]))).length;
  const lastCapture = offers.reduce((latest, offer) => offer.captured_at > latest ? offer.captured_at : latest, '');

  return (
    <section className={`card ${s.quoteCard}`} aria-labelledby="spec-quotes-title">
      <div className={`card-header ${s.quoteHeading}`}>
        <div>
          <h3 id="spec-quotes-title">{rows.length || !remainingOptions.length ? '规格报价' : '规格选项'}</h3>
          {offers.length > 0 && (
            <div className={s.quoteMeta}>
              <span>{pricedCount} 个规格组合有报价</span>
              {lastCapture && <span>最近采集 <time dateTime={lastCapture}>{lastCapture.slice(0, 16).replace('T', ' ')}</time></span>}
            </div>
          )}
        </div>
        {captureUrl && <a className="btn btn-outline btn-sm" href={captureUrl} target="_blank" rel="noopener noreferrer">
          {offers.length ? '重新采集规格' : '自动采集规格价格'}
        </a>}
      </div>
      {rows.length ? (
        <>
          {sharedGroups.length > 0 && <dl className={s.quoteShared}>
            {sharedGroups.map(name => <div key={name}>
              <dt>{name}</dt>
              <dd>{capture?.hot_options?.[name]?.includes(offers[0].selected[name]) && <span aria-label="热销">🔥</span>}
                {offers[0].selected[name]}</dd>
            </div>)}
          </dl>}
          <div className={s.quoteScroll}>
            <table className={s.quoteTable} aria-labelledby="spec-quotes-title">
              <thead><tr>
                <th scope="col">规格</th>
                {columns.map(({ key, label }) => <th key={key} scope="col"
                  aria-sort={sort?.key === key ? (sort.descending ? 'descending' : 'ascending') : undefined}>
                  <button type="button" className={s.quoteSort} onClick={() => setSort(current => ({
                    key, descending: current?.key === key ? !current.descending : false,
                  }))}>{label}<span aria-hidden="true">{sort?.key === key ? (sort.descending ? '↓' : '↑') : '↕'}</span></button>
                </th>)}
                {!columns.length && <th scope="col">报价</th>}
              </tr></thead>
              <tbody>{rows.map(({ selected, prices, image }) => {
                const labels = rowGroups.filter(name => selected[name]);
                const variantName = labels.map(name => selected[name]).join(' / ') || '规格';
                return <tr key={JSON.stringify(Object.keys(selected).sort().map(name => [name, selected[name]]))}>
                  <th scope="row" className={s.quoteVariant}>
                    <div className={s.quoteVariantInner}>
                      {image && <button type="button" className={s.quoteImage} aria-label={`查看规格图片：${variantName}`} onClick={() => onPreview(image)}>
                        <img src={image} alt="" width={48} height={48} loading="lazy" referrerPolicy="no-referrer" />
                      </button>}
                      <div className={s.quoteVariantNames}>
                        {labels.length ? labels.map(name => <div key={name}>
                          <span className={s.quoteOptionType}>{name}</span>
                          {capture?.hot_options?.[name]?.includes(selected[name]) && <span aria-label="热销">🔥</span>}
                          <span>{selected[name]}</span>
                        </div>) : '未识别规格'}
                      </div>
                    </div>
                  </th>
                  {columns.map(({ key }, index) => {
                    const value = prices[key];
                    return <td key={key} className={index === 0 && hasPrice(value) ? s.quotePrimary : undefined}>
                      {hasPrice(value) ? `¥${value.toFixed(2)}` : <span className={s.quoteMissing} aria-label="未采集">—</span>}
                    </td>;
                  })}
                  {!columns.length && <td className={s.quoteMissing}>未采集</td>}
                </tr>;
              })}</tbody>
            </table>
          </div>
        </>
      ) : <p className={s.quoteEmpty}>暂无规格报价</p>}
      {remainingOptions.length > 0 && (
        <div className={s.quoteOptions}>
          {rows.length > 0 && <h4>其他规格选项</h4>}
          {remainingOptions.map(({ name, values }) => (
            <div key={name} className={s.detailSpecRow}>
              <div className={s.detailSpecLabel}>{name}</div>
              <div className={s.detailSpecValues}>
                {values.map(value => <span key={value} className="badge">
                  {capture?.hot_options?.[name]?.includes(value) && <span aria-label="热销">🔥</span>}
                  {value}
                </span>)}
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
