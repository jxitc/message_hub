# Message Hub — 远端部署指南

本文档记录把 Message Hub 部署到远程服务器的全过程，并给出一个**可重复**执行的部署脚本
（`deploy/deploy.sh`）。换一台机器/服务器照抄即可。

> 部署时间：2026-09-03。服务器：DigitalOcean，1 vCPU / 1 GB，Ubuntu 24.10，伦敦（lon1）。

## 1. 目标

- 把 Message Hub（Flask + SQLite）跑在远端，由 **systemd** 托管（常驻、开机自启、掉线自动重启）。
- 访问入口：`http://<SERVER_IP>:5001`（Web UI + API 同一端口）。
- 部署可自动化：`./deploy/deploy.sh` 一键完成 rsync 推代码 → 建 venv → 装依赖 →
  写 systemd 单元 → 开防火墙 → 健康检查。

## 2. 当前服务器

| 项 | 值 |
|---|---|
| 公网 IP | `188.166.172.192` |
| SSH | `root@188.166.172.192`，**密钥登录** |
| 主机名 | `ubuntu-s-1vcpu-1gb-lon1-01`（Ubuntu 24.10, 1vCPU/1GB, 24G 盘） |
| 应用目录 | `/opt/message_hub` |
| 端口 | `5001`（`0.0.0.0:5001`） |
| 运行 | `gunicorn -w 1 -b 0.0.0.0:5001 "app:create_app()"`，systemd 单元 `message-hub.service` |
| 数据库 | SQLite：`/opt/message_hub/instance/message_hub.db`（无样例数据，纯空 schema） |
| 防火墙 | `ufw`：允许 `OpenSSH`(22) 与 `5001/tcp` |

## 3. 一次性准备：SSH 密钥

部署走**密钥**认证（不把密码放脚本/仓库）。

1. 本机生成专用部署密钥（**不要**提交 git）：
   ```bash
   mkdir -p ~/mypro/.mh_deploy && chmod 700 ~/mypro/.mh_deploy
   ssh-keygen -t ed25519 -f ~/mypro/.mh_deploy/id_ed25519 -N '' -C "mh-deploy"
   chmod 600 ~/mypro/.mh_deploy/id_ed25519
   ```
2. 把 **公钥** `~/mypro/.mh_deploy/id_ed25519.pub` 加到服务器 `root` 的 `authorized_keys`：
   ```bash
   # 在服务器上（root@SERVER）
   mkdir -p ~/.ssh && chmod 700 ~/.ssh
   echo "ssh-ed25519 AAAA... mh-deploy" >> ~/.ssh/authorized_keys
   chmod 600 ~/.ssh/authorized_keys
   ```
3. 本机首次连接会记录主机指纹到 `~/mypro/.mh_deploy/known_hosts`。

> ⚠️ 密钥与 known_hosts 都在 `~/mypro/.mh_deploy/`（仓库外），被 `.gitignore` 之外的根目录天然隔离，
> 不会进 git。服务器端 `SECRET_KEY` 生成后只存在服务器的 `/opt/message_hub/.env`，也不进 git。

## 4. 一键部署

```bash
cd /Users/jxitc/mypro/message_hub
./deploy/deploy.sh
```

可用环境变量覆盖默认值：

```bash
MH_SERVER=1.2.3.4 MH_PORT=8080 MH_WORKERS=2 ./deploy/deploy.sh
```

脚本做（全部**幂等**，可反复跑）：

1. `rsync -az --delete` 推项目（排除 `.git` / `venv` / `android_client` / `instance` / `logs` / `keystore` 等运行时产物）。
2. 远端：建 `instance logs` 目录 → 无 `venv` 则建 → `pip install -r requirements.txt`。
3. 首次生成 `SECRET_KEY`（`openssl rand -hex 24`，写入 `/opt/message_hub/.env`，`chmod 600`；重复部署不重生成）。
4. 建表：`db.create_all()`（**不塞示例数据**）。
5. 写 systemd 单元 `/etc/systemd/system/message-hub.service`，`daemon-reload`、`enable`、`restart`。
6. `ufw allow OpenSSH` + `ufw allow 5001/tcp` + `ufw --force enable`。
7. 轮询 `http://127.0.0.1:5001/health` 直到 OK，然后本机 curl 公网 `/health` 二次确认。

