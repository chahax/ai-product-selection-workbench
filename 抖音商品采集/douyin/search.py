from __future__ import annotations

import difflib
import base64
import io
import json
import pathlib
import re
import subprocess
import time
import shutil
from dataclasses import dataclass


SUPPORT = pathlib.Path(__file__).resolve().parent
CLIPBOARD = SUPPORT / 'emulator_clipboard.cjs'
OCR = SUPPORT / 'ocr.ps1'


@dataclass
class Line:
    text: str
    key: str
    x: float
    y: float
    w: float
    h: float


class AppIntervention(RuntimeError):
    def __init__(self, status, reason):
        super().__init__(reason)
        self.status = status


def check_intervention(lines):
    candidates = [line.key for line in lines if line.y <= 1000 and len(line.key) <= 65]
    prompts = ('身份验证', '人机验证', '请完成安全验证', '实名认证', '滑动拼图')
    if any(prompt in title for prompt in prompts for title in candidates):
        raise AppIntervention('identity_verification', '抖音要求在设备上完成身份或安全验证')
    logins = ('手机号登录', '手机号一键登录', '本机号码一键登录', '登录后继续', '扫码登录', '验证码登录')
    if any(prompt in title for prompt in logins for title in candidates):
        raise AppIntervention('app_login_required', '抖音要求先在设备上登录')


def screenshot(phone, worker, target, ocr=True):
    image = phone.screen().resize((540, 1200))
    if not ocr:
        if target is not None:
            image.save(target)
        return image, []
    buffer = io.BytesIO()
    image.save(buffer, format='PNG')
    deadline = getattr(phone, 'deadline', None)
    seconds = max(.1, min(5, deadline - time.monotonic())) if deadline else 5
    raw = subprocess.run(
        ['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', str(OCR), '-ReadStdin'],
        input=base64.b64encode(buffer.getvalue()),
        check=True, capture_output=True, timeout=seconds).stdout
    parsed = json.loads(raw.decode('utf-8-sig'))
    report = parsed[0] if isinstance(parsed, list) else parsed
    lines = []
    for line in report.get('lines', []):
        words = line['words']
        if not words:
            continue
        x = min(word['x'] for word in words)
        y = min(word['y'] for word in words)
        right = max(word['x'] + word['w'] for word in words)
        bottom = max(word['y'] + word['h'] for word in words)
        lines.append(Line(line['text'], worker.normalize(line['text']), x, y, right - x, bottom - y))
        if 28 <= y <= 95:
            rightwords = [word for word in words if word['x'] >= 443]
            key = worker.normalize(''.join(word['text'] for word in rightwords))
            if key == '搜索':
                left = min(word['x'] for word in rightwords)
                right = max(word['x'] + word['w'] for word in rightwords)
                lines.append(Line('搜索',key,left,y,right-left,bottom-y))
    check_intervention(lines)
    if target is not None:
        image.save(target)
    return image, lines


def near(lines, key, min_x=0, min_y=0, max_x=540, max_y=1200):
    return next((line for line in lines if line.key == key and min_x <= line.x <= max_x
                 and min_y <= line.y <= max_y), None)


def tap_line(phone, line):
    phone.tap(round((line.x + line.w / 2) * 2), round((line.y + line.h / 2) * 2))


def is_loading(image):
    for center in range(619,683,4):
        samples = [image.getpixel((x,center+dy))[:3] for x,dy in ((242,-4),(297,-4),(270,22))]
        if all(33 <= min(pixel) <= max(pixel) <= 90 for pixel in samples):
            return True
    return False

def result_tabs(lines):
    return any(line.key in ('商品','全部','综合','销量','价格','相关商品','找到相似商品')
               for line in lines if 90 <= line.y <= 210)

