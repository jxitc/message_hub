"""Deterministic date extraction for message text — the LLM must not guess dates.

Read side of this pair is qa.py's calendar route; the write side is
pkb_extract.py, which stores the result on each message row. This module is the
one place that decides what date a message is *about*.

(2026-09-28：从 info_agent 搬到 message_hub。原位置是早期 PKB 实验的目录，
而实体索引现在已经是有 API、有网页、被问答流水线依赖的 hub 契约的一部分，
产出它的程序就该和读它的 pkb_index.py 待在同一个仓库里。)

Why this module exists: the extractor used to have the model return an
`event_date`, and it got two things wrong on real data —

  * a refund SMS reading "9月28日07:11" (received 2026-09-27) came back as
    **2025**-09-28: no year in the text, so the model invented one;
  * the same instant appeared as two different dates because bank SMS write
    Beijing time while the message is stored in UTC, and nothing recorded which
    convention was used.

Dates are arithmetic, not judgement: they can be parsed exactly, cheaply, and
auditably. So the model no longer returns a date at all; this module reads it out
of the text, and **records the matched substring plus how the year was resolved**,
so a wrong answer can be traced to a specific match instead of to a black box.

Conventions, deliberately chosen and written down:

* A date written in the text is a **calendar date as the writer meant it**. We do
  not convert it between timezones — "9月23日04:05" is what the bank said, and
  translating it would be inventing a fact. The message's own `timestamp` stays
  UTC and is what "收到时间" means.
* A year-less date means **the occurrence nearest to the message time**. People
  drop the year exactly when it is obvious from context, and the obvious reading
  is the nearby one: "9月28日" in a message from 9月27日 is tomorrow, while
  "1月5日" in a message from 12月20日 is next year, not eleven months ago. This is
  the rule that fixes the 2025 bug. The cost of the rule: a reference genuinely
  about a far-past date can be read as a near-future one, so `year_from` is
  recorded as inferred-from-message and `ambiguous_year` is set — the guess is
  always visible, never silent.
* If the text yields no date, the result is None. Never fall back to the received
  date: "we don't know when this happens" is a real answer, and a fabricated one
  poisons the calendar route in the QA pipeline.
* If the text holds more than `MAX_DISTINCT_DATES` different dates, none of them
  can be called *the* date of the message, so the result is None. A ten-date
  Evernote note should not silently become a calendar entry.
"""

from __future__ import annotations

import re
from datetime import date, datetime, timedelta

#: More distinct dates than this and the message is not *about* one date.
MAX_DISTINCT_DATES = 3

MONTHS = {
    'jan': 1, 'january': 1, 'feb': 2, 'february': 2, 'mar': 3, 'march': 3,
    'apr': 4, 'april': 4, 'may': 5, 'jun': 6, 'june': 6, 'jul': 7, 'july': 7,
    'aug': 8, 'august': 8, 'sep': 9, 'sept': 9, 'september': 9, 'oct': 10,
    'october': 10, 'nov': 11, 'november': 11, 'dec': 12, 'december': 12,
}

RELATIVE_DAYS = {'今天': 0, '今日': 0, '明天': 1, '明日': 1, '后天': 2,
                 '大后天': 3, '昨天': -1, '昨日': -1, '前天': -2}

#: 英文的同一批词。这个库里英国短信和美国邮件都不少（"Your parcel arrives today"、
#: "tomorrow at 19:00"），漏掉它们等于白丢一半相对日期。大小写不敏感。
RELATIVE_DAYS_EN = {'today': 0, 'tonight': 0, 'tomorrow': 1, 'yesterday': -1,
                    'day after tomorrow': 2}

WEEKDAYS_EN = {'monday': 0, 'tuesday': 1, 'wednesday': 2, 'thursday': 3,
               'friday': 4, 'saturday': 5, 'sunday': 6,
               'mon': 0, 'tue': 1, 'tues': 1, 'wed': 2, 'thu': 3, 'thur': 3,
               'thurs': 3, 'fri': 4, 'sat': 5, 'sun': 6}

WEEKDAYS = {'一': 0, '二': 1, '三': 2, '四': 3, '五': 4, '六': 5, '日': 6,
            '天': 6, '1': 0, '2': 1, '3': 2, '4': 3, '5': 4, '6': 5, '7': 6}

# ISO / 中文 / 英文三种写法。顺序有意义：更具体的在前面，先匹配到的先记下。
PATTERNS = [
    # 2026-09-22 / 2026/09/22 / 2026.09.22
    ('iso', re.compile(r'(?<!\d)(20\d{2})[-/.](\d{1,2})[-/.](\d{1,2})(?!\d)')),
    # 2026年9月22日 / 9月22日 / 9月22号
    ('cn', re.compile(r'(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]')),
    # 16 Oct 2026 / 6 October 2026 / Oct 06, 2026 / October 6 2026
    ('en', re.compile(r'(?<!\d)(\d{1,2})\s*([A-Za-z]{3,9})\.?,?\s*(20\d{2})(?!\d)')),
    ('en2', re.compile(r'([A-Za-z]{3,9})\.?\s*(\d{1,2}),?\s*(20\d{2})(?!\d)')),
    # 16/10/26 / 16/10/2026（日/月/年，与英式写法一致）
    ('dmy', re.compile(r'(?<!\d)(\d{1,2})/(\d{1,2})/(\d{2}|20\d{2})(?!\d)')),
]


