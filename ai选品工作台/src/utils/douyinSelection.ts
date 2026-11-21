import type { DouyinRecord, DouyinSku } from '../types/douyin';
import { isPriceFen } from './douyinProduct';

export interface DouyinSelectionRule {
  ratioPercent: number;
  thresholdYuan: number;
}

export interface DouyinSelectionSignal {
  salesMinimum: number | null;
  periodDays: number | null;
  dailySalesMinimum: number | null;
  skuIndex: number | null;
  skuName: string;
  skuCount: number;
  priceFen: number | null;
  skuSource: 'manual' | 'hot_price' | 'hot' | 'fallback' | null;
  estimatedYuan: number | null;
  marked: boolean;
}

export const DEFAULT_DOUYIN_SELECTION_RULE: Readonly<DouyinSelectionRule> = {
  ratioPercent: 30,
  thresholdYuan: 1500,
};

const COUNT = '(\\d+(?:,\\d{3})*(?:\\.\\d+)?)([百千万亿]?)件?';
const COUNT_RANGE = new RegExp(`^${COUNT}(?:(?:[-~–—−至到])${COUNT}|\\+)?$`);
const UNIT: Readonly<Record<string, number>> = { '': 1, 百: 100, 千: 1000, 万: 10000, 亿: 100000000 };
const DATE_RANGE = /(?<!\d)(\d{4})[./-](\d{1,2})[./-](\d{1,2})\s*[-~–—−至到]\s*(\d{4})[./-](\d{1,2})[./-](\d{1,2})(?!\d)/;
const PERIOD_LABEL = /^(?:最近|过去|近)?(\d{1,5})(?:日|天|d)$/i;
const MAX_PERIOD_DAYS = 36600;

function validPeriodDays(days: unknown): days is number {
  return typeof days === 'number' && Number.isSafeInteger(days) && days > 0 && days <= MAX_PERIOD_DAYS;
}

function utcDate(year: number, month: number, day: number): number | null {
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() + 1 === month && date.getUTCDate() === day
    ? date.getTime() : null;
}

function daysFromDateRange(value: unknown): number | null {
  if (typeof value !== 'string' || value.length > 160) return null;
  const match = DATE_RANGE.exec(value.normalize('NFKC'));
  if (!match) return null;
  const [, startYear, startMonth, startDay, endYear, endMonth, endDay] = match.map(Number);
  const start = utcDate(startYear, startMonth, startDay);
  const end = utcDate(endYear, endMonth, endDay);
  if (start === null || end === null) return null;
  const days = (end - start) / 86_400_000 + 1;
  return validPeriodDays(days) ? days : null;
}

function cardSalesPeriodDays(detail: DouyinRecord['source_detail']): number | null {
  if (validPeriodDays(detail?.core_data_period_days)) return detail.core_data_period_days;
  const observedDays = daysFromDateRange(detail?.core_data_date_text);
  if (observedDays !== null) return observedDays;
  if (typeof detail?.core_data_period !== 'string') return null;
  const label = detail.core_data_period.normalize('NFKC').replace(/\s/g, '');
  if (['昨日', '昨天', '今日', '今天', '当日', '当天'].includes(label)) return 1;
  const days = Number(PERIOD_LABEL.exec(label)?.[1]);
  return validPeriodDays(days) ? days : null;
}

function countWithUnit(amount: string, unit: string): number | null {
  const [whole, fraction = ''] = amount.replaceAll(',', '').split('.');
  const scale = 10n ** BigInt(fraction.length);
  const scaled = BigInt(whole + fraction) * BigInt(UNIT[unit]);
  if (scaled % scale) return null;
  const value = Number(scaled / scale);
  return Number.isSafeInteger(value) ? value : null;
}

export function minimumDouyinCardSales(input: unknown): number | null {
  if (typeof input === 'number') return Number.isSafeInteger(input) && input >= 0 ? input : null;
  if (typeof input !== 'string' || input.length > 80) return null;
  const match = COUNT_RANGE.exec(input.normalize('NFKC').replace(/\s/g, ''));
  if (!match) return null;
  const [, first, firstUnit, last, lastUnit] = match;
  let lower = countWithUnit(first, firstUnit);
  if (last !== undefined) {
    const upper = countWithUnit(last, lastUnit || firstUnit);
    // In a range such as 2.5-5万 the final unit applies to both endpoints.
    if (!firstUnit && lastUnit && upper !== null) {
      const sameUnit = countWithUnit(first, lastUnit);
      if (sameUnit !== null && sameUnit <= upper) lower = sameUnit;
    }
    if (lower === null || upper === null || lower > upper) return null;
  }
  return lower;
}

type SkuLabel = string | { name?: string; label?: string; text?: string };
type SkuPricing = DouyinSku & {
  price_text?: string;
  price_label?: string;
  promotion_label?: string;
  tags?: SkuLabel[];
  badges?: SkuLabel[];
  labels?: SkuLabel[];
};

function hasHotPrice(value: unknown): value is string {
  return typeof value === 'string' && value.replace(/\s/g, '').includes('火爆价');
}

