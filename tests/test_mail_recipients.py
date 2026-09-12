"""Recipient (To/Cc) extraction from forwarded mail.

The case that motivated this: a hosted mailbox forwarded into Gmail. Delivered-To
is rewritten to the Gmail account, so only the To header says which mailbox the
message was really addressed to.
"""
import mail_collector as mc


def raw(headers, body='Body text'):
    return (headers + '\n\n' + body).encode('utf-8')


def test_to_header_is_the_recipient():
    parsed = mc.parse_message(raw(
        'Delivered-To: jiangxjx@gmail.com\n'
        'From: Someone <no-reply@everyoneactive.co.uk>\n'
        'To: Xiao J <jxitc@hotmail.com>\n'
        'Subject: Booking Confirmation\n'
        'Message-ID: <abc@example.com>'))

    assert parsed['recipients'] == ['jxitc@hotmail.com']
    # Delivered-To is available on the parsed dict, but must never be mistaken
    # for the address the mail was addressed to.
    assert parsed['delivered_to'] == 'jiangxjx@gmail.com'
    assert parsed['to'] == 'Xiao J <jxitc@hotmail.com>'


def test_cc_addresses_are_included():
    parsed = mc.parse_message(raw(
        'From: a@b.com\n'
        'To: one@example.com\n'
        'Cc: two@example.com, three@example.com\n'
        'Subject: hi'))
    assert parsed['recipients'] == ['one@example.com', 'two@example.com', 'three@example.com']


def test_recipients_are_deduped_case_insensitively():
    parsed = mc.parse_message(raw(
        'From: a@b.com\n'
        'To: One@Example.com\n'
        'Cc: one@example.com\n'
        'Subject: hi'))
    assert parsed['recipients'] == ['One@Example.com']


def test_mime_encoded_display_names_decode():
    # =?utf-8?B?5rWL6K+V?= is "测试"
    parsed = mc.parse_message(raw(
        'From: a@b.com\n'
        'To: =?utf-8?B?5rWL6K+V?= <alias+test@jxitc.com>\n'
        'Subject: hi'))
    assert parsed['recipients'] == ['alias+test@jxitc.com']
    assert '测试' in parsed['to']


def test_x_original_to_is_parsed_but_not_stored():
    """Parsing keeps it; storage must not, or we would have two sources of truth."""
    parsed = mc.parse_message(raw(
        'From: a@b.com\n'
        'To: catchall@example.com\n'
        'X-Original-To: real@example.com\n'
        'Subject: hi'))
    assert parsed['original_to'] == 'real@example.com'
    assert 'original_to' not in mc.build_payload(parsed, 'main')['metadata']


def test_missing_to_is_empty_not_broken():
    parsed = mc.parse_message(raw('From: a@b.com\nSubject: hi'))
    assert parsed['recipients'] == []
    assert parsed['to'] == ''
    # content still renders, with an explicit placeholder
    payload = mc.build_payload(parsed, 'main')
    assert 'To: (unknown)' in payload['content']


def test_payload_puts_to_in_content_and_a_single_key_in_metadata():
    parsed = mc.parse_message(raw(
        'From: no-reply@everyoneactive.co.uk\n'
        'To: Xiao J <jxitc@hotmail.com>\n'
        'Cc: Someone <cc@example.com>\n'
        'Subject: Booking Confirmation\n'
        'Date: Mon, 1 Sep 2025 10:00:00 +0100'))

    payload = mc.build_payload(parsed, 'main')
    # Readable provenance lives in content...
    assert '\nTo: Xiao J <jxitc@hotmail.com>\n' in payload['content']
    # ...and metadata carries the normalised list only (Cc addresses included).
    assert payload['metadata']['recipients'] == ['jxitc@hotmail.com', 'cc@example.com']
    assert payload['metadata']['mailbox'] == 'main'
    assert payload['source_device_id'] == 'mail-main'


def test_metadata_has_exactly_one_recipient_key():
    """No duplicate copies of the same fact — see migrate.py for the history."""
    parsed = mc.parse_message(raw(
        'Delivered-To: me@gmail.com\n'
        'From: a@b.com\n'
        'To: X <one@example.com>\n'
        'Cc: Y <two@example.com>\n'
        'X-Original-To: three@example.com\n'
        'Subject: hi'))
    metadata = mc.build_payload(parsed, 'main')['metadata']

    assert set(metadata) == {'mailbox', 'message_id', 'subject', 'recipients'}
    for removed in ('to', 'cc', 'delivered_to', 'original_to'):
        assert removed not in metadata


def test_empty_recipient_list_is_omitted_from_metadata():
    """Keeps the JSON small — no keys with empty values."""
    parsed = mc.parse_message(raw('From: a@b.com\nSubject: hi'))
    metadata = mc.build_payload(parsed, 'main')['metadata']
    assert 'recipients' not in metadata
    assert set(metadata) == {'mailbox', 'message_id', 'subject'}


def test_extract_addresses_handles_bare_and_bracketed():
    assert mc.extract_addresses('jxitc@hotmail.com') == ['jxitc@hotmail.com']
    assert mc.extract_addresses('<jxitc@hotmail.com>') == ['jxitc@hotmail.com']
    assert mc.extract_addresses('Name <a@b.com>, c@d.com') == ['a@b.com', 'c@d.com']
    assert mc.extract_addresses('') == []
    assert mc.extract_addresses('no-address-here') == []
