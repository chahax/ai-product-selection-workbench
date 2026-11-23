const endpoint = 'https://service.kaogujia.com/api/condition/m/all/lv/v2/full';
const maximumResponse = 8 * 1024 * 1024;

async function readResponse(response) {
  if (!response.body) throw new Error('考古加返回了空响应');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let size = 0;
  let text = '';
  try {
    for (let chunk; !(chunk = await reader.read()).done;) {
      size += chunk.value.length;
      if (size > maximumResponse) throw new Error('考古加类目响应超过大小限制');
      text += decoder.decode(chunk.value, { stream: true });
    }
    return JSON.parse(text + decoder.decode());
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}

export async function fetchKaoguCategories() {
  const response = await fetch(endpoint, {
    headers: { version_code: '3.1' },
    signal: AbortSignal.timeout(20_000),
    redirect: 'error',
  });
  if (!response.ok) {
    await response.body?.cancel();
    throw new Error(`考古加类目接口返回 HTTP ${response.status}`);
  }
  const result = await readResponse(response);
  if (!result.success || !Array.isArray(result.data)) throw new Error('考古加类目响应格式无效');

  const paths = new Set();
  function categories(nodes, parent = []) {
    return nodes.filter(node => node.is_new === true && node.disabled === false && node.value !== 0).map(node => {
      const { label, children } = node;
      if (typeof label !== 'string' || !label || label !== label.trim() || /[\u0000-\u001f\u007f]/.test(label)) {
        throw new Error('考古加类目名称格式无效');
      }
      const path = [...parent, label];
      const key = path.join('-');
      if (paths.has(key) || key.length > 100 || path.length > 5) throw new Error('考古加类目路径重名或超出限制');
      paths.add(key);
      const branches = Array.isArray(children) ? categories(children, path) : [];
      return branches.length ? { name: label, children: branches } : { name: label };
    });
  }

  const tree = categories(result.data);
  if (tree.length < 20 || paths.size < 6000) throw new Error('考古加返回的类目疑似不完整');
  return { categories: tree, total: paths.size };
}
