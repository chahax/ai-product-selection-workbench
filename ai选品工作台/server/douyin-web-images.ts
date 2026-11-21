import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, realpath, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { promisify } from 'node:util';

const DETAIL_PATH = '/ecommerce/trade/detail/index.html';
const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
const runBrowser = promisify(execFile);

export type WebProductImages = {
  productId: string;
  productUrl: string;
  coverage: 'cover_only' | 'page_images';
  images: { sourceUrl: string; mime: string; bytes: Buffer }[];
};

function allowedShare(url: URL) {
  return url.protocol === 'https:' && !url.username && !url.password && !url.port
    && (url.hostname === 'v.douyin.com' && /^\/[\w-]+\/?$/.test(url.pathname)
      || url.hostname === 'haohuo.jinritemai.com' && url.pathname === DETAIL_PATH
          && /^\d{10,25}$/.test(url.searchParams.get('id') ?? ''));
}

function allowedImage(url: URL) {
  return url.protocol === 'https:' && !url.username && !url.password && !url.port
    && url.hostname.endsWith('.ecombdimg.com') && url.pathname.startsWith('/img/');
}

async function follow(start: string, allowed: (url: URL) => boolean, signal: AbortSignal) {
  let url = new URL(start);
  for (let hop = 0; hop < 6; hop++) {
    if (!allowed(url)) throw new Error('链接不属于可读取的抖音商品或图片地址');
    const response = await fetch(url, { signal, redirect: 'manual' });
    if (response.ok) return { url, response };
    const next = response.headers.get('location');
    await response.body?.cancel();
    if (response.status < 300 || response.status >= 400 || !next) throw new Error('抖音网页或图片暂时不可用');
    url = new URL(next, url);
  }
  throw new Error('商品链接的跳转未完成');
}

async function limitedBytes(response: Response, max: number) {
  if (!response.body) return Buffer.alloc(0);
  const reader = response.body.getReader();
  const buffers: Uint8Array[] = [];
  let bytes = 0;
  try {
    while (true) {
      const part = await reader.read();
      if (part.done) return Buffer.concat(buffers);
      bytes += part.value.length;
      if (bytes > max) throw new Error('网页图片超过大小限制');
      buffers.push(part.value);
    }
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

function imageType(bytes: Buffer) {
  if (bytes.length < 12) return '';
  if (bytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'))) return 'image/png';
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg';
  if (bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  return '';
}

function imageAddresses(value: unknown): string[] {
  const values = Array.isArray(value) ? value : [value];
  return [...new Set(values.flatMap(item => {
    const address = typeof item === 'string' ? item : item?.contentUrl ?? item?.url;
    try { return typeof address === 'string' && allowedImage(new URL(address)) ? [address] : []; }
    catch { return []; }
  }))].slice(0, 24);
}

function pageImages(html: string) {
  for (const [, body] of html.matchAll(/<script\b[^>]*\btype=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)) {
    try {
      const root = JSON.parse(body);
      const nodes = Array.isArray(root) ? root : Array.isArray(root?.['@graph']) ? root['@graph'] : [root];
      const product = nodes.filter(node => node?.['@type'] === 'Product');
      if (product.length === 1) {
        const images = imageAddresses(product[0].image);
        if (images.length) return images;
      }
    } catch { /* Try other structured blocks. */ }
  }
  return [];
}

function chromeExecutable() {
  const override = process.env.DOUYIN_CHROME_EXECUTABLE;
  if (override) return override;
  return [process.env.ProgramFiles, process.env['ProgramFiles(x86)'], process.env.LOCALAPPDATA]
    .filter((dir): dir is string => !!dir)
    .map(dir => join(dir, 'Google', 'Chrome', 'Application', 'chrome.exe'))
    .find(file => existsSync(file));
}

async function renderedCarousel(url: URL, signal: AbortSignal) {
  const executable = chromeExecutable();
  if (!executable || !allowedShare(url) || url.hostname !== 'haohuo.jinritemai.com') return [];
  const tempRoot = await realpath(tmpdir());
  const profile = await mkdtemp(join(tempRoot, 'workbench-douyin-'));
  try {
    const { stdout } = await runBrowser(executable, [
      '--headless=new', '--dump-dom', '--virtual-time-budget=12000',
      '--disable-extensions', '--no-first-run', '--no-default-browser-check',
      `--user-data-dir=${profile}`, url.href,
    ], { windowsHide: true, timeout: 15_000, maxBuffer: 8 * 1024 * 1024, signal });
    const images: string[] = [];
    for (const [tag] of stdout.matchAll(/<[^<>]+\bclass="[^"]*\bhead-figure__media-view__content(?=[\s"])[^"]*"[^<>]*>/g)) {
      const style = tag.match(/\bstyle="([^"]*)"/)?.[1]?.replaceAll('&quot;', '"').replaceAll('&amp;', '&');
      const source = style?.match(/\bbackground-image:\s*url\(["']?(https:\/\/[^\s"'()]+)["']?\)/)?.[1];
      if (source) images.push(source);
    }
    return imageAddresses(images);
  } catch {
    return [];
  } finally {
    if (dirname(profile) === tempRoot) {
      await rm(profile, { recursive: true, force: true, maxRetries: 3 }).catch(() => undefined);
    }
  }
}

export async function fetchWebProductImages(link: string, render = false): Promise<WebProductImages> {
  const signal = AbortSignal.timeout(25_000);
  const { url, response } = await follow(link, allowedShare, signal);
  const productId = url.searchParams.get('id');
  if (url.hostname !== 'haohuo.jinritemai.com' || !productId || !/^\d{10,25}$/.test(productId)) {
    await response.body?.cancel();
    throw new Error('没有解析到抖音商品 ID');
  }
  const productUrl = `https://haohuo.jinritemai.com${DETAIL_PATH}?id=${productId}`;
  const structured = pageImages((await limitedBytes(response, 3 * 1024 * 1024)).toString('utf8'));
  const carousel = render && structured.length < 2 ? await renderedCarousel(url, signal).catch(() => []) : [];
  let mirrors: string[] = [];
  try { mirrors = imageAddresses(JSON.parse(url.searchParams.get('goods_detail') ?? '')?.img?.url_list); }
  catch { /* Some product links carry no sharing cover. */ }
  const gallery = carousel.length ? carousel : structured;
  const groups = gallery.length
    ? gallery.map((address, index) => index === 0
      ? [address, ...mirrors.filter(mirror => new URL(mirror).pathname === new URL(address).pathname && mirror !== address)]
      : [address])
    : mirrors.length ? [mirrors] : [];
  const images: WebProductImages['images'] = [];
  for (const addresses of groups) {
    for (const address of addresses) {
      try {
        const image = await follow(address, allowedImage, signal);
        const bytes = await limitedBytes(image.response, MAX_IMAGE_BYTES);
        const mime = imageType(bytes);
        if (!mime) continue;
        images.push({ sourceUrl: image.url.href, mime, bytes });
        break;
      } catch { /* Try a mirror of the same image. */ }
    }
  }
  const completeGallery = (carousel.length > 0 || structured.length > 1) && images.length === gallery.length;
  return { productId, productUrl, coverage: completeGallery ? 'page_images' : 'cover_only', images };
}
