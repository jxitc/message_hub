from marshmallow import Schema, fields, validate

class DeviceRegisterSchema(Schema):
    id = fields.Str(required=True, validate=validate.Length(min=1, max=255))
    name = fields.Str(required=True, validate=validate.Length(min=1, max=255))
    type = fields.Str(required=True, validate=validate.OneOf(['android', 'ios', 'web', 'api']))

class DeviceResponseSchema(Schema):
    id = fields.Str()
    name = fields.Str()
    type = fields.Str()
    last_sync_at = fields.DateTime()
    is_active = fields.Bool()
    created_at = fields.DateTime()

class DeviceListSchema(Schema):
    devices = fields.List(fields.Nested(DeviceResponseSchema))
    total = fields.Int()