def matching_caption(lines, query):
    captions = [line.key for line in lines if 28 <= line.y <= 95 and line.x < 200]
    if not query:
        return False
    return any(query in caption or difflib.SequenceMatcher(
        None, query, caption, autojunk=False).find_longest_match().size >= min(9, len(query))
        for caption in captions)


def results_present(lines):
    return any(line.y >= 205 and (
        '已售' in line.key or '券后价' in line.key or '进店' in line.key
        or re.search(r'[¥￥]\s*\d', line.text)
    ) for line in lines)


def search_results(phone, worker, folder, query, progress):
    limit = time.monotonic() + 14
    resubmitted = False
    while True:
        image, lines = screenshot(phone, worker, folder / '抖音搜索结果.png')
        if is_anr(lines):
            screenshot(phone, worker, folder / '抖音无响应.png', ocr=False)
            raise RuntimeError('Android 显示抖音无响应')
        loading = is_loading(image)
        tabs = result_tabs(lines)
        no_results = any('没有搜索到' in line.key or '暂无搜索结果' in line.key for line in lines)
        read_error = any('网络异常' in line.key for line in lines)
        results = tabs and results_present(lines)
        if matching_caption(lines, query) and (loading or tabs or no_results or read_error):
            progress['search_submitted'] = True
            progress['search_stage'] = 'search_response'
        if no_results or results:
            if progress['search_submitted']:
                progress['search_result_received'] = True
                progress['search_stage'] = 'result_screen'
            return lines
        if read_error:
            raise RuntimeError('抖音搜索页显示网络异常')
        pending = [line for line in lines if 18 <= line.x <= 58 and 115 <= line.y <= 970 and len(line.key) > 4]
        if not loading and not tabs and len(pending) >= 4 and not resubmitted:
            submit = near(lines, '搜索', 430, 28, 540, 100)
            if submit is not None:
                tap_line(phone, submit)
                resubmitted = True
                time.sleep(.6)
        if time.monotonic() >= limit:
            reason = '抖音搜索结果持续加载' if loading or tabs else '未出现商品搜索结果列表'
            raise RuntimeError(reason)
        time.sleep(.7)


def is_anr(lines):
    return any("isntresponding" in line.key or '应用无响应' in line.key or '抖音无响应' in line.key for line in lines)

def app_starting(image):
    return all(max(image.getpixel(point)[:3]) < 28 for point in ((10,250),(525,250),(10,900),(525,900)))

def input_editor(phone, worker, images, folder):
    for _ in range(6):
        state = phone.top()
        if phone.is_app_activity(state) and re.search(r'(?:Login|SignIn)Activity\b', state):
            raise AppIntervention('app_login_required', '抖音要求先在设备上登录')
        image, lines = screenshot(phone, worker, folder / '搜索入口.png')
        if is_anr(lines):
            screenshot(phone,worker,folder/'抖音无响应.png',ocr=False)
            raise RuntimeError('Android 显示抖音无响应')
        if app_starting(image):
            time.sleep(1.6)
            continue
        if 'detail.ui.DetailActivity' in state:
            phone.back()
            time.sleep(1.8)
            continue
        if images.preview_crop(image.resize((1080, 2400))) is not None:
            phone.back()
        elif any(near(lines, key, 0, 75, 265, 210) for key in ('扫一扫','我的钱包')):
            phone.back()
        elif is_loading(image):
            phone.back()
        elif worker.SCAN in state or 'BulletContainer' in state or worker.network_error_image(image.resize((1080, 2400))):
            phone.back()
        elif near(lines, '搜索', 425, 35, 540, 85):
            phone.tap(260, 118)
            time.sleep(1.8)
            return
        elif near(lines, '搜索', 375, 93, 540, 148):
            phone.tap(235, 228)
            time.sleep(1.8)
            return
        elif phone.is_product_activity(state):
            if near(lines, '加入购物车', 10, 1070, 540, 1160) or near(lines, '大图', 445, 150, 540, 850):
                phone.back()
            else:
                phone.tap(690, 120)
        elif near(lines, '商城', 60, 35, 520, 90) or near(lines, '首页', 0, 1080, 210, 1190):
            phone.tap(1005, 120)
        else:
            time.sleep(1.25)
            continue
        time.sleep(.65)
    raise RuntimeError('未定位抖音搜索框')


