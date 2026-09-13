#!/usr/bin/env bash
# acceptance-attachments.sh — 验收**已部署**的附件链路（不是单元测试）
#
#   ./scripts/acceptance-attachments.sh
#   MH_BASE=... MH_BLOB_BASE=... MH_RESOLVE="--resolve host:443:ip" ./scripts/acceptance-attachments.sh
#
# 为什么要有它：pytest 跑的是内存里的 Flask 测试客户端，验证不了 nginx 的
# client_max_body_size、Cloudflare 的证书与代理、独立源的路由、gunicorn 的超时、
# OCR 工具链是否真装在服务器上、以及签名链接在真实浏览器场景下能不能用。
# 这些只有打真实域名才看得出来。
#
# 自己创建并删除 mh-accept 设备的测试数据，跑完不残留（孤儿 blob 也会 GC）。
set -uo pipefail

SSH_KEY="${MH_SSH_KEY:-$HOME/mypro/.mh_deploy/id_ed25519}"
SSH_HOST="${MH_SSH:-root@188.166.172.192}"
KNOWN_HOSTS="${MH_KNOWN_HOSTS:-$HOME/mypro/.mh_deploy/known_hosts}"
remote() {
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new \
      -o UserKnownHostsFile="$KNOWN_HOSTS" -o ConnectTimeout=12 "$SSH_HOST" "$@"
}

KEY_VALUE="${MH_API_KEY:-}"
if [ -z "$KEY_VALUE" ] && [ -f "$SSH_KEY" ]; then
  KEY_VALUE=$(remote 'grep -o "MH_API_KEY=.*" /opt/message_hub/.env | cut -d= -f2' 2>/dev/null)
fi
if [ -z "$KEY_VALUE" ]; then
  echo "需要 API key：设 MH_API_KEY=...，或让 $SSH_KEY 能登录 $SSH_HOST 以读取 .env"
  exit 1
fi

BASE="${MH_BASE:-https://mh.jxitc.com}"
BLOB_BASE="${MH_BLOB_BASE:-https://mhblob.jxitc.com}"
RESOLVE="${MH_RESOLVE:-}"
DEV=mh-accept
TMP=$(mktemp -d)
PASS=0; FAIL=0
trap 'rm -rf "$TMP"' EXIT

chk() {  # chk 描述 实际 期望
  if [ "$2" = "$3" ]; then printf '  ✅ %s: %s\n' "$1" "$2"; PASS=$((PASS+1))
  else printf '  ❌ %s: 期望 %s，实得 %s\n' "$1" "$3" "$2"; FAIL=$((FAIL+1)); fi
}
note() { printf '     %s\n' "$1"; }
jget() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

# ── 素材：必须有真文本层，否则"提取成功"证明不了任何事 ────────────────────────
python3 - "$TMP" <<'PY'
import os, struct, sys, zlib
d = sys.argv[1]

def pdf_with_text(path, text):
    """手搓带真文本层的 PDF（正确 xref），不依赖任何外部工具。"""
    content = ('BT /F1 24 Tf 40 700 Td (%s) Tj ET' % text).encode()
    objects = [
        (1, b'<< /Type /Catalog /Pages 2 0 R >>'),
        (2, b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>'),
        (3, b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] '
            b'/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>'),
        (4, b'<< /Length ' + str(len(content)).encode() + b' >>\nstream\n'
            + content + b'\nendstream'),
        (5, b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>'),
    ]
    out = bytearray(b'%PDF-1.4\n'); offsets = {}
    for num, body in objects:
        offsets[num] = len(out)
        out += b'%d 0 obj\n' % num + body + b'\nendobj\n'
    xref_at = len(out)
    out += b'xref\n0 %d\n' % (len(objects) + 1) + b'0000000000 65535 f \n'
    for num, _b in objects:
        out += b'%010d 00000 n \n' % offsets[num]
    out += (b'trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n'
            % (len(objects) + 1, xref_at))
    open(path, 'wb').write(bytes(out))

def image_with_text(path, text):
    """有 PIL 就画字；没有则纯白图，调用方据此降低断言强度。"""
    try:
        from PIL import Image, ImageDraw, ImageFont
        img = Image.new('RGB', (640, 140), 'white')
        draw = ImageDraw.Draw(img)
        font = None
        for pt in (48, 40, 32):
            try:
                font = ImageFont.load_default(size=pt); break
            except TypeError:
                continue
        draw.text((24, 44), text, fill='black', font=font)
        img.save(path)
        return 'text'
    except Exception as exc:
        sys.stderr.write('PIL 不可用（%s），改用空白图\n' % exc)
        w = h = 64
        raw = b''.join(b'\x00' + b'\xff\xff\xff' * w for _ in range(h))
        def chunk(tag, data):
            body = tag + data
            return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xffffffff)
        open(path, 'wb').write(b'\x89PNG\r\n\x1a\n'
            + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))
        return 'blank'

