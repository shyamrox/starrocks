// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "io/array_input_stream.h"

#include <gtest/gtest.h>

#include "common/logging.h"
#include "testutil/assert.h"
#include "testutil/parallel_test.h"

namespace starrocks::io {

// NOLINTNEXTLINE
PARALLEL_TEST(ArrayInputStreamTest, test_read) {
    std::string s("0123456789");
    ArrayInputStream in(s.data(), static_cast<int64_t>(s.size()));
    ASSERT_EQ(10, *in.get_size());

    char buff[10];
    ASSIGN_OR_ABORT(auto nread, in.read(buff, 2));
    ASSERT_EQ(2, nread);
    ASSERT_EQ(2, *in.position());
    ASSIGN_OR_ABORT(nread, in.read(buff + 2, 10));
    ASSERT_EQ(8, nread);
    ASSERT_EQ(10, *in.position());
    ASSERT_EQ("0123456789", std::string_view(buff, 10));
    ASSIGN_OR_ABORT(nread, in.read(buff, 10));
    ASSERT_EQ(0, nread);
}

// NOLINTNEXTLINE
PARALLEL_TEST(ArrayInputStreamTest, test_read_empty) {
    std::string s("");
    ArrayInputStream in(s.data(), static_cast<int64_t>(s.size()));
    ASSERT_EQ(0, *in.get_size());

    char buff[2];
    ASSIGN_OR_ABORT(auto nread, in.read(buff, 2));
    ASSERT_EQ(0, nread);
}

// NOLINTNEXTLINE
PARALLEL_TEST(ArrayInputStreamTest, test_read_invalid_count) {
    std::string s("01234");
    ArrayInputStream in(s.data(), static_cast<int64_t>(s.size()));
    char buff[2];
    ASSERT_ERROR(in.read(buff, -1));
    ASSERT_ERROR(in.read_at(0, buff, -1));
}

// NOLINTNEXTLINE
PARALLEL_TEST(ArrayInputStreamTest, test_read_at_invalid_offset) {
    std::string s("01234");
    ArrayInputStream in(s.data(), static_cast<int64_t>(s.size()));
    char buff[2];
    ASSERT_ERROR(in.read_at(-1, buff, 2));
    ASSIGN_OR_ABORT(auto r, in.read_at(6, buff, 2));
    ASSERT_EQ(0, r);
}

// NOLINTNEXTLINE
PARALLEL_TEST(ArrayInputStreamTest, test_read_at) {
    std::string s("0123456789");
    ArrayInputStream in(s.data(), static_cast<int64_t>(s.size()));
    ASSERT_EQ(10, *in.get_size());

    char buff[10];
    ASSIGN_OR_ABORT(auto nread, in.read_at(0, buff, 2));
    ASSERT_EQ(2, *in.position());
    ASSERT_EQ("01", std::string_view(buff, nread));

    ASSIGN_OR_ABORT(nread, in.read(buff, 4));
    ASSERT_EQ(6, *in.position());
    ASSERT_EQ("2345", std::string_view(buff, nread));

    ASSIGN_OR_ABORT(nread, in.read_at(2, buff, 10));
    ASSERT_EQ(8, nread);
    ASSERT_EQ(10, *in.position());
    ASSERT_EQ("23456789", std::string_view(buff, nread));
    ASSIGN_OR_ABORT(nread, in.read_at(10, buff, 10));
    ASSERT_EQ(0, nread);
    ASSIGN_OR_ABORT(nread, in.read_at(12, buff, 10));
    ASSERT_EQ(0, nread);

    ASSIGN_OR_ABORT(nread, in.read(buff, 10));
    ASSERT_EQ(0, nread);
}

// NOLINTNEXTLINE
PARALLEL_TEST(ArrayInputStreamTest, test_seek_and_peak) {
    std::string s("0123456789");
    ArrayInputStream in(s.data(), static_cast<int64_t>(s.size()));

    ASSERT_TRUE(in.allows_peak());

    ASSERT_OK(in.seek(5));
    ASSERT_EQ(5, *in.position());
    ASSERT_EQ("56789", *in.peak(10));

    ASSERT_OK(in.seek(7));
    ASSERT_EQ(7, *in.position());
    ASSERT_EQ("789", *in.peak(10));

    ASSERT_OK(in.seek(10));
    ASSERT_EQ(10, *in.position());
    ASSERT_EQ("", *in.peak(10));

    ASSERT_OK(in.seek(11));
    ASSERT_EQ("", *in.peak(10));
}

} // namespace starrocks::io
