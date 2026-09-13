from marshmallow import Schema, fields, validate, ValidationError

from metadata_policy import MESSAGE_TYPES

class MessageCreateSchema(Schema):
    source_device_id = fields.Str(required=True, validate=validate.Length(min=1, max=255))
    type = fields.Str(required=True, validate=validate.OneOf(list(MESSAGE_TYPES)))
    sender = fields.Str(required=True, validate=validate.Length(min=1, max=255))
    # 允许空字符串，但只在这种情况下：带了附件（例如只上传一张图，正文等 OCR
    # 提取后再填）。"正文与附件不能同时为空" 这个不变式由 api/v1/messages.py 把关，
    # 放在那里比藏在 schema 的 min 里更容易看懂。
    content = fields.Str(required=True, validate=validate.Length(min=0))
    timestamp = fields.DateTime(required=True)
    metadata = fields.Dict(missing=dict)

class MessageResponseSchema(Schema):
    id = fields.Str()
    source_device = fields.Str()
    type = fields.Str()
    sender = fields.Str()
    content = fields.Str()
    timestamp = fields.DateTime()
    received_at = fields.DateTime()
    metadata = fields.Dict()

class MessageListSchema(Schema):
    messages = fields.List(fields.Nested(MessageResponseSchema))
    total = fields.Int()
    page = fields.Int()
    per_page = fields.Int()
    has_more = fields.Bool()