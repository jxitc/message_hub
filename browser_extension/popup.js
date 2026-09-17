/** 弹窗：快速记一条文字；真正的"划选入库"由右键菜单完成（不需要额外权限）。 */
const DEFAULTS = { serverUrl: 'https://mh.jxitc.com', apiKey: '', deviceId: 'browser-extension' };
const $ = (id) => document.getElementById(id);
const say = (text, cls) => { $('status').textContent = text; $('status').className = cls || ''; };

function browserLabel() {
  const ua = navigator.userAgent;
  if (ua.includes('Edg/')) return 'Edge';
  if (ua.includes('OPR/')) return 'Opera';
  if (ua.includes('Brave')) return 'Brave';
  if (ua.includes('Firefox/')) return 'Firefox';
  if (ua.includes('Chrome/')) return 'Chrome';
  return 'browser';
}

async function currentTabInfo() {
  try {
    const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
    return { url: tab?.url || '', title: tab?.title || '' };
  } catch (_) {
    return { url: '', title: '' };
  }
}

$('save').addEventListener('click', async () => {
  const text = $('note').value.trim();
  if (!text) { say('写点什么再保存', 'bad'); return; }
  const cfg = await chrome.storage.local.get(DEFAULTS);
  if (!cfg.apiKey) { say('先在「设置」里填服务器地址和 API key', 'bad'); return; }

  const site = await currentTabInfo();
  const base = cfg.serverUrl.replace(/\/+$/, '');
  say('保存中…');
  try {
    const response = await fetch(`${base}/api/v1/messages`, {
      method: 'POST',
      headers: { 'X-API-Key': cfg.apiKey, 'Content-Type': 'application/json' },
      body: JSON.stringify({
        source_device_id: cfg.deviceId,
        type: 'NOTE',
        sender: browserLabel(),
        content: text,
        timestamp: new Date().toISOString(),
        metadata: { url: site.url, title: site.title, via: 'popup' },
      }),
    });
    if (response.status === 201) {
      $('note').value = '';
      say('✅ 已保存', 'ok');
      return;
    }
    let detail = '';
    try { detail = (await response.json()).error || ''; } catch (_) {}
    say(`❌ HTTP ${response.status} ${detail}`, 'bad');
  } catch (err) {
    say(`❌ 连不上：${err}`, 'bad');
  }
});

$('note').addEventListener('keydown', (e) => {
  if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') $('save').click();
});

$('options').addEventListener('click', () => chrome.runtime.openOptionsPage());

// 打开弹窗时先看看配置齐不齐，省得点了保存才发现没配
chrome.storage.local.get(DEFAULTS).then((cfg) => {
  if (!cfg.apiKey) say('还没配置：点下面的「设置」填服务器地址与 API key', 'bad');
  else say(`服务器：${cfg.serverUrl}`);
});
