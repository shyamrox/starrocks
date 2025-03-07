// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/runtime/small_file_mgr.cpp

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

#include "runtime/small_file_mgr.h"

#include <boost/algorithm/string/classification.hpp> // boost::is_any_of
#include <boost/algorithm/string/predicate.hpp>      // boost::algorithm::starts_with
#include <cstdint>
#include <cstdio>
#include <sstream>
#include <utility>

#include "common/status.h"
#include "env/env.h"
#include "gen_cpp/HeartbeatService.h"
#include "gutil/strings/split.h"
#include "gutil/strings/substitute.h"
#include "http/http_client.h"
#include "runtime/exec_env.h"
#include "util/file_utils.h"
#include "util/md5.h"
#include "util/starrocks_metrics.h"

namespace starrocks {

SmallFileMgr::SmallFileMgr(ExecEnv* env, std::string local_path) : _exec_env(env), _local_path(std::move(local_path)) {
    REGISTER_GAUGE_STARROCKS_METRIC(small_file_cache_count, [this]() {
        std::lock_guard<std::mutex> l(_lock);
        return _file_cache.size();
    });
}

SmallFileMgr::~SmallFileMgr() = default;

Status SmallFileMgr::init() {
    RETURN_IF_ERROR(_load_local_files());
    return Status::OK();
}

Status SmallFileMgr::_load_local_files() {
    RETURN_IF_ERROR(FileUtils::create_dir(_local_path));

    auto scan_cb = [this](std::string_view file) {
        if (is_dot_or_dotdot(file)) {
            return true;
        }
        auto st = _load_single_file(_local_path, std::string(file));
        if (!st.ok()) {
            LOG(WARNING) << "load small file failed: " << st.get_error_msg();
        }
        return true;
    };

    RETURN_IF_ERROR(Env::Default()->iterate_dir(_local_path, scan_cb));
    return Status::OK();
}

Status SmallFileMgr::_load_single_file(const std::string& path, const std::string& file_name) {
    // file name format should be like:
    // file_id.md5
    std::vector<std::string> parts = strings::Split(file_name, ".");
    if (parts.size() != 2) {
        return Status::InternalError(strings::Substitute("Not a valid file name: $0", file_name));
    }
    int64_t file_id = std::stol(parts[0]);
    std::string md5 = parts[1];

    if (_file_cache.find(file_id) != _file_cache.end()) {
        return Status::InternalError(strings::Substitute("File with same id is already been loaded: $0", file_id));
    }

    std::string file_md5;
    RETURN_IF_ERROR(FileUtils::md5sum(path + "/" + file_name, &file_md5));
    if (file_md5 != md5) {
        return Status::InternalError(strings::Substitute("Invalid md5 of file: $0", file_name));
    }

    CacheEntry entry;
    entry.path = path + "/" + file_name;
    entry.md5 = file_md5;

    _file_cache.emplace(file_id, entry);
    return Status::OK();
}

Status SmallFileMgr::get_file(int64_t file_id, const std::string& md5, std::string* file_path) {
    std::unique_lock<std::mutex> l(_lock);
    // find in cache
    auto it = _file_cache.find(file_id);
    if (it != _file_cache.end()) {
        // find the cached file, check it
        CacheEntry& entry = it->second;
        Status st = _check_file(entry, md5);
        if (!st.ok()) {
            // check file failed, we should remove this cache and download it from FE again
            if (remove(entry.path.c_str()) != 0) {
                std::stringstream ss;
                ss << "failed to remove file: " << file_id << ", err: " << std::strerror(errno);
                return Status::InternalError(ss.str());
            }
            _file_cache.erase(it);
        } else {
            // check ok, return the path
            *file_path = entry.path;
            return Status::OK();
        }
    }

    // file not found in cache. download it from FE
    RETURN_IF_ERROR(_download_file(file_id, md5, file_path));

    return Status::OK();
}

Status SmallFileMgr::_check_file(const CacheEntry& entry, const std::string& md5) {
    if (!FileUtils::check_exist(entry.path)) {
        return Status::InternalError("file not exist");
    }
    if (!boost::iequals(md5, entry.md5)) {
        return Status::InternalError("invalid MD5");
    }
    return Status::OK();
}

Status SmallFileMgr::_download_file(int64_t file_id, const std::string& md5, std::string* file_path) {
    std::stringstream ss;
    ss << _local_path << "/" << file_id << ".tmp";
    std::string tmp_file = ss.str();
    bool should_delete = true;
    auto fp_closer = [&tmp_file, &should_delete](FILE* fp) {
        fclose(fp);
        if (should_delete) remove(tmp_file.c_str());
    };

    std::unique_ptr<FILE, decltype(fp_closer)> fp(fopen(tmp_file.c_str(), "w"), fp_closer);
    if (fp == nullptr) {
        LOG(WARNING) << "fail to open file, file=" << tmp_file;
        return Status::InternalError("fail to open file");
    }

    HttpClient client;

    std::stringstream url_ss;
    TMasterInfo* master_info = _exec_env->master_info();
    url_ss << master_info->network_address.hostname << ":" << master_info->http_port << "/api/get_small_file?"
           << "file_id=" << file_id << "&token=" << master_info->token;

    std::string url = url_ss.str();

    LOG(INFO) << "download file from: " << url;

    RETURN_IF_ERROR(client.init(url));
    Status status;
    Md5Digest digest;
    auto download_cb = [&status, &tmp_file, &fp, &digest](const void* data, size_t length) {
        digest.update(data, length);
        auto res = fwrite(data, length, 1, fp.get());
        if (res != 1) {
            LOG(WARNING) << "fail to write data to file, file=" << tmp_file << ", error=" << ferror(fp.get());
            status = Status::InternalError("fail to write data when download");
            return false;
        }
        return true;
    };
    RETURN_IF_ERROR(client.execute(download_cb));
    RETURN_IF_ERROR(status);
    digest.digest();

    if (!boost::iequals(digest.hex(), md5)) {
        LOG(WARNING) << "file's checksum is not equal, download: " << digest.hex() << ", expected: " << md5
                     << ", file: " << file_id;
        return Status::InternalError("download with invalid md5");
    }

    // close this file
    should_delete = false;
    fp.reset();

    // rename temporary file to library file
    std::stringstream real_ss;
    real_ss << _local_path << "/" << file_id << "." << md5;
    std::string real_file_path = real_ss.str();
    auto ret = rename(tmp_file.c_str(), real_file_path.c_str());
    if (ret != 0) {
        char buf[64];
        LOG(WARNING) << "fail to rename file from=" << tmp_file << ", to=" << real_file_path << ", errno=" << errno
                     << ", errmsg=" << strerror_r(errno, buf, 64);
        remove(tmp_file.c_str());
        remove(real_file_path.c_str());
        return Status::InternalError("fail to rename file");
    }

    // add to file cache
    CacheEntry entry;
    entry.path = real_file_path;
    entry.md5 = md5;
    _file_cache.emplace(file_id, entry);

    *file_path = real_file_path;

    LOG(INFO) << "finished to download file: " << file_path;
    return Status::OK();
}

} // end namespace starrocks
