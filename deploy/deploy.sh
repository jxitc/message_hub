#!/usr/bin/env bash
#
# deploy/deploy.sh — Deploy (or re-deploy) Message Hub to a remote server.
#
#   Idempotent: safe to run repeatedly. Add the deployment SSH key to the
#   server's :root/.ssh/authorized_keys first (see docs/deploy.md).
#
#   Secrets are NOT stored in git. The SSH key lives locally at
#   <repo>/../.mh_deploy/id_ed25519 (chmod 600). Server-side SECRET_KEY is
#   generated once and kept in the server's $REMOTE_DIR/.env (also not in git).
#
# Usage:
#   # defaults (server 188.166.172.192, root, /opt/message_hub, port 5001)
#   ./deploy/deploy.sh
#
#   # override
#   MH_SERVER=1.2.3.4 MH_PORT=8080 MH_WORKERS=2 ./deploy/deploy.sh
#
set -euo pipefail

# ---- config (override via env) -------------------------------------------------
SERVER="${MH_SERVER:-188.166.172.192}"
SSH_USER="${MH_SSH_USER:-root}"
PORT="${MH_PORT:-5001}"
WORKERS="${MH_WORKERS:-1}"
TIMEOUT="${MH_TIMEOUT:-120}"   # 多部分上传/尾部慢链路留余量（提取是异步的，不占请求）
REMOTE_DIR="${MH_REMOTE_DIR:-/opt/message_hub}"
APP_NAME="message-hub"

# ---- paths ---------------------------------------------------------------------
DEPLOY_DIR="$(cd "$(dirname "$0")" && pwd)"            # <repo>/deploy
PROJECT_ROOT="$(cd "$DEPLOY_DIR/.." && pwd)"            # <repo>
DEPLOY_HOME="$(cd "$DEPLOY_DIR/../.." && pwd)"          # workspace root
SSH_KEY="${MH_SSH_KEY:-$DEPLOY_HOME/.mh_deploy/id_ed25519}"
KNOWN_HOSTS="${MH_KNOWN_HOSTS:-$DEPLOY_HOME/.mh_deploy/known_hosts}"

if [[ ! -f "$SSH_KEY" ]]; then
    echo "ERROR: deployment key not found: $SSH_KEY"; exit 1
fi

SSH_OPTS=(
    -i "$SSH_KEY"
    -o StrictHostKeyChecking=accept-new
    -o UserKnownHostsFile="$KNOWN_HOSTS"
    -o ConnectTimeout=12
)
RSYNC_SSH="ssh -i '$SSH_KEY' -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=$KNOWN_HOSTS"

# ---- rsync code (exclude runtime artifacts) ------------------------------------
echo "==> rsync project to ${SERVER}:${REMOTE_DIR}"
rsync -az --delete \
    -e "$RSYNC_SSH" \
    --exclude '.git' \
    --exclude '.mh_deploy' \
    --exclude 'venv' \
    --exclude 'android_client' \
    --exclude 'instance' \
    --exclude 'logs' \
    --exclude '__pycache__' \
    --exclude '*.pyc' \
    --exclude 'keystore' \
    --exclude '.gradle' \
    --exclude 'app/build' \
    --exclude '.DS_Store' \
    --exclude '.env' \
    --exclude 'deploy/secret' \
    "$PROJECT_ROOT/" "$SSH_USER@${SERVER}:${REMOTE_DIR}/"
# NOTE: --delete only affects files tracked in this rsync (it won't touch the
# remote venv/instance because those are in --exclude, which also protects them
# from deletion when using --delete-excluded-style semantics is avoided).

# ---- remote bootstrap (idempotent) ---------------------------------------------
echo "==> remote setup on ${SERVER}"
ssh "${SSH_OPTS[@]}" "$SSH_USER@${SERVER}" "bash -s" <<REMOTE
set -euo pipefail
cd "$REMOTE_DIR"

mkdir -p instance logs

# venv
if [ ! -d venv ]; then
  echo "==> creating venv"
  python3 -m venv venv
fi
venv/bin/pip install --quiet --upgrade pip
echo "==> installing requirements"
venv/bin/pip install --quiet -r requirements.txt

