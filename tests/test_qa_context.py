"""上下文组装：召回对了，但内容被截掉，等于没召回。

真实事故：护照那两条 👎 修完 OCR、修完召回之后仍然答不出来。原因是 `qa.py` 对
**每条消息只取 700 字**，而两本护照是同一个消息的两个附件——实测那条消息的附件文本
合计上千字，实际发给模型的只有 701 字，**新护照那一段根本没进上下文**。
全库更极端的：一条消息附件文本 31,964 字，同样只送 701 字。

所以这里钉三件事：正文与附件各有自己的额度；一条长消息不能吃掉全部预算；
排在后面的来源不能被整体丢掉。
"""
from datetime import datetime

import qa
from models import Message


def make_message(app, content, attachments):
    with app.app_context():
        message = Message(
            id='m-%d' % abs(hash((content, tuple(attachments)))),
            source_device_id='phone', type='NOTE', sender='web', content=content,
            timestamp=datetime(2026, 9, 29), received_at=datetime(2026, 9, 29),
            message_metadata={'attachments': [
                {'name': name, 'kind': 'pdf',
                 'extraction': {'text': text, 'engine': 'tesseract', 'status': 'done'}}
                for name, text in attachments]})
        return message


class TestPerMessageBudget:
    def test_attachment_text_actually_reaches_the_model(self, app):
        """正文很长也不能把附件挤没——附件往往才是这条消息被召回的原因。"""
        message = make_message(app, '正' * 5000, [('护照.pdf', '护照号 EF0000001 有效期至 01 JAN 2030')])
        text = qa._message_text(message)
        assert 'EF0000001' in text, '附件内容必须出现在上下文里'
        assert '01 JAN 2030' in text

    def test_each_attachment_gets_its_own_slice(self, app):
        """同一条消息里的第二个附件不能被第一个吃掉（两本护照就是这种情况）。"""
        message = make_message(app, '见附件', [
            ('旧护照.pdf', '旧本 ' + '甲' * 4000),
            ('新护照.pdf', '新本 EF0000002 有效期至 16 JUL 2029'),
        ])
        text = qa._message_text(message)
        assert 'EF0000002' in text, '第二个附件的内容也必须进来'
        assert '16 JUL 2029' in text

    def test_the_per_message_cap_still_holds(self, app):
        message = make_message(app, '正' * 3000, [('a.pdf', '甲' * 3000), ('b.pdf', '乙' * 3000)])
        assert len(qa._message_text(message)) <= qa.MAX_SOURCE_EXCERPT + 200

    def test_an_explicit_budget_is_respected(self, app):
        message = make_message(app, '正' * 3000, [('a.pdf', '甲' * 3000)])
        assert len(qa._message_text(message, budget=900)) <= 1100
