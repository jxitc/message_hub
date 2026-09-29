"""召回的文本路由必须能搜到附件里的 OCR 文本。

两个真实缺口，都是在线上量出来的：

1. **附件文本原先根本没被搜过。** OCR 出的字存在
   `metadata.attachments[].extraction.text`，不在 `content` 列里。于是只出现在
   扫描件里的关键词一条都找不到：`PASSPORT` 0→6、`G43934807` 0→6、
   `Terminal 5` 0→2、`驾驶证` 0→1。而 OCR 的用途恰恰就是这个。
2. **JSON 列里的非 ASCII 是转义的。** SQLAlchemy 的 JSON 列用 `json.dumps` 落库，
   非 ASCII 会被转义成 `\\uXXXX`——**这个库里没有任何一行 metadata 含字面中文**
   （2,628 行是转义形态）。所以 `metadata LIKE '%护照%'` 永远匹配不到，
   而 `'%PASSPORT%'` 能。中文关键词必须拿转义形态去比。
"""
from datetime import datetime

import pytest
from sqlalchemy import or_

import qa
from models import db, Message


def add(app, content, ocr_text=None, name='scan.pdf'):
    """一条消息；ocr_text 非空时把 OCR 结果塞进 metadata（走 SQLAlchemy 的 JSON 列，
    所以中文会被转义——这正是要测的那个形态）。"""
    metadata = {}
    if ocr_text is not None:
        metadata['attachments'] = [{
            'name': name, 'kind': 'pdf',
            'extraction': {'text': ocr_text, 'engine': 'tesseract', 'status': 'done'},
        }]
    with app.app_context():
        db.session.add(Message(
            id='msg-%d' % abs(hash((content, ocr_text))),
            source_device_id='phone', type='NOTE', sender='web',
            content=content, timestamp=datetime(2026, 9, 29),
            received_at=datetime(2026, 9, 29), message_metadata=metadata))
        db.session.commit()


def found(app, term):
    with app.app_context():
        return [row[0] for row in db.session.query(Message.id)
                .filter(or_(*qa._message_match_conditions(term))).all()]


class TestAttachmentTextIsSearchable:
    def test_an_english_term_only_present_in_ocr_text_is_found(self, app):
        add(app, '一本证件的扫描件', ocr_text='PASSPORT No. G43934807 EXPIRY 04 JUL 2020')
        assert found(app, 'G43934807'), '护照号只在附件 OCR 里，必须能搜到'

    def test_a_term_in_the_body_is_still_found(self, app):
        add(app, '正文里就写着 奥斯丁租车')
        assert found(app, '奥斯丁')

    def test_a_chinese_term_only_present_in_ocr_text_is_found(self, app):
        """这条是关键：中文在 JSON 列里是 `\\uXXXX` 形态。"""
        add(app, '一张手机拍的证件照', ocr_text='中华人民共和国 驾驶证 准驾车型 C1')
        assert found(app, '驾驶证'), '中文关键词必须能穿过 JSON 转义匹配到'

    def test_the_stored_metadata_really_is_escaped(self, app):
        """把上一条的前提钉住：如果哪天改成存字面中文，这条会失败，
        提醒我们那个"转义形态"的分支可以删掉了。"""
        add(app, '标题里有中文：护照', ocr_text='护照')
        with app.app_context():
            raw = db.session.execute(db.text(
                "SELECT message_metadata FROM messages LIMIT 1")).fetchone()[0]
        if '护照' in raw:
            pytest.skip('现在已经存字面中文了，转义分支可以删掉')
        assert '\\u' in raw, 'JSON 列应当存的是转义形态（本测试的前提）'

    def test_no_false_positive_for_an_absent_term(self, app):
        add(app, '一本证件的扫描件', ocr_text='PASSPORT No. G43934807')
        assert found(app, '登机牌') == []

    def test_both_spellings_are_tried(self, app):
        """英文词两种形态相同；中文词两种形态都试，所以两种存法都能命中。"""
        assert len(qa._message_match_conditions('PASSPORT')) == 2   # 正文 + metadata 各一
        assert len(qa._message_match_conditions('护照')) == 4       # 两种形态 × 两个列
