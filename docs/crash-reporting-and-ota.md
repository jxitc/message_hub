# 崩溃诊断与 APK 分发（远程闭环）

> 目标：**手机在外面崩了，不用连线、不用 adb logcat，也能远程知道原因并推送修复版**。
>
> 本文档描述两部分：① 客户端崩溃上报（谁采集、怎么上报、服务器怎么存）；② APK 分发
> （怎么把新版本送到手机）。最后是「操作手册」——远程排障时直接照抄的命令。

---

## 1. 为什么需要它

2026-09-12 的一次启动闪退排查经历：

- 现象：app 能打开、能看到本地缓存消息，约 5 秒后**自己关闭，没有任何错误弹窗**
- 排查代价：需要 USB 线 + `adb logcat` + `adb logcat -b crash`，还要翻 App 私有目录的文件日志
- 根因（事后）：`HttpLoggingInterceptor.Level.BODY` 把 5508 字节的响应体当**一条**日志打印，
  超过 logd 单条上限（约 4068 字节），Android 16 的日志分片函数在 ubsan 溢出检查下
  **直接 abort 进程**（SIGABRT，没有 Java 堆栈）——所以看不到任何异常提示

**结论**：这类崩溃靠 `try/catch` 拦不住（系统层直接杀进程），唯一出路是
**崩溃后把现场自动送到服务器**。有了它，同样的故障下次只需打开网页看一眼。

---

## 2. 整体链路

```
┌──────────────────┐   崩溃发生
│  Android 客户端   │──────────────┐
│                  │              ▼
│  ① UncaughtExceptionHandler     ② ApplicationExitInfo
│     (Java 异常, 堆栈完整)          (native abort / ANR, 系统 tombstone)
│                  │              │
│                  └──────┬───────┘
│                         ▼
│              files/crash_pending/*.json   ← 本地队列(不依赖网络)
└──────────────────────────┬───────────────
                           │ 下次启动 POST
                           ▼
        POST /api/v1/diagnostics/crashes      (X-API-Key 鉴权)
                           │
                           ▼
                  ┌─────────────────┐
                  │  MH 服务器       │  crash_reports 表
                  │                 │
                  ├─────────────────┤
                  │ 网页 /crashes    │ ← 打开就能看堆栈
                  │ GET /api/v1/diagnostics/crashes  │ ← agent 远程拉取
                  └─────────────────┘
```

修复后如何送回去（OTA）——两条路径，手机上的应用内更新是主线：

```
本机: ./scripts/publish-apk.sh
        │ 编译 + 读版本号 + 写 latest.json
        ▼
服务器 instance/releases/{messagehub-debug.apk, latest.json}
        │
        ├──▶ GET /api/v1/releases/latest-info   ← 手机 app 拉元数据比版本
        │         │
        │         ▼
        │    Settings → App 更新 → 检查更新 → 下载并安装 → 系统安装器（点一下）
        │
        └──▶ GET /api/v1/releases/latest        ← 浏览器短链接（备用/首次安装）
```

---

## 3. 服务端

### 3.1 数据表 `crash_reports`

| 字段 | 说明 |
|---|---|
| `id` | 主键；客户端可传 `client_report_id` 作为幂等键（重复上传不重复入库） |
| `source_device_id` | 上报设备（当前固定 `android-phone-1`） |
| `reason` | `CRASH_JAVA` / `CRASH_NATIVE` / `ANR` / … |
| `reason_code` | `ApplicationExitInfo.getReason()` 原始值 |
| `summary` | 短摘要（异常类名+消息，或系统 description） |
| `stacktrace` | 完整堆栈 / tombstone 文本 |
| `app_version` / `build_type` / `device_model` / `android_version` | 现场环境 |
| `occurred_at` / `received_at` | 崩溃发生时间 / 服务器收到时间 |
| `fingerprint` | `sha256(reason+summary)[:32]`，用于在页面上聚合"同一类崩溃 ×N" |

表由 `db.create_all()` 自动创建（`deploy.sh` 每次部署都会跑），**无需手工迁移**。