def mumu_text(query):
    return ' '.join(''.join(char if char.isalnum() else ' ' for char in query).split())


def set_query(phone, worker, images, folder, query):
    input_editor(phone, worker, images, folder)
    serial = phone.adb[-1]
    deadline = getattr(phone, 'deadline', None)
    seconds = max(.1, min(6, deadline - time.monotonic())) if deadline else 6
    ascii_text = re.fullmatch(r'[a-zA-Z0-9]+', query) is not None
    mumu_manager = getattr(phone, 'mumu_manager', None)
    if not ascii_text and mumu_manager is None:
        subprocess.run([shutil.which('node') or 'node', str(CLIPBOARD), serial, query], check=True, capture_output=True, timeout=seconds)
    try:
        phone.run('shell', 'input', 'keycombination', '113', '29', timeout=3)
    except subprocess.CalledProcessError:
        # Older Android builds may not provide Ctrl+A; the pasted text is checked below.
        pass
    if ascii_text:
        phone.run('shell','input','text',query)
    elif mumu_manager is not None:
        text = mumu_text(query)
        if not text:
            raise ValueError('MuMu 搜索词没有可输入的文字')
        limit = max(.1, min(8, deadline - time.monotonic())) if deadline else 8
        subprocess.run([str(mumu_manager), 'adb', '-v', str(phone.mumu_index), '-c', 'input_text', text],
                       check=True, capture_output=True, timeout=limit)
    else:
        phone.run('shell', 'input', 'keyevent', 'KEYCODE_PASTE')
    time.sleep(1.7)
    _, lines = screenshot(phone, worker, folder / '搜索词.png')
    q = worker.normalize(query)
    query_labels = [line.key for line in lines if 25 <= line.y <= 92 and line.x < 200]
    checked = any(q in word or q[:min(8,len(q))] in word or q[-min(8,len(q)):] in word for word in query_labels)
    if not checked:
        checked = any(difflib.SequenceMatcher(None,q,word,autojunk=False).find_longest_match().size >= min(9,len(q))
                      for word in query_labels)
    if not checked:
        checked = sum(q in line.key for line in lines if 115 <= line.y <= 805) >= 2
    search = near(lines, '搜索', 420, 28, 540, 100)
    if not checked and not any(line.x < 200 and 32 <= line.y <= 95 and len(line.key) >= 3 for line in lines):
        raise RuntimeError('搜索框没有读到输入内容')
    if search is None:
        raise RuntimeError('未识别搜索按钮')
    tap_line(phone, search)
    time.sleep(1.65)
    return checked


def candidate_matches(lines, expected, shop):
    result = []
    pool = sorted((line for line in lines if 23 <= line.y <= 1060), key=lambda x: (x.y, x.x))
    for line in pool:
        if line.y < 215 or len(line.key) < min(9, len(expected)):
            continue
        if line.x < 12 and line.w < 120:
            continue
        clean = re.sub(r'^(?:品牌|旗舰|官方正品|直播中){1,2}', '', line.key)
        if not clean:
            continue
        prefix = expected[:min(len(clean), len(expected), 25)]
        score = difflib.SequenceMatcher(None, prefix, clean[:len(prefix)], autojunk=False).ratio()
        if score < .68:
            continue
        right_shop = next((other for other in pool
                           if shop and shop in other.key and 14 <= other.y - line.y <= 155
                           and -25 <= other.x - line.x <= 55), None)
        result.append((score + (.25 if right_shop else 0), line, right_shop))
    best = []
    for score, line, store in sorted(result, key=lambda x: -x[0]):
        if all(abs(line.y - other[1].y) > 42 or abs(line.x - other[1].x) > 120 for other in best):
            best.append((score, line, store))
    return best[:4]