# SECRET_KEY: write once, keep (never re-generated on re-deploy)
if [ ! -f .env ]; then
  echo "==> generating SECRET_KEY (.env)"
  echo "SECRET_KEY=\$(openssl rand -hex 24)" > .env
  chmod 600 .env
fi
[ -f .env ] && set -a && . ./.env && set +a

# create DB schema (no sample data)
echo "==> ensuring schema"
venv/bin/python - <<'PY'
from app import create_app
from models import db
app = create_app()
with app.app_context():
    db.create_all()
print("tables ok")
PY

# run idempotent schema migrations (drops stale columns e.g. messages.is_read)
echo "==> running migrations"
venv/bin/python migrate.py

# systemd unit
echo "==> writing systemd unit"
cat > /etc/systemd/system/${APP_NAME}.service <<UNIT
[Unit]
Description=Message Hub Server
After=network.target

[Service]
Type=simple
User=root
WorkingDirectory=$REMOTE_DIR
EnvironmentFile=$REMOTE_DIR/.env
ExecStart=$REMOTE_DIR/venv/bin/gunicorn -w ${WORKERS} --threads 2 -b 0.0.0.0:${PORT} --timeout ${TIMEOUT} "app:create_app()"
Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable ${APP_NAME} >/dev/null 2>&1 || true
systemctl restart ${APP_NAME}

# firewall
if command -v ufw >/dev/null 2>&1; then
  echo "==> firewall (ufw)"
  # 附件独立源：把用户上传的文件放到与 Web UI 不同的源上提供（PDF/SVG 即使被诱导
  # 执行，也够不到主站的会话）。路径 /<key> -> /api/v1/blobs/<key>，鉴权仍由应用负责。
  #
  # 只在文件不存在时创建：之后 TLS 行是 certbot 追加的，每次部署都重写会把证书配置冲掉。
  #
  # ⚠️ 主机名必须是**一级**子域：Cloudflare 免费版 Universal SSL 只覆盖 apex 与一级
  # 子域，二级（如 blob.mh.jxitc.com）在边缘没有证书，握手会直接失败。
  if [ ! -f /etc/nginx/sites-available/mhblob.jxitc.com.conf ]; then
    cat > /etc/nginx/sites-available/mhblob.jxitc.com.conf <<'NGINX'
server {
    server_name mhblob.jxitc.com;
    client_max_body_size 1m;          # downloads only
    location / {
        proxy_pass http://127.0.0.1:5001/api/v1/blobs/;
        # 注意：\$ 在这里必须转义。本文件是「未加引号的外层 heredoc」，不转义会在本地
        # 就展开 nginx 变量，远端 set -u 会直接报 unbound variable 而中断部署。
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    listen 80;
}
NGINX
    ln -sf /etc/nginx/sites-available/mhblob.jxitc.com.conf /etc/nginx/sites-enabled/
    echo "  created mhblob.jxitc.com vhost (run: certbot --nginx -d mhblob.jxitc.com)"
  fi

  # 附件上传：nginx 默认 client_max_body_size 是 1MB，而单个附件上限正好是 1MB，
  # 加上 multipart 开销就会 413。给 API 单独放宽。
  cat > /etc/nginx/conf.d/mh-upload.conf <<'NGINX'
# 由 deploy.sh 写入：附件上传的体积上限。
client_max_body_size 16m;
NGINX
  nginx -t >/dev/null 2>&1 && systemctl reload nginx || true
  ufw allow OpenSSH >/dev/null 2>&1 || true
  ufw allow ${PORT}/tcp >/dev/null 2>&1 || true
  ufw --force enable >/dev/null 2>&1 || true
fi

echo "==> waiting for health"
for i in \$(seq 1 15); do
  if curl -fsS "http://127.0.0.1:${PORT}/health" >/dev/null 2>&1; then
    echo "HEALTH OK after \${i}s"
    break
  fi
  sleep 1
done
systemctl --no-pager --lines=0 status ${APP_NAME} || true
REMOTE

echo
echo "==> local check: http://${SERVER}:${PORT}/health"
curl -fsS --max-time 8 "http://${SERVER}:${PORT}/health" && echo && echo "DEPLOY OK"
