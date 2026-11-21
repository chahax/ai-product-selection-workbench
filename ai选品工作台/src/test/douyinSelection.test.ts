import { describe, expect, it } from 'vitest';
import type { DouyinRecord, DouyinSku } from '../types/douyin';
import {
  calculateDouyinSelection,
  DEFAULT_DOUYIN_SELECTION_RULE,
  douyinSelectionLabel,
  eligibleDouyinSkus,
  minimumDouyinCardSales,
} from '../utils/douyinSelection';

function record(
  sales: string,
  skus: DouyinSku[],
  period: Pick<NonNullable<DouyinRecord['source_detail']>, 'core_data_period_days' | 'core_data_date_text' | 'core_data_period'>
    = { core_data_period_days: 1 },
): DouyinRecord {
  return {
    record_id: 'record',
    captured_at: '2026-11-20T03:00:00Z',
    platform: 'douyin_mall',
    source_detail: { core_data: { 商品卡销量: sales, 商品销量: '10万+' }, ...period },
    skus,
  };
}

describe('Douyin selection signals', () => {
  it.each([
    ['0-250', 0],
    ['2.5万-5万', 25000],
    ['2.5千–5千', 2500],
    ['5千-1万', 5000],
    ['10万+', 100000],
    ['2.5～5万', 25000],
    ['5000-1万', 5000],
    ['１，２３４ 件', 1234],
    ['1.001千', 1001],
    ['—', null],
    ['2万-1万', null],
    ['约3万', null],
    ['低于250件', null],
  ])('takes the evidenced lower bound of %s', (value, expected) => {
    expect(minimumDouyinCardSales(value)).toBe(expected);
  });

  it('prefers a usable explicit hot price and takes the amount beside its own label', () => {
    const signal = calculateDouyinSelection(record('2.5千-5千', [
      { name: '热销规格', hot: true, price_after_coupon_fen: 5000 },
      { name: '已售罄', available: false, price_after_label: '火爆价', price_after_coupon_fen: 3900 },
      { name: '火爆价规格', price_before_label: '火爆价', price_before_coupon_fen: 6000, price_after_coupon_fen: 3000 },
    ]));
    expect(signal).toMatchObject({ skuIndex: 2, skuSource: 'hot_price', priceFen: 6000, salesMinimum: 2500, estimatedYuan: 45000, marked: true });
  });

  it('recognizes a separately captured promotion badge and falls back to the captured coupon price', () => {
    const sku = { name: '活动款', promotion_label: '火爆价', price_after_coupon_fen: 3099 };
    const signal = calculateDouyinSelection(record('250', [{ price_after_coupon_fen: 7900 }, sku]));
    expect(signal).toMatchObject({ skuIndex: 1, skuSource: 'hot_price', priceFen: 3099 });
  });

  it('uses the amount verified in price text when the structured fields have no promotion label', () => {
    const sku = { price_text: '火爆价¥59.9券后价¥39.99', price_before_coupon_fen: 5990, price_after_coupon_fen: 3999 };
    const signal = calculateDouyinSelection(record('250', [sku]));
    expect(signal).toMatchObject({ skuIndex: 0, skuSource: 'hot_price', priceFen: 5990 });
  });

  it('uses a hot-marked SKU or the first usable fallback with its coupon price', () => {
    const skus = [
      { available: false, price_after_coupon_fen: 3000 },
      { price_before_coupon_fen: 2599 },
      { name: '首个有券后价规格', price_after_coupon_fen: 5500 },
      { name: '热销规格', hot: true, price_after_coupon_fen: 6000 },
    ];
    expect(calculateDouyinSelection(record('250', skus))).toMatchObject({ skuIndex: 3, skuSource: 'hot' });
    const fallback = record('250', skus.slice(0, 3));
    expect(calculateDouyinSelection(fallback)).toMatchObject({ skuIndex: 2, skuSource: 'fallback', priceFen: 5500 });
    expect(eligibleDouyinSkus(fallback)).toEqual([
      { index: 1, name: '规格 2', priceFen: 2599 },
      { index: 2, name: '首个有券后价规格', priceFen: 5500 },
    ]);
  });

  it('averages all priced hot SKUs, including a single-quote SKU, without rounding the threshold comparison', () => {
    const row = record('100', [
      { name: '普通款', price_after_coupon_fen: 9000 },
      { name: '热销款 A', hot: true, price_before_coupon_fen: 5000 },
      { name: '热销款 B', hot: true, price_after_coupon_fen: 5001 },
      { name: '已售罄热销款', hot: true, available: false, price_after_coupon_fen: 8000 },
    ]);
    const signal = calculateDouyinSelection(row, { ratioPercent: 30, thresholdYuan: 1500.15 });
    expect(signal).toMatchObject({ skuCount: 2, skuIndex: null, skuSource: 'hot', priceFen: 5000.5, estimatedYuan: 1500.15, marked: false });
    expect(douyinSelectionLabel(signal)).toBe('商品卡测算未达标');
    expect(calculateDouyinSelection(row, DEFAULT_DOUYIN_SELECTION_RULE, 1))
      .toMatchObject({ skuCount: 1, skuIndex: 1, skuSource: 'manual', priceFen: 5000 });
  });

  it('averages multiple explicitly labeled hot prices', () => {
    const row = record('100', [
      { price_before_coupon_fen: 6000, price_before_label: '火爆价', price_after_coupon_fen: 3000 },
      { price_after_coupon_fen: 8000, price_after_label: '火爆价' },
      { hot: true, price_after_coupon_fen: 9000 },
    ]);
    const signal = calculateDouyinSelection(row);
    expect(signal).toMatchObject({ skuCount: 2, skuSource: 'hot_price', priceFen: 7000, marked: true });
    expect(douyinSelectionLabel(signal)).toBe('商品卡测算达标');
  });

  it('supports a chosen SKU and uses strictly greater than after rule adjustment', () => {
    const row = record('100-200', [
      { price_after_coupon_fen: 3000, price_after_label: '火爆价' },
      { price_after_coupon_fen: 5000 },
    ]);
    expect(calculateDouyinSelection(row, DEFAULT_DOUYIN_SELECTION_RULE, 1))
      .toMatchObject({ skuIndex: 1, skuSource: 'manual', estimatedYuan: 1500, marked: false });
    expect(calculateDouyinSelection(row, { ratioPercent: 30, thresholdYuan: 1499.99 }, 1))
      .toMatchObject({ estimatedYuan: 1500, marked: true });
    expect(calculateDouyinSelection(row, { ratioPercent: 10.15, thresholdYuan: 507.5 }, 1))
      .toMatchObject({ estimatedYuan: 507.5, marked: false });
    expect(calculateDouyinSelection(row, DEFAULT_DOUYIN_SELECTION_RULE, 7)).toMatchObject({ skuIndex: 0, skuSource: 'hot_price' });
  });

  it('retains zero and leaves missing or unavailable evidence unmarked', () => {
    const row = record('0-250', [{ price_after_coupon_fen: 5000 }]);
    expect(calculateDouyinSelection(row)).toMatchObject({ salesMinimum: 0, estimatedYuan: 0, marked: false });
    const missing = calculateDouyinSelection(record('未知', row.skus));
    expect(missing).toMatchObject({ salesMinimum: null, estimatedYuan: null, marked: false });
    expect(douyinSelectionLabel(missing)).toBe('');
    expect(calculateDouyinSelection(record('250', [{ price_before_coupon_fen: 5000 }])))
      .toMatchObject({ salesMinimum: 250, skuIndex: 0, estimatedYuan: 3750, marked: true });
    expect(calculateDouyinSelection({ ...row, product_state: 'invalid' }))
      .toMatchObject({ skuIndex: null, estimatedYuan: null, marked: false });
    expect(eligibleDouyinSkus({ ...row, status: 'failed' })).toEqual([]);
  });

  it('normalizes the 30-day volume before applying price and ratio', () => {
    const row = record('5万-10万', [{ price_after_coupon_fen: 15172 }], {
      core_data_period_days: 30,
      core_data_date_text: '2026.11.14 - 2026.11.20',
      core_data_period: '7日',
    });
    expect(calculateDouyinSelection(row)).toMatchObject({
      salesMinimum: 50_000, periodDays: 30, dailySalesMinimum: 50_000 / 30,
      estimatedYuan: 75_860, marked: true,
    });
  });

  it('counts both endpoints in the observed date window ahead of the filter label', () => {
    const row = record('1千-2千', [{ price_after_coupon_fen: 3500 }], {
      core_data_date_text: '数据时间 2026.11.14 - 2026.11.20',
      core_data_period: '30日',
    });
    expect(calculateDouyinSelection(row)).toMatchObject({
      periodDays: 7, dailySalesMinimum: 1000 / 7, estimatedYuan: 1500, marked: false,
    });
  });

  it.each([['近７日', 7], ['昨日', 1], ['自定义', null], ['0日', null]])(
    'uses a valid detail period label %s when dates and day count are absent',
    (label, expected) => {
      const row = record('100-200', [{ price_after_coupon_fen: 6000 }], { core_data_period: label });
      expect(calculateDouyinSelection(row)).toMatchObject({
        periodDays: expected,
        dailySalesMinimum: expected === null ? null : 100 / expected,
        estimatedYuan: expected === null ? null : 1800 / expected,
        marked: expected === 1,
      });
    },
  );

  it('leaves unknown or invalid date periods unmarked even when volume is high', () => {
    const row = record('10万+', [{ price_after_coupon_fen: 5000 }], {
      core_data_date_text: '2026.02.30 - 2026.03.06',
    });
    expect(calculateDouyinSelection(row)).toMatchObject({
      salesMinimum: 100_000, periodDays: null, dailySalesMinimum: null, estimatedYuan: null, marked: false,
    });
  });

  it('compares before rounding the daily volume or estimated currency', () => {
    const row = record('45001', [{ price_after_coupon_fen: 1000 }], { core_data_period_days: 90 });
    const signal = calculateDouyinSelection(row, { ratioPercent: 30, thresholdYuan: 1500.03 });
    expect(signal.dailySalesMinimum).toBeCloseTo(45001 / 90);
    expect(signal.estimatedYuan).toBeCloseTo(1500.033333);
    expect(signal.marked).toBe(true);
  });
});
