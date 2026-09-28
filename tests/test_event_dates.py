"""日期解析：把真实踩过的 bug 当用例。（2026-09-28 随抽取器一起搬进 message_hub。）

这组测试存在的理由很具体——模型猜日期猜错过两次（"9月28日" 推成 2025 年；
银行短信的北京时间和入库 UTC 混用），而这两类错误用代码是可以完全避免的。
所以日期从此不由模型产出，这些用例锁住规则本身。
"""
import event_dates as dates


def event(text, received):
    value = dates.infer_event_date(text, received)
    return value.isoformat() if value else None


class TestRealBugs:
    def test_year_less_date_is_not_guessed_wrong(self):
        """真实 bug：退款短信只写「9月28日」，被模型推成了 2025-09-28。"""
        assert event('尾号9303卡9月28日07:11收入(退款财付通-简单心理)300元',
                     '2026-09-27T23:12:00') == '2026-09-28'

    def test_december_message_about_january_means_next_year(self):
        assert event('您的订阅将于1月5日续费', '2026-12-20T10:00:00') == '2027-01-05'

    def test_a_date_already_in_the_past_is_read_as_next_occurrence(self):
        """规则是"取最近的一次出现"，所以离得更近的次年会被选中。这是已知代价，
        因此 evidence 里会标 ambiguous_year（见下面的可审计性测试）。"""
        assert event('订单于3月2日发出', '2026-09-27T10:00:00') == '2027-03-02'

    def test_bank_local_time_is_not_converted(self):
        """银行短信写的是北京时间，入库是 UTC。按"原文写的日期"记，不做换算——
        换算是发明事实。"""
        assert event('尾号9303卡9月23日04:05支出(消费财付通-简单心理)600元',
                     '2026-09-22T20:05:32') == '2026-09-23'


class TestFormats:
    def test_chinese_full_date(self):
        assert event('时间：2026年09月22日 19:00-19:50（北京时间）',
                     '2026-09-15T12:24:04') == '2026-09-22'

    def test_english_month_name_first(self):
        assert event('appointment on Sep 22, 2026 at 19:00',
                     '2026-09-21T13:30:00') == '2026-09-22'

    def test_english_day_first(self):
        assert event('YF17KHE needs an MOT by 16 Oct 2026.',
                     '2026-09-15T09:00:00') == '2026-10-16'

    def test_uk_numeric_date(self):
        assert event('Your MOT expires on 16/10/26',
                     '2026-09-15T09:00:00') == '2026-10-16'

    def test_iso_date(self):
        assert event('Delivery expected 2026-09-28 between 09:30-11:00',
                     '2026-09-27T08:00:00') == '2026-09-28'

    def test_relative_days(self):
        assert event('你明天有1个预约，请于19:00进行视频咨询',
                     '2026-09-21T13:30:33') == '2026-09-22'
        assert event('学校提醒后天INSET日停课', '2026-09-26T10:00:00') == '2026-09-28'

    def test_weekday_without_a_qualifier_means_the_coming_one(self):
        # 2026-09-28 是周一，说到"周三"就是本周三
        assert event('周三下午的家长会', '2026-09-28T10:00:00') == '2026-09-30'

    def test_next_week_weekday_is_a_week_later(self):
        """「下周四」不能同时命中里面的「周四」，否则会选到本周那个。"""
        assert event('下周四交作业', '2026-09-28T10:00:00') == '2026-10-08'


class TestRefusesToGuess:
    def test_no_date_expression_gives_none(self):
        assert event('今天天气不错', '2026-09-27T10:00:00') == '2026-09-27'  # 相对日期是真日期
        assert event('好的', '2026-09-27T10:00:00') is None

    def test_no_anchor_time_gives_none(self):
        assert dates.infer_event_date('9月28日', None) is None

    def test_too_many_distinct_dates_gives_none(self):
        """十个日期的笔记不该悄悄变成一条日历项。"""
        text = '1月1日 2月2日 3月3日 4月4日 5月5日'
        assert dates.infer_event_date(text, '2026-09-27T10:00:00') is None


class TestAuditability:
    def test_evidence_records_the_matched_text_and_where_the_year_came_from(self):
        value, evidence = dates.infer_event_date(
            '尾号9303卡9月28日07:11收入(退款财付通-简单心理)300元',
            '2026-09-27T23:12:00', debug=True)
        assert value.isoformat() == '2026-09-28'
        assert evidence['raw'] == '9月28日'
        assert evidence['year_from'] == 'inferred-from-message'
        assert evidence['ambiguous_year'] is False

    def test_an_explicit_year_is_marked_as_coming_from_the_text(self):
        _, evidence = dates.infer_event_date(
            '取车 Oct 05, 2026 16:00', '2026-09-19T12:21:49', debug=True)
        assert evidence['year_from'] == 'text'
        assert evidence['ambiguous_year'] is False


class TestPicksTheEventNotTheSendDate:
    def test_a_booking_prefers_the_pickup_date_over_the_order_date(self):
        """订单邮件同时写了"下单于 9/19"和"取车 10/5"，摘要是给日历用的，
        选错就把行程记成了下单日。"""
        text = ('Booking confirmation: Your car rental booking in Austin has been '
                'confirmed. Pick up: Oct 05, 2026 16:00. Booked on Sep 19, 2026.')
        assert event(text, '2026-09-19T12:21:49') == '2026-10-05'

    def test_a_message_received_on_the_day_itself_still_resolves(self):
        assert event('Your parcel arrives today between 09:30-11:00',
                     '2026-09-28T08:00:00') == '2026-09-28'

    def test_english_relative_words(self):
        assert event('Your parcel arrives tomorrow between 09:30-11:00',
                     '2026-09-28T08:00:00') == '2026-09-29'

    def test_english_weekday(self):
        # 2026-09-28 是周一
        assert event('Workshop moved to Thursday, KS2 parents only',
                     '2026-09-28T10:00:00') == '2026-10-01'
        assert event('Workshop moved to next Thursday',
                     '2026-09-28T10:00:00') == '2026-10-08'
