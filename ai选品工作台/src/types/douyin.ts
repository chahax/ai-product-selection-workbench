import type { DouyinSelectionSignal } from '../utils/douyinSelection';
import type { GroupInfo, TagInfo } from './index';

export interface WebImageFetchResult {
  status: 'cover_only' | 'page_images' | 'unavailable' | 'no_link';
  outcome: 'updated' | 'unchanged' | 'retained' | 'unavailable' | 'no_link';
  image_count: number;
  product_id?: string;
}

export interface DouyinSku {
  name?: string;
  specifications?: Record<string, string>;
  price_before_coupon_fen?: number;
  price_after_coupon_fen?: number;
  price_before_label?: string;
  price_after_label?: string;
  price_text?: string;
  promotion_label?: string;
  hot?: boolean;
  available?: boolean;
}

export interface DouyinRecord {
  record_id: string;
  revision?: number;
  captured_at: string;
  batch_id?: string;
  product_id?: string;
  platform: 'douyin_mall';
  status?: string;
  product_state?: string;
  invalid_reason?: string;
  collection_error?: string;
  douyin_share_url?: string;
  douyin_product_url?: string;
  source_url?: string;
  source_title?: string;
  source_date_range?: string;
  title?: string;
  kaogujia_product_name?: string;
  douyin_product_name?: string;
  kaogujia_shop_name?: string;
  douyin_shop_name?: string;
  shop_name?: string;
  source_card?: { category_text?: string; date_label?: string };
  source_detail?: {
    shop_name?: string;
    core_data_period?: string;
    core_data_period_days?: number;
    core_data_period_source?: string;
    core_data_date_text?: string;
    core_data?: Record<string, string>;
  };
  product_tags?: string[];
  product_images?: { position: number }[];
  stored_product_images?: { position: number; url: string; source_url?: string }[];
  stored_image_source?: 'web' | 'phone';
  web_image_status?: 'pending' | 'cover_only' | 'page_images' | 'unavailable';
  stored_cover_url?: string;
  library_original_price?: number | null;
  library_price?: number | null;
  library_price_max?: number | null;
  library_price_modified?: boolean;
  library_price_label?: '券前价' | '券后价' | '工作台报价';
  library_sales?: number | null;
  library_shop?: string;
  tags?: TagInfo[];
  groups?: GroupInfo[];
  selection_signal?: DouyinSelectionSignal;
  selection_labels?: string[];
  skus: DouyinSku[];
}

