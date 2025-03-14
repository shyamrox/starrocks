// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/test/runtime/stream_load_pipe_test.cpp

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "runtime/stream_load/stream_load_pipe.h"

#include <gtest/gtest.h>

#include <thread>

#include "testutil/parallel_test.h"
#include "util/monotime.h"

namespace starrocks {

class StreamLoadPipeTest : public testing::Test {
public:
    StreamLoadPipeTest() {}
    virtual ~StreamLoadPipeTest() {}
    void SetUp() override {}
};

PARALLEL_TEST(StreamLoadPipeTest, append_buffer) {
    StreamLoadPipe pipe(66, 64);

    auto appender = [&pipe] {
        int k = 0;
        for (int i = 0; i < 2; ++i) {
            auto byte_buf = ByteBuffer::allocate(64);
            char buf[64];
            for (int j = 0; j < 64; ++j) {
                buf[j] = '0' + (k++ % 10);
            }
            byte_buf->put_bytes(buf, 64);
            byte_buf->flip();
            pipe.append(byte_buf);
        }
        pipe.finish();
    };
    std::thread t1(appender);

    char buf[256];
    size_t buf_len = 256;
    bool eof = false;
    auto st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(128, buf_len);
    ASSERT_FALSE(eof);
    for (int i = 0; i < 128; ++i) {
        ASSERT_EQ('0' + (i % 10), buf[i]);
    }
    st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(0, buf_len);
    ASSERT_TRUE(eof);

    t1.join();
}

PARALLEL_TEST(StreamLoadPipeTest, append_bytes) {
    StreamLoadPipe pipe(66, 64);

    auto appender = [&pipe] {
        for (int i = 0; i < 128; ++i) {
            char buf = '0' + (i % 10);
            pipe.append(&buf, 1);
        }
        pipe.finish();
    };
    std::thread t1(appender);

    char buf[256];
    size_t buf_len = 256;
    bool eof = false;
    auto st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(128, buf_len);
    ASSERT_FALSE(eof);
    for (int i = 0; i < 128; ++i) {
        ASSERT_EQ('0' + (i % 10), buf[i]);
    }
    st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(0, buf_len);
    ASSERT_TRUE(eof);

    t1.join();
}

PARALLEL_TEST(StreamLoadPipeTest, append_bytes2) {
    StreamLoadPipe pipe(66, 64);

    auto appender = [&pipe] {
        for (int i = 0; i < 128; ++i) {
            char buf = '0' + (i % 10);
            pipe.append(&buf, 1);
        }
        pipe.finish();
    };
    std::thread t1(appender);

    char buf[128];
    size_t buf_len = 62;
    bool eof = false;
    auto st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(62, buf_len);
    ASSERT_FALSE(eof);
    for (int i = 0; i < 62; ++i) {
        ASSERT_EQ('0' + (i % 10), buf[i]);
    }
    for (int i = 62; i < 128; ++i) {
        char ch;
        buf_len = 1;
        auto st = pipe.read((uint8_t*)&ch, &buf_len, &eof);
        ASSERT_TRUE(st.ok());
        ASSERT_EQ(1, buf_len);
        ASSERT_FALSE(eof);
        ASSERT_EQ('0' + (i % 10), ch);
    }
    st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(0, buf_len);
    ASSERT_TRUE(eof);

    t1.join();
}

PARALLEL_TEST(StreamLoadPipeTest, append_mix) {
    StreamLoadPipe pipe(66, 64);

    auto appender = [&pipe] {
        // 10
        int k = 0;
        for (int i = 0; i < 10; ++i) {
            char buf = '0' + (k++ % 10);
            pipe.append(&buf, 1);
        }
        // 60
        {
            auto byte_buf = ByteBuffer::allocate(60);
            char buf[60];
            for (int j = 0; j < 60; ++j) {
                buf[j] = '0' + (k++ % 10);
            }
            byte_buf->put_bytes(buf, 60);
            byte_buf->flip();
            pipe.append(byte_buf);
        }
        // 8
        for (int i = 0; i < 8; ++i) {
            char buf = '0' + (k++ % 10);
            pipe.append(&buf, 1);
        }
        // 50
        {
            auto byte_buf = ByteBuffer::allocate(50);
            char buf[50];
            for (int j = 0; j < 50; ++j) {
                buf[j] = '0' + (k++ % 10);
            }
            byte_buf->put_bytes(buf, 50);
            byte_buf->flip();
            pipe.append(byte_buf);
        }
        pipe.finish();
    };
    std::thread t1(appender);

    char buf[128];
    size_t buf_len = 128;
    bool eof = false;
    auto st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(128, buf_len);
    ASSERT_FALSE(eof);
    for (int i = 0; i < 128; ++i) {
        ASSERT_EQ('0' + (i % 10), buf[i]);
    }
    st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(0, buf_len);
    ASSERT_TRUE(eof);

    t1.join();
}

PARALLEL_TEST(StreamLoadPipeTest, cancel) {
    StreamLoadPipe pipe(66, 64);

    auto appender = [&pipe] {
        int k = 0;
        for (int i = 0; i < 10; ++i) {
            char buf = '0' + (k++ % 10);
            pipe.append(&buf, 1);
        }
        SleepFor(MonoDelta::FromMilliseconds(100));
        pipe.cancel(Status::Cancelled("Cancelled"));
    };
    std::thread t1(appender);

    char buf[128];
    size_t buf_len = 128;
    bool eof = false;
    auto st = pipe.read((uint8_t*)buf, &buf_len, &eof);
    ASSERT_FALSE(st.ok());
    t1.join();
}

PARALLEL_TEST(StreamLoadPipeTest, close) {
    StreamLoadPipe pipe(66, 64);

    auto appender = [&pipe] {
        int k = 0;
        {
            auto byte_buf = ByteBuffer::allocate(64);
            char buf[64];
            for (int j = 0; j < 64; ++j) {
                buf[j] = '0' + (k++ % 10);
            }
            byte_buf->put_bytes(buf, 64);
            byte_buf->flip();
            pipe.append(byte_buf);
        }
        {
            auto byte_buf = ByteBuffer::allocate(64);
            char buf[64];
            for (int j = 0; j < 64; ++j) {
                buf[j] = '0' + (k++ % 10);
            }
            byte_buf->put_bytes(buf, 64);
            byte_buf->flip();
            auto st = pipe.append(byte_buf);
            ASSERT_TRUE(st.ok());
        }
    };
    std::thread t1(appender);

    SleepFor(MonoDelta::FromMilliseconds(10));

    pipe.close();

    t1.join();
}

PARALLEL_TEST(StreamLoadPipeTest, read_one_message) {
    StreamLoadPipe pipe(66, 64);

    auto appender = [&pipe] {
        int k = 0;
        auto byte_buf = ByteBuffer::allocate(64);
        char buf[64];
        for (int j = 0; j < 64; ++j) {
            buf[j] = '0' + (k++ % 10);
        }
        byte_buf->put_bytes(buf, 64);
        byte_buf->flip();
        // 1st append
        pipe.append(byte_buf);

        // 2nd append
        pipe.append(buf, sizeof(buf));

        pipe.finish();
    };
    std::thread t1(appender);

    std::unique_ptr<uint8_t[]> buf;
    size_t buf_cap = 0;
    size_t buf_sz = 0;
    // 1st message
    auto st = pipe.read_one_message(&buf, &buf_cap, &buf_sz, 0);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(64, buf_sz);
    for (int i = 0; i < buf_sz; ++i) {
        ASSERT_EQ('0' + (i % 10), buf[i]);
    }

    // 2nd message
    st = pipe.read_one_message(&buf, &buf_cap, &buf_sz, 0);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(64, buf_sz);
    for (int i = 0; i < buf_sz; ++i) {
        ASSERT_EQ('0' + (i % 10), buf[i]);
    }

    st = pipe.read_one_message(&buf, &buf_cap, &buf_sz, 0);
    ASSERT_TRUE(st.ok());
    ASSERT_EQ(0, buf_sz);

    t1.join();
}

} // namespace starrocks
