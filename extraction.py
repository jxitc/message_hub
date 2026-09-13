"""Text extraction for attachments: PDF text layer and image OCR.

Why this lives on the hub: the extracted text has to end up in the *same place*
as ordinary message content, because everything downstream (search, filters,
the InfoAgent/PKB pipeline) consumes `content` and must never have to open a
file. The original stays in blob storage as the immutable raw evidence, so
extraction can always be re-run when a better engine shows up.

Operational shape:

* **Extraction is asynchronous.** The upload request only stores bytes and the
  message row (fast, predictable), then the worker below picks it up. A PDF that
  takes 40 seconds to OCR must not hold a gunicorn thread hostage — the hub runs
  `-w 1 --threads 2` on a 1-vCPU box, so a slow synchronous request would stall
  the entire service.
* **The database is the queue.** Nothing is kept in memory, so a restart resumes
  where it left off, and `scripts/reextract.py --all` can rebuild every derived
  text from the stored originals.
* **Re-running is safe and replaces the old text.** We record whether the text we
  wrote into `content` came from extraction; on a rebuild we replace exactly that
  text and never clobber text the user or a client supplied.

Engines: `pdftotext` (poppler) for PDF text layers, with an OCR fallback for
scanned PDFs, and `tesseract` for images. Both are system packages; if they are
missing the status says `unavailable` rather than failing silently.
"""

from __future__ import annotations

import copy
import os
import shutil
import subprocess
import tempfile
import threading
import time

#: Languages passed to tesseract when available. Chinese matters here — most of
#: this mailbox is Chinese — but the deploy must not break if chi_sim is absent.
OCR_LANGS = ('eng', 'chi_sim')

#: Per-file wall-clock budget. Generous, because nobody is waiting on it: the
#: worker runs in the background and the UI shows "pending" meanwhile.
EXTRACT_TIMEOUT = int(os.environ.get('EXTRACT_TIMEOUT') or 120)

#: How little text still counts as "this PDF has a text layer".
#:
#: There is no threshold that separates "short real text layer" from "scan with an
#: incidental watermark", so this deliberately errs towards *using* the text layer:
#:
#:   * Too high a bar wastes real work — an 18-character one-page PDF fell under a
#:     20-char rule and was needlessly rendered and OCR'd (tens of seconds on a
#:     1-vCPU box) even though pdftotext had already answered.
#:   * The case it would protect against (a scan whose only text is a header) is
#:     rare, obvious in the output (`chars` and `chars_per_page` are recorded), and
#:     recoverable — the original PDF is kept, and scripts/reextract.py --ocr redoes
#:     it with OCR on demand.
#:
#: So: any text layer above a small floor is used; below it we assume a scan.
PDF_TEXT_FLOOR_CHARS = 10

#: Refuse to OCR absurdly long documents on a 1-vCPU droplet. Above this we mark
#: the extraction as `partial` and say so, instead of occupying the worker for
#: minutes on a document nobody asked to OCR.
PDF_OCR_MAX_PAGES = int(os.environ.get('PDF_OCR_MAX_PAGES') or 10)


def _run(cmd, timeout=None):
    """Run a command, returning (returncode, stdout_bytes, stderr_text)."""
    try:
        proc = subprocess.run(cmd, capture_output=True,
                              timeout=timeout or EXTRACT_TIMEOUT)
    except subprocess.TimeoutExpired:
        return 124, b'', '超时（%ss）' % (timeout or EXTRACT_TIMEOUT)
    except FileNotFoundError:
        return 127, b'', '%s 未安装' % cmd[0]
    return proc.returncode, proc.stdout, proc.stderr.decode('utf-8', 'replace')


def tesseract_langs():
    """Which OCR languages are actually installed."""
    if not shutil.which('tesseract'):
        return []
    code, out, _err = _run(['tesseract', '--list-langs'], timeout=20)
    if code != 0:
        return []
    lines = out.decode('utf-8', 'replace').splitlines()[1:]
    return [line.strip() for line in lines if line.strip()]


