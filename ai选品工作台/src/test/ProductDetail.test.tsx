import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
import ProductDetail from '../pages/ProductDetail';

vi.mock('../api', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api')>(),
  fetchProduct: vi.fn(async (id: string) => ({
    goods_id: id,
    title: 'Sample product',
    price: 10,
    main_images: [],
    detail_images: [],
    selling_points: [],
    specs: id === 'sample' ? {} : { 款式: ['基础款', '折叠款'], 颜色: ['白色'] },
    spec_capture: id === 'with-quotes' ? {
      groups: { 款式: ['基础款', '折叠款'] },
      offers: [{ selected: { 款式: '基础款', 颜色: '白色' }, prices: { coupon: 10 }, captured_at: '' }],
    } : undefined,
  })),
  fetchAiPresets: vi.fn().mockResolvedValue([]),
  fetchProductAiImages: vi.fn().mockResolvedValue([]),
  fetchTags: vi.fn().mockResolvedValue([]),
  fetchProductTags: vi.fn().mockResolvedValue([]),
}));

function renderProduct(id: string) {
  const client = new QueryClient();
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/product/${id}`]}>
        <Routes>
          <Route path="/product/:id" element={<ProductDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

it('loads the product detail even when the gallery is empty', async () => {
  renderProduct('sample');

  expect(await screen.findByText('Sample product')).toBeVisible();
  expect(screen.getByText('暂无图片')).toBeVisible();
});

it('lists only options missing from the quote table in the same card', async () => {
  renderProduct('with-quotes');

  const quotes = within(await screen.findByRole('region', { name: '规格报价' }));
  expect(within(quotes.getByRole('table')).getByText('基础款')).toBeVisible();
  expect(quotes.getByText('折叠款')).toBeVisible();
  expect(screen.getAllByText('折叠款')).toHaveLength(1);
  expect(screen.getAllByText('基础款')).toHaveLength(1);
  expect(screen.getAllByText('白色')).toHaveLength(1);
});

it('keeps spec options visible before quotes are captured', async () => {
  renderProduct('without-quotes');

  const options = within(await screen.findByRole('region', { name: '规格选项' }));
  expect(options.getByText('暂无规格报价')).toBeVisible();
  expect(options.getByText('基础款')).toBeVisible();
  expect(options.getByText('折叠款')).toBeVisible();
});
