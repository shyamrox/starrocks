// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "exprs/vectorized/time_functions.h"

#include <string_view>

#include "column/column_helper.h"
#include "exprs/vectorized/binary_function.h"
#include "exprs/vectorized/unary_function.h"
#include "runtime/date_value.h"
#include "runtime/runtime_state.h"
#include "udf/udf_internal.h"

namespace starrocks::vectorized {
// index as day of week(1: Sunday, 2: Monday....), value as distance of this day and first day(Monday) of this week.
static int day_to_first[8] = {0 /*never use*/, 6, 0, 1, 2, 3, 4, 5};

// avoid format function OOM, the value just based on experience
const static int DEFAULT_DATE_FORMAT_LIMIT = 100;

#define DEFINE_TIME_UNARY_FN(NAME, TYPE, RESULT_TYPE)                                                         \
    ColumnPtr TimeFunctions::NAME(FunctionContext* context, const starrocks::vectorized::Columns& columns) {  \
        return VectorizedStrictUnaryFunction<NAME##Impl>::evaluate<TYPE, RESULT_TYPE>(VECTORIZED_FN_ARGS(0)); \
    }

#define DEFINE_TIME_STRING_UNARY_FN(NAME, TYPE, RESULT_TYPE)                                                        \
    ColumnPtr TimeFunctions::NAME(FunctionContext* context, const starrocks::vectorized::Columns& columns) {        \
        return VectorizedStringStrictUnaryFunction<NAME##Impl>::evaluate<TYPE, RESULT_TYPE>(VECTORIZED_FN_ARGS(0)); \
    }

#define DEFINE_TIME_UNARY_FN_WITH_IMPL(NAME, TYPE, RESULT_TYPE, FN) \
    DEFINE_UNARY_FN(NAME##Impl, FN);                                \
    DEFINE_TIME_UNARY_FN(NAME, TYPE, RESULT_TYPE);

#define DEFINE_TIME_BINARY_FN(NAME, LTYPE, RTYPE, RESULT_TYPE)                                                         \
    ColumnPtr TimeFunctions::NAME(FunctionContext* context, const starrocks::vectorized::Columns& columns) {           \
        return VectorizedStrictBinaryFunction<NAME##Impl>::evaluate<LTYPE, RTYPE, RESULT_TYPE>(VECTORIZED_FN_ARGS(0),  \
                                                                                               VECTORIZED_FN_ARGS(1)); \
    }

#define DEFINE_TIME_BINARY_FN_WITH_IMPL(NAME, LTYPE, RTYPE, RESULT_TYPE, FN) \
    DEFINE_BINARY_FUNCTION(NAME##Impl, FN);                                  \
    DEFINE_TIME_BINARY_FN(NAME, LTYPE, RTYPE, RESULT_TYPE);

#define DEFINE_TIME_UNARY_FN_EXTEND(NAME, TYPE, RESULT_TYPE, IDX)                                               \
    ColumnPtr TimeFunctions::NAME(FunctionContext* context, const starrocks::vectorized::Columns& columns) {    \
        return VectorizedStrictUnaryFunction<NAME##Impl>::evaluate<TYPE, RESULT_TYPE>(VECTORIZED_FN_ARGS(IDX)); \
    }

template <PrimitiveType Type>
ColumnPtr date_valid(const ColumnPtr& v1) {
    if (v1->only_null()) {
        return v1;
    }

    if (v1->is_constant()) {
        auto value = ColumnHelper::get_const_value<Type>(v1);
        if (value.is_valid()) {
            return v1;
        } else {
            return ColumnHelper::create_const_null_column(v1->size());
        }
    } else if (v1->is_nullable()) {
        auto v = ColumnHelper::as_column<NullableColumn>(v1);
        auto& nulls = v->null_column()->get_data();
        auto& values = ColumnHelper::cast_to_raw<Type>(v->data_column())->get_data();

        auto null_column = NullColumn::create();
        null_column->resize(v1->size());
        auto& null_result = null_column->get_data();

        int size = v->size();
        for (int i = 0; i < size; ++i) {
            // if null or is invalid
            null_result[i] = nulls[i] | (!values[i].is_valid());
        }

        return NullableColumn::create(v->data_column(), null_column);
    } else {
        auto null_column = NullColumn::create();
        null_column->resize(v1->size());
        auto& nulls = null_column->get_data();
        auto& values = ColumnHelper::cast_to_raw<Type>(v1)->get_data();

        int size = v1->size();
        for (int i = 0; i < size; ++i) {
            nulls[i] = (!values[i].is_valid());
        }

        return NullableColumn::create(v1, null_column);
    }
}

#define DEFINE_TIME_CALC_FN(NAME, LTYPE, RTYPE, RESULT_TYPE)                                                 \
    ColumnPtr TimeFunctions::NAME(FunctionContext* context, const starrocks::vectorized::Columns& columns) { \
        auto p = VectorizedStrictBinaryFunction<NAME##Impl>::evaluate<LTYPE, RTYPE, RESULT_TYPE>(            \
                VECTORIZED_FN_ARGS(0), VECTORIZED_FN_ARGS(1));                                               \
        return date_valid<RESULT_TYPE>(p);                                                                   \
    }

Status TimeFunctions::convert_tz_prepare(starrocks_udf::FunctionContext* context,
                                         starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL || context->get_num_args() != 3 ||
        context->get_arg_type(1)->type != TYPE_VARCHAR || context->get_arg_type(2)->type != TYPE_VARCHAR ||
        !context->is_constant_column(1) || !context->is_constant_column(2)) {
        return Status::OK();
    }

    ConvertTzCtx* ctc = new ConvertTzCtx();
    context->set_function_state(scope, ctc);

    // find from timezone
    auto from = context->get_constant_column(1);
    if (from->only_null()) {
        ctc->is_valid = false;
        return Status::OK();
    }

    auto from_value = ColumnHelper::get_const_value<TYPE_VARCHAR>(from);
    if (!TimezoneUtils::find_cctz_time_zone(std::string_view(from_value), ctc->from_tz)) {
        ctc->is_valid = false;
        return Status::OK();
    }

    // find to timezone
    auto to = context->get_constant_column(2);
    if (to->only_null()) {
        ctc->is_valid = false;
        return Status::OK();
    }

    auto to_value = ColumnHelper::get_const_value<TYPE_VARCHAR>(to);
    if (!TimezoneUtils::find_cctz_time_zone(std::string_view(to_value), ctc->to_tz)) {
        ctc->is_valid = false;
        return Status::OK();
    }

    ctc->is_valid = true;
    return Status::OK();
}

Status TimeFunctions::convert_tz_close(starrocks_udf::FunctionContext* context,
                                       starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::FRAGMENT_LOCAL) {
        ConvertTzCtx* ctc =
                reinterpret_cast<ConvertTzCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
        if (ctc != nullptr) {
            delete ctc;
        }
    }

    return Status::OK();
}