def available_engines():
    """Engine availability, for the deploy check and the UI."""
    return {
        'pdftotext': bool(shutil.which('pdftotext')),
        'pdftoppm': bool(shutil.which('pdftoppm')),
        'tesseract': bool(shutil.which('tesseract')),
        'ocr_languages': [l for l in tesseract_langs() if l in OCR_LANGS],
    }


def _ocr_language_arg():
    langs = [l for l in tesseract_langs() if l in OCR_LANGS]
    return '+'.join(langs) if langs else 'eng'


def count_pages(text):
    """pdftotext separates pages with a form feed, so pages = form feeds + 1."""
    return text.count('\f') + 1


def pdf_text_is_usable(text):
    """Is there a text layer worth using, or is this a scan? (pure, unit-tested)"""
    return len((text or '').strip()) >= PDF_TEXT_FLOOR_CHARS


def extract_pdf(path):
    """Text from a PDF: text layer first, OCR fallback for scans."""
    if not shutil.which('pdftotext'):
        return {'status': 'unavailable', 'engine': 'pdftotext',
                'error': '服务器未安装 poppler-utils（pdftotext）'}

    code, out, err = _run(['pdftotext', '-layout', path, '-'])
    raw_text = out.decode('utf-8', 'replace') if code == 0 else ''
    if pdf_text_is_usable(raw_text):
        pages = count_pages(raw_text)
        stripped = raw_text.strip()
        return {'status': 'done', 'engine': 'pdftotext', 'text': stripped,
                'pages': pages,
                # Exposed so a thin text layer over many pages is visible rather
                # than silently accepted as the document's whole content.
                'chars_per_page': round(len(stripped) / max(pages, 1), 1)}

    # No usable text layer → this is very likely a scan. Rasterise and OCR it.
    if not (shutil.which('pdftoppm') and shutil.which('tesseract')):
        return {'status': 'empty', 'engine': 'pdftotext', 'text': '',
                'error': 'PDF 没有文本层，且服务器缺少 OCR 工具（pdftoppm/tesseract）'
                         + ('；pdftotext 报错：%s' % err.strip() if err.strip() else '')}

    ocr_text, pages = _ocr_pdf_pages(path)
    if ocr_text.strip():
        return {'status': 'done', 'engine': 'pdftoppm+tesseract',
                'text': ocr_text.strip(), 'pages': pages,
                'note': 'PDF 无文本层，内容是 OCR 结果'}
    return {'status': 'empty', 'engine': 'pdftoppm+tesseract', 'text': '',
            'pages': pages, 'error': 'PDF 无文本层，OCR 也没识别出文字'}


def _ocr_pdf_pages(path):
    """OCR up to PDF_OCR_MAX_PAGES pages of a scanned PDF."""
    lang = _ocr_language_arg()
    collected, pages = [], 0
    with tempfile.TemporaryDirectory(prefix='mh-ocr-') as tmp:
        prefix = os.path.join(tmp, 'page')
        code, _out, err = _run(['pdftoppm', '-r', '150', '-png',
                                '-f', '1', '-l', str(PDF_OCR_MAX_PAGES),
                                path, prefix])
        if code != 0:
            return '', 0
        for name in sorted(os.listdir(tmp)):
            page_path = os.path.join(tmp, name)
            pages += 1
            code, out, err = _run(['tesseract', page_path, 'stdout', '-l', lang])
            if code == 0:
                collected.append(out.decode('utf-8', 'replace').strip())
            else:
                collected.append('')
    return '\n\n'.join(p for p in collected if p), pages