class Match:
    """One date found in the text, with enough context to audit it."""

    __slots__ = ('year', 'month', 'day', 'raw', 'kind', 'explicit_year', 'pos')

    def __init__(self, year, month, day, raw, kind, explicit_year, pos):
        self.year, self.month, self.day = year, month, day
        self.raw, self.kind = raw, kind
        self.explicit_year = explicit_year
        self.pos = pos

    @property
    def as_date(self):
        """The date, or None when this is not a plain calendar date yet.

        Relative and weekday matches ("明天", "下周四") carry no month/day at
        parse time — they are resolved against the message time later — so they
        must come back as None here rather than raising.
        """
        if self.year is None or self.month is None or self.day is None:
            return None
        try:
            return date(self.year, self.month, self.day)
        except ValueError:
            return None

    def __repr__(self):
        return '<%s %04d-%02d-%02d raw=%r%s>' % (
            self.kind, self.year, self.month, self.day, self.raw,
            ' explicit' if self.explicit_year else '')


def _valid(year, month, day):
    return 1 <= month <= 12 and 1 <= day <= 31


def _from_raw(kind, groups, whole, pos):
    """Turn a regex match into a Match, or None if it is not a real date."""
    if kind == 'iso':
        year, month, day = int(groups[0]), int(groups[1]), int(groups[2])
        return Match(year, month, day, whole, kind, True, pos) if _valid(year, month, day) else None

    if kind == 'cn':
        year, month, day = groups
        month, day = int(month), int(day)
        if not _valid(0, month, day):
            return None
        if year:
            return Match(int(year), month, day, whole, kind, True, pos)
        return Match(None, month, day, whole, kind, False, pos)

    if kind == 'en':
        day, name, year = int(groups[0]), groups[1].lower(), int(groups[2])
        month = MONTHS.get(name) or MONTHS.get(name[:4]) or MONTHS.get(name[:3])
        if not month or not _valid(year, month, day):
            return None
        return Match(year, month, day, whole, kind, True, pos)

    if kind == 'en2':
        name, day, year = groups[0].lower(), int(groups[1]), int(groups[2])
        month = MONTHS.get(name) or MONTHS.get(name[:4]) or MONTHS.get(name[:3])
        if not month or not _valid(year, month, day):
            return None
        return Match(year, month, day, whole, kind, True, pos)

    if kind == 'dmy':
        day, month, year = int(groups[0]), int(groups[1]), groups[2]
        year = int(year)
        if year < 100:
            year += 2000
        # 16/10/26 与 10/16/26 都能出现。>12 的那一位确定谁是日；两位都 ≤12 时
        # 按**日在前**读（这个库里的短信/邮件以英国格式为主），并把原样记下来，
        # 读错了能查。
        if day > 12 >= month:
            pass
        elif month > 12 >= day:
            day, month = month, day
        if not _valid(year, month, day):
            return None
        return Match(year, month, day, whole, kind, True, pos)

    return None


def find_dates(text):
    """All dates in `text`, de-duplicated by (month, day, raw), in text order."""
    text = text or ''
    found, seen = [], set()
    for kind, pattern in PATTERNS:
        for match in pattern.finditer(text):
            candidate = _from_raw(kind, match.groups(), match.group(0), match.start())
            if candidate is None:
                continue
            # ISO 已经含年份，中文/英文里的同一个日期可能被 en2 再匹配一次
            fingerprint = (candidate.month, candidate.day, candidate.raw[:10].lower())
            if fingerprint in seen:
                continue
            seen.add(fingerprint)
            found.append(candidate)

    for word, offset in RELATIVE_DAYS.items():
        position = text.find(word)
        if position >= 0:
            found.append(Match(None, None, None, word, 'relative', False, position))
            found[-1].day = offset          # 相对日期先存"偏移天数"，见 resolve()
    # 长的写法先匹配、短的不许重叠：否则「下周四」会同时命中「下周X」(下周)和
    # 里面的「周X」(本周)，两个日期都进候选，最后按"最近"选到错的那个。
    for word, offset in RELATIVE_DAYS_EN.items():
        match = re.search(r'(?<![A-Za-z])%s(?![A-Za-z])' % word.replace(' ', r'\s+'),
                          text, re.I)
        if match:
            found.append(Match(None, None, None, match.group(0), 'relative', False,
                               match.start()))
            found[-1].day = offset

    en_weekday = '|'.join(sorted(WEEKDAYS_EN, key=len, reverse=True))
    for match in re.finditer(r'(?<![A-Za-z])(next\s+)?(%s)(?![A-Za-z])' % en_weekday,
                             text, re.I):
        found.append(Match(None, None, None, match.group(0), 'weekday', False,
                           match.start()))
        found[-1].day = (7 if match.group(1) else 0,
                         WEEKDAYS_EN[match.group(2).lower()])

    taken = []
    for pattern, offset in ((r'下周([一二三四五六日天1-7])', 7),
                            (r'本周([一二三四五六日天1-7])', 0),
                            (r'这周([一二三四五六日天1-7])', 0),
                            (r'星期([一二三四五六日天1-7])', 0),
                            (r'周([一二三四五六日天1-7])', 0)):
        for match in re.finditer(pattern, text):
            if any(not (match.end() <= a or match.start() >= b) for a, b in taken):
                continue
            taken.append((match.start(), match.end()))
            found.append(Match(None, None, None, match.group(0), 'weekday', False,
                               match.start()))
            found[-1].day = (offset, WEEKDAYS[match.group(1)])

    found.sort(key=lambda m: m.pos)
    return found