pdf_with_text(os.path.join(d, 'doc.pdf'), 'MH ACCEPTANCE 2026')
kind = image_with_text(os.path.join(d, 'shot.png'), 'MH OCR 2026')
open(os.path.join(d, 'fixture-kind'), 'w').write(kind)
open(os.path.join(d, 'notes.txt'), 'wb').write('纯文本附件\n'.encode())
open(os.path.join(d, 'evil.html'), 'wb').write(b'<html><script>alert(1)</script></html>')
open(os.path.join(d, 'binary.bin'), 'wb').write(b'PK\x03\x04\x00\x00\x00\x00binary\x00\x00')
open(os.path.join(d, 'huge.png'), 'wb').write(b'\x89PNG\r\n\x1a\n' + b'\x00' * 1200000)
PY
IMG_KIND=$(cat "$TMP/fixture-kind")

post_file() {  # post_file <file> [content]
  curl -s -o /dev/null -w '%{http_code}' $RESOLVE -X POST "$BASE/api/v1/messages" \
    -H "X-API-Key: $KEY_VALUE" -F "source_device_id=$DEV" -F "type=NOTE" \
    -F "sender=acceptance" -F "content=${2:-x}" \
    -F "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)" -F "attachments=@$1"
}
post_json() {
  curl -s -o /dev/null -w '%{http_code}' $RESOLVE -X POST "$BASE/api/v1/messages" \
    -H "X-API-Key: $KEY_VALUE" -H 'Content-Type: application/json' \
    -d "{\"source_device_id\":\"$DEV\",\"type\":\"$2\",\"sender\":\"acceptance\",\"content\":\"$1\",\"timestamp\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\"}"
}
upload() {  # upload <file> [content] [type]
  curl -s $RESOLVE -X POST "$BASE/api/v1/messages" -H "X-API-Key: $KEY_VALUE" \
    -F "source_device_id=$DEV" -F "type=${3:-NOTE}" -F "sender=acceptance" \
    -F "content=${2:-}" -F 'metadata={"source":"acceptance"}' \
    -F "timestamp=$(date -u +%Y-%m-%dT%H:%M:%SZ)" -F "attachments=@$1"
}
fetch() { curl -s $RESOLVE "$BASE/api/v1/messages/$1" -H "X-API-Key: $KEY_VALUE"; }

echo "── 1. 限额契约（客户端据此决定压缩）"
LIM=$(curl -s $RESOLVE "$BASE/api/v1/attachments/limits" -H "X-API-Key: $KEY_VALUE")
chk "max_bytes" "$(echo "$LIM" | jget "d['max_bytes']")" "1048576"
chk "允许类型数" "$(echo "$LIM" | jget "len(d['allowed'])")" "6"

echo "── 2. multipart 上传"
IMG=$(upload "$TMP/shot.png")
IMG_ID=$(echo "$IMG" | jget "d['id']")
IMG_KEY=$(echo "$IMG" | jget "d['attachments'][0]['key']")
IMG_URL=$(echo "$IMG" | jget "d['attachments'][0]['url']")
chk "图片上传成功" "$([ -n "$IMG_ID" ] && echo yes || echo no)" "yes"
chk "有附件时正文可为空" "$(echo "$IMG" | jget "d['data']['content']")" ""
chk "对外 URL 指向独立源" "$(echo "$IMG_URL" | grep -c "^$BLOB_BASE/")" "1"

PDF=$(upload "$TMP/doc.pdf" "正文在此" DOCUMENT)
PDF_ID=$(echo "$PDF" | jget "d['id']")
chk "PDF 上传 mime" "$(echo "$PDF" | jget "d['attachments'][0]['mime']")" "application/pdf"
chk "纯文本被接受" "$(post_file "$TMP/notes.txt")" "201"