def active_product_id(phone, worker, pid):
    try:
        activity = phone.run('shell','dumpsys','activity','-a','activities',timeout=5).decode('utf-8','replace')
    except subprocess.SubprocessError:
        return False
    resumed = worker.resumed_activity(activity)
    if not phone.is_product_activity(resumed):
        return False
    active = re.search(r'ActivityRecord\{([a-f0-9]+) ', resumed)
    if active is None:
        return False
    lines = activity.splitlines()
    for pos, line in enumerate(lines):
        if '* Hist' in line and ('ActivityRecord{' + active[1] + ' ') in line:
            end = next((i for i in range(pos + 1, len(lines)) if re.search(r'\*\s+Hist\s+#',lines[i])),len(lines))
            record = '\n'.join(lines[pos:end])
            prefix = r'(?:[?&]id=|product(?:_?id)?[\s=:"%]+|goods(?:_?id)?[\s=:"%]+)'
            return re.search(prefix + re.escape(pid) + r'(?!\d)', record, re.IGNORECASE) is not None
    return False


def full_ocr_match(lines, literal):
    if not literal:
        return False
    lines = sorted((line for line in lines if 385 < line.y < 1080 and 10 <= line.x < 315),
                   key=lambda l: (l.y, l.x))
    for i, line in enumerate(lines):
        near_lines = [other.key for other in lines[i:i+3]
                      if 0 <= other.y-line.y <= 63 and abs(other.x-line.x) <= 220]
        if literal in ''.join(near_lines):
            return True
    return False


def search_identity(phone, worker, lines, item, store_seen):
    pid = str(item.get('product_id') or item.get('id') or '')
    if pid and active_product_id(phone,worker,pid):
        return 'product_id', phone.tree(timeout=4)
    title = worker.normalize(item['title'])
    shop = worker.normalize(item.get('shop') or item.get('shop_name') or '')
    tree = phone.tree(timeout=3)
    values = worker.scan_labels(tree) if tree is not None else []
    normalized_values = [worker.normalize(value) for value in values]
    title_match = title in normalized_values or full_ocr_match(lines,title)
    shop_match = bool(shop and (shop in normalized_values or any(shop in line.key for line in lines) or store_seen))
    return ('exact_title_and_shop', tree) if title_match and shop_match else (None,tree)


def try_sku(phone, worker, images, folder, result, tree, lines, page_only):
    from . import mall
    if phone.app_package == mall.PACKAGE:
        tags, _ = mall.product_tags(tree, result.get('title_seen', ''))
        if tags:
            result['product_tags'] = tags
    labels = worker.scan_labels(tree) if tree is not None else []
    result['page_prices'] = [
        {'price_before_coupon_fen':a,'price_after_coupon_fen':b,'is_from':c}
        for a,b,c in worker.pairs(labels)
    ]
    if page_only:
        return
    specs = [line for line in lines if 420 <= line.x <= 516 and 325 <= line.y <= 935
             and re.fullmatch(r'共\d+款',line.key)]
    if len(specs) == 1:
        tap_line(phone, specs[0])
    elif tree is None or not worker.sku_popup(tree):
        result['sku_note'] = '商品核对成功，未定位规格入口'
        return
    time.sleep(.6)
    shot = folder / '搜索商品规格.png'
    screenshot(phone, worker, shot)
    result['specs_shot'] = str(shot)
    popup = phone.tree(timeout=6)
    if popup is None:
        result['sku_note'] = '规格面板已打开，规格文本未能读取'
        return
    quotes, result['sku_note'] = worker.quotes(phone,popup)
    result['skus'] = quotes
    try:
        captured, result['sku_image_note'] = images.capture_sku_images(phone,worker,quotes,folder)
        if captured:
            result['skus'] = captured
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError):
        result['sku_image_note'] = '未完成逐款图片核对'


