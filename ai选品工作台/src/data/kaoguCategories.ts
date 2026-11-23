import tree from './kaogu-categories.json';

export type KaoguCategory = { name: string; children?: KaoguCategory[] };
export const kaoguCategories: KaoguCategory[] = tree;
export const allKaoguCategories = '不限';
export const defaultKaoguCategory = ['3C数码家电', '电器', '个护健康电器'];

export function indexKaoguCategories(categories: KaoguCategory[]) {
  const paths = new Map<string, readonly string[]>();
  function index(nodes: KaoguCategory[], parent: readonly string[]) {
    for (const node of nodes) {
      const parts = [...parent, node.name];
      paths.set(parts.join('-'), parts);
      if (node.children) index(node.children, parts);
    }
  }
  index(categories, []);
  return paths;
}

export const bundledKaoguPaths = indexKaoguCategories(kaoguCategories);

export function findKaoguCategory(path: string) {
  return bundledKaoguPaths.get(path);
}

export function searchKaoguCategories(paths: ReadonlyMap<string, readonly string[]>, query: string) {
  const term = query.trim().toLocaleLowerCase('zh-CN');
  if (!term) return [];
  const exact: [string, readonly string[]][] = [];
  const partial: [string, readonly string[]][] = [];
  for (const [key, parts] of paths) {
    const name = parts[parts.length - 1].toLocaleLowerCase('zh-CN');
    if (name === term) exact.push([key, parts]);
    else if (name.includes(term)) partial.push([key, parts]);
  }
  return [...exact, ...partial].slice(0, 40);
}
