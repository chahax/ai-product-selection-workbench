export interface SelectionInput {
  category: string;
  keywords: string[];
  exclude_words?: string[];
  source_url?: string;
  price_min?: number;
  price_max?: number;
  candidate_limit: number;
  deep_limit: number;
}

export type SelectionStatus = 'ready' | 'running' | 'paused' | 'complete';
export type CollectionStatus = 'pending' | 'running' | 'done' | 'partial' | 'failed' | 'not_selected';

export interface SelectionSku {
  name: string;
  prices: Record<string, number>;
  price: number;
  price_type: string;
  captured_at?: string | null;
}

interface HeatSignal {
  label: string;
}

export interface SelectionCosts {
  cost: number | null;
  expected_receipt: number | null;
  shipping_cost: number | null;
  platform_fee: number | null;
  promotion_cost: number | null;
  after_sales_cost: number | null;
}

export interface SelectionCandidate extends Partial<SelectionCosts> {
  goods_id: string;
  title: string;
  source_url: string;
  keyword: string;
  price: number | null;
  page_price?: number | null;
  shop_name?: string;
  title_labels: string[];
  buyers_text?: string | null;
  heat?: { buyers?: HeatSignal | null; sold?: HeatSignal | null; views?: HeatSignal | null };
  rank?: number | null;
  score: number | null;
  score_status?: 'qualified' | 'review' | 'out_of_range' | 'excluded';
  deep_selected: boolean;
  overview_state: CollectionStatus;
  spec_state: CollectionStatus;
  offer_count: number;
  recommended_variant?: string | null;
  recommended_sku?: SelectionSku | null;
  reasons: string[];
  missing: string[];
  overview_captured_at?: string | null;
  list_captured_at?: string | null;
  estimated_profit?: number | null;
  estimated_profit_rate?: number | null;
}

export interface SelectionSummary {
  discovered: number;
  screened: number;
  ranked: number;
  deep_selected: number;
  deep_captured: number;
  pending_verification: number;
  jobs_done: number;
  jobs_total: number;
  message?: string;
}

export interface SelectionJob {
  id: string;
  stage: 'discovery' | 'overview' | 'spec';
  status: CollectionStatus;
  keyword?: string;
  message?: string;
  goods_id?: string;
}

export interface SelectionTask {
  id: string;
  category: string;
  keywords: string[];
  exclude_words?: string[];
  source_url?: string;
  price_min?: number | null;
  price_max?: number | null;
  candidate_limit: number;
  deep_limit: number;
  created_at: string;
  updated_at: string;
  status: SelectionStatus;
  summary: SelectionSummary;
  candidates?: SelectionCandidate[];
  jobs?: SelectionJob[];
}

export interface SelectionAiReview {
  goods_id: string;
  normalized_title: string;
  brand?: string;
  model?: string;
  specification?: string;
  duplicate_hint: string;
  reason: string;
  risk: string;
}

export interface SelectionAiResult {
  configured: boolean;
  status: string;
  items: SelectionAiReview[];
  message?: string;
}