## 5. 部署后验证

```bash
# 健康
curl http://188.166.172.192:5001/health
# Web UI（页面 200）
curl -I http://188.166.172.192:5001/dashboard
curl -I http://188.166.172.192:5001/messages
curl -I http://188.166.172.192:5001/status
# API 入库闭环（POST -> GET）
curl -X POST http://188.166.172.192:5001/api/v1/messages -H 'Content-Type: application/json' \
  -d '{"source_device_id":"deploy-test","type":"SMS","sender":"+100","content":"smoke test","timestamp":"2026-09-03T21:45:00Z"}'
curl 'http://188.166.172.192:5001/api/v1/messages?limit=5'
```

## 6. 运维常用

```bash
# 服务状态 / 重启 / 日志
systemctl status message-hub
systemctl restart message-hub
journalctl -u message-hub -f          # 实时日志
# 直接查看 gunicorn 输出（服务日志）
# 数据库
cd /opt/message_hub && venv/bin/python -c "import sqlite3;c=sqlite3.connect('instance/message_hub.db');print(c.execute('select count(*) from messages').fetchone()[0])"
```

## 7. 更新代码

改完本地 → 重新 `./deploy/deploy.sh`（rsync 增量 + systemctl restart，几分钟内）。

回滚：`git` 回退后重新部署，或在服务器上恢复文件后 `systemctl restart message-hub`。

## 8. 把手机指向公网服务器

安卓端 `server_url` 改为 `http://188.166.172.192:5001`（手机在任意网络都能推上来）。
改法（任选）：
- App 内 Settings → 服务器地址 → 填上面 URL。
- 或 adb 预置偏好（参考 Android 侧文档）：写入 `messagehub_prefs.xml`。

## 9. 安全提醒（重要）

当前 API **无认证**（MVP 阶段）。公网暴露意味着：任何能访问 `5001` 的人都可 `POST` 垃圾消息、
`GET` 读取全部消息。已做的最低限度：
- `ufw` 只放行 `22` 和 `5001`。
- `SECRET_KEY` 已生成（用于 flash/session 签名）。

**强烈建议后续**：加共享 `API Key` 鉴权（服务器端校验 header + 安卓端带上），或至少将 `/api` 置于
反向代理（nginx）并用 `Basic Auth`/IP 白名单保护。未做之前请勿上传敏感数据。

## 10. 目录结构说明

- `deploy/deploy.sh`：部署脚本（本仓库内）。
- `docs/deploy.md`：本文档。
- 部署相关密钥/机密**均不在本仓库**（密钥在仓库外的 `~/.mh_deploy/`，`SECRET_KEY` 在服务器 `.env`）。

## 11. 域名接入（mh.jxitc.com，可选）

用子域名替代 IP，且可用**不带端口**的 `http://mh.jxitc.com/health`。

- **DNS（Squarespace）**：给 `jxitc.com` 加一条 `A` 记录，Host=`mh`，Value=`188.166.172.192`。
  生效验证：`dig +short mh.jxitc.com`（应返回 188.166.172.192）。
- **服务器 nginx 反向代理**：`188.166.172.192` 上已装 nginx（或 `apt install -y nginx`），
  新增 `/etc/nginx/sites-available/mh.jxitc.com.conf`（`server_name mh.jxitc.com;` `proxy_pass http://127.0.0.1:5001;`），
  `ln -s` 进 `sites-enabled`，`nginx -t && systemctl reload nginx`。
  - 注意：服务器原本就有 `ads-science.com` vhost（另一站点），新增的 `mh.jxitc.com.conf` 与之并存、互不影响。
- **防火墙**：`ufw allow 'Nginx Full'`（80/443）；`5001/tcp` 目前仍放行（手机暂还指向 `:5001`），
  **等手机切成 `http://mh.jxitc.com` 后**再 `ufw delete allow 5001/tcp` 收回内网，更安全。
- **HTTPS（已启用 2026-09-03）**：`certbot --nginx -d mh.jxitc.com`（`--register-unsafely-without-email --redirect`）；
  证书到期 `2026-12-02`，`certbot.timer` 自动续期；`http` 301 → `https`，`https://mh.jxitc.com/health` 返回 healthy。
- 验证：`curl http://mh.jxitc.com/health` → healthy；`curl http://mh.jxitc.com/` → Web UI。
