import { createServer } from 'node:http';
import { createDouyinMiddleware } from './douyin';

const { WORKBENCH_DOUYIN_DB, WORKBENCH_BASE_PATH = '/', WORKBENCH_PORT = '18310' } = process.env;
if (!WORKBENCH_DOUYIN_DB) throw new Error('请设置 WORKBENCH_DOUYIN_DB');
const handleDouyin = createDouyinMiddleware(WORKBENCH_DOUYIN_DB, WORKBENCH_BASE_PATH.replace(/\/$/, ''));

createServer((request, response) => {
  handleDouyin(request, response, () => {
    response.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
    response.end(JSON.stringify({ error: '此服务器未配置该工作台接口' }));
  });
}).listen(Number(WORKBENCH_PORT), '127.0.0.1');