ColumnPtr TimeFunctions::convert_tz_general(FunctionContext* context, const Columns& columns) {
    auto time_viewer = ColumnViewer<TYPE_DATETIME>(columns[0]);
    auto from_str = ColumnViewer<TYPE_VARCHAR>(columns[1]);
    auto to_str = ColumnViewer<TYPE_VARCHAR>(columns[2]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_DATETIME> result(size);
    TimezoneHsScan timezone_hsscan;
    timezone_hsscan.compile();
    for (int row = 0; row < size; ++row) {
        if (time_viewer.is_null(row) || from_str.is_null(row) || to_str.is_null(row)) {
            result.append_null();
            continue;
        }

        auto datetime_value = time_viewer.value(row);
        auto from_format = from_str.value(row);
        auto to_format = to_str.value(row);

        int year, month, day, hour, minute, second, usec;
        datetime_value.to_timestamp(&year, &month, &day, &hour, &minute, &second, &usec);
        DateTimeValue ts_value(TIME_DATETIME, year, month, day, hour, minute, second, usec);

        cctz::time_zone ctz;
        int64_t timestamp;
        int64_t offset;
        if (TimezoneUtils::timezone_offsets(from_format, to_format, &offset)) {
            TimestampValue ts = TimestampValue::create(year, month, day, hour, minute, second);
            ts.from_unix_second(ts.to_unix_second() + offset);
            result.append(ts);
            continue;
        }

        if (!ts_value.from_cctz_timezone(timezone_hsscan, from_format, ctz) ||
            !ts_value.unix_timestamp(&timestamp, ctz)) {
            result.append_null();
            continue;
        }

        DateTimeValue ts_value2;
        if (!ts_value2.from_cctz_timezone(timezone_hsscan, to_format, ctz) ||
            !ts_value2.from_unixtime(timestamp, ctz)) {
            result.append_null();
            continue;
        }

        TimestampValue ts;
        ts.from_timestamp(ts_value2.year(), ts_value2.month(), ts_value2.day(), ts_value2.hour(), ts_value2.minute(),
                          ts_value2.second(), 0);
        result.append(ts);
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

ColumnPtr TimeFunctions::convert_tz_const(FunctionContext* context, const Columns& columns, const cctz::time_zone& from,
                                          const cctz::time_zone& to) {
    auto time_viewer = ColumnViewer<TYPE_DATETIME>(columns[0]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_DATETIME> result(size);
    for (int row = 0; row < size; ++row) {
        if (time_viewer.is_null(row)) {
            result.append_null();
            continue;
        }

        auto datetime_value = time_viewer.value(row);

        int year, month, day, hour, minute, second, usec;
        datetime_value.to_timestamp(&year, &month, &day, &hour, &minute, &second, &usec);
        DateTimeValue ts_value(TIME_DATETIME, year, month, day, hour, minute, second, usec);

        int64_t timestamp;
        // TODO find a better approach to replace datetime_value.unix_timestamp
        if (!ts_value.unix_timestamp(&timestamp, from)) {
            result.append_null();
            continue;
        }
        DateTimeValue ts_value2;
        // TODO find a better approach to replace datetime_value.from_unixtime
        if (!ts_value2.from_unixtime(timestamp, to)) {
            result.append_null();
            continue;
        }

        TimestampValue ts;
        ts.from_timestamp(ts_value2.year(), ts_value2.month(), ts_value2.day(), ts_value2.hour(), ts_value2.minute(),
                          ts_value2.second(), 0);
        result.append(ts);
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

ColumnPtr TimeFunctions::convert_tz(FunctionContext* context, const Columns& columns) {
    ConvertTzCtx* ctc = reinterpret_cast<ConvertTzCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
    if (ctc == nullptr) {
        return convert_tz_general(context, columns);
    }

    if (!ctc->is_valid) {
        return ColumnHelper::create_const_null_column(columns[0]->size());
    }

    return convert_tz_const(context, columns, ctc->from_tz, ctc->to_tz);
}

ColumnPtr TimeFunctions::utc_timestamp(FunctionContext* context, const Columns& columns) {
    starrocks::RuntimeState* state = context->impl()->state();
    DateTimeValue dtv;
    if (dtv.from_unixtime(state->timestamp_ms() / 1000, "+00:00")) {
        TimestampValue ts;
        ts.from_timestamp(dtv.year(), dtv.month(), dtv.day(), dtv.hour(), dtv.minute(), dtv.second(), 0);
        return ColumnHelper::create_const_column<TYPE_DATETIME>(ts, 1);
    } else {
        return ColumnHelper::create_const_null_column(1);
    }
}

ColumnPtr TimeFunctions::timestamp(FunctionContext* context, const Columns& columns) {
    return columns[0];
}

ColumnPtr TimeFunctions::now(FunctionContext* context, const Columns& columns) {
    starrocks::RuntimeState* state = context->impl()->state();
    DateTimeValue dtv;
    if (dtv.from_unixtime(state->timestamp_ms() / 1000, state->timezone_obj())) {
        TimestampValue ts;
        ts.from_timestamp(dtv.year(), dtv.month(), dtv.day(), dtv.hour(), dtv.minute(), dtv.second(), 0);
        return ColumnHelper::create_const_column<TYPE_DATETIME>(ts, 1);
    } else {
        return ColumnHelper::create_const_null_column(1);
    }
}

ColumnPtr TimeFunctions::curtime(FunctionContext* context, const Columns& columns) {
    starrocks::RuntimeState* state = context->impl()->state();
    DateTimeValue dtv;
    if (dtv.from_unixtime(state->timestamp_ms() / 1000, state->timezone())) {
        double seconds = dtv.hour() * 3600 + dtv.minute() * 60 + dtv.second();
        return ColumnHelper::create_const_column<TYPE_TIME>(seconds, 1);
    } else {
        return ColumnHelper::create_const_null_column(1);
    }
}

ColumnPtr TimeFunctions::curdate(FunctionContext* context, const Columns& columns) {
    starrocks::RuntimeState* state = context->impl()->state();
    DateTimeValue dtv;
    if (dtv.from_unixtime(state->timestamp_ms() / 1000, state->timezone())) {
        DateValue dv;
        dv.from_date(dtv.year(), dtv.month(), dtv.day());
        return ColumnHelper::create_const_column<TYPE_DATE>(dv, 1);
    } else {
        return ColumnHelper::create_const_null_column(1);
    }
}

// year
DEFINE_UNARY_FN_WITH_IMPL(yearImpl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return y;
}

DEFINE_TIME_UNARY_FN(year, TYPE_DATETIME, TYPE_INT);

// year
// return type: INT16
DEFINE_UNARY_FN_WITH_IMPL(yearV2Impl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return y;
}

DEFINE_TIME_UNARY_FN(yearV2, TYPE_DATETIME, TYPE_SMALLINT);

DEFINE_UNARY_FN_WITH_IMPL(yearV3Impl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return y;
}

DEFINE_TIME_UNARY_FN(yearV3, TYPE_DATE, TYPE_SMALLINT);

// quarter
DEFINE_UNARY_FN_WITH_IMPL(quarterImpl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return (m - 1) / 3 + 1;
}
DEFINE_TIME_UNARY_FN(quarter, TYPE_DATETIME, TYPE_INT);

// month
DEFINE_UNARY_FN_WITH_IMPL(monthImpl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return m;
}
DEFINE_TIME_UNARY_FN(month, TYPE_DATETIME, TYPE_INT);

// month
// return type: INT8
DEFINE_UNARY_FN_WITH_IMPL(monthV2Impl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return m;
}
DEFINE_TIME_UNARY_FN(monthV2, TYPE_DATETIME, TYPE_TINYINT);

DEFINE_UNARY_FN_WITH_IMPL(monthV3Impl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return m;
}
DEFINE_TIME_UNARY_FN(monthV3, TYPE_DATE, TYPE_TINYINT);

// day
DEFINE_UNARY_FN_WITH_IMPL(dayImpl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return d;
}
DEFINE_TIME_UNARY_FN(day, TYPE_DATETIME, TYPE_INT);

// day
// return type: INT8
DEFINE_UNARY_FN_WITH_IMPL(dayV2Impl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return d;
}
DEFINE_TIME_UNARY_FN(dayV2, TYPE_DATETIME, TYPE_TINYINT);

DEFINE_UNARY_FN_WITH_IMPL(dayV3Impl, v) {
    int y, m, d;
    ((DateValue)v).to_date(&y, &m, &d);
    return d;
}
DEFINE_TIME_UNARY_FN(dayV3, TYPE_DATE, TYPE_TINYINT);

// hour of the day
DEFINE_UNARY_FN_WITH_IMPL(hourImpl, v) {
    int hour1, mintue1, second1, usec1;
    v.to_time(&hour1, &mintue1, &second1, &usec1);
    return hour1;
}
DEFINE_TIME_UNARY_FN(hour, TYPE_DATETIME, TYPE_INT);

// hour of the day
DEFINE_UNARY_FN_WITH_IMPL(hourV2Impl, v) {
    int hour1, mintue1, second1, usec1;
    v.to_time(&hour1, &mintue1, &second1, &usec1);
    return hour1;
}
DEFINE_TIME_UNARY_FN(hourV2, TYPE_DATETIME, TYPE_TINYINT);

// minute of the hour
DEFINE_UNARY_FN_WITH_IMPL(minuteImpl, v) {
    int hour1, mintue1, second1, usec1;
    v.to_time(&hour1, &mintue1, &second1, &usec1);
    return mintue1;
}
DEFINE_TIME_UNARY_FN(minute, TYPE_DATETIME, TYPE_INT);

// minute of the hour
DEFINE_UNARY_FN_WITH_IMPL(minuteV2Impl, v) {
    int hour1, mintue1, second1, usec1;
    v.to_time(&hour1, &mintue1, &second1, &usec1);
    return mintue1;
}
DEFINE_TIME_UNARY_FN(minuteV2, TYPE_DATETIME, TYPE_TINYINT);

// second of the minute
DEFINE_UNARY_FN_WITH_IMPL(secondImpl, v) {
    int hour1, mintue1, second1, usec1;
    v.to_time(&hour1, &mintue1, &second1, &usec1);
    return second1;
}
DEFINE_TIME_UNARY_FN(second, TYPE_DATETIME, TYPE_INT);

// second of the minute
DEFINE_UNARY_FN_WITH_IMPL(secondV2Impl, v) {
    int hour1, mintue1, second1, usec1;
    v.to_time(&hour1, &mintue1, &second1, &usec1);
    return second1;
}
DEFINE_TIME_UNARY_FN(secondV2, TYPE_DATETIME, TYPE_TINYINT);

// day_of_week
DEFINE_UNARY_FN_WITH_IMPL(day_of_weekImpl, v) {
    int day = ((DateValue)v).weekday();
    return day + 1;
}
DEFINE_TIME_UNARY_FN(day_of_week, TYPE_DATETIME, TYPE_INT);

DEFINE_UNARY_FN_WITH_IMPL(time_to_secImpl, v) {
    return static_cast<int64_t>(v);
}
DEFINE_TIME_UNARY_FN(time_to_sec, TYPE_TIME, TYPE_BIGINT);

// month_name
DEFINE_UNARY_FN_WITH_IMPL(month_nameImpl, v) {
    return ((DateValue)v).month_name();
}
DEFINE_TIME_STRING_UNARY_FN(month_name, TYPE_DATETIME, TYPE_VARCHAR);

// day_name
DEFINE_UNARY_FN_WITH_IMPL(day_nameImpl, v) {
    return ((DateValue)v).day_name();
}
DEFINE_TIME_STRING_UNARY_FN(day_name, TYPE_DATETIME, TYPE_VARCHAR);

// day_of_year
DEFINE_UNARY_FN_WITH_IMPL(day_of_yearImpl, v) {
    auto day = (DateValue)v;
    int year, month, day1;
    day.to_date(&year, &month, &day1);
    DateValue first_day_year;
    first_day_year.from_date(year, 1, 1);
    return day.julian() - first_day_year.julian() + 1;
}
DEFINE_TIME_UNARY_FN(day_of_year, TYPE_DATETIME, TYPE_INT);

// week_of_year
DEFINE_UNARY_FN_WITH_IMPL(week_of_yearImpl, v) {
    auto day = (DateValue)v;
    int weeks;
    if (day.get_weeks_of_year_with_cache(&weeks)) {
        return weeks;
    }
    return day.get_week_of_year();
}
DEFINE_TIME_UNARY_FN(week_of_year, TYPE_DATETIME, TYPE_INT);

// to_date
DEFINE_UNARY_FN_WITH_IMPL(to_dateImpl, v) {
    return (DateValue)v;
}
DEFINE_TIME_UNARY_FN(to_date, TYPE_DATETIME, TYPE_DATE);

template <TimeUnit UNIT>
TimestampValue timestamp_add(TimestampValue tsv, int count) {
    return tsv.add<UNIT>(count);
}

#define DEFINE_TIME_ADD_FN(FN, UNIT)                                                                               \
    DEFINE_BINARY_FUNCTION_WITH_IMPL(FN##Impl, timestamp, value) { return timestamp_add<UNIT>(timestamp, value); } \
                                                                                                                   \
    DEFINE_TIME_CALC_FN(FN, TYPE_DATETIME, TYPE_INT, TYPE_DATETIME);

#define DEFINE_TIME_SUB_FN(FN, UNIT)                                                                                \
    DEFINE_BINARY_FUNCTION_WITH_IMPL(FN##Impl, timestamp, value) { return timestamp_add<UNIT>(timestamp, -value); } \
                                                                                                                    \
    DEFINE_TIME_CALC_FN(FN, TYPE_DATETIME, TYPE_INT, TYPE_DATETIME);

#define DEFINE_TIME_ADD_AND_SUB_FN(FN_PREFIX, UNIT) \
    DEFINE_TIME_ADD_FN(FN_PREFIX##_add, UNIT);      \
    DEFINE_TIME_SUB_FN(FN_PREFIX##_sub, UNIT);

// years_add
// years_sub
DEFINE_TIME_ADD_AND_SUB_FN(years, TimeUnit::YEAR);

// months_add
// months_sub
DEFINE_TIME_ADD_AND_SUB_FN(months, TimeUnit::MONTH);

// weeks_add
// weeks_sub
DEFINE_TIME_ADD_AND_SUB_FN(weeks, TimeUnit::WEEK);

// days_add
// days_sub
DEFINE_TIME_ADD_AND_SUB_FN(days, TimeUnit::DAY);

// hours_add
// hours_sub
DEFINE_TIME_ADD_AND_SUB_FN(hours, TimeUnit::HOUR);

// minutes_add
// minutes_sub
DEFINE_TIME_ADD_AND_SUB_FN(minutes, TimeUnit::MINUTE);

// seconds_add
// seconds_sub
DEFINE_TIME_ADD_AND_SUB_FN(seconds, TimeUnit::SECOND);

// micros_add
// micros_sub
DEFINE_TIME_ADD_AND_SUB_FN(micros, TimeUnit::MICROSECOND);

#undef DEFINE_TIME_ADD_FN
#undef DEFINE_TIME_SUB_FN
#undef DEFINE_TIME_ADD_AND_SUB_FN

// years_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(years_diffImpl, l, r) {
    int year1, month1, day1, hour1, mintue1, second1, usec1;
    int year2, month2, day2, hour2, mintue2, second2, usec2;
    l.to_timestamp(&year1, &month1, &day1, &hour1, &mintue1, &second1, &usec1);
    r.to_timestamp(&year2, &month2, &day2, &hour2, &mintue2, &second2, &usec2);

    int year = (year1 - year2);

    if (year >= 0) {
        year -= ((month1 * 100 + day1) * 1000000L + (hour1 * 10000 + mintue1 * 100 + second1) <
                 (month2 * 100 + day2) * 1000000L + (hour2 * 10000 + mintue2 * 100 + second2));
    } else {
        year += ((month1 * 100 + day1) * 1000000L + (hour1 * 10000 + mintue1 * 100 + second1) >
                 (month2 * 100 + day2) * 1000000L + (hour2 * 10000 + mintue2 * 100 + second2));
    }

    return year;
}

DEFINE_TIME_BINARY_FN(years_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

// months_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(months_diffImpl, l, r) {
    int year1, month1, day1, hour1, mintue1, second1, usec1;
    int year2, month2, day2, hour2, mintue2, second2, usec2;
    l.to_timestamp(&year1, &month1, &day1, &hour1, &mintue1, &second1, &usec1);
    r.to_timestamp(&year2, &month2, &day2, &hour2, &mintue2, &second2, &usec2);

    int month = (year1 - year2) * 12 + (month1 - month2);

    if (month >= 0) {
        month -= (day1 * 1000000L + (hour1 * 10000 + mintue1 * 100 + second1) <
                  day2 * 1000000L + (hour2 * 10000 + mintue2 * 100 + second2));
    } else {
        month += (day1 * 1000000L + (hour1 * 10000 + mintue1 * 100 + second1) >
                  day2 * 1000000L + (hour2 * 10000 + mintue2 * 100 + second2));
    }

    return month;
}

DEFINE_TIME_BINARY_FN(months_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

// quarters_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(quarters_diffImpl, l, r) {
    auto diff = months_diffImpl::apply<LType, RType, ResultType>(l, r);
    return diff / 3;
}
DEFINE_TIME_BINARY_FN(quarters_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

// weeks_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(weeks_diffImpl, l, r) {
    return l.diff_microsecond(r) / USECS_PER_WEEK;
}
DEFINE_TIME_BINARY_FN(weeks_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

// days_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(days_diffImpl, l, r) {
    return l.diff_microsecond(r) / USECS_PER_DAY;
}
DEFINE_TIME_BINARY_FN(days_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

// date_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(date_diffImpl, l, r) {
    return ((DateValue)l).julian() - ((DateValue)r).julian();
}
DEFINE_TIME_BINARY_FN(date_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_INT);

// time_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(time_diffImpl, l, r) {
    return l.diff_microsecond(r) / USECS_PER_SEC;
}
DEFINE_TIME_BINARY_FN(time_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_TIME);

// hours_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(hours_diffImpl, l, r) {
    return l.diff_microsecond(r) / USECS_PER_HOUR;
}
DEFINE_TIME_BINARY_FN(hours_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

// minutes_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(minutes_diffImpl, l, r) {
    return l.diff_microsecond(r) / USECS_PER_MINUTE;
}
DEFINE_TIME_BINARY_FN(minutes_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

// seconds_diff
DEFINE_BINARY_FUNCTION_WITH_IMPL(seconds_diffImpl, l, r) {
    return l.diff_microsecond(r) / USECS_PER_SEC;
}
DEFINE_TIME_BINARY_FN(seconds_diff, TYPE_DATETIME, TYPE_DATETIME, TYPE_BIGINT);

/*
 * definition for to_unix operators(SQL TYPE: DATETIME)
 */
ColumnPtr TimeFunctions::to_unix_from_datetime(FunctionContext* context, const Columns& columns) {
    DCHECK_EQ(columns.size(), 1);

    auto date_viewer = ColumnViewer<TYPE_DATETIME>(columns[0]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_INT> result(size);
    for (int row = 0; row < size; ++row) {
        if (date_viewer.is_null(row)) {
            result.append_null();
            continue;
        }

        auto date = date_viewer.value(row);

        int year, month, day, hour, minute, second, usec;
        date.to_timestamp(&year, &month, &day, &hour, &minute, &second, &usec);
        DateTimeValue tv(TIME_DATETIME, year, month, day, hour, minute, second, usec);

        int64_t timestamp;
        if (!tv.unix_timestamp(&timestamp, context->impl()->state()->timezone_obj())) {
            result.append_null();
        } else {
            timestamp = timestamp < 0 ? 0 : timestamp;
            timestamp = timestamp > INT_MAX ? 0 : timestamp;
            result.append(timestamp);
        }
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

/*
 * definition for to_unix operators(SQL TYPE: DATE)
 */
ColumnPtr TimeFunctions::to_unix_from_date(FunctionContext* context, const Columns& columns) {
    DCHECK_EQ(columns.size(), 1);

    auto date_viewer = ColumnViewer<TYPE_DATE>(columns[0]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_INT> result(size);
    for (int row = 0; row < size; ++row) {
        if (date_viewer.is_null(row)) {
            result.append_null();
            continue;
        }

        auto date = date_viewer.value(row);

        int year, month, day;
        date.to_date(&year, &month, &day);
        DateTimeValue tv(TIME_DATE, year, month, day, 0, 0, 0, 0);

        int64_t timestamp;
        if (!tv.unix_timestamp(&timestamp, context->impl()->state()->timezone_obj())) {
            result.append_null();
        } else {
            timestamp = timestamp < 0 ? 0 : timestamp;
            timestamp = timestamp > INT_MAX ? 0 : timestamp;
            result.append(timestamp);
        }
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

ColumnPtr TimeFunctions::to_unix_from_datetime_with_format(FunctionContext* context, const Columns& columns) {
    DCHECK_EQ(columns.size(), 2);
    RETURN_IF_COLUMNS_ONLY_NULL(columns);

    auto date_viewer = ColumnViewer<TYPE_VARCHAR>(columns[0]);
    auto formatViewer = ColumnViewer<TYPE_VARCHAR>(columns[1]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_INT> result(size);
    for (int row = 0; row < size; ++row) {
        if (date_viewer.is_null(row) || formatViewer.is_null(row)) {
            result.append_null();
            continue;
        }

        auto date = date_viewer.value(row);
        auto format = formatViewer.value(row);
        if (date.empty() || format.empty()) {
            result.append_null();
            continue;
        }
        DateTimeValue tv;
        if (!tv.from_date_format_str(format.data, format.size, date.data, date.size)) {
            result.append_null();
            continue;
        }
        int64_t timestamp;
        if (!tv.unix_timestamp(&timestamp, context->impl()->state()->timezone_obj())) {
            result.append_null();
            continue;
        }

        timestamp = timestamp < 0 ? 0 : timestamp;
        timestamp = timestamp > INT_MAX ? 0 : timestamp;
        result.append(timestamp);
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

ColumnPtr TimeFunctions::to_unix_for_now(FunctionContext* context, const Columns& columns) {
    DCHECK_EQ(columns.size(), 0);
    auto result = Int32Column::create();
    result->append(context->impl()->state()->timestamp_ms() / 1000);
    return ConstColumn::create(result, 1);
}
/*
 * end definition for to_unix operators
 */

/*
 * definition for from_unix operators
 */
ColumnPtr TimeFunctions::from_unix_to_datetime(FunctionContext* context, const Columns& columns) {
    DCHECK_EQ(columns.size(), 1);

    RETURN_IF_COLUMNS_ONLY_NULL(columns);

    ColumnViewer<TYPE_INT> data_column(columns[0]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_VARCHAR> result(size);
    for (int row = 0; row < size; ++row) {
        if (data_column.is_null(row)) {
            result.append_null();
            continue;
        }

        auto date = data_column.value(row);
        if (date < 0 || date > INT_MAX) {
            result.append_null();
            continue;
        }

        DateTimeValue dtv;
        if (!dtv.from_unixtime(date, context->impl()->state()->timezone_obj())) {
            result.append_null();
            continue;
        }
        char buf[64];
        dtv.to_string(buf);
        result.append(Slice(buf));
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

std::string TimeFunctions::convert_format(const Slice& format) {
    switch (format.get_size()) {
    case 8:
        if (strncmp((const char*)format.get_data(), "yyyyMMdd", 8) == 0) {
            std::string tmp("%Y%m%d");
            return tmp;
        }
        break;
    case 10:
        if (strncmp((const char*)format.get_data(), "yyyy-MM-dd", 10) == 0) {
            std::string tmp("%Y-%m-%d");
            return tmp;
        }
        break;
    case 19:
        if (strncmp((const char*)format.get_data(), "yyyy-MM-dd HH:mm:ss", 19) == 0) {
            std::string tmp("%Y-%m-%d %H:%i:%s");
            return tmp;
        }
        break;
    default:
        break;
    }
    return format.to_string();
}

// regex method
Status TimeFunctions::from_unix_prepare(starrocks_udf::FunctionContext* context,
                                        starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }

    FromUnixState* state = new FromUnixState();
    context->set_function_state(scope, state);

    if (!context->is_notnull_constant_column(1)) {
        return Status::OK();
    }

    state->const_format = true;
    auto column = context->get_constant_column(1);
    auto format = ColumnHelper::get_const_value<TYPE_VARCHAR>(column);

    if (format.size > DEFAULT_DATE_FORMAT_LIMIT) {
        return Status::InvalidArgument("Time format invalid");
    }

    state->format_content = convert_format(format);
    return Status::OK();
}

Status TimeFunctions::from_unix_close(starrocks_udf::FunctionContext* context,
                                      starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::FRAGMENT_LOCAL) {
        FromUnixState* state = reinterpret_cast<FromUnixState*>(context->get_function_state(scope));
        delete state;
    }
    return Status::OK();
}

ColumnPtr TimeFunctions::from_unix_with_format_general(FunctionContext* context, const Columns& columns) {
    DCHECK_EQ(columns.size(), 2);

    RETURN_IF_COLUMNS_ONLY_NULL(columns);

    ColumnViewer<TYPE_INT> data_column(columns[0]);
    ColumnViewer<TYPE_VARCHAR> format_column(columns[1]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_VARCHAR> result(size);
    for (int row = 0; row < size; ++row) {
        if (data_column.is_null(row) || format_column.is_null(row)) {
            result.append_null();
            continue;
        }

        auto date = data_column.value(row);
        auto format = format_column.value(row);
        if (date < 0 || date > INT_MAX || format.empty()) {
            result.append_null();
            continue;
        }

        DateTimeValue dtv;
        if (!dtv.from_unixtime(date, context->impl()->state()->timezone_obj())) {
            result.append_null();
            continue;
        }
        // use lambda to avoid adding method for TimeFunctions.
        if (format.size > DEFAULT_DATE_FORMAT_LIMIT) {
            result.append_null();
            continue;
        }

        string new_fmt = convert_format(format);

        char buf[128];
        if (!dtv.to_format_string((const char*)new_fmt.c_str(), new_fmt.size(), buf)) {
            result.append_null();
            continue;
        }
        result.append(Slice(buf));
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

ColumnPtr TimeFunctions::from_unix_with_format_const(std::string& format_content, FunctionContext* context,
                                                     const Columns& columns) {
    DCHECK_EQ(columns.size(), 2);

    RETURN_IF_COLUMNS_ONLY_NULL(columns);

    ColumnViewer<TYPE_INT> data_column(columns[0]);

    auto size = columns[0]->size();
    ColumnBuilder<TYPE_VARCHAR> result(size);
    for (int row = 0; row < size; ++row) {
        if (data_column.is_null(row) || format_content.empty()) {
            result.append_null();
            continue;
        }

        auto date = data_column.value(row);
        if (date < 0 || date > INT_MAX) {
            result.append_null();
            continue;
        }

        DateTimeValue dtv;
        if (!dtv.from_unixtime(date, context->impl()->state()->timezone_obj())) {
            result.append_null();
            continue;
        }

        char buf[128];
        if (!dtv.to_format_string((const char*)format_content.c_str(), format_content.size(), buf)) {
            result.append_null();
            continue;
        }
        result.append(Slice(buf));
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

ColumnPtr TimeFunctions::from_unix_to_datetime_with_format(FunctionContext* context,
                                                           const starrocks::vectorized::Columns& columns) {
    DCHECK_EQ(columns.size(), 2);
    FromUnixState* state =
            reinterpret_cast<FromUnixState*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));

    if (state->const_format) {
        std::string format_content = state->format_content;
        return from_unix_with_format_const(format_content, context, columns);
    }

    return from_unix_with_format_general(context, columns);
}

/*
 * end definition for from_unix operators
 */

// from_days
DEFINE_UNARY_FN_WITH_IMPL(from_daysImpl, v) {
    DateValue dv{date::BC_EPOCH_JULIAN + v};
    return dv;
}
ColumnPtr TimeFunctions::from_days(FunctionContext* context, const Columns& columns) {
    return date_valid<TYPE_DATE>(
            VectorizedStrictUnaryFunction<from_daysImpl>::evaluate<TYPE_INT, TYPE_DATE>(VECTORIZED_FN_ARGS(0)));
}

// to_days
DEFINE_UNARY_FN_WITH_IMPL(to_daysImpl, v) {
    return v.julian() - date::BC_EPOCH_JULIAN;
}
DEFINE_TIME_UNARY_FN(to_days, TYPE_DATE, TYPE_INT);

// remove spaces at start and end, and if remained slice is "%Y-%m-%d", '-' means
// any char, then return true, set start to the first unspace char; else return false;
bool TimeFunctions::is_date_format(const Slice& slice, char** start) {
    char* ptr = slice.data;
    char* end = slice.data + slice.size;

    while (ptr < end && isspace(*ptr)) {
        ++ptr;
    }
    while (ptr < end && isspace(*(end - 1))) {
        --end;
    }

    *start = ptr;
    return (end - ptr) == 8 && ptr[0] == '%' && ptr[1] == 'Y' &&
           // slice[2] is default '-' and ignored actually
           ptr[3] == '%' && ptr[4] == 'm' &&
           // slice[5] is default '-' and ignored actually
           ptr[6] == '%' && ptr[7] == 'd';
}

// remove spaces at start and end, and if remained slice is "%Y-%m-%d %H:%i:%s", '-'/':' means
// any char, then return true, set start to the first unspace char; else return false;
bool TimeFunctions::is_datetime_format(const Slice& slice, char** start) {
    char* ptr = slice.data;
    char* end = slice.data + slice.size;

    while (ptr < end && isspace(*ptr)) {
        ++ptr;
    }
    while (ptr < end && isspace(*(end - 1))) {
        --end;
    }

    *start = ptr;
    return (end - ptr) == 17 && ptr[0] == '%' && ptr[1] == 'Y' &&
           // slice[2] is default '-' and ignored actually
           ptr[3] == '%' && ptr[4] == 'm' &&
           // slice[5] is default '-' and ignored actually
           ptr[6] == '%' && ptr[7] == 'd' &&
           // slice[8] is default ' ' and ignored actually
           ptr[9] == '%' && ptr[10] == 'H' &&
           // slice[11] is default ':' and ignored actually
           ptr[12] == '%' && ptr[13] == 'i' &&
           // slice[14] is default ':' and ignored actually
           ptr[15] == '%' && ptr[16] == 's';
}

// prepare for string format, if it is "%Y-%m-%d" or "%Y-%m-%d %H:%i:%s"
Status TimeFunctions::str_to_date_prepare(starrocks_udf::FunctionContext* context,
                                          starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }

    if (!context->is_notnull_constant_column(1)) {
        return Status::OK();
    }

    ColumnPtr column = context->get_constant_column(1);
    Slice slice = ColumnHelper::get_const_value<TYPE_VARCHAR>(column);

    // start point to the first unspace char in string format.
    char* start;
    if (is_date_format(slice, &start)) {
        StrToDateCtx* fc = new StrToDateCtx();
        fc->fmt_type = yyyycMMcdd;
        fc->fmt = start;
        context->set_function_state(scope, fc);
    } else if (is_datetime_format(slice, &start)) {
        StrToDateCtx* fc = new StrToDateCtx();
        fc->fmt_type = yyyycMMcddcHHcmmcss;
        fc->fmt = start;
        context->set_function_state(scope, fc);
    }
    return Status::OK();
}

// try to transfer content to date format based on "%Y-%m-%d",
// if successful, return result TimestampValue
// else take a uncommon approach to process this content.
ColumnPtr TimeFunctions::str_to_date_from_date_format(FunctionContext* context,
                                                      const starrocks::vectorized::Columns& columns,
                                                      const char* str_format) {
    size_t size = columns[0]->size();
    ColumnBuilder<TYPE_DATETIME> result(size);

    TimestampValue ts;
    auto str_viewer = ColumnViewer<TYPE_VARCHAR>(columns[0]);
    auto fmt_viewer = ColumnViewer<TYPE_VARCHAR>(columns[1]);
    if (!columns[0]->has_null()) {
        for (size_t i = 0; i < size; ++i) {
            const Slice& str = str_viewer.value(i);
            bool r = ts.from_date_format_str(str.get_data(), str.get_size(), str_format);
            if (r) {
                result.append(ts);
            } else {
                const Slice& fmt = fmt_viewer.value(i);
                str_to_date_internal(&ts, fmt, str, &result);
            }
        }
    } else {
        for (size_t i = 0; i < size; ++i) {
            if (str_viewer.is_null(i)) {
                result.append_null();
            } else {
                const Slice& str = str_viewer.value(i);
                bool r = ts.from_date_format_str(str.get_data(), str.get_size(), str_format);
                if (r) {
                    result.append(ts);
                } else {
                    const Slice& fmt = fmt_viewer.value(i);
                    str_to_date_internal(&ts, fmt, str, &result);
                }
            }
        }
    }
    return result.build(ColumnHelper::is_all_const(columns));
}

// try to transfer content to date format based on "%Y-%m-%d %H:%i:%s",
// if successful, return result TimestampValue
// else take a uncommon approach to process this content.
ColumnPtr TimeFunctions::str_to_date_from_datetime_format(FunctionContext* context,
                                                          const starrocks::vectorized::Columns& columns,
                                                          const char* str_format) {
    size_t size = columns[0]->size();
    ColumnBuilder<TYPE_DATETIME> result(size);

    TimestampValue ts;
    auto str_viewer = ColumnViewer<TYPE_VARCHAR>(columns[0]);
    auto fmt_viewer = ColumnViewer<TYPE_VARCHAR>(columns[1]);
    if (!columns[0]->has_null()) {
        for (size_t i = 0; i < size; ++i) {
            const Slice& str = str_viewer.value(i);
            bool r = ts.from_datetime_format_str(str.get_data(), str.get_size(), str_format);
            if (r) {
                result.append(ts);
            } else {
                const Slice& fmt = fmt_viewer.value(i);
                str_to_date_internal(&ts, fmt, str, &result);
            }
        }
    } else {
        for (size_t i = 0; i < size; ++i) {
            if (str_viewer.is_null(i)) {
                result.append_null();
            } else {
                const Slice& str = str_viewer.value(i);
                bool r = ts.from_datetime_format_str(str.get_data(), str.get_size(), str_format);
                if (r) {
                    result.append(ts);
                } else {
                    const Slice& fmt = fmt_viewer.value(i);
                    str_to_date_internal(&ts, fmt, str, &result);
                }
            }
        }
    }
    return result.build(ColumnHelper::is_all_const(columns));
}

// uncommon approach to process string content, based on uncommon string format.
void TimeFunctions::str_to_date_internal(TimestampValue* ts, const Slice& fmt, const Slice& str,
                                         ColumnBuilder<TYPE_DATETIME>* result) {
    bool r = ts->from_uncommon_format_str(fmt.get_data(), fmt.get_size(), str.get_data(), str.get_size());
    if (r) {
        result->append(*ts);
    } else {
        result->append_null();
    }
}

// Try to process string content, based on uncommon string format
ColumnPtr TimeFunctions::str_to_date_uncommon(FunctionContext* context, const Columns& columns) {
    RETURN_IF_COLUMNS_ONLY_NULL(columns);

    size_t size = columns[0]->size(); // minimum number of rows.
    ColumnBuilder<TYPE_DATETIME> result(size);

    auto str_viewer = ColumnViewer<TYPE_VARCHAR>(columns[0]);
    auto fmt_viewer = ColumnViewer<TYPE_VARCHAR>(columns[1]);
    for (size_t i = 0; i < size; ++i) {
        if (str_viewer.is_null(i) || fmt_viewer.is_null(i)) {
            result.append_null();
        } else {
            const Slice& str = str_viewer.value(i);
            const Slice& fmt = fmt_viewer.value(i);
            TimestampValue ts;
            str_to_date_internal(&ts, fmt, str, &result);
        }
    }

    return result.build(ColumnHelper::is_all_const(columns));
}

// str_to_date, for the "str_to_date" in sql.
ColumnPtr TimeFunctions::str_to_date(FunctionContext* context, const Columns& columns) {
    StrToDateCtx* ctx = reinterpret_cast<StrToDateCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
    if (ctx == nullptr) {
        return str_to_date_uncommon(context, columns);
    } else if (ctx->fmt_type == yyyycMMcdd) { // for string format like "%Y-%m-%d"
        return str_to_date_from_date_format(context, columns, ctx->fmt);
    } else { // for string format like "%Y-%m-%d %H:%i:%s"
        return str_to_date_from_datetime_format(context, columns, ctx->fmt);
    }
}

// reclaim memory for str_to_date.
Status TimeFunctions::str_to_date_close(starrocks_udf::FunctionContext* context,
                                        starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }

    StrToDateCtx* fc = reinterpret_cast<StrToDateCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
    if (fc != nullptr) {
        delete fc;
    }

    return Status::OK();
}

DEFINE_UNARY_FN_WITH_IMPL(TimestampToDate, value) {
    return DateValue{timestamp::to_julian(value._timestamp)};
}

ColumnPtr TimeFunctions::str2date(FunctionContext* context, const Columns& columns) {
    ColumnPtr datetime = str_to_date(context, columns);
    return VectorizedStrictUnaryFunction<TimestampToDate>::evaluate<TYPE_DATETIME, TYPE_DATE>(datetime);
}

Status TimeFunctions::format_prepare(starrocks_udf::FunctionContext* context,
                                     starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }

    if (!context->is_constant_column(1)) {
        return Status::OK();
    }

    ColumnPtr column = context->get_constant_column(1);
    FormatCtx* fc = new FormatCtx();
    context->set_function_state(scope, fc);

    if (column->only_null()) {
        fc->is_valid = false;
        return Status::OK();
    }

    Slice slice = ColumnHelper::get_const_value<TYPE_VARCHAR>(column);
    fc->fmt = slice.to_string();

    fc->len = DateTimeValue::compute_format_len(slice.data, slice.size);
    if (fc->len >= 128) {
        fc->is_valid = false;
        return Status::OK();
    }

    if (fc->fmt == "%Y%m%d" || fc->fmt == "yyyyMMdd") {
        fc->fmt_type = TimeFunctions::yyyyMMdd;
    } else if (fc->fmt == "%Y-%m-%d" || fc->fmt == "yyyy-MM-dd") {
        fc->fmt_type = TimeFunctions::yyyy_MM_dd;
    } else if (fc->fmt == "%Y-%m-%d %H:%i:%s" || fc->fmt == "yyyy-MM-dd HH:mm:ss") {
        fc->fmt_type = TimeFunctions::yyyy_MM_dd_HH_mm_ss;
    } else if (fc->fmt == "%Y-%m") {
        fc->fmt_type = TimeFunctions::yyyy_MM;
    } else if (fc->fmt == "%Y%m") {
        fc->fmt_type = TimeFunctions::yyyyMM;
    } else if (fc->fmt == "%Y") {
        fc->fmt_type = TimeFunctions::yyyy;
    } else {
        fc->fmt_type = TimeFunctions::None;
    }

    fc->is_valid = true;
    return Status::OK();
}

Status TimeFunctions::format_close(starrocks_udf::FunctionContext* context,
                                   starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }

    FormatCtx* fc = reinterpret_cast<FormatCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
    if (fc != nullptr) {
        delete fc;
    }

    return Status::OK();
}

template <typename OP, PrimitiveType Type>
ColumnPtr date_format_func(const Columns& cols, size_t patten_size) {
    ColumnViewer<Type> viewer(cols[0]);

    size_t num_rows = viewer.size();
    ColumnBuilder<TYPE_VARCHAR> builder(num_rows);
    builder.data_column()->reserve(num_rows, num_rows * patten_size);

    for (int i = 0; i < num_rows; ++i) {
        if (viewer.is_null(i)) {
            builder.append_null();
            continue;
        }

        builder.append(OP::template apply<RunTimeCppType<Type>, RunTimeCppType<TYPE_VARCHAR>>(viewer.value(i)));
    }

    return builder.build(ColumnHelper::is_all_const(cols));
}

std::string format_for_yyyyMMdd(const DateValue& date_value) {
    int y, m, d, t;
    date_value.to_date(&y, &m, &d);
    char to[8];

    t = y / 100;
    to[0] = t / 10 + '0';
    to[1] = t % 10 + '0';

    t = y % 100;
    to[2] = t / 10 + '0';
    to[3] = t % 10 + '0';

    to[4] = m / 10 + '0';
    to[5] = m % 10 + '0';
    to[6] = d / 10 + '0';
    to[7] = d % 10 + '0';
    return std::string(to, 8);
}

DEFINE_STRING_UNARY_FN_WITH_IMPL(yyyyMMddImpl, v) {
    return format_for_yyyyMMdd((DateValue)v);
}

std::string format_for_yyyy_MM_dd_Impl(const DateValue& date_value) {
    return date_value.to_string();
}

DEFINE_STRING_UNARY_FN_WITH_IMPL(yyyy_MM_dd_Impl, v) {
    DateValue d = (DateValue)v;
    return format_for_yyyy_MM_dd_Impl((DateValue)d);
}

std::string format_for_yyyyMMddHHmmssImpl(const TimestampValue& date_value) {
    return date_value.to_string();
}

DEFINE_STRING_UNARY_FN_WITH_IMPL(yyyyMMddHHmmssImpl, v) {
    return format_for_yyyyMMddHHmmssImpl((TimestampValue)v);
}

std::string format_for_yyyy_MMImpl(const DateValue& date_value) {
    int y, m, d, t;
    date_value.to_date(&y, &m, &d);
    char to[7];
    t = y / 100;
    to[0] = t / 10 + '0';
    to[1] = t % 10 + '0';

    t = y % 100;
    to[2] = t / 10 + '0';
    to[3] = t % 10 + '0';

    to[4] = '-';
    to[5] = m / 10 + '0';
    to[6] = m % 10 + '0';
    return std::string(to, 7);
}

DEFINE_STRING_UNARY_FN_WITH_IMPL(yyyy_MMImpl, v) {
    return format_for_yyyy_MMImpl((DateValue)v);
}

std::string format_for_yyyyMMImpl(const DateValue& date_value) {
    int y, m, d, t;
    date_value.to_date(&y, &m, &d);
    char to[6];
    t = y / 100;
    to[0] = t / 10 + '0';
    to[1] = t % 10 + '0';

    t = y % 100;
    to[2] = t / 10 + '0';
    to[3] = t % 10 + '0';

    to[4] = m / 10 + '0';
    to[5] = m % 10 + '0';
    return std::string(to, 6);
}

DEFINE_STRING_UNARY_FN_WITH_IMPL(yyyyMMImpl, v) {
    return format_for_yyyyMMImpl((DateValue)v);
}

std::string format_for_yyyyImpl(const DateValue& date_value) {
    int y, m, d, t;
    date_value.to_date(&y, &m, &d);
    char to[4];
    t = y / 100;
    to[0] = t / 10 + '0';
    to[1] = t % 10 + '0';

    t = y % 100;
    to[2] = t / 10 + '0';
    to[3] = t % 10 + '0';
    return std::string(to, 4);
}

DEFINE_STRING_UNARY_FN_WITH_IMPL(yyyyImpl, v) {
    return format_for_yyyyImpl((DateValue)v);
}

bool standard_format_one_row(const TimestampValue& timestamp_value, char* buf, const std::string& fmt) {
    int year, month, day, hour, minute, second, microsecond;
    timestamp_value.to_timestamp(&year, &month, &day, &hour, &minute, &second, &microsecond);
    DateTimeValue dt(TIME_DATETIME, year, month, day, hour, minute, second, microsecond);
    bool b = dt.to_format_string(fmt.c_str(), fmt.size(), buf);
    return b;
}

template <PrimitiveType Type>
ColumnPtr standard_format(const std::string& fmt, int len, const starrocks::vectorized::Columns& columns) {
    if (fmt.size() <= 0) {
        return ColumnHelper::create_const_null_column(columns[0]->size());
    }

    auto ts_viewer = ColumnViewer<Type>(columns[0]);

    size_t size = columns[0]->size();
    ColumnBuilder<TYPE_VARCHAR> result(size);

    char buf[len];
    for (size_t i = 0; i < size; ++i) {
        if (ts_viewer.is_null(i)) {
            result.append_null();
        } else {
            TimestampValue ts = (TimestampValue)ts_viewer.value(i);
            bool b = standard_format_one_row(ts, buf, fmt);
            result.append(Slice(std::string(buf)), !b);
        }
    }
    return result.build(ColumnHelper::is_all_const(columns));
}

template <PrimitiveType Type>
ColumnPtr do_format(const TimeFunctions::FormatCtx* ctx, const Columns& cols) {
    if (ctx->fmt_type == TimeFunctions::yyyyMMdd) {
        return date_format_func<yyyyMMddImpl, Type>(cols, 8);
    } else if (ctx->fmt_type == TimeFunctions::yyyy_MM_dd) {
        return date_format_func<yyyy_MM_dd_Impl, Type>(cols, 10);
    } else if (ctx->fmt_type == TimeFunctions::yyyy_MM_dd_HH_mm_ss) {
        return date_format_func<yyyyMMddHHmmssImpl, Type>(cols, 28);
    } else if (ctx->fmt_type == TimeFunctions::yyyy_MM) {
        return date_format_func<yyyy_MMImpl, Type>(cols, 7);
    } else if (ctx->fmt_type == TimeFunctions::yyyyMM) {
        return date_format_func<yyyyMMImpl, Type>(cols, 6);
    } else if (ctx->fmt_type == TimeFunctions::yyyy) {
        return date_format_func<yyyyImpl, Type>(cols, 4);
    } else {
        return standard_format<Type>(ctx->fmt, 128, cols);
    }
}

template <PrimitiveType Type>
void common_format_process(ColumnViewer<Type>* viewer_date, ColumnViewer<TYPE_VARCHAR>* viewer_format,
                           ColumnBuilder<TYPE_VARCHAR>* builder, int i) {
    if (viewer_format->is_null(i) || viewer_format->value(i).empty()) {
        builder->append_null();
        return;
    }

    auto format = viewer_format->value(i).to_string();
    if (format == "%Y%m%d" || format == "yyyyMMdd") {
        builder->append(format_for_yyyyMMdd(viewer_date->value(i)));
    } else if (format == "%Y-%m-%d" || format == "yyyy-MM-dd") {
        builder->append(format_for_yyyy_MM_dd_Impl(viewer_date->value(i)));
    } else if (format == "%Y-%m-%d %H:%i:%s" || format == "yyyy-MM-dd HH:mm:ss") {
        builder->append(format_for_yyyyMMddHHmmssImpl(viewer_date->value(i)));
    } else if (format == "%Y-%m") {
        builder->append(format_for_yyyy_MMImpl(viewer_date->value(i)));
    } else if (format == "%Y%m") {
        builder->append(format_for_yyyyMMImpl(viewer_date->value(i)));
    } else if (format == "%Y") {
        builder->append(format_for_yyyyImpl(viewer_date->value(i)));
    } else {
        char buf[128];
        TimestampValue ts = (TimestampValue)viewer_date->value(i);
        bool b = standard_format_one_row(ts, buf, viewer_format->value(i).to_string());
        builder->append(Slice(std::string(buf)), !b);
    }
}

// datetime_format
ColumnPtr TimeFunctions::datetime_format(FunctionContext* context, const Columns& columns) {
    RETURN_IF_COLUMNS_ONLY_NULL(columns);
    FormatCtx* fc = reinterpret_cast<FormatCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));

    if (fc != nullptr && fc->is_valid) {
        return do_format<TYPE_DATETIME>(fc, columns);
    } else {
        bool all_const = ColumnHelper::is_all_const(columns);
        ColumnViewer<TYPE_DATETIME> viewer_date(columns[0]);
        ColumnViewer<TYPE_VARCHAR> viewer_format(columns[1]);

        // all_const was true viewer_date.size() will return 1
        // which could reduce unnecessary calculations
        size_t num_rows = all_const ? viewer_date.size() : columns[0]->size();

        ColumnBuilder<TYPE_VARCHAR> builder(columns[0]->size());
        for (int i = 0; i < num_rows; ++i) {
            if (viewer_date.is_null(i)) {
                builder.append_null();
                continue;
            }

            common_format_process(&viewer_date, &viewer_format, &builder, i);
        }

        return builder.build(all_const);
    }
}

// date_format
ColumnPtr TimeFunctions::date_format(FunctionContext* context, const Columns& columns) {
    RETURN_IF_COLUMNS_ONLY_NULL(columns);

    FormatCtx* fc = reinterpret_cast<FormatCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));

    if (fc != nullptr && fc->is_valid) {
        return do_format<TYPE_DATE>(fc, columns);
    } else {
        int num_rows = columns[0]->size();
        ColumnViewer<TYPE_DATE> viewer_date(columns[0]);
        ColumnViewer<TYPE_VARCHAR> viewer_format(columns[1]);

        ColumnBuilder<TYPE_VARCHAR> builder(columns[0]->size());

        for (int i = 0; i < num_rows; ++i) {
            if (viewer_date.is_null(i)) {
                builder.append_null();
                continue;
            }

            common_format_process(&viewer_date, &viewer_format, &builder, i);
        }

        return builder.build(ColumnHelper::is_all_const(columns));
    }
}

Status TimeFunctions::datetime_trunc_prepare(starrocks_udf::FunctionContext* context,
                                             starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }

    if (!context->is_notnull_constant_column(0)) {
        return Status::InternalError("datetime_trunc just support const format value");
    }

    ColumnPtr column = context->get_constant_column(0);
    Slice slice = ColumnHelper::get_const_value<TYPE_VARCHAR>(column);
    auto format_value = slice.to_string();

    ScalarFunction function;
    if (format_value == "second") {
        function = &TimeFunctions::datetime_trunc_second;
    } else if (format_value == "minute") {
        function = &TimeFunctions::datetime_trunc_minute;
    } else if (format_value == "hour") {
        function = &TimeFunctions::datetime_trunc_hour;
    } else if (format_value == "day") {
        function = &TimeFunctions::datetime_trunc_day;
    } else if (format_value == "month") {
        function = &TimeFunctions::datetime_trunc_month;
    } else if (format_value == "year") {
        function = &TimeFunctions::datetime_trunc_year;
    } else if (format_value == "week") {
        function = &TimeFunctions::datetime_trunc_week;
    } else if (format_value == "quarter") {
        function = &TimeFunctions::datetime_trunc_quarter;
    } else {
        return Status::InternalError("format value must in {second, minute, hour, day, month, year, week, quarter}");
    }

    auto fc = new DateTruncCtx();
    fc->function = function;
    context->set_function_state(scope, fc);
    return Status::OK();
}

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_secondImpl, v) {
    TimestampValue result = v;
    result.trunc_to_second();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_second, TYPE_DATETIME, TYPE_DATETIME, 1);

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_minuteImpl, v) {
    TimestampValue result = v;
    result.trunc_to_minute();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_minute, TYPE_DATETIME, TYPE_DATETIME, 1);

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_hourImpl, v) {
    TimestampValue result = v;
    result.trunc_to_hour();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_hour, TYPE_DATETIME, TYPE_DATETIME, 1);

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_dayImpl, v) {
    TimestampValue result = v;
    result.trunc_to_day();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_day, TYPE_DATETIME, TYPE_DATETIME, 1);

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_monthImpl, v) {
    TimestampValue result = v;
    result.trunc_to_month();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_month, TYPE_DATETIME, TYPE_DATETIME, 1);

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_yearImpl, v) {
    TimestampValue result = v;
    result.trunc_to_year();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_year, TYPE_DATETIME, TYPE_DATETIME, 1);

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_weekImpl, v) {
    int day_of_week = ((DateValue)v).weekday() + 1;
    TimestampValue result = v;
    result.trunc_to_week(-day_to_first[day_of_week]);
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_week, TYPE_DATETIME, TYPE_DATETIME, 1);

DEFINE_UNARY_FN_WITH_IMPL(datetime_trunc_quarterImpl, v) {
    TimestampValue result = v;
    result.trunc_to_quarter();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(datetime_trunc_quarter, TYPE_DATETIME, TYPE_DATETIME, 1);

ColumnPtr TimeFunctions::datetime_trunc(FunctionContext* context, const Columns& columns) {
    auto ctc = reinterpret_cast<DateTruncCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
    return ctc->function(context, columns);
}

Status TimeFunctions::datetime_trunc_close(starrocks_udf::FunctionContext* context,
                                           starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::FRAGMENT_LOCAL) {
        auto fc = reinterpret_cast<DateTruncCtx*>(context->get_function_state(scope));
        delete fc;
    }

    return Status::OK();
}

Status TimeFunctions::date_trunc_prepare(starrocks_udf::FunctionContext* context,
                                         starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope != FunctionContext::FRAGMENT_LOCAL) {
        return Status::OK();
    }

    if (!context->is_constant_column(0)) {
        return Status::InternalError("date_trunc just support const format value");
    }

    ColumnPtr column = context->get_constant_column(0);

    if (column->only_null()) {
        return Status::InternalError("format value can't be null");
    }

    Slice slice = ColumnHelper::get_const_value<TYPE_VARCHAR>(column);
    auto format_value = slice.to_string();

    ScalarFunction function;
    if (format_value == "day") {
        function = &TimeFunctions::date_trunc_day;
    } else if (format_value == "month") {
        function = &TimeFunctions::date_trunc_month;
    } else if (format_value == "year") {
        function = &TimeFunctions::date_trunc_year;
    } else if (format_value == "week") {
        function = &TimeFunctions::date_trunc_week;
    } else if (format_value == "quarter") {
        function = &TimeFunctions::date_trunc_quarter;
    } else {
        return Status::InternalError("format value must in {day, month, year, week, quarter}");
    }

    auto fc = new DateTruncCtx();
    fc->function = function;
    context->set_function_state(scope, fc);
    return Status::OK();
}

ColumnPtr TimeFunctions::date_trunc_day(FunctionContext* context, const starrocks::vectorized::Columns& columns) {
    return columns[1];
}

DEFINE_UNARY_FN_WITH_IMPL(date_trunc_monthImpl, v) {
    DateValue result = v;
    result.trunc_to_month();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(date_trunc_month, TYPE_DATE, TYPE_DATE, 1);

DEFINE_UNARY_FN_WITH_IMPL(date_trunc_yearImpl, v) {
    DateValue result = v;
    result.trunc_to_year();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(date_trunc_year, TYPE_DATE, TYPE_DATE, 1);

DEFINE_UNARY_FN_WITH_IMPL(date_trunc_weekImpl, v) {
    DateValue result = v;
    result.trunc_to_week();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(date_trunc_week, TYPE_DATE, TYPE_DATE, 1);

DEFINE_UNARY_FN_WITH_IMPL(date_trunc_quarterImpl, v) {
    DateValue result = v;
    result.trunc_to_quarter();
    return result;
}
DEFINE_TIME_UNARY_FN_EXTEND(date_trunc_quarter, TYPE_DATE, TYPE_DATE, 1);

ColumnPtr TimeFunctions::date_trunc(FunctionContext* context, const Columns& columns) {
    auto ctc = reinterpret_cast<DateTruncCtx*>(context->get_function_state(FunctionContext::FRAGMENT_LOCAL));
    return ctc->function(context, columns);
}

Status TimeFunctions::date_trunc_close(starrocks_udf::FunctionContext* context,
                                       starrocks_udf::FunctionContext::FunctionStateScope scope) {
    if (scope == FunctionContext::FRAGMENT_LOCAL) {
        auto fc = reinterpret_cast<DateTruncCtx*>(context->get_function_state(scope));
        delete fc;
    }

    return Status::OK();
}

#undef DEFINE_TIME_UNARY_FN
#undef DEFINE_TIME_UNARY_FN_WITH_IMPL
#undef DEFINE_TIME_BINARY_FN
#undef DEFINE_TIME_BINARY_FN_WITH_IMPL
#undef DEFINE_TIME_STRING_UNARY_FN
#undef DEFINE_TIME_UNARY_FN_EXTEND

} // namespace starrocks::vectorized
