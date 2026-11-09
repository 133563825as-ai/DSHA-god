'use strict';
// DSHA 原生运行环境的 App 层接口调用器（容器里用 curl -H @/root/.dsh/.bridge_headers）。
//
// 用法：  "$DSHA_NODE" "$DSHA_APP" /app/<路径> [键=值 ...]
// - 只允许 /app/ 开头的路径，固定发往 127.0.0.1 的设备桥端口；
// - 凭据只从宿主写入的 .bridge_token 读进请求头，不出现在命令行、URL 或输出里；
// - 参数逐个 URL 编码（中文、空格直接写）；/app/ui/* 自动带上当前手机任务身份；
// - 输出原样写到 stdout；HTTP 非 200 时退出码 1。
const fs = require('node:fs');
const http = require('node:http');

const LIMIT = 16 * 1024 * 1024;

function fail(message, code = 2) {
  process.stderr.write('dsha-app: ' + message + '\n');
  process.exit(code);
}

function stateDirectory() {
  const value = process.env.DSHA_BRIDGE_STATE_DIR;
  if (typeof value !== 'string' || !value.startsWith('/') || value.length > 1024
      || value.split('/').some((part, index) => index > 0 && (part === '' || part === '.' || part === '..')))
    fail('DSHA_BRIDGE_STATE_DIR 未设置或无效');
  return value;
}

/** 与 native-consent 相同的核验：普通文件、0600、O_NOFOLLOW、打开前后同一文件。 */
function readSmall(path, max, pattern) {
  const before = fs.lstatSync(path);
  if (!before.isFile() || before.isSymbolicLink() || before.size < 1 || before.size > max
      || (before.mode & 0o077) !== 0) fail('凭据文件不可用：' + path.split('/').pop());
  const fd = fs.openSync(path, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
  try {
    const opened = fs.fstatSync(fd);
    if (opened.dev !== before.dev || opened.ino !== before.ino) fail('凭据文件已变化');
    const bytes = Buffer.alloc(max + 1), count = fs.readSync(fd, bytes, 0, bytes.length, 0);
    const text = bytes.subarray(0, count).toString('latin1').trim();
    if (!pattern.test(text)) fail('凭据文件内容无效');
    return text;
  } finally {
    fs.closeSync(fd);
  }
}

function main(argv) {
  const [route, ...pairs] = argv;
  if (!route || route === '-h' || route === '--help') {
    process.stdout.write('用法："$DSHA_NODE" "$DSHA_APP" /app/<路径> [键=值 ...]   例：/app/help、/app/launch pkg=com.example\n');
    return;
  }
  if (!/^\/app\/[A-Za-z0-9/_-]*$/.test(route) || route.includes('..') || route.includes('//'))
    fail('只允许 /app/ 开头、不含查询串的路径（参数请写成 键=值）');
  const params = new URLSearchParams();
  for (const pair of pairs) {
    const at = pair.indexOf('=');
    if (at < 1) fail('参数要写成 键=值：' + pair);
    const key = pair.slice(0, at);
    if (!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(key)) fail('参数名无效：' + key);
    if (key === 'token') fail('不能传 token 参数；凭据由本工具从宿主文件读取');
    params.append(key, pair.slice(at + 1));
  }
  // 手机任务身份来自实际工具环境，不由模型提供（与容器引导同一规则）。
  if (route.startsWith('/app/ui/')) {
    for (const [key, env] of [['taskSessionId', 'DSH_PHONE_SESSION_ID'], ['turnId', 'DSH_PHONE_TURN_ID'],
      ['completedTurnId', 'DSH_PHONE_COMPLETED_TURN_ID']]) {
      const value = process.env[env];
      if (value && !params.has(key)) params.set(key, value);
    }
  }
  const dir = stateDirectory();
  const token = readSmall(dir + '/.bridge_token', 129, /^[A-Za-z0-9_-]{1,128}$/);
  let port;
  try {
    port = Number(readSmall(dir + '/.bridge_port', 5, /^[0-9]{1,5}$/));
  } catch {
    // 与上游 build280 一致：不回退任何固定端口。3090–3099 可能属于同机另一个 DSHA 安装，
    // 猜一个端口再带上本安装 token 发过去，等于把凭据交给别的应用。
    fail('设备桥端口尚未发布（.bridge_port 不存在或无效），设备桥未就绪；请打开 DSHA 后再试');
  }
  if (!(port >= 1 && port <= 65535)) fail('端口无效');
  const query = params.toString();
  const request = http.request({
    host: '127.0.0.1', port, method: 'GET', agent: false,
    path: route + (query ? '?' + query : ''),
    headers: { 'X-Token': token },
    timeout: route.startsWith('/app/ask') ? 10 * 60 * 1000 : 120 * 1000,
  }, response => {
    let size = 0;
    response.on('data', chunk => {
      size += chunk.length;
      if (size > LIMIT) {
        request.destroy();
        fail('回复超过 16 MiB，已截断', 1);
      }
      process.stdout.write(chunk);
    });
    response.on('end', () => {
      if (response.statusCode !== 200) {
        process.stderr.write('dsha-app: HTTP ' + response.statusCode + '\n');
        process.exitCode = 1;
      }
    });
  });
  request.on('timeout', () => {
    request.destroy();
    fail('请求超时：结果未知，可能已执行，先核对实际状态再决定', 1);
  });
  request.on('error', error => fail('连不上设备桥：' + error.code, 1));
  request.end();
}

main(process.argv.slice(2));
