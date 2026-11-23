import { fireEvent, render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
import * as mobile from '../api/mobileCollector';
import * as pdd from '../api/pddMobile';
import MobileCollector from '../pages/MobileCollector';

vi.mock('../api/mobileCollector', { spy: true });
vi.mock('../api/pddMobile', { spy: true });

it('loads and refreshes Douyin categories and retains the refreshed catalog across platform changes', async () => {
  const initial = { version: '1', categories: [{ name: '服务端初始类目' }], total: 1, updated_at: null };
  vi.mocked(mobile.fetchMobileCategories).mockResolvedValue(initial);
  vi.mocked(mobile.refreshMobileCategories).mockResolvedValue({
    ...initial, version: '2', categories: [{ name: '服务端更新类目' }], changed: true,
  });
  vi.mocked(mobile.fetchMobileDevices).mockResolvedValue({ devices: [] });
  vi.mocked(mobile.fetchMobileJobs).mockResolvedValue({ jobs: [] });
  vi.mocked(pdd.pddStatus).mockResolvedValue({ devices: [], tasks: [] });

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/mobile-collector']}>
    <MobileCollector />
  </MemoryRouter></QueryClientProvider>);

  expect(await screen.findByRole('option', { name: '服务端初始类目' })).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '更新类目' }));
  expect(await screen.findByRole('option', { name: '服务端更新类目' })).toBeInTheDocument();
  expect(mobile.refreshMobileCategories).toHaveBeenCalledTimes(1);

  const platform = screen.getByRole('combobox', { name: '平台' });
  fireEvent.change(platform, { target: { value: 'pdd' } });
  expect(await screen.findByRole('heading', { name: '新建拼多多任务' })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: '更新类目' })).toBeNull();
  expect(pdd.pddStatus).toHaveBeenCalled();
  fireEvent.change(platform, { target: { value: 'douyin' } });
  expect(await screen.findByRole('option', { name: '服务端更新类目' })).toBeInTheDocument();
});
