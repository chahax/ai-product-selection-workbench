from __future__ import annotations

import re
import time
from pathlib import Path

from PIL import Image, ImageChops, ImageOps, ImageStat


SCREEN_SIZE = (1080, 2400)
SMALL_SIZE = (540, 1200)
PREVIEW_BACK = (75, 151)
MIN_PRICE_CONTROL_Y = 2140
REFERENCES = Path(__file__).with_name('references')


def bounds(node):
    values = re.findall(r'\d+', node.get('bounds', ''))
    return tuple(map(int, values)) if len(values) == 4 else None


def preview_crop(image):
    """Find an isolated fullscreen preview, leaving the SKU and counter controls out."""
    if image.size != SCREEN_SIZE:
        return None
    frame = image.convert('RGB')
    pixels = frame.load()
    if max(pixels[12, 280] + pixels[1070, 280] + pixels[12, 1870] + pixels[1070, 1870]) > 16:
        return None
    rows = [y for y in range(325, 2010)
            if sum(max(pixels[x, y]) > 24 for x in range(35, 1080, 100)) >= 5]
    if not rows:
        return None
    groups = [[rows[0]]]
    for y in rows[1:]:
        if y - groups[-1][-1] >= 28:
            groups.append([])
        groups[-1].append(y)
    group = max(groups, key=len)
    top, bottom = group[0], group[-1] + 1
    if not (380 <= bottom - top <= 1500 and top < 1169 < bottom):
        return None
    side = []
    for x in range(1080):
        if sum(max(pixels[x, y]) > 24 for y in range(top + 7, bottom - 6, max(11, (bottom - top) // 13))) >= 4:
            side.append(x)
    if not side or side[-1] - side[0] < 480:
        return None
    left = side[0] if side[0] > 20 else 0
    right = side[-1] + 1 if side[-1] < 1059 else 1080
    if right == 1080 and left == 0 and abs(bottom - top - 1080) <= 8:
        top = (top + bottom - 1080) // 2
        bottom = top + 1080
    return frame.crop((left, top, right, bottom))


def _diff(im1, im2):
    return sum(ImageStat.Stat(ImageChops.difference(im1, im2)).mean) / len(im1.getbands())


def _template(template, frame, range_x, range_y, limit):
    width, height = template.size
    best = None
    for x in range_x:
        for y in range_y:
            diff = _diff(template, frame.crop((x, y, x + width, y + height)))
            if diff < limit:
                limit = diff
                best = (x + width // 2, y + height // 2, diff)
    return best


def _large_button_position(frame, template_dir):
    small = frame.resize(SMALL_SIZE).convert('L')
    references = (
        ('sku_show_large_a.png', True),
        ('sku_show_large_b.png', True),
        ('sku_show_small.png', False),
    )
    matches = []
    for file, opens_grid in references:
        with Image.open(template_dir / file) as image:
            sample = image.convert('L')
        match = _template(sample, small, range(453, 466), range(300, 877), 24)
        if match:
            matches.append((match[2], *match[:2], opens_grid))
    if not matches:
        return None, False
    _, x, y, opens_grid = min(matches)
    return (x, y), opens_grid

def _zoom_points(frame, template_dir, start_y):
    small = frame.resize(SMALL_SIZE).convert('L')
    samples = []
    for name in ('first','second','third','next_row'):
        with Image.open(template_dir / f'sku_zoom_{name}.png') as image:
            samples.append(image.convert('L'))
    points = []
    for x_group in (range(27, 47, 2), range(196, 218, 2), range(365, 387, 2)):
        found = []
        for x in x_group:
            for y in range(max(325, start_y), 1014, 2):
                frame_part = small.crop((x, y, x + 14, y + 15))
                low, high = frame_part.getextrema()
                if high < 223 or high - low < 41:
                    continue
                score = min(_diff(frame_part, sample) for sample in samples)
                if score <= 26:
                    found.append((score, x + 7, y + 7))
        for score, x, y in sorted(found):
            if all(abs(y - existing[2]) > 18 or abs(x - existing[1]) > 16 for existing in points):
                points.append((score, x, y))
    return sorted(((x, y) for score, x, y in points), key=lambda xy: (round(xy[1] / 12), xy[0]))


def _label_node(tree, api, label):
    if tree is None:
        return None
    for node in tree.iter('node'):
        box = bounds(node)
        if box and label in api.labels(node) and 0 <= box[1] < box[3] <= MIN_PRICE_CONTROL_Y:
            return node
    return None


def _row_names(tree, api):
    names = {}
    if tree is None:
        return names
    for node in tree.iter('node'):
        box = bounds(node)
        if box is None:
            continue
        left, top, right, bottom = box
        if not (25 <= left <= 170 and 530 < top < bottom <= 2080 and right - left >= 160):
            continue
        for label in api.labels(node):
            match = re.fullmatch(r'(?:未选择|未选中|已选择|已选中|已选)\s*(.+?)(?:\s*[¥￥]\s*\d+(?:\.\d{1,2})?)?', label)
            if match and len(match[1].strip()) >= 2:
                names.setdefault(match[1].strip(), node)
    return names


def _thumb_match(thumb, preview):
    left = ImageOps.fit(thumb.convert('RGB'), (38, 38))
    right = ImageOps.fit(preview.convert('RGB'), (38, 38))
    left, right = left.crop((4, 4, 34, 34)), right.crop((4, 4, 34, 34))
    sr, sg, sb = ImageStat.Stat(left).mean
    pr, pg, pb = ImageStat.Stat(right).mean
    chroma = abs((sr - sg) - (pr - pg)) + abs((sg - sb) - (pg - pb))
    return _diff(left, right) + 0.45 * chroma


def _thumbnail_from_row(image, row):
    left, top, _, bottom = bounds(row)
    # The list places a square item image at its leading edge.
    middle = (top + bottom) // 2
    # Use the observed 96×96 thumbnail centered in the first 96 px of the list.
    side = min(94, bottom - top - 4)
    edge = 44 if left >= 88 else max(44, left)
    if side < 64:
        return None
    thumb = image.crop((edge, middle - side // 2, edge + side, middle + side // 2))
    if sum(ImageStat.Stat(thumb).stddev) < 28:
        return None
    return thumb


def _save(img, path, source):
    img.save(path)
    return {'file': str(path), 'width': img.width, 'height': img.height, 'source': source}


def _preview_sku_label(tree, api, names):
    if tree is None:
        return None
    for node in tree.iter('node'):
        box = bounds(node)
        if not box or not (1800 <= box[1] < box[3] <= 2240 and box[0] < 680 < box[2] + 80):
            continue
        for label in api.labels(node):
            key = api.normalize(re.sub(r'^(?:已选择|已选中|已选)\s*', '', label))
            matches = [name for name in names if api.normalize(name) == key]
            if len(matches) == 1 and (node.get('selected') == 'true' or (box[0] + box[2]) / 2 <= 650):
                return matches[0]
    return None


def capture_sku_images(phone, api, sku_quotes, output, template_dir=REFERENCES):
    """Capture matching native gallery preview images; pair prices by exact SKU name."""
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    template_dir = Path(template_dir)
    tree = phone.tree(timeout=7)
    if tree is None:
        return [], '规格窗无法读取，已跳过图片匹配'
    names = {quote['name']: dict(quote) for quote in sku_quotes if quote.get('name')}
    names.update({name: names.get(name, {'name': name}) for name in _row_names(tree, api)})
    if not names:
        return [], '规格名称无法确认'
    frame = phone.screen()
    exact_rows = {name: node for name, (node, _, _) in api.row_nodes(tree).items()}
    exact_rows.update(_row_names(tree, api))
    thumbnails = {name: thumbnail for name, node in exact_rows.items()
                  if (thumbnail := _thumbnail_from_row(frame, node)) is not None}
    large = _label_node(tree, api, '大图')
    if large is None:
        large = _label_node(tree, api, '切换大图')
    if large is not None:
        box = bounds(large)
        large_y = sum((box[1], box[3])) // 4
        if not phone.tap_node(large):
            return list(names.values()), '规格图按钮无法打开'
    else:
        position, opens_grid = _large_button_position(frame, template_dir)
        if not position:
            return list(names.values()), '未定位到规格大图入口'
        x, large_y = position
        if opens_grid:
            phone.tap(x * 2, large_y * 2)
    time.sleep(0.7)
    frame = phone.screen()
    points = _zoom_points(frame, template_dir, large_y + 17)
    previews = []
    for index, (x, y) in enumerate(points, 1):
        phone.tap(x * 2, y * 2)
        time.sleep(0.85)
        screen = phone.screen()
        preview = preview_crop(screen)
        if preview is None:
            continue
        label = _preview_sku_label(phone.tree(timeout=5), api, names)
        scores = sorted((_thumb_match(thumb, preview), name) for name, thumb in thumbnails.items())
        if label is None and scores and scores[0][0] < 28 and (len(scores) == 1 or scores[1][0] - scores[0][0] >= 4):
            label = scores[0][1]
        previews.append((index, label, preview))
        phone.tap(*PREVIEW_BACK)
        time.sleep(0.4)
    for index, name, preview in previews:
        if name is None:
            _save(preview, output / f'待核对规格图-{index:02d}.png', '抖音规格大图预览裁切')
            continue
        if names[name].get('image'):
            continue
        names[name]['image'] = _save(preview, output / f'规格图-{list(names).index(name)+1:02d}.png', '抖音规格大图预览裁切')
    missing = sum('image' not in row for row in names.values())
    return list(names.values()), (f'{missing} 款规格还没有核对对应图片' if missing else None)

