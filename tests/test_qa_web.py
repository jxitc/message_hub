"""问答页必须真的能渲染。

这组测试的存在理由是一次真实的漏测：给回答加了"花费"显示之后，我只测了
`/api/v1/qa/ask`（走 `to_dict()`）和 `GET /ask`（没有 turn，不渲染回答卡片），
于是**模板里引用了模型上不存在的属性**这件事完全没被发现——线上每次提问都变成
500 错误页，而错误页是没有样式的，看起来就像"配色坏了"。

教训很具体：页面渲染的路径必须被渲染一次，光测 API 不算。
"""
import pytest

from models import db, QaTurn


class FakeResult:
    """一次 qa.ask() 的返回值，形状与服务端一致。"""

    @staticmethod
    def make(**overrides):
        result = {
            'question': '我给简单心理一共付了多少钱？',
            'rewritten': '我在简单心理一共支付了多少钱？',
            'keywords': ['简单心理', '退款'],
            'entities': [{'name': '简单心理', 'kind': 'org'}],
            'sources': [{'id': 'msg-1', 'timestamp': '2026-09-15T12:24:02',
                         'type': 'SMS', 'sender': '95588',
                         'text': '尾号9303卡支出300元', 'routes': ['entity'],
                         'why': ['实体「简单心理」']}],
            'cited': [1],
            'answer': '一共 900 元，退了 300 [1]',
            'steps': [
                {'step': 'rewrite', 'elapsed_ms': 900, 'input': 'q', 'output': 'r',
                 'tokens': {'prompt_tokens': 683, 'completion_tokens': 150,
                            'model': 'deepseek-chat'}, 'cost': 0.0026},
                {'step': 'recall', 'elapsed_ms': 100, 'keywords': [], 'entities_hit': [],
                 'by_text': [], 'counts': {'candidates': 5, 'used': 1,
                                           'entities_hit': 1, 'text_terms': 2}},
                {'step': 'answer', 'elapsed_ms': 1500,
                 'tokens': {'prompt_tokens': 10261, 'completion_tokens': 126},
                 'cost': 0.0215, 'cited': [1], 'context_chars': 120},
            ],
            'cost': 0.0241,
            'currency': '¥',
            'tokens': {'prompt': 10944, 'completion': 276},
            'elapsed_ms': 2500,
            'source': 'web',
        }
        result.update(overrides)
        return result


@pytest.fixture
def stub_pipeline(monkeypatch):
    import qa

    def fake_ask(question, history=None, source='web'):
        return FakeResult.make(question=question)

    monkeypatch.setattr(qa, 'ask', fake_ask)


class TestAskPageRenders:
    def test_the_answer_card_renders(self, app, client, stub_pipeline):
        """模板引用了模型上不存在的属性时，这条会失败——它就是为了这个而写的。"""
        response = client.post('/ask', data={'question': '我给简单心理一共付了多少钱？'})
        assert response.status_code == 200
        body = response.data.decode('utf-8')
        assert '一共 900 元' in body
        assert '约 ¥0.0241' in body, '花费要显示出来'
        assert '10944 + 276 tokens' in body

    def test_the_process_panel_renders_every_step(self, app, client, stub_pipeline):
        body = client.post('/ask', data={'question': 'x'}).data.decode('utf-8')
        for step in ('rewrite', 'recall', 'answer'):
            assert step in body
        assert '看这一步步是怎么走的' in body
        assert '实体「简单心理」' in body, '每条召回都要说明为什么'

    def test_a_failed_turn_still_renders(self, app, client, monkeypatch):
        """LLM 挂掉时给用户看的是一条错误提示，不是一个 500。"""
        import llm, qa

        def boom(question, history=None, source='web'):
            raise llm.LLMError('LLM 调用失败：超时')

        monkeypatch.setattr(qa, 'ask', boom)
        response = client.post('/ask', data={'question': 'x'})
        assert response.status_code == 200
        assert 'LLM 调用失败' in response.data.decode('utf-8')

    def test_the_just_answered_turn_has_full_rating_buttons(self, app, client,
                                                            stub_pipeline):
        body = client.post('/ask', data={'question': 'x'}).data.decode('utf-8')
        assert '👍 好' in body and '👎 差' in body
        assert '备注（可选）' in body

    def test_history_lets_you_rate_a_past_question(self, app, client, stub_pipeline):
        """经常是过一会儿才想起哪条答错了，所以历史里也必须能补评——
        只让刚问完那条能评，等于把调试入口关掉一半。"""
        client.post('/ask', data={'question': '第一个问题'})
        body = client.get('/ask').data.decode('utf-8')
        assert '第一个问题' in body, '问过的要出现在历史里'
        assert 'value="good"' in body and 'value="bad"' in body

    def test_rating_can_be_set_and_cleared(self, app, client, stub_pipeline):
        client.post('/ask', data={'question': 'x'})
        with app.app_context():
            turn_id = db.session.query(QaTurn.id).first()[0]

        client.post('/ask/rate/%s' % turn_id, data={'rating': 'bad', 'note': '召回错了'})
        with app.app_context():
            turn = db.session.get(QaTurn, turn_id)
            assert (turn.rating, turn.rating_note) == ('bad', '召回错了')

        client.post('/ask/rate/%s' % turn_id, data={'rating': ''})
        with app.app_context():
            turn = db.session.get(QaTurn, turn_id)
            assert turn.rating is None and turn.rated_at is None


class TestTokenShape:
    def test_the_model_and_the_api_expose_tokens_the_same_way(self, app):
        """模板直接渲染模型、API 渲染 to_dict()，两边形状必须一致。"""
        with app.app_context():
            turn = QaTurn(question='q', tokens_prompt=10, tokens_completion=5)
            db.session.add(turn)
            db.session.commit()
            assert turn.tokens == {'prompt': 10, 'completion': 5}
            assert turn.to_dict()['tokens'] == turn.tokens
