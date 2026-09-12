"""The field contract must stay honest about the schema it describes."""
from models import Message
import message_contract as contract


def test_common_fields_all_exist_as_real_columns():
    """The contract's first tier claims these are columns — keep it true.

    If someone renames or drops a column, this fails instead of leaving the
    documented contract describing a schema that no longer exists.
    """
    columns = {c.name for c in Message.__table__.columns}
    missing = set(contract.COMMON_FIELDS) - columns
    assert not missing, 'contract documents non-existent columns: %s' % sorted(missing)


def test_lint_flags_facts_that_duplicate_a_column():
    issues = contract.lint_metadata('SMS', {
        'timestamp': '1788472487821',   # duplicates messages.timestamp
        'source': 'phone',              # derivable from type
        'contact_name': '张丽捷',        # duplicates sender
        'phone_number': '+447907011046',  # genuine channel detail — not a duplicate
    })
    flagged = ' '.join(issues)
    assert 'metadata.timestamp' in flagged
    assert 'metadata.source' in flagged
    assert 'metadata.contact_name' in flagged
    assert 'phone_number' not in flagged


def test_lint_ignores_empty_values():
    """A key present but empty is not a second source of truth."""
    assert contract.lint_metadata('SMS', {
        'timestamp': '', 'source': None, 'contact_name': [],
    }) == []
    assert contract.lint_metadata('SMS', {}) == []
    assert contract.lint_metadata('SMS', None) == []


def test_lint_accepts_a_conforming_email():
    assert contract.lint_metadata('EMAIL', {
        'mailbox': 'main',
        'message_id': 'abc@example.com',
        'subject': 'hi',
        'recipients': ['jxitc@hotmail.com'],
    }) == []


def test_reserved_names_are_not_flagged_as_redundant():
    """`recipients` is the reserved public name, not a duplicate."""
    assert 'recipients' not in contract.REDUNDANT_METADATA_KEYS
    assert 'recipients' in contract.RESERVED_JSON_NAMES


def test_describe_contract_covers_all_three_tiers():
    text = contract.describe_contract()
    assert '第一层：公共列' in text
    assert '第二层：公共保留名' in text
    assert '第三层：渠道私有键' in text
    for column in contract.COMMON_FIELDS:
        assert column in text
