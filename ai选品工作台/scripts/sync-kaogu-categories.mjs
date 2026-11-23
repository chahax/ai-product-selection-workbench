import { writeFile } from 'node:fs/promises';
import { fetchKaoguCategories } from '../server/kaogu-catalog-source.mjs';

const output = new URL('../src/data/kaogu-categories.json', import.meta.url);
const { categories, total } = await fetchKaoguCategories();
await writeFile(output, `[\n${categories.map(node => `  ${JSON.stringify(node)}`).join(',\n')}\n]\n`);
console.log(`已同步 ${categories.length} 个一级类目、${total} 个类目节点`);