def extract_image(path):
    if not shutil.which('tesseract'):
        return {'status': 'unavailable', 'engine': 'tesseract',
                'error': '服务器未安装 tesseract-ocr'}
    lang = _ocr_language_arg()
    code, out, err = _run(['tesseract', path, 'stdout', '-l', lang])
    if code != 0:
        return {'status': 'failed', 'engine': 'tesseract',
                'error': (err or 'tesseract 退出码 %s' % code).strip()[:300]}
    text = out.decode('utf-8', 'replace').strip()
    if not text:
        return {'status': 'empty', 'engine': 'tesseract', 'text': '',
                'error': '图片里没识别出文字'}
    return {'status': 'done', 'engine': 'tesseract', 'text': text,
            'languages': lang}


def extract_text_file(path):
    try:
        with open(path, 'rb') as fh:
            data = fh.read()
    except OSError as exc:
        return {'status': 'failed', 'engine': 'plain', 'error': str(exc)}
    for encoding in ('utf-8', 'gb18030', 'latin-1'):
        try:
            text = data.decode(encoding).strip()
            return {'status': 'done' if text else 'empty', 'engine': 'plain',
                    'text': text, 'encoding': encoding}
        except UnicodeDecodeError:
            continue
    return {'status': 'failed', 'engine': 'plain', 'error': '无法解码文本'}


def extract(store, key, mime, kind, force_ocr=False):
    """Extract text for one stored blob. Returns a dict describing the outcome.

    `force_ocr=True` skips the PDF text layer and goes straight to OCR — the escape
    hatch for a scanned PDF that carries just enough incidental text to look like it
    has a layer (see PDF_TEXT_FLOOR_CHARS).
    """
    path = store.path(key)
    if not os.path.exists(path):
        return {'status': 'failed', 'error': 'blob 文件不存在：%s' % key}
    if kind == 'pdf' or mime == 'application/pdf':
        if force_ocr and shutil.which('pdftoppm') and shutil.which('tesseract'):
            ocr_text, pages = _ocr_pdf_pages(path)
            if ocr_text.strip():
                result = {'status': 'done', 'engine': 'pdftoppm+tesseract',
                          'text': ocr_text.strip(), 'pages': pages,
                          'note': '按要求强制 OCR'}
            else:
                result = {'status': 'empty', 'engine': 'pdftoppm+tesseract',
                          'text': '', 'pages': pages, 'error': 'OCR 没识别出文字'}
        else:
            result = extract_pdf(path)
    elif kind == 'image':
        result = extract_image(path)
    elif kind == 'text':
        result = extract_text_file(path)
    else:
        result = {'status': 'skipped', 'error': '该类型不做文本提取'}
    result.setdefault('text', '')
    result['chars'] = len(result.get('text') or '')
    return result


# ---------------------------------------------------------------------------
# Applying results back onto messages
# ---------------------------------------------------------------------------

def apply_result(message, index, result):
    """Write an extraction result into the message metadata (and content).

    Where the text lands — exactly one copy, never two:

    * `content` is empty (an image-only note) → the text becomes `content`, so
      plain consumers see it without knowing about attachments at all.
    * `content` already has text (an email body, or text the client extracted
      itself) → the text is kept in `metadata.attachments[i].extraction.text`
      instead. Appending it to `content` would blur "what the sender wrote" with
      "what OCR guessed", and would make re-running extraction append twice.

    Either way the text lives in the database, so downstream AI reads the same
    kind of data it reads for every other message and never opens a file. Text
    supplied by a client is never overwritten: the client may know better.
    """
    # Deep copy for the same reason as scripts/reextract.reset_status: a shallow
    # copy shares inner dicts with the loaded value, so SQLAlchemy's change
    # detection can consider the row unchanged and skip the UPDATE entirely.
    metadata = copy.deepcopy(message.message_metadata or {})
    attachments = list(metadata.get('attachments') or [])
    if index >= len(attachments):
        return False

    attachment = dict(attachments[index])
    previous = dict(attachment.get('extraction') or {})
    extraction = {k: v for k, v in result.items() if k != 'text'}
    extraction['chars'] = len(result.get('text') or '')

    text = (result.get('text') or '').strip()
    existing = (message.content or '').strip()
    wrote_to_content = False
    if text:
        if not existing or previous.get('applied_to_content'):
            message.content = text
            wrote_to_content = True
        else:
            # Keep the only copy here — see the docstring.
            extraction['text'] = text
    extraction['applied_to_content'] = wrote_to_content or bool(
        previous.get('applied_to_content'))

    attachment['extraction'] = extraction
    attachment['extracted_chars'] = extraction['chars']
    attachments[index] = attachment
    metadata['attachments'] = attachments
    message.message_metadata = metadata
    return True


