/**
 * Message Hub 浏览器插件的后台服务工作线程（MV3）。
 *
 * 职责只有三件：右键菜单、把内容 POST 到 hub、把结果告诉用户。
 * 不做任何后台扫描、不读页面其它内容——只处理你**明确选中/右键**的那一点东西。
 *
 * 上传形状与网页版「添加」页、手机端完全一致（type=NOTE，站点信息进 metadata），
 * 因为三者的校验在服务端是同一份实现（message_ingest）。
 */

const DEFAULTS = {
  serverUrl: 'https://mh.jxitc.com',
  apiKey: '',
  deviceId: 'browser-extension',
};

const MENU_SELECTION = 'mh-save-selection';
const MENU_IMAGE = 'mh-save-image';
const MENU_PAGE = 'mh-save-page';

/** 读配置（缺省值兜底，避免第一次用就报错）。 */
async function getConfig() {
  const stored = await chrome.storage.local.get(DEFAULTS);
  return { ...DEFAULTS, ...stored };
}

/** 浏览器名当 sender：多浏览器时能看出是哪台/哪个记的（与手机用设备名同理）。 */
function browserLabel() {
  const ua = navigator.userAgent;
  if (ua.includes('Edg/')) return 'Edge';
  if (ua.includes('OPR/')) return 'Opera';
  if (ua.includes('Brave')) return 'Brave';
  if (ua.includes('Firefox/')) return 'Firefox';
  if (ua.includes('Chrome/')) return 'Chrome';
  return 'browser';
}

/** 短暂的角标反馈：不打扰，但能看见成功/失败。 */
async function flashBadge(text, color) {
  try {
    await chrome.action.setBadgeBackgroundColor({ color: color || '#198754' });
    await chrome.action.setBadgeText({ text });
    setTimeout(() => chrome.action.setBadgeText({ text: '' }).catch(() => {}), 4000);
  } catch (_) {
    /* 角标失败不影响主流程 */
  }
}

async function notify(title, message) {
  try {
    await chrome.notifications.create({
      type: 'basic',
      iconUrl: 'icon128.png',
      title,
      message: String(message).slice(0, 300),
    });
  } catch (_) {
    /* 通知权限被拒时静默降级为角标 */
  }
}

/** 服务端把错误和原因都写在 {"error": "..."} 里，尽量原样显示给用户。 */
async function describeFailure(response) {
  let detail = '';
  try {
    const body = await response.json();
    detail = body.error || JSON.stringify(body).slice(0, 200);
  } catch (_) {
    detail = await response.text().catch(() => '');
  }
  if (response.status === 401) return 'API key 无效或缺失（在插件选项页填写）';
  if (response.status === 413) return `文件太大：${detail}`;
  if (response.status === 415) return `类型不支持：${detail}`;
  if (response.status === 507) return `服务器存储已满：${detail}`;
  return `HTTP ${response.status} ${detail}`.trim();
}

/**
 * 提交一条消息。有 blob 时走 multipart（与网页版同一形状），否则走 JSON。
 * 注意：multipart 时**不要**自己设 Content-Type，浏览器要自己加 boundary。
 */
async function submit({ content, metadata, blob, filename, mime }) {
  const cfg = await getConfig();
  if (!cfg.apiKey) {
    await notify('还没配置', '请先在插件的选项页填写服务器地址与 API key。');
    await flashBadge('!', '#dc3545');
    return false;
  }

  const base = cfg.serverUrl.replace(/\/+$/, '');
  const sender = browserLabel();
  let response;
  try {
    if (blob) {
      const form = new FormData();
      form.append('source_device_id', cfg.deviceId);
      form.append('type', 'NOTE');
      form.append('sender', sender);
      form.append('content', content || '');
      form.append('timestamp', new Date().toISOString());
      form.append('metadata', JSON.stringify(metadata || {}));
      form.append('attachments', blob, filename || 'clip');
      response = await fetch(`${base}/api/v1/messages`, {
        method: 'POST',
        headers: { 'X-API-Key': cfg.apiKey },
        body: form,
      });
    } else {
      response = await fetch(`${base}/api/v1/messages`, {
        method: 'POST',
        headers: { 'X-API-Key': cfg.apiKey, 'Content-Type': 'application/json' },
        body: JSON.stringify({
          source_device_id: cfg.deviceId,
          type: 'NOTE',
          sender,
          content,
          timestamp: new Date().toISOString(),
          metadata: metadata || {},
        }),
      });
    }
  } catch (err) {
    await notify('连不上服务器', `${base}\n${err}`);
    await flashBadge('!', '#dc3545');
    return false;
  }

  if (response.status === 201) {
    await flashBadge('✓', '#198754');
    return true;
  }
  const reason = await describeFailure(response);
  await notify('保存失败', reason);
  await flashBadge('!', '#dc3545');
  return false;
}

/** 图片：≤1MB 原样传；超过则用 OffscreenCanvas 压到长边 2048 / JPEG，仍超就拒绝。 */
async function prepareImage(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`取图失败 HTTP ${response.status}`);
  let blob = await response.blob();
  const type = blob.type || 'image/png';
  const limit = 1024 * 1024;
  if (blob.size <= limit) return { blob, mime: type };

  const bitmap = await createImageBitmap(blob);
  const scale = Math.min(1, 2048 / Math.max(bitmap.width, bitmap.height));
  const canvas = new OffscreenCanvas(
    Math.round(bitmap.width * scale), Math.round(bitmap.height * scale));
  canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
  for (const quality of [0.8, 0.7, 0.6, 0.5]) {
    const out = await canvas.convertToBlob({ type: 'image/jpeg', quality });
    if (out.size <= limit) return { blob: out, mime: 'image/jpeg' };
  }
  throw new Error('压缩后仍超过 1MB');
}

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    chrome.contextMenus.create({
      id: MENU_SELECTION, title: '把选中文字存入 Message Hub',
      contexts: ['selection'],
    });
    chrome.contextMenus.create({
      id: MENU_IMAGE, title: '把这张图片存入 Message Hub',
      contexts: ['image'],
    });
    chrome.contextMenus.create({
      id: MENU_PAGE, title: '把本页标题与链接存入 Message Hub',
      contexts: ['page'],
    });
  });
});

chrome.contextMenus.onClicked.addListener(async (info, tab) => {
  const site = { url: info.pageUrl || (tab && tab.url) || '', title: (tab && tab.title) || '' };

  if (info.menuItemId === MENU_SELECTION) {
    const text = (info.selectionText || '').trim();
    if (!text) return;
    // 站点信息与选中文字一起留下：以后能回溯"这句话是从哪抄的"。
    await submit({
      content: text,
      metadata: { url: site.url, title: site.title, via: 'selection' },
    });
    return;
  }

  if (info.menuItemId === MENU_IMAGE) {
    try {
      const { blob, mime } = await prepareImage(info.srcUrl);
      const name = (info.srcUrl.split('/').pop() || 'image').split('?')[0] || 'image';
      await submit({
        content: '',
        metadata: { url: site.url, title: site.title, via: 'image', image_url: info.srcUrl },
        blob,
        filename: name.includes('.') ? name : `${name}.jpg`,
        mime,
      });
    } catch (err) {
      await notify('图片没存上', String(err));
      await flashBadge('!', '#dc3545');
    }
    return;
  }

  if (info.menuItemId === MENU_PAGE) {
    await submit({
      content: site.title ? `${site.title}\n${site.url}` : site.url,
      metadata: { url: site.url, title: site.title, via: 'page' },
    });
  }
});