### 3.2 API（都在 `/api/v1` 下，需要 `X-API-Key`）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/diagnostics/crashes` | 上报。支持单条，或 `{"reports":[...]}` 批量。同 `client_report_id` 幂等 |
| GET | `/diagnostics/crashes?limit=&device=&reason=` | 列表（**不含**堆栈，只给长度，便于快速扫一眼） |
| GET | `/diagnostics/crashes/<id>` | 单条详情，**含完整堆栈** |

### 3.3 网页

- `/crashes` —— 崩溃列表：reason 徽章、同指纹聚合计数（×N）、设备/版本、可展开堆栈
- `/settings` —— 除 API Key 管理外，新增 **Mobile App (APK)** 区块，列出可下载的构建

---

## 4. 客户端（Android）

`utils/CrashReporter.kt`，在 `MessageHubApplication.onCreate` 里安装：

1. **Java 未捕获异常**：`Thread.setDefaultUncaughtExceptionHandler`
   - 崩溃瞬间把堆栈写进 `files/crash_pending/<id>.json`（**先落盘，不依赖网络**）
   - 然后调用原 handler，保持系统默认行为
2. **native 崩溃 / ANR**：`ActivityManager.getHistoricalProcessExitReasons(pkg, 0, 20)`（API 30+，无需 root）
   - 注意正确的方法名是 `getHistoricalProcessExitReasons`（不是 `getHistoricalProcessExitInfos`）
   - 只取 `REASON_CRASH_NATIVE` / `REASON_ANR`；`REASON_CRASH` 跳过（Java 崩溃已由上面那条路径记录，
     否则同一次崩溃会上报两条）
   - native 崩溃能从 `getTraceInputStream()` 拿到系统 tombstone 文本 —— **这是 Java 层永远拿不到的信息**
   - 已处理过的退出记录（`timestamp:reason`）存在 `shared_prefs/crash_reporter.xml`，避免每次启动重复上报
3. **上报**：下次启动时把 `crash_pending/` 里的 JSON 批量 POST；成功即删除文件，失败留待下次

设计要求：**崩溃路径本身绝不能抛异常**（每个环节 try/catch 兜住），且堆栈**直接写文件**
（不走 `Logger`，因为 Logger 有 3500 字节截断，会切掉堆栈）。

---

## 4.5 应用内自动更新（自建 OTA）

实现在 `data/remote/UpdateChecker.kt` + Settings 页的「App 更新」卡片：

1. `GET /api/v1/releases/latest-info`（带 `X-API-Key`）拿服务器上的
   `{version_name, version_code, size_mb, download_url, notes}`
2. 与自己 `versionCode` 比较：`remote <= local` → "已是最新"
3. 有新版：显示版本号/体积/notes → 点「下载并安装」
   - 下载到 `cacheDir/updates/`，带百分比进度
   - 用 `FileProvider` 把文件以 `content://` 交给系统安装器（`ACTION_VIEW` +
     `application/vnd.android.package-archive`）
4. 系统弹安装确认 → 用户点"安装" → 覆盖安装（签名一致时**数据保留**）

需要的声明：`REQUEST_INSTALL_PACKAGES` 权限 + `FileProvider`
（`${applicationId}.fileprovider` → `res/xml/file_paths.xml` 暴露 `cache-path updates/`）。
首次安装未知来源应用时，用 `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` 引导授权。

**为什么最后一步不能自动化**：Android 不允许普通应用静默自升级（除非设备所有者 / MDM /
系统签名）。能做的是把"找地址 → 下载 → 点开"这前三步省掉，只剩一次系统确认。

## 5. 已知限制（诚实记录）

| 限制 | 说明 | 缓解 |
|---|---|---|
| **无法静默安装** | Android 禁止普通 app 自升级，必须用户点"安装" | 手机点两下即可；连着 USB 时可 `adb install -r` 全自动 |
| **启动即崩（onCreate 之前）** | handler 还没装上就崩，可能漏报 | 概率极低（无自定义 ContentProvider）；必要时用 USB |
| **同一个包名换签名会冲突** | 换了签名必须卸载重装 → **丢数据** | 固定用同一台机器编译；换机器前先备份（见第 7 节） |
| `ApplicationExitInfo` 历史会被清 | 卸载重装后读不到旧崩溃 | 崩溃发生后尽快启动一次 app 触发上报 |
| ColorOS 剥离 adb 广播 extras | `am broadcast --es cmd ...` 在 Android 16 + ColorOS 上收不到参数 | 该 debug 后门在这台设备上不可用；改用 DB 注入或真实操作验证 |