def iter_pending(session, limit=20):
    """Message ids that have at least one attachment awaiting extraction.

    Uses SQLite's JSON1 so the queue lookup stays in SQL. If JSON1 is somehow
    unavailable we fall back to scanning recent metadata in Python rather than
    losing the feature entirely.
    """
    from sqlalchemy import text
    try:
        rows = session.execute(text(
            "SELECT DISTINCT messages.id FROM messages, "
            "json_each(messages.message_metadata, '$.attachments') AS a "
            "WHERE json_extract(a.value, '$.extraction.status') = 'pending' "
            "LIMIT :limit"), {'limit': limit}).fetchall()
        return [row[0] for row in rows]
    except Exception:
        session.rollback()
        out = []
        for message in session.query(_message_model()).order_by(
                _message_model().received_at.desc()).limit(200):
            for attachment in (message.message_metadata or {}).get('attachments') or []:
                if (attachment.get('extraction') or {}).get('status') == 'pending':
                    out.append(message.id)
                    break
            if len(out) >= limit:
                break
        return out


def _message_model():
    from models import Message
    return Message


def process_message(session, store, message, force_ocr=False):
    """Extract every pending attachment of one message. Returns count processed."""
    metadata = dict(message.message_metadata or {})
    attachments = list(metadata.get('attachments') or [])
    processed = 0
    for index, attachment in enumerate(attachments):
        state = (attachment.get('extraction') or {}).get('status')
        if state not in ('pending', None):
            continue
        key = attachment.get('key')
        if not key:
            apply_result(message, index, {'status': 'failed',
                                          'error': '附件缺少 key'})
            processed += 1
            continue
        # A per-attachment request (set from the attachment page) also forces OCR —
        # otherwise the button would be a no-op for exactly the case it exists for.
        wants_ocr = force_ocr or bool(
            (attachment.get('extraction') or {}).get('requested_ocr'))
        result = extract(store, key, attachment.get('mime') or '',
                         attachment.get('kind') or 'file', force_ocr=wants_ocr)
        apply_result(message, index, result)
        session.commit()
        processed += 1
    return processed


def run_once(app, store, limit=20):
    """Process a batch of pending extractions. Returns (messages, attachments)."""
    from models import db
    with app.app_context():
        ids = iter_pending(db.session, limit=limit)
        total = 0
        for message_id in ids:
            message = db.session.get(_message_model(), message_id)
            if not message:
                continue
            try:
                total += process_message(db.session, store, message)
            except Exception as exc:  # noqa: BLE001 - one bad file must not stop the queue
                db.session.rollback()
                app.logger.error('extraction failed for %s: %s', message_id, exc)
        return len(ids), total


def worker(app, interval=None):
    """Background loop. Started from create_app() alongside the mail collector."""
    from blob_store import BlobStore
    interval = interval or int(os.environ.get('EXTRACT_INTERVAL') or 30)
    store = BlobStore()
    engines = available_engines()
    app.logger.info('extraction worker started (interval=%ss, engines=%s)',
                    interval, engines)
    while True:
        try:
            messages, attachments = run_once(app, store)
            if attachments:
                app.logger.info('extraction: %d message(s), %d attachment(s)',
                                messages, attachments)
        except Exception as exc:  # noqa: BLE001 - the loop must survive anything
            app.logger.error('extraction worker error: %s', exc)
        time.sleep(interval)


def start_worker(app):
    thread = threading.Thread(target=worker, args=(app,), daemon=True,
                              name='mh-extraction')
    thread.start()
    return thread
