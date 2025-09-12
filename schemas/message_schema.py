from marshmallow import Schema, fields, validate, ValidationError

class MessageCreateSchema(Schema):
    source_device_id = fields.Str(required=True, validate=validate.Length(min=1, max=255))
    type = fields.Str(required=True, validate=validate.OneOf(['SMS', 'PUSH_NOTIFICATION', 'CALL_LOG', 'EMAIL']))
    sender = fields.Str(required=True, validate=validate.Length(min=1, max=255))
    content = fields.Str(required=True, validate=validate.Length(min=1))
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
    is_read = fields.Bool()

class MessageListSchema(Schema):
    messages = fields.List(fields.Nested(MessageResponseSchema))
    total = fields.Int()
    page = fields.Int()
    per_page = fields.Int()
    has_more = fields.Bool()