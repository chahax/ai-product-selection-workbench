import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate, useParams } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from './components/Toast';
import Layout from './components/Layout';
import ErrorBoundary from './components/ErrorBoundary';
import { ConfirmProvider } from './components/Confirm';

const Dashboard = lazy(() => import('./pages/Dashboard'));
const Products = lazy(() => import('./pages/Products'));
const MobileCollector = lazy(() => import('./pages/MobileCollector'));
const PddBatchResults = lazy(() => import('./pages/PddBatchResults'));
const DouyinLibrary = lazy(() => import('./pages/DouyinLibrary'));
const ProductDetail = lazy(() => import('./pages/ProductDetail'));
const Selection = lazy(() => import('./pages/Selection'));
const ProfitCalculator = lazy(() => import('./pages/ProfitCalculator'));
const Export = lazy(() => import('./pages/Export'));
const Import = lazy(() => import('./pages/Import'));
const Settings = lazy(() => import('./pages/Settings'));
const AiEditor = lazy(() => import('./pages/AiEditor'));
const Compare = lazy(() => import('./pages/Compare'));
const AiCopywriting = lazy(() => import('./pages/AiCopywriting'));
const Logs = lazy(() => import('./pages/Logs'));
const Monitor = lazy(() => import('./pages/Monitor'));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 30_000 },
  },
});

function LegacyDouyinRoute() {
  const { recordId } = useParams();
  return <Navigate replace to={recordId ? `/products/douyin/${encodeURIComponent(recordId)}` : '/products?platform=douyin'} />;
}

function PageSkeleton() {
  return (
    <div className="page-skeleton">
      <div className="skeleton skeleton-line" />
      <div className="skeleton skeleton-line-sm" />
    </div>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <ErrorBoundary>
          <ConfirmProvider>
            <BrowserRouter basename={import.meta.env.BASE_URL}>
              <Suspense fallback={<PageSkeleton />}>
                <Routes>
                  <Route element={<Layout />}>
                    <Route index element={<Dashboard />} />
                    <Route path="products" element={<Products />} />
                    <Route path="mobile-collector" element={<MobileCollector />} />
                    <Route path="mobile-collector/pdd/:taskId" element={<PddBatchResults />} />
                    <Route path="products/douyin/:recordId" element={<DouyinLibrary />} />
                    <Route path="douyin" element={<LegacyDouyinRoute />} />
                    <Route path="douyin/:recordId" element={<LegacyDouyinRoute />} />
                    <Route path="product/:id" element={<ProductDetail />} />
                    <Route path="selection" element={<Selection />} />
                    <Route path="profit" element={<ProfitCalculator />} />
                    <Route path="export" element={<Export />} />
                    <Route path="import" element={<Import />} />
                    <Route path="logs" element={<Logs />} />
                    <Route path="settings" element={<Settings />} />
                    <Route path="ai-editor" element={<AiEditor />} />
                    <Route path="compare" element={<Compare />} />
                    <Route path="ai-copywriting" element={<AiCopywriting />} />
                    <Route path="monitor" element={<Monitor />} />
                  </Route>
                </Routes>
              </Suspense>
            </BrowserRouter>
          </ConfirmProvider>
        </ErrorBoundary>
      </ToastProvider>
    </QueryClientProvider>
  );
}

