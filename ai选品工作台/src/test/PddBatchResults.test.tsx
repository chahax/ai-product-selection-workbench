import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
import PddBatchResults from '../pages/PddBatchResults';
import { pddBatch } from '../api/pddMobile';

vi.mock('../api/pddMobile', async original => ({
  ...await original<typeof import('../api/pddMobile')>(), pddBatch: vi.fn(),
}));

it('keeps weekly-only sales separate from displayed daily sales and uses the unrounded threshold decision', async () => {
  vi.mocked(pddBatch).mockResolvedValue({
    id: 'test', device: 'phone', state: '已完成',
    settings: { route: '百亿补贴 → 个护', quantity: 5, threshold: '1500', share: '30', weekly: true, pricing: '火标规格最高价' },
    items: [
      { id: 'item-1', title: '日销临界', daily: 100, estimate: '1500.00', qualifies: false, imageState: 'ready' },
      { title: '仅有周销', weekly: 701, estimate: '1500.00', qualifies: true },
      { title: '无标签', salesStatus: '页面未展示日/周销量' },
    ],
  });
  render(<QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/result/test']}>
    <Routes><Route path="/result/:taskId" element={<PddBatchResults />} /></Routes>
  </MemoryRouter></QueryClientProvider>);
  expect(await screen.findByText('已结束（未采满）')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: '下载图片' })).toHaveAttribute('href', '/api/workbench/pdd-mobile/tasks/test/images/item-1.jpg');
  expect(screen.getByRole('img', { name: '日销临界' })).toBeInTheDocument();
  expect(screen.getByText('页面未展示日/周销量')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '页面日销 ≥ 100（1）' }));
  expect(screen.getByText('日销临界')).toBeInTheDocument();
  expect(screen.queryByText('仅有周销')).toBeNull();
  fireEvent.click(screen.getByRole('button', { name: '预估日销售额 > 1500 元（1）' }));
  expect(screen.getByText('仅有周销')).toBeInTheDocument();
  expect(screen.queryByText('日销临界')).toBeNull();
  expect(screen.getByRole('link', { name: '导出金额达标 CSV' })).toHaveAttribute('href', '/api/workbench/pdd-mobile/tasks/test/qualified.csv');
});
