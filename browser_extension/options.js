/** 选项页：保存配置、按需申请该域名的访问权限、测试连接与鉴权。 */
const DEFAULTS = { serverUrl: 'https://mh.jxitc.com', apiKey: '', deviceId: 'browser-extension' };
const $ = (id) => document.getElementById(id);
const setStatus = (text, cls) => { $('status').textContent = text; $('status').className = cls || ''; };

async function load() {
  const cfg = await chrome.storage.local.get(DEFAULTS);
  $('serverUrl').value = cfg.serverUrl;
  $('apiKey').value = cfg.apiKey;
  $('deviceId').value = cfg.deviceId;
}

/**
 * MV3 要求把要访问的域名**静态声明或运行时申请**。这里按你填的地址申请那一个源，
 * 而不是笼统地要 `<all_urls>`——插件只该能连自己的 hub。
 */
async function ensurePermission(serverUrl) {
  const origin = new URL(serverUrl).origin + '/*';
  const already = await chrome.permissions.contains({ origins: [origin] });
  if (already) return true;
  return chrome.permissions.request({ origins: [origin] });
}

/** 两步测试：先连通性（/health 不需要 key），再鉴权（带 key 打限额接口）。 */
async function testConnection(cfg) {
  const base = cfg.serverUrl.replace(/\/+$/, '');
  setStatus('测试中…', 'warn');
  try {
    const health = await fetch(`${base}/health`);
    if (!health.ok) { setStatus(`❌ 服务器不可达：HTTP ${health.status}`, 'bad'); return false; }
  } catch (err) {
    setStatus(`❌ 连不上 ${base}\n${err}\n（若域名不在授权列表里，先点「保存并授权该域名」）`, 'bad');
    return false;
  }
  try {
    const limits = await fetch(`${base}/api/v1/attachments/limits`, {
      headers: { 'X-API-Key': cfg.apiKey },
    });
    if (limits.status === 401) { setStatus('❌ 服务器通了，但 API key 无效或缺失', 'bad'); return false; }
    if (!limits.ok) { setStatus(`❌ 鉴权接口 HTTP ${limits.status}`, 'bad'); return false; }
    const body = await limits.json();
    setStatus(`✅ 一切正常\n服务器：${base}\n单文件上限：${(body.max_bytes / 1024) | 0} KB\n允许类型：${(body.allowed || []).join(', ')}`, 'ok');
    return true;
  } catch (err) {
    setStatus(`❌ 鉴权测试失败：${err}`, 'bad');
    return false;
  }
}

$('save').addEventListener('click', async () => {
  const serverUrl = $('serverUrl').value.trim() || DEFAULTS.serverUrl;
  let origin;
  try {
    origin = new URL(serverUrl).origin;
  } catch (_) {
    setStatus('❌ 服务器地址不是合法 URL', 'bad');
    return;
  }
  const granted = await ensurePermission(serverUrl);
  if (!granted) { setStatus(`❌ 没有获得 ${origin} 的访问权限，插件无法上传`, 'bad'); return; }

  const cfg = {
    serverUrl,
    apiKey: $('apiKey').value.trim(),
    deviceId: $('deviceId').value.trim() || DEFAULTS.deviceId,
  };
  await chrome.storage.local.set(cfg);
  setStatus(`已保存（${origin} 已授权）。正在测试…`, 'warn');
  await testConnection(cfg);
});

$('test').addEventListener('click', () => testConnection({
  serverUrl: $('serverUrl').value.trim() || DEFAULTS.serverUrl,
  apiKey: $('apiKey').value.trim(),
}));

load();