function hasHotPriceBadge(values: unknown) {
  return Array.isArray(values) && values.some((entry: SkuLabel) => hasHotPrice(entry)
    || (entry !== null && typeof entry === 'object' && [entry.name, entry.label, entry.text].some(hasHotPrice)));
}

function skuPrices(sku: SkuPricing) {
  const after = isPriceFen(sku.price_after_coupon_fen) ? sku.price_after_coupon_fen : null;
  const before = isPriceFen(sku.price_before_coupon_fen) ? sku.price_before_coupon_fen : null;
  if (hasHotPrice(sku.price_after_label) && after !== null) return { priceFen: after, hotPrice: true };
  if (hasHotPrice(sku.price_before_label) && before !== null) return { priceFen: before, hotPrice: true };

  if (hasHotPrice(sku.price_text)) {
    const explicit = /火\s*爆\s*价\s*[:：]?\s*[¥￥]?\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)/.exec(sku.price_text);
    if (explicit) {
      const amount = Math.round(Number(explicit[1].replaceAll(',', '')) * 100);
      if (amount === after || amount === before) return { priceFen: amount, hotPrice: true };
    }
  }

  const hotPrice = [sku.price_after_label, sku.price_before_label, sku.price_label, sku.price_text, sku.promotion_label].some(hasHotPrice)
    || [sku.tags, sku.badges, sku.labels].some(hasHotPriceBadge);
  return { priceFen: after ?? before, hotPrice, hasDiscountPrice: after !== null };
}

type PricedSku = { index: number; name: string; priceFen: number; hotPrice: boolean; hot: boolean; hasDiscountPrice?: boolean };

function pricedSkus(record: DouyinRecord): PricedSku[] {
  return Array.isArray(record.skus) ? record.skus.flatMap((sku, index) => {
    if (!sku || typeof sku !== 'object' || sku.available === false) return [];
    const { priceFen, hotPrice, hasDiscountPrice } = skuPrices(sku);
    if (priceFen === null) return [];
    const name = typeof sku.name === 'string' && sku.name.trim() ? sku.name.trim() : `规格 ${index + 1}`;
    return [{ index, name, priceFen, hotPrice, hot: sku.hot === true, hasDiscountPrice }];
  }) : [];
}

export function eligibleDouyinSkus(record: DouyinRecord): { index: number; name: string; priceFen: number }[] {
  if (record.product_state === 'invalid' || record.status === 'failed') return [];
  return pricedSkus(record).map(({ index, name, priceFen }) => ({ index, name, priceFen }));
}

export function douyinSelectionLabel(signal: DouyinSelectionSignal | undefined) {
  if (signal?.estimatedYuan == null) return '';
  return signal.marked ? '商品卡测算达标' : '商品卡测算未达标';
}

export function calculateDouyinSelection(
  record: DouyinRecord,
  rule: DouyinSelectionRule = DEFAULT_DOUYIN_SELECTION_RULE,
  preferredSkuIndex?: number,
): DouyinSelectionSignal {
  const salesMinimum = minimumDouyinCardSales(record.source_detail?.core_data?.['商品卡销量']);
  const periodDays = cardSalesPeriodDays(record.source_detail);
  const dailySalesMinimum = salesMinimum !== null && periodDays !== null ? salesMinimum / periodDays : null;
  const empty = { salesMinimum, periodDays, dailySalesMinimum, skuIndex: null, skuName: '', skuCount: 0, priceFen: null, skuSource: null, estimatedYuan: null, marked: false };
  if (record.product_state === 'invalid' || record.status === 'failed') return empty;

  const options = pricedSkus(record);
  const manual = options.find(sku => sku.index === preferredSkuIndex);
  const explicit = options.filter(sku => sku.hotPrice);
  const hot = options.filter(sku => sku.hot);
  const fallback = options.find(sku => sku.hasDiscountPrice) ?? options[0];
  const selected = manual ? [manual] : explicit.length ? explicit : hot.length ? hot : fallback ? [fallback] : [];
  if (!selected.length) return empty;
  const sumFen = selected.reduce((sum, sku) => sum + BigInt(sku.priceFen), 0n);
  const count = selected.length;

  const picked: DouyinSelectionSignal = {
    ...empty,
    skuIndex: count === 1 ? selected[0].index : null,
    skuName: count === 1 ? selected[0].name : '',
    skuCount: count,
    priceFen: Number(sumFen) / count,
    skuSource: manual ? 'manual' : explicit.length ? 'hot_price' : hot.length ? 'hot' : 'fallback',
  };
  const ratio = Math.round(rule.ratioPercent * 100);
  const thresholdFen = Math.round(rule.thresholdYuan * 100);
  if (salesMinimum === null || periodDays === null || !Number.isSafeInteger(ratio) || ratio < 0 || ratio > 10000
      || !Number.isSafeInteger(thresholdFen) || thresholdFen < 0) return picked;

  const amount = BigInt(salesMinimum) * sumFen * BigInt(ratio);
  const periods = BigInt(periodDays) * BigInt(count);
  return {
    ...picked,
    estimatedYuan: Number(amount) / (1_000_000 * Number(periods)),
    marked: amount > BigInt(thresholdFen) * 10_000n * periods,
  };
}
