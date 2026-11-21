import type { DouyinRecord, DouyinSku } from '../types/douyin';

export function display(...values: unknown[]) {
  return values.find((value): value is string => typeof value === 'string' && value.trim().length > 0) ?? '';
}

export function title(record: DouyinRecord) {
  return display(record.douyin_product_name, record.kaogujia_product_name, record.source_title, record.title) || '未采到商品名';
}

export function status(record: DouyinRecord) {
  if (record.product_state === 'invalid') return '商品已失效';
  if (record.status === 'complete') return '完整';
  if (record.status === 'partial') return '部分完成';
  if (record.status === 'failed') return '采集失败';
  return '状态未确认';
}

export function isPriceFen(fen: unknown): fen is number {
  return typeof fen === 'number' && Number.isSafeInteger(fen) && fen >= 0;
}

export function price(fen: unknown) {
  return isPriceFen(fen) ? `¥${(fen / 100).toFixed(2)}` : '未采到';
}

export function skuQuotes(sku: DouyinSku) {
  const before = sku.price_before_coupon_fen;
  const after = sku.price_after_coupon_fen;
  return {
    before: isPriceFen(before) ? { fen: before, label: display(sku.price_before_label) || (isPriceFen(after) ? '券前价' : '商品价') } : null,
    after: isPriceFen(after) ? { fen: after, label: display(sku.price_after_label) || '券后价' } : null,
  };
}

export function productLink(record: DouyinRecord) {
  for (const value of [record.douyin_share_url, record.douyin_product_url, record.source_url]) {
    if (!display(value)) continue;
    try {
      const link = new URL(value!);
      if (link.protocol === 'https:' && !link.username && !link.password && !link.port
          && (link.hostname === 'v.douyin.com' && /^\/[\w-]+\/?$/.test(link.pathname)
              || link.hostname === 'haohuo.jinritemai.com' && link.pathname === '/ecommerce/trade/detail/index.html'
                  && /^\d{10,25}$/.test(link.searchParams.get('id') ?? ''))) return link.href;
    } catch { /* Ignore a legacy record with an invalid link. */ }
  }
  return '';
}

export function observedDouyinPrice(record: DouyinRecord) {
  let fen: number | null = null;
  let maxFen: number | null = null;
  const labels = new Set<string>();
  for (const sku of record.skus) {
    if (sku.available === false) continue;
    const { before, after } = skuQuotes(sku);
    const quote = after ?? before;
    if (!quote) continue;
    fen = Math.min(fen ?? quote.fen, quote.fen);
    maxFen = Math.max(maxFen ?? quote.fen, quote.fen);
    labels.add(quote.label);
  }
  return { fen, maxFen, label: labels.size > 1 ? '规格报价' : labels.values().next().value };
}
