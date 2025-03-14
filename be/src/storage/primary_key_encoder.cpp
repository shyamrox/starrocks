// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/kudu/blob/master/src/kudu/common/key_encoder.h

// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

// Some code copy from Kudu
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

#include "storage/primary_key_encoder.h"

#include <cstring>
#include <memory>
#include <type_traits>

#include "column/binary_column.h"
#include "column/chunk.h"
#include "column/fixed_length_column.h"
#include "column/schema.h"
#include "gutil/endian.h"
#include "runtime/date_value.hpp"
#include "storage/tablet_schema.h"

namespace starrocks {

template <class UT>
UT to_bigendian(UT v);

template <>
uint8_t to_bigendian(uint8_t v) {
    return v;
}
template <>
uint16_t to_bigendian(uint16_t v) {
    return BigEndian::FromHost16(v);
}
template <>
uint32_t to_bigendian(uint32_t v) {
    return BigEndian::FromHost32(v);
}
template <>
uint64_t to_bigendian(uint64_t v) {
    return BigEndian::FromHost64(v);
}
template <>
uint128_t to_bigendian(uint128_t v) {
    return BigEndian::FromHost128(v);
}

template <class T>
void encode_integral(const T& v, string* dest) {
    if constexpr (std::is_signed<T>::value) {
        typedef typename std::make_unsigned<T>::type UT;
        UT uv = v;
        uv ^= static_cast<UT>(1) << (sizeof(UT) * 8 - 1);
        uv = to_bigendian(uv);
        dest->append(reinterpret_cast<const char*>(&uv), sizeof(uv));
    } else {
        T nv = to_bigendian(v);
        dest->append(reinterpret_cast<const char*>(&nv), sizeof(nv));
    }
}

template <class T>
void decode_integral(Slice* src, T* v) {
    if constexpr (std::is_signed<T>::value) {
        typedef typename std::make_unsigned<T>::type UT;
        UT uv = *(UT*)(src->data);
        uv = to_bigendian(uv);
        uv ^= static_cast<UT>(1) << (sizeof(UT) * 8 - 1);
        *v = uv;
    } else {
        T nv = *(T*)(src->data);
        *v = to_bigendian(nv);
    }
    src->remove_prefix(sizeof(T));
}

template <int LEN>
static bool SSEEncodeChunk(const uint8_t** srcp, uint8_t** dstp) {
#if defined(__aarch64__) || !defined(__SSE4_2__)
    return false;
#else
    __m128i data;
    if (LEN == 16) {
        // Load 16 bytes (unaligned) into the XMM register.
        data = _mm_loadu_si128(reinterpret_cast<const __m128i*>(*srcp));
    } else if (LEN == 8) {
        // Load 8 bytes (unaligned) into the XMM register
        data = reinterpret_cast<__m128i>(_mm_load_sd(reinterpret_cast<const double*>(*srcp)));
    }
    // Compare each byte of the input with '\0'. This results in a vector
    // where each byte is either \x00 or \xFF, depending on whether the
    // input had a '\x00' in the corresponding position.
    __m128i zeros = reinterpret_cast<__m128i>(_mm_setzero_pd());
    __m128i zero_bytes = _mm_cmpeq_epi8(data, zeros);

    // Check whether the resulting vector is all-zero.
    bool all_zeros;
    if (LEN == 16) {
        all_zeros = _mm_testz_si128(zero_bytes, zero_bytes);
    } else { // LEN == 8
        all_zeros = _mm_cvtsi128_si64(zero_bytes) == 0;
    }

    // If it's all zero, we can just store the entire chunk.
    if (PREDICT_FALSE(!all_zeros)) {
        return false;
    }

    if (LEN == 16) {
        _mm_storeu_si128(reinterpret_cast<__m128i*>(*dstp), data);
    } else {
        _mm_storel_epi64(reinterpret_cast<__m128i*>(*dstp), data); // movq m64, xmm
    }
    *dstp += LEN;
    *srcp += LEN;
    return true;
#endif //__aarch64__
}

// Non-SSE loop which encodes 'len' bytes from 'srcp' into 'dst'.
static inline void EncodeChunkLoop(const uint8_t** srcp, uint8_t** dstp, int len) {
    while (len--) {
        if (PREDICT_FALSE(**srcp == '\0')) {
            *(*dstp)++ = 0;
            *(*dstp)++ = 1;
        } else {
            *(*dstp)++ = **srcp;
        }
        (*srcp)++;
    }
}

inline void encode_slice(const Slice& s, string* dst, bool is_last) {
    if (is_last) {
        dst->append(s.data, s.size);
    } else {
        // If we're a middle component of a composite key, we need to add a \x00
        // at the end in order to separate this component from the next one. However,
        // if we just did that, we'd have issues where a key that actually has
        // \x00 in it would compare wrong, so we have to instead add \x00\x00, and
        // encode \x00 as \x00\x01.
        size_t old_size = dst->size();
        dst->resize(old_size + s.size * 2 + 2);

        const uint8_t* srcp = (const uint8_t*)s.data;
        uint8_t* dstp = reinterpret_cast<uint8_t*>(&(*dst)[old_size]);
        size_t len = s.size;
        size_t rem = len;

        while (rem >= 16) {
            if (!SSEEncodeChunk<16>(&srcp, &dstp)) {
                goto slow_path;
            }
            rem -= 16;
        }
        while (rem >= 8) {
            if (!SSEEncodeChunk<8>(&srcp, &dstp)) {
                goto slow_path;
            }
            rem -= 8;
        }
        // Roll back to operate in 8 bytes at a time.
        if (len > 8 && rem > 0) {
            dstp -= 8 - rem;
            srcp -= 8 - rem;
            if (!SSEEncodeChunk<8>(&srcp, &dstp)) {
                // TODO: optimize for the case where the input slice has '\0'
                // bytes. (e.g. move the pointer to the first zero byte.)
                dstp += 8 - rem;
                srcp += 8 - rem;
                goto slow_path;
            }
            rem = 0;
            goto done;
        }

    slow_path:
        EncodeChunkLoop(&srcp, &dstp, rem);

    done:
        *dstp++ = 0;
        *dstp++ = 0;
        dst->resize(dstp - reinterpret_cast<uint8_t*>(&(*dst)[0]));
    }
}

inline Status decode_slice(Slice* src, string* dest, bool is_last) {
    if (is_last) {
        dest->append(src->data, src->size);
    } else {
        uint8_t* separator = static_cast<uint8_t*>(memmem(src->data, src->size, "\0\0", 2));
        DCHECK(separator) << "bad encoded primary key, separator not found";
        if (PREDICT_FALSE(separator == nullptr)) {
            LOG(WARNING) << "bad encoded primary key, separator not found";
            return Status::InvalidArgument("bad encoded primary key, separator not found");
        }
        uint8_t* data = (uint8_t*)src->data;
        int len = separator - data;
        for (int i = 0; i < len; i++) {
            if (i >= 1 && data[i - 1] == '\0' && data[i] == '\1') {
                continue;
            }
            dest->push_back((char)data[i]);
        }
        src->remove_prefix(len + 2);
    }
    return Status::OK();
}

bool PrimaryKeyEncoder::is_supported(const vectorized::Field& f) {
    if (f.is_nullable()) {
        return false;
    }
    auto type = f.type()->type();
    switch (type) {
    case OLAP_FIELD_TYPE_BOOL:
    case OLAP_FIELD_TYPE_TINYINT:
    case OLAP_FIELD_TYPE_SMALLINT:
    case OLAP_FIELD_TYPE_INT:
    case OLAP_FIELD_TYPE_BIGINT:
    case OLAP_FIELD_TYPE_LARGEINT:
    case OLAP_FIELD_TYPE_VARCHAR:
    case OLAP_FIELD_TYPE_DATE_V2:
    case OLAP_FIELD_TYPE_TIMESTAMP:
        return true;
    default:
        return false;
    }
}

bool PrimaryKeyEncoder::is_supported(const vectorized::Schema& schema) {
    size_t n = schema.num_key_fields();
    for (size_t i = 0; i < n; i++) {
        if (!is_supported(*schema.field(i))) {
            return false;
        }
    }
    return true;
}

FieldType PrimaryKeyEncoder::encoded_primary_key_type(const vectorized::Schema& schema) {
    if (!is_supported(schema)) {
        return OLAP_FIELD_TYPE_NONE;
    }
    if (schema.num_key_fields() == 1) {
        return schema.field(0)->type()->type();
    }
    return OLAP_FIELD_TYPE_VARCHAR;
}

size_t PrimaryKeyEncoder::get_encoded_fixed_size(const vectorized::Schema& schema) {
    size_t ret = 0;
    size_t n = schema.num_key_fields();
    for (size_t i = 0; i < n; i++) {
        auto t = schema.field(i)->type()->type();
        if (t == OLAP_FIELD_TYPE_VARCHAR || t == OLAP_FIELD_TYPE_CHAR) {
            return 0;
        }
        ret += TabletColumn::get_field_length_by_type(t, 0);
    }
    return ret;
}

Status PrimaryKeyEncoder::create_column(const vectorized::Schema& schema,
                                        std::unique_ptr<vectorized::Column>* pcolumn) {
    if (!is_supported(schema)) {
        return Status::NotSupported("type not supported for primary key encoding");
    }
    // TODO: let `Chunk::column_from_field_type` and `Chunk::column_from_field` return a
    // `std::unique_ptr<Column>` instead of `std::shared_ptr<Column>`, in order to reuse
    // its code here.
    if (schema.num_key_fields() == 1) {
        // simple encoding
        // integer's use fixed length original column
        // varchar use binary
        auto type = schema.field(0)->type()->type();
        switch (type) {
        case OLAP_FIELD_TYPE_BOOL:
            *pcolumn = vectorized::BooleanColumn::create_mutable();
            break;
        case OLAP_FIELD_TYPE_TINYINT:
            *pcolumn = vectorized::Int8Column::create_mutable();
            break;
        case OLAP_FIELD_TYPE_SMALLINT:
            *pcolumn = vectorized::Int16Column::create_mutable();
            break;
        case OLAP_FIELD_TYPE_INT:
            *pcolumn = vectorized::Int32Column::create_mutable();
            break;
        case OLAP_FIELD_TYPE_BIGINT:
            *pcolumn = vectorized::Int64Column::create_mutable();
            break;
        case OLAP_FIELD_TYPE_LARGEINT:
            *pcolumn = vectorized::Int128Column::create_mutable();
            break;
        case OLAP_FIELD_TYPE_VARCHAR:
            *pcolumn = std::make_unique<vectorized::BinaryColumn>();
            break;
        case OLAP_FIELD_TYPE_DATE_V2:
            *pcolumn = vectorized::DateColumn::create_mutable();
            break;
        case OLAP_FIELD_TYPE_TIMESTAMP:
            *pcolumn = vectorized::TimestampColumn::create_mutable();
            break;
        default:
            return Status::NotSupported(StringPrintf("primary key type not support: %s", field_type_to_string(type)));
        }
    } else {
        // composite keys encoding to binary
        // TODO(cbl): support fixed length encoded keys, e.g. (int32, int32) => int64
        *pcolumn = std::make_unique<vectorized::BinaryColumn>();
    }
    return Status::OK();
}

typedef void (*EncodeOp)(const void*, int, string*);

static void prepare_ops_datas(const vectorized::Schema& schema, const vectorized::Chunk& chunk, vector<EncodeOp>* pops,
                              vector<const void*>* pdatas) {
    int ncol = schema.num_key_fields();
    auto& ops = *pops;
    auto& datas = *pdatas;
    ops.resize(ncol, nullptr);
    datas.resize(ncol, nullptr);
    for (int j = 0; j < ncol; j++) {
        datas[j] = chunk.get_column_by_index(j)->raw_data();
        switch (schema.field(j)->type()->type()) {
        case OLAP_FIELD_TYPE_BOOL:
            ops[j] = [](const void* data, int idx, string* buff) {
                encode_integral(((const uint8_t*)data)[idx], buff);
            };
            break;
        case OLAP_FIELD_TYPE_TINYINT:
            ops[j] = [](const void* data, int idx, string* buff) { encode_integral(((const int8_t*)data)[idx], buff); };
            break;
        case OLAP_FIELD_TYPE_SMALLINT:
            ops[j] = [](const void* data, int idx, string* buff) {
                encode_integral(((const int16_t*)data)[idx], buff);
            };
            break;
        case OLAP_FIELD_TYPE_INT:
            ops[j] = [](const void* data, int idx, string* buff) {
                encode_integral(((const int32_t*)data)[idx], buff);
            };
            break;
        case OLAP_FIELD_TYPE_BIGINT:
            ops[j] = [](const void* data, int idx, string* buff) {
                encode_integral(((const int64_t*)data)[idx], buff);
            };
            break;
        case OLAP_FIELD_TYPE_LARGEINT:
            ops[j] = [](const void* data, int idx, string* buff) {
                encode_integral(((const int128_t*)data)[idx], buff);
            };
            break;
        case OLAP_FIELD_TYPE_VARCHAR:
            if (j + 1 == ncol) {
                ops[j] = [](const void* data, int idx, string* buff) {
                    encode_slice(((const Slice*)data)[idx], buff, true);
                };
            } else {
                ops[j] = [](const void* data, int idx, string* buff) {
                    encode_slice(((const Slice*)data)[idx], buff, false);
                };
            }
            break;
        case OLAP_FIELD_TYPE_DATE_V2:
            ops[j] = [](const void* data, int idx, string* buff) {
                encode_integral(((const int32_t*)data)[idx], buff);
            };
            break;
        case OLAP_FIELD_TYPE_TIMESTAMP:
            ops[j] = [](const void* data, int idx, string* buff) {
                encode_integral(((const int64_t*)data)[idx], buff);
            };
            break;
        default:
            CHECK(false) << "type not supported for primary key encoding "
                         << field_type_to_string(schema.field(j)->type()->type());
        }
    }
}

void PrimaryKeyEncoder::encode(const vectorized::Schema& schema, const vectorized::Chunk& chunk, size_t offset,
                               size_t len, vectorized::Column* dest) {
    if (schema.num_key_fields() == 1) {
        // simple encoding, src & dest should have same type
        auto& src = chunk.get_column_by_index(0);
        dest->append(*src, offset, len);
    } else {
        CHECK(dest->is_binary()) << "dest column should be binary";
        int ncol = schema.num_key_fields();
        vector<EncodeOp> ops;
        vector<const void*> datas;
        prepare_ops_datas(schema, chunk, &ops, &datas);
        vectorized::BinaryColumn& bdest = down_cast<vectorized::BinaryColumn&>(*dest);
        bdest.reserve(bdest.size() + len);
        string buff;
        for (size_t i = 0; i < len; i++) {
            buff.clear();
            for (int j = 0; j < ncol; j++) {
                ops[j](datas[j], offset + i, &buff);
            }
            bdest.append(buff);
        }
    }
}

void PrimaryKeyEncoder::encode_selective(const vectorized::Schema& schema, const vectorized::Chunk& chunk,
                                         const uint32_t* indexes, size_t len, vectorized::Column* dest) {
    if (schema.num_key_fields() == 1) {
        // simple encoding, src & dest should have same type
        auto& src = chunk.get_column_by_index(0);
        dest->append_selective(*src, indexes, 0, len);
    } else {
        CHECK(dest->is_binary()) << "dest column should be binary";
        int ncol = schema.num_key_fields();
        vector<EncodeOp> ops;
        vector<const void*> datas;
        prepare_ops_datas(schema, chunk, &ops, &datas);
        vectorized::BinaryColumn& bdest = down_cast<vectorized::BinaryColumn&>(*dest);
        bdest.reserve(bdest.size() + len);
        string buff;
        for (int i = 0; i < len; i++) {
            uint32_t idx = indexes[i];
            buff.clear();
            for (int j = 0; j < ncol; j++) {
                ops[j](datas[j], idx, &buff);
            }
            bdest.append(buff);
        }
    }
}

bool PrimaryKeyEncoder::encode_exceed_limit(const vectorized::Schema& schema, const vectorized::Chunk& chunk,
                                            size_t offset, size_t len, const size_t limit_size) {
    int ncol = schema.num_key_fields();
    vector<const void*> datas(ncol, nullptr);
    if (ncol == 1) {
        if (schema.field(0)->type()->type() == OLAP_FIELD_TYPE_VARCHAR) {
            if (static_cast<const Slice*>(static_cast<const void*>(chunk.get_column_by_index(0)->raw_data()))
                        ->get_size() > limit_size) {
                return true;
            }
        }
    } else {
        size_t size = 0;

        std::vector<int> varchar_indexes;

        for (int i = 0; i < ncol; i++) {
            datas[i] = chunk.get_column_by_index(i)->raw_data();
            if (schema.field(i)->type()->type() == OLAP_FIELD_TYPE_VARCHAR) {
                varchar_indexes.push_back(i);
            } else {
                size += TabletColumn::get_field_length_by_type(schema.field(i)->type()->type(), 0);
            }
            if (size > limit_size) {
                return true;
            }
        }

        const int accumulated_fixed_size = size;

        for (size_t i = 0; i < len; i++) {
            size = accumulated_fixed_size;
            for (const auto varchar_index : varchar_indexes) {
                if (varchar_index + 1 == ncol) {
                    size += static_cast<const Slice*>(datas[varchar_index])[i].get_size();
                } else {
                    auto s = static_cast<const Slice*>(datas[varchar_index])[i];
                    std::string_view sv(s.get_data(), s.get_size());
                    size += s.get_size() + std::count(sv.begin(), sv.end(), 0) + 2;
                }
            }
            if (size > limit_size) {
                return true;
            }
        }
    }

    return false;
}

Status PrimaryKeyEncoder::decode(const vectorized::Schema& schema, const vectorized::Column& keys, size_t offset,
                                 size_t len, vectorized::Chunk* dest) {
    if (schema.num_key_fields() == 1) {
        // simple decoding, src & dest should have same type
        dest->get_column_by_index(0)->append(keys, offset, len);
    } else {
        CHECK(keys.is_binary()) << "keys column should be binary";
        auto& bkeys = down_cast<const vectorized::BinaryColumn&>(keys);
        const int ncol = schema.num_key_fields();
        for (int i = 0; i < len; i++) {
            Slice s = bkeys.get_slice(offset + i);
            for (int j = 0; j < ncol; j++) {
                auto& column = *(dest->get_column_by_index(j));
                switch (schema.field(j)->type()->type()) {
                case OLAP_FIELD_TYPE_BOOL: {
                    auto& tc = down_cast<vectorized::UInt8Column&>(column);
                    uint8_t v;
                    decode_integral(&s, &v);
                    tc.append((int8_t)v);
                } break;
                case OLAP_FIELD_TYPE_TINYINT: {
                    auto& tc = down_cast<vectorized::Int8Column&>(column);
                    int8_t v;
                    decode_integral(&s, &v);
                    tc.append(v);
                } break;
                case OLAP_FIELD_TYPE_SMALLINT: {
                    auto& tc = down_cast<vectorized::Int16Column&>(column);
                    int16_t v;
                    decode_integral(&s, &v);
                    tc.append(v);
                } break;
                case OLAP_FIELD_TYPE_INT: {
                    auto& tc = down_cast<vectorized::Int32Column&>(column);
                    int32_t v;
                    decode_integral(&s, &v);
                    tc.append(v);
                } break;
                case OLAP_FIELD_TYPE_BIGINT: {
                    auto& tc = down_cast<vectorized::Int64Column&>(column);
                    int64_t v;
                    decode_integral(&s, &v);
                    tc.append(v);
                } break;
                case OLAP_FIELD_TYPE_LARGEINT: {
                    auto& tc = down_cast<vectorized::Int128Column&>(column);
                    int128_t v;
                    decode_integral(&s, &v);
                    tc.append(v);
                } break;
                case OLAP_FIELD_TYPE_VARCHAR: {
                    auto& tc = down_cast<vectorized::BinaryColumn&>(column);
                    string v;
                    RETURN_IF_ERROR(decode_slice(&s, &v, j + 1 == ncol));
                    tc.append(v);
                } break;
                case OLAP_FIELD_TYPE_DATE_V2: {
                    auto& tc = down_cast<vectorized::DateColumn&>(column);
                    vectorized::DateValue v;
                    decode_integral(&s, &v._julian);
                    tc.append(v);
                } break;
                case OLAP_FIELD_TYPE_DATETIME: {
                    auto& tc = down_cast<vectorized::TimestampColumn&>(column);
                    vectorized::TimestampValue v;
                    decode_integral(&s, &v._timestamp);
                    tc.append(v);
                } break;
                default:
                    CHECK(false) << "type not supported for primary key encoding";
                }
            }
        }
    }
    return Status::OK();
}

} // namespace starrocks