def recover(phone, worker, images, item, folder, page_only=False, remaining=24):
    """Try a normal Douyin search after QR failure; only return data after exact identity."""
    folder = pathlib.Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    result = {'search_attempted':True, 'search_submitted':False, 'search_stage':'opening_editor',
              'search_result_received':False, 'search_status':'not_found'}
    end = time.monotonic() + remaining
    old_deadline = getattr(phone,'deadline',None)
    phone.deadline = min(end,old_deadline) if old_deadline else end
    try:
        title = item.get('title') or ''
        model = re.findall(r'\b[A-Za-z]{1,4}\d{2,5}(?:\s*(?:Pro|Plus|Max|SE|Lite))?',title,re.IGNORECASE)
        model = [name for name in model if len(name.strip()) >= 4 and not name.lower().endswith('w')]
        query = (item.get('search_query') or (model[-1] if model else title)).strip()[:24]
        title_key = worker.normalize(title)
        store_key = worker.normalize(item.get('shop') or item.get('shop_name') or '')
        if len(title_key) < 6:
            result['search_reason'] = '来源商品名称不足以核对'
            return result
        set_query(phone, worker, images, folder, query)
        result['search_stage'] = 'submit_control_clicked'
        result['search_keyword'] = query
        normalized = worker.normalize(query)
        lines = search_results(phone, worker, folder, normalized, result)
        goods = near(lines, '商品', 50, 95, 470, 155)
        if goods and not any('没有搜索到' in line.key or '暂无搜索结果' in line.key for line in lines):
            tap_line(phone, goods)
            time.sleep(.9)
            lines = search_results(phone, worker, folder, normalized, result)
        candidates = candidate_matches(lines,title_key,store_key)
        result['candidates_scanned'] = len(candidates)
        for index, (_, candidate, shop_line) in enumerate(candidates,1):
            if time.monotonic() >= phone.deadline - 2:
                break
            tap_line(phone,candidate)
            try:
                worker.wait_for(lambda: phone.is_product_activity(phone.top()),3,'未进入商品详情')
            except (RuntimeError,subprocess.SubprocessError):
                phone.back()
                time.sleep(.5)
                continue
            time.sleep(.55)
            shot = folder/f'搜索候选-{index:02d}.png'
            _, page = screenshot(phone,worker,shot)
            matched, tree = search_identity(phone,worker,page,item,shop_line is not None)
            if matched:
                result.update(status='verified_product', search_status='verified_product',
                              matched_by=matched, title_seen=title, shot=str(shot), search_stage='verified_product')
                try:
                    try_sku(phone,worker,images,folder,result,tree,page,page_only)
                except AppIntervention:
                    raise
                except (RuntimeError, OSError, ValueError, subprocess.SubprocessError):
                    result['sku_note'] = '商品身份已核对，规格或价格读取未完成'
                break
            phone.back()
            time.sleep(.65)
        if result['search_status'] != 'verified_product':
            result['search_reason'] = '未在搜索结果核对商品 ID 或完整商品名称及店铺名'
    except AppIntervention as ex:
        result.update(status=ex.status, search_status=ex.status, search_stage=ex.status, search_reason=str(ex))
    except (OSError,ValueError,RuntimeError,subprocess.SubprocessError) as ex:
        result['search_status'] = 'unavailable'
        if isinstance(ex,RuntimeError) and '抖音无响应' in str(ex):
            result['search_status'] = 'app_unresponsive'
            phone.deadline = time.monotonic() + 12
            try:
                phone.restart_douyin()
            except (RuntimeError,subprocess.SubprocessError):
                pass
        result['search_reason'] = str(ex) if isinstance(ex,(RuntimeError,ValueError)) else type(ex).__name__
    finally:
        phone.deadline = old_deadline
    return result
