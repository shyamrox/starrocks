// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/util/file_cache.h

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

#pragma once

#include <memory>
#include <string>

#include "common/logging.h"
#include "util/lru_cache.h"

namespace starrocks {

class Env;

// A "smart" retrieved LRU cache handle.
//
// The cache handle is released when this object goes out of scope, possibly
// closing the opened file if it is no longer in the cache.
template <class FileType>
class OpenedFileHandle {
public:
    OpenedFileHandle() {}

    // A opened file handle
    explicit OpenedFileHandle(Cache* cache, Cache::Handle* handle) : _cache(cache), _handle(handle) {}

    // release cache handle
    ~OpenedFileHandle() {
        if (_handle != nullptr) {
            _cache->release(_handle);
        }
    }

    OpenedFileHandle(OpenedFileHandle&& other) noexcept {
        std::swap(_cache, other._cache);
        std::swap(_handle, other._handle);
    }

    OpenedFileHandle& operator=(OpenedFileHandle&& other) noexcept {
        std::swap(_cache, other._cache);
        std::swap(_handle, other._handle);
        return *this;
    }

    FileType* file() const {
        DCHECK(_handle != nullptr);
        return reinterpret_cast<FileType*>(_cache->value(_handle));
    }

private:
    Cache* _cache{nullptr};
    Cache::Handle* _handle{nullptr};
};

// Cache of open files.
//
// The purpose of this cache is to enforce an upper bound on the maximum number
// of files open at a time. Files opened through the cache may be closed at any
// time, only to be reopened upon next use.
//
// The file cache can be viewed as having two logical parts: the client-facing
// File handle and the LRU cache.
//
// Client-facing API
// -----------------
// The core of the client-facing API is the cache descriptor. A descriptor

// LRU cache
// ---------
// The lower half of the file cache is a standard LRU cache whose keys are file
// names and whose values are pointers to opened file objects allocated on the
// heap. Unlike the descriptor map, this cache has an upper bound on capacity,
// and handles are evicted (and closed) according to an LRU algorithm.
//
// Whenever a descriptor is used by a client in file I/O, its file name is used
// in an LRU cache lookup. If found, the underlying file is still open and the
// file access is performed. Otherwise, the file must have been evicted and
// closed, so it is reopened and reinserted (possibly evicting a different open
// file) before the file access is performed.
//
// Every public method in the file cache is thread safe.
template <class FileType>
class FileCache {
public:
    // Creates a new file cache.
    //
    // The 'cache_name' is used to disambiguate amongst other file cache
    // instances. The cache will use 'max_open_files' as a soft upper bound on
    // the number of files open at any given time.
    FileCache(std::string cache_name, int max_open_files);

    // Creates a new file cache with given cache.
    //
    // The 'cache_name' is used to disambiguate amongst other file cache
    // instances. Please use this constructor only you want to share _cache
    // with other.
    FileCache(std::string cache_name, std::shared_ptr<Cache> cache);

    // find whether the file has been cached
    // if cached, return true and set the file_handle
    // else return false
    bool lookup(const std::string& file_name, OpenedFileHandle<FileType>* file_handle);

    // insert new FileType* into lru cache
    // and return file_handle
    void insert(const std::string& file_name, FileType* file, OpenedFileHandle<FileType>* file_handle);

    void erase(const std::string& file_name);

private:
    // Name of the cache.
    std::string _cache_name;

    // Underlying cache instance. Caches opened files.
    std::shared_ptr<Cache> _cache;

    FileCache(const FileCache&) = delete;
    const FileCache& operator=(const FileCache&) = delete;
};

} // namespace starrocks
