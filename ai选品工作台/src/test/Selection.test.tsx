import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, expect, it, vi } from 'vitest';
import Selection from '../pages/Selection';
import { createSelectionTask, fetchSelectionTask, fetchSelectionTasks, updateSelectionCost } from '../api';
import type { SelectionTask } from '../types/selection';

vi.mock('../api', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api')>(),
  createSelectionTask: vi.fn(),
  fetchSelectionTask: vi.fn(),
  fetchSelectionTasks: vi.fn(),
  updateSelectionCost: vi.fn(),
  fetchCaptureToggle: vi.fn().mockResolvedValue({ enabled: true }),
}));

const emptyTask: SelectionTask = {
  id: 'sample', category: '小家电', keywords: ['吹风机'], candidate_limit: 50, deep_limit: 15,
  status: 'ready', created_at: '2026-11-17T09:00:00', updated_at: '2026-11-17T09:00:00',
  summary: {
    discovered: 0, screened: 0, ranked: 0, deep_selected: 0, deep_captured: 0,
    pending_verification: 0, jobs_done: 0, jobs_total: 1,
  },
  candidates: [],
};

const completed: SelectionTask = {
  ...emptyTask, status: 'complete',
  summary: { ...emptyTask.summary, discovered: 1, ranked: 1, deep_selected: 1, deep_captured: 1 },
  candidates: [{
    goods_id: '123', title: '测试吹风机', source_url: '', keyword: '吹风机', price: 50, page_price: 50,
    title_labels: ['1天内200+人买过'], score: 60, deep_selected: true, overview_state: 'done', spec_state: 'done',
    offer_count: 1, reasons: [], missing: [], overview_captured_at: '2026-11-17T09:00:00',
    recommended_sku: { name: '基础款', price: 39.9, price_type: 'coupon', prices: { coupon: 39.9 } },
  }],
};

function renderSelection() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/selection']}><Selection /></MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
});

it('keeps a launch link after the browser blocks the collection tab', async () => {
  vi.spyOn(window, 'open').mockReturnValue(null);
  vi.mocked(fetchSelectionTasks).mockResolvedValue({ tasks: [] });
  vi.mocked(fetchSelectionTask).mockResolvedValue(emptyTask);
  vi.mocked(createSelectionTask).mockResolvedValue(emptyTask);
  renderSelection();
  fireEvent.change(screen.getByLabelText('类目'), { target: { value: '小家电' } });
  fireEvent.change(screen.getByLabelText('关键词'), { target: { value: '吹风机、吹风机，便携吹风机' } });
  fireEvent.change(screen.getByLabelText(/排除词/), { target: { value: '二手、配件' } });
  fireEvent.click(screen.getByRole('button', { name: '开始选品' }));

  expect(await screen.findByRole('link', { name: '打开采集页' }))
    .toHaveAttribute('href', '/api/selection/tasks/sample/worker?resume=1');
  expect(vi.mocked(createSelectionTask).mock.calls[0]?.[0]).toMatchObject({
    keywords: ['吹风机', '便携吹风机'], exclude_words: ['二手', '配件'],
  });
});

it('shows the page price and confirmed SKU price with their respective labels', async () => {
  vi.mocked(fetchSelectionTasks).mockResolvedValue({ tasks: [completed] });
  vi.mocked(fetchSelectionTask).mockResolvedValue(completed);
  renderSelection();

  const cells = within(await screen.findByRole('row', { name: /测试吹风机/ })).getAllByRole('cell');
  expect(cells[2]).toHaveTextContent('¥50.00商品页价');
  expect(cells[4]).toHaveTextContent('¥39.90券后价 · 基础款');
  expect(screen.getByRole('link', { name: '导出 CSV' })).toHaveAttribute('href', '/api/selection/tasks/sample/export');
});

it('saves known zero fees separately from blank amounts and displays only returned profit', async () => {
  vi.mocked(fetchSelectionTasks).mockResolvedValue({ tasks: [completed] });
  vi.mocked(fetchSelectionTask).mockResolvedValue(completed);
  vi.mocked(updateSelectionCost).mockImplementation(async (_id, _goodsId, costs) => ({
    ...completed,
    candidates: [{
      ...completed.candidates![0], ...costs,
      estimated_profit: costs.platform_fee === 0 ? 10 : null,
      estimated_profit_rate: costs.platform_fee === 0 ? 0.25 : null,
    }],
  }));
  renderSelection();
  fireEvent.click(await screen.findByText('查看依据'));
  fireEvent.click(screen.getByText('利润试算'));
  for (const [label, value] of Object.entries({
    '实收（扣下列费用前）': '40', '进货成本（同规格）': '30', 运费: '0', 推广费用: '0', 售后预留: '0',
  })) {
    fireEvent.change(screen.getByLabelText(label), { target: { value } });
  }
  fireEvent.click(screen.getByRole('button', { name: '保存试算数据' }));
  await screen.findByText('试算数据已保存。');

  expect(vi.mocked(updateSelectionCost).mock.calls[0]?.[2]).toMatchObject({ shipping_cost: 0, platform_fee: null });
  expect(screen.queryByText('预计每单利润')).not.toBeInTheDocument();

  fireEvent.change(screen.getByLabelText('平台费用'), { target: { value: '0' } });
  fireEvent.click(screen.getByRole('button', { name: '保存试算数据' }));
  expect(await screen.findByText('预计每单利润')).toBeVisible();
  expect(screen.getByText(/25\.0%/)).toBeVisible();
});
