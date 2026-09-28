"""One place that talks to the LLM.

Only the answering pipeline uses it, but that pipeline makes three different
calls per question (rewrite, extract, answer) and they must not each grow their
own retry/timeout/JSON habits — the drift shows up as one step mysteriously
returning prose where JSON was expected.

The key is read from config (which reads the environment / the server's `.env`)
and is never written to a file that is tracked by git.
"""

from __future__ import annotations

import json
import time

import requests
from flask import current_app


class LLMError(RuntimeError):
    """Raised when the model could not be reached or returned nothing usable."""


def configured() -> bool:
    return bool(current_app.config.get('LLM_API_KEY'))


def chat(messages, *, json_mode=False, temperature=0.2, timeout=120, tries=3,
         max_tokens=None):
    """Call the chat endpoint. Returns (content, usage_dict).

    `usage` is returned rather than logged-and-forgotten because the QA log
    records tokens per step — "why was this answer slow/expensive" is only
    answerable later if it was written down at the time.
    """
    key = current_app.config.get('LLM_API_KEY')
    if not key:
        raise LLMError('未配置 LLM_API_KEY（服务器 .env 里加一行 LLM_API_KEY=...）')

    base = (current_app.config.get('LLM_BASE_URL') or '').rstrip('/')
    payload = {
        'model': current_app.config.get('LLM_MODEL'),
        'messages': messages,
        'temperature': temperature,
    }
    if json_mode:
        payload['response_format'] = {'type': 'json_object'}
    if max_tokens:
        payload['max_tokens'] = max_tokens

    last_error = None
    for attempt in range(tries):
        started = time.time()
        try:
            response = requests.post(
                base + '/chat/completions', json=payload, timeout=timeout,
                headers={'Authorization': 'Bearer ' + key,
                         'Content-Type': 'application/json'})
            if response.status_code >= 400:
                raise LLMError('HTTP %s: %s' % (response.status_code,
                                                response.text[:300]))
            body = response.json()
            content = body['choices'][0]['message']['content']
            usage = body.get('usage') or {}
            usage['elapsed_ms'] = int((time.time() - started) * 1000)
            usage['model'] = body.get('model') or payload['model']
            return content, usage
        except (requests.RequestException, ValueError, KeyError,
                LLMError) as exc:
            last_error = exc
            current_app.logger.warning('LLM call failed (attempt %d/%d): %s',
                                       attempt + 1, tries, exc)
            time.sleep(2 ** attempt)
    raise LLMError('LLM 调用失败：%s' % last_error)


def chat_json(messages, **kwargs):
    """Same, but parses the reply as JSON.

    Models still wrap JSON in ``` fences or add a sentence in front of it despite
    `response_format`, so the outermost braces are extracted before parsing. A
    parse failure is a normal, expected outcome here — callers get a clear error
    instead of a confusing KeyError three steps later.
    """
    kwargs['json_mode'] = True
    content, usage = chat(messages, **kwargs)
    text = (content or '').strip()
    if text.startswith('```'):
        text = text.strip('`')
        text = text.split('\n', 1)[1] if '\n' in text else text
    start, end = text.find('{'), text.rfind('}')
    if start == -1 or end <= start:
        raise LLMError('模型没有返回 JSON：%s' % text[:200])
    try:
        return json.loads(text[start:end + 1]), usage
    except ValueError as exc:
        raise LLMError('模型返回的 JSON 解析失败：%s' % exc)