echo "── 3. 拒绝路径（按内容校验，不采信声明）"
chk "真二进制（含 NUL）" "$(post_file "$TMP/binary.bin")" "415"
chk "危险扩展名 html" "$(post_file "$TMP/evil.html")" "415"
chk "超 1MB" "$(post_file "$TMP/huge.png")" "413"
chk "空正文且无附件" "$(post_json '' NOTE)" "400"
chk "未知类型" "$(post_json x WAT)" "400"
chk "同源下载无 key" "$(curl -s -o /dev/null -w '%{http_code}' $RESOLVE "$BASE/api/v1/blobs/$IMG_KEY")" "401"
chk "独立源下载无 token" "$(curl -s -o /dev/null -w '%{http_code}' $RESOLVE "$BLOB_BASE/$IMG_KEY")" "401"

echo "── 4. 独立源 + 签名链接（浏览器 <img>/<a> 带不了 header）"
TOK=$(remote "cd /opt/message_hub && set -a && . ./.env && set +a && ./venv/bin/python -c \"
import sys; sys.path.insert(0,'.')
from api.v1.blobs import sign
print(sign('$IMG_KEY'))
\" 2>/dev/null | tail -1")
chk "取到签名" "$([ ${#TOK} -gt 40 ] && echo yes || echo no)" "yes"
curl -s -o "$TMP/dl.png" $RESOLVE "$BLOB_BASE/$IMG_KEY?token=$TOK"
chk "签名下载字节一致" "$(cmp -s "$TMP/dl.png" "$TMP/shot.png" && echo yes || echo no)" "yes"
chk "伪造 token 被拒" "$(curl -s -o /dev/null -w '%{http_code}' $RESOLVE "$BLOB_BASE/$IMG_KEY?token=99.deadbeef")" "401"

echo "── 5. 异步提取（两条落位规则）"
note "content 为空 → 文本进 content；content 已有文本 → 留在 metadata.extraction.text"
sleep "${MH_EXTRACT_WAIT:-35}"
read -r st en ap <<<"$(fetch "$IMG_ID" | jget "'%s %s %s' % (d['metadata']['attachments'][0]['extraction'].get('status'), d['metadata']['attachments'][0]['extraction'].get('engine'), d['metadata']['attachments'][0]['extraction'].get('applied_to_content'))")"
chk "图片：状态/引擎/写进正文" "$st|$en|$ap" "done|tesseract|True"
if [ "$IMG_KIND" = "text" ]; then
  chk "OCR 文本与素材一致" "$(fetch "$IMG_ID" | jget "'MH' in d['content'] and '2026' in d['content']")" "True"
fi

read -r st en ap mt <<<"$(fetch "$PDF_ID" | jget "'%s %s %s %s' % (d['metadata']['attachments'][0]['extraction'].get('status'), d['metadata']['attachments'][0]['extraction'].get('engine'), d['metadata']['attachments'][0]['extraction'].get('applied_to_content'), ('ACCEPTANCE' in (d['metadata']['attachments'][0]['extraction'].get('text') or '') and d['content'] == '正文在此'))")"
# 主路径是 pdftotext；OCR 回退也接受（那是设计行为：无文本层或文本层过薄时自动回退）。
case "$st|$en" in
  "done|pdftotext"|"done|pdftoppm+tesseract") chk "PDF：状态/引擎" "$st|$en" "$st|$en" ;;
  *) chk "PDF：状态/引擎" "$st|$en" "done|pdftotext 或 done|pdftoppm+tesseract" ;;
esac
chk "PDF：未覆盖正文且文本在 metadata" "$ap|$mt" "False|True"

echo "── 6. 健康与引擎"
chk "健康" "$(curl -s $RESOLVE "$BASE/health" | jget "d['status']")" "healthy"
chk "OCR/PDF 引擎已装（含中文）" "$(curl -s $RESOLVE "$BASE/api/v1/blobs" -H "X-API-Key: $KEY_VALUE" | jget "'yes' if d['engines'].get('pdftotext') and d['engines'].get('tesseract') and 'chi_sim' in d['engines']['ocr_languages'] else 'no'")" "yes"

echo "── 清理测试数据"
curl -s $RESOLVE -X POST "$BASE/api/v1/messages/delete" -H "X-API-Key: $KEY_VALUE" \
  -H 'Content-Type: application/json' -d "{\"device\":\"$DEV\",\"dry_run\":false}" >/dev/null
remote 'cd /opt/message_hub && ./venv/bin/python scripts/blob-gc.py --apply 2>&1 | tail -1' 2>/dev/null

echo
echo "════ 通过 $PASS 项，失败 $FAIL 项 ════"
[ "$FAIL" -eq 0 ] || exit 1