def resolve_year(candidate, anchor):
    """Fill in a missing year, and turn relative words into real dates.

    Kept separate from parsing so the *rule* is testable on its own: the anchor
    (when the message arrived) is the only input, and the output records nothing
    about how it was derived.
    """
    if candidate.kind == 'relative':
        return (anchor + timedelta(days=candidate.day)).date()
    if candidate.kind == 'weekday':
        weeks, target = candidate.day
        # 消息当天算第 0 天：先到本周目标日，再按"下周"加一周。已经过的那天
        # 指的是下周的同一天（"周四"在周五说出来 = 下周四）。
        delta = (target - anchor.weekday()) % 7
        if delta == 0:
            delta = 7 if weeks else 0
        return (anchor + timedelta(days=delta + weeks)).date()
    if candidate.explicit_year:
        return candidate.as_date
    # 三选一：今年 / 明年 / 去年，取离 anchor 最近的那个。
    options = []
    for year in (anchor.year - 1, anchor.year, anchor.year + 1):
        try:
            options.append(date(year, candidate.month, candidate.day))
        except ValueError:
            continue
    if not options:
        return None
    return min(options, key=lambda value: abs((value - anchor.date()).days))


def infer_event_date(text, received, debug=False):
    """The date a message is *about*, or None.

    `received` may be a datetime or an ISO string; it anchors year-less and
    relative dates. Returns (date|None, evidence|None); with `debug=True` the
    evidence dict carries every candidate considered and which one won.
    """
    if isinstance(received, str):
        received = datetime.fromisoformat(received.replace('Z', '+00:00'))
    elif received is None:
        return (None, {'reason': 'no anchor time'}) if debug else None

    candidates = find_dates(text)
    if not candidates:
        return (None, {'reason': 'no date expression found', 'candidates': []}) \
            if debug else None

    resolved = []
    for candidate in candidates:
        value = resolve_year(candidate, received)
        if value:
            resolved.append((value, candidate))

    distinct = {value for value, _ in resolved}
    if len(distinct) > MAX_DISTINCT_DATES:
        evidence = {'reason': 'too many distinct dates (%d)' % len(distinct),
                    'candidates': sorted(str(d) for d in distinct)}
        return (None, evidence) if debug else None

    # 选哪个日期，按三档优先级：
    #   1. **严格在收到时间之后**的最近一个。通知讲的是接下来要发生的事，而正文里
    #      等于收到时间的那一个往往是发信时间（订单邮件会同时写"下单于 9/19"和
    #      "取车 10/5"，选错就把行程记成了下单日）。
    #   2. 没有严格未来的，就取等于收到时间的（"今天送达"这类）。
    #   3. 再没有，取最近过去的（银行流水：事件时刻就是收到时刻）。
    today = received.date()
    strictly_future = [(v, c) for v, c in resolved if v > today]
    if strictly_future:
        chosen, candidate = min(strictly_future, key=lambda pair: pair[0])
    else:
        not_past = [(v, c) for v, c in resolved if v == today]
        if not_past:
            chosen, candidate = not_past[0]
        else:
            chosen, candidate = max(resolved, key=lambda pair: pair[0])

    evidence = {
        'date': chosen.isoformat(),
        'raw': candidate.raw,
        'kind': candidate.kind,
        'year_from': 'text' if candidate.explicit_year else 'inferred-from-message',
        # 年份是推断出来的、而且换一年也说得通时标出来 —— 猜可以猜，但要留痕。
        'ambiguous_year': (not candidate.explicit_year and len({v.year for v, _ in resolved}) > 1),
        'candidates': sorted(str(d) for d in distinct),
    }
    return (chosen, evidence) if debug else chosen
