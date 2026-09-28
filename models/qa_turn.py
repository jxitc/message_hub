from . import db
from datetime import datetime
import uuid


class QaTurn(db.Model):
    """一行 = 用户的一次提问，连同这条 pipeline 的全部过程。

    为什么把"过程"存进主库而不是日志文件：一次答错之后要能回答"是哪一步错了"——
    改写丢了主语？实体没抽到？召回是空的？日志文件能看不能查，而这里要按
    rating='bad' 反查、按关键词反查、给手机端拉历史，都是查询。

    同表还承载手机端聊天记录：它就是"用户问过的每一句话 + 回答"的唯一事实来源，
    没有第二份。
    """

    __tablename__ = 'qa_turns'

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    question = db.Column(db.Text, nullable=False)
    rewritten = db.Column(db.Text)
    answer = db.Column(db.Text)

    # 每一步的输入输出、耗时、token —— 见 qa.py 的 steps
    keywords = db.Column(db.JSON, default=list)
    entities = db.Column(db.JSON, default=list)
    sources = db.Column(db.JSON, default=list)
    steps = db.Column(db.JSON, default=list)
    cited = db.Column(db.JSON, default=list)

    elapsed_ms = db.Column(db.Integer)
    #: 谁问的：web | android。手机端要拉自己的历史，所以要区分。
    source = db.Column(db.String(32), default='web', index=True)
    #: 会话分组：手机端每个聊天会话一个 id；网页端暂用 'web'。
    conversation_id = db.Column(db.String(64), default='web', index=True)

    #: 人工评价，供以后 debug：good | bad，null 表示还没评。
    rating = db.Column(db.String(16), index=True)
    rating_note = db.Column(db.Text)
    rated_at = db.Column(db.DateTime(timezone=True))

    error = db.Column(db.Text)
    created_at = db.Column(db.DateTime(timezone=True), nullable=False,
                           default=datetime.utcnow, index=True)

    def to_dict(self, with_steps=True):
        payload = {
            'id': self.id,
            'question': self.question,
            'rewritten': self.rewritten,
            'answer': self.answer,
            'keywords': self.keywords or [],
            'entities': self.entities or [],
            'cited': self.cited or [],
            'source_count': len(self.sources or []),
            'elapsed_ms': self.elapsed_ms,
            'source': self.source,
            'conversation_id': self.conversation_id,
            'rating': self.rating,
            'rating_note': self.rating_note,
            'error': self.error,
            'created_at': self.created_at.isoformat() if self.created_at else None,
        }
        if with_steps:
            payload['steps'] = self.steps or []
            payload['sources'] = self.sources or []
        return payload
