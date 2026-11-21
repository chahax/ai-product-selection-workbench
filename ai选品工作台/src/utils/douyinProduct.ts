import type { DouyinRecord } from '../types/douyin';

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

export function price(fen: unknown) {
  return typeof fen === 'number' && Number.isSafeInteger(fen) && fen >= 0 ? `¥${(fen / 100).toFixed(2)}` : '未采到';
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

export function couponPrice(record: DouyinRecord) {
  const amounts = record.skus.flatMap(sku => typeof sku.price_after_coupon_fen === 'number'
    && Number.isSafeInteger(sku.price_after_coupon_fen) && sku.price_after_coupon_fen >= 0
    ? [sku.price_after_coupon_fen] : []);
  if (!amounts.length) return '';
  const min = Math.min(...amounts), max = Math.max(...amounts);
  return min === max ? price(min) : `${price(min)} – ${price(max)}`;
}