---

## 6. 操作手册

### 6.1 远程拉崩溃（agent / 命令行）

```bash
# 取 API key（服务器 .env）
KEY=$(ssh -i ~/mypro/.mh_deploy/id_ed25519 root@188.166.172.192 \
      'grep "^MH_API_KEY" /opt/message_hub/.env | cut -d= -f2-')

# 最近崩溃列表
curl -s -H "X-API-Key: $KEY" "https://mh.jxitc.com/api/v1/diagnostics/crashes?limit=10" | python3 -m json.tool

# 某条的完整堆栈
curl -s -H "X-API-Key: $KEY" "https://mh.jxitc.com/api/v1/diagnostics/crashes/<id>" | python3 -c "import sys,json;print(json.load(sys.stdin)['stacktrace'])"
```

> ⚠️ 本机 DNS 可能缓存了旧 IP；走 Cloudflare 时加 `--resolve mh.jxitc.com:443:<CF_IP>`，
> 或直接 SSH 到服务器用 `127.0.0.1:5001`。

### 6.2 编译并发布新版本（一条命令）

```bash
# 1) 先把 versionCode 加一（app 靠它判断有没有新版本）
#    android_client/MessageHub/app/build.gradle.kts: versionCode / versionName

# 2) 编译 + 上传 + 写版本元数据 + 回读验证
MH_NOTES="这次改了什么（会显示在手机上）" ./scripts/publish-apk.sh

# 只上传已编译好的包：./scripts/publish-apk.sh --no-build
```

脚本做四件事：`assembleDebug` → 用 aapt2 读出 versionName/versionCode →
scp APK 与 `latest.json` 到 `instance/releases/`（该目录被 rsync 排除，`--delete` 不会清掉）→
curl 回读 `latest-info` 确认发布成功。

发布后手机上：**Settings → App 更新 → 检查更新 → 下载并安装**。
首次会在系统里要求"允许安装未知应用"（每个来源应用单独授权一次）。

### 6.3 本机 adb（装机/救砖时用）

```bash
export PATH="$HOME/mypro/toolchain/android-sdk/platform-tools:$PATH"
adb devices
adb install -r app-debug.apk          # 签名一致才可覆盖
adb shell run-as com.jxitc.messagehub cat files/logs/messagehub.log   # 文件日志
adb logcat -b crash                    # 崩溃缓冲
```

> 这台 OPPO 上 `adb install` 报 `Failure [-99]`，改用 `adb push` + `adb shell pm install -r`。

---

## 7. 数据备份（换机器/换签名前必读）

卸载重装会清空 app 数据（Room 数据库里的待同步消息、偏好设置）。**重装前先备份**：

```bash
adb exec-out run-as com.jxitc.messagehub tar cf - databases shared_prefs > backup.tar

# 恢复（macOS tar 与 Android toybox tar 格式不兼容，改用 push + cp 逐文件恢复）
adb push messagehub_database /data/local/tmp/
adb shell run-as com.jxitc.messagehub cp /data/local/tmp/messagehub_database databases/messagehub_database
# -wal / -shm / shared_prefs/*.xml 同理
```

2026-09-12 那次就是这么把 232 条历史记录和 14 条待同步消息救回来的。
备份留存：`~/mypro/tmp/mh-phone-backup-<date>/`

---

## 8. 相关代码位置

| 作用 | 文件 |
|---|---|
| 服务端崩溃 API | `api/v1/diagnostics.py` |
| 服务端数据模型 | `models/crash_report.py` |
| 崩溃列表页 | `templates/crashes.html`、`web/views.py` (`/crashes`) |
| APK 下载端点 | `web/views.py` (`/downloads/<filename>`, `_list_releases`) |
| 客户端采集 | `android_client/.../utils/CrashReporter.kt` |
| 客户端接入点 | `android_client/.../MessageHubApplication.kt` |
| 应用内更新逻辑 | `android_client/.../data/remote/UpdateChecker.kt` |
| 发布脚本 | `scripts/publish-apk.sh` |
| 日志安全阀（防超长日志杀进程） | `android_client/.../utils/Logger.kt` |
