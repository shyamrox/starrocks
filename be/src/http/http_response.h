// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/http/http_response.h

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

#include <map>
#include <string>
#include <vector>

#include "http/http_status.h"

namespace starrocks {

class HttpResponse {
public:
    // Only have to send status line
    HttpResponse(const HttpStatus& status);

    // status and content
    HttpResponse(const HttpStatus& status, const std::string* content);

    // status and content
    HttpResponse(const HttpStatus& status, const std::string& type, const std::string* content);

    // Add one header
    void add_header(const std::string& key, const std::string& value);

    const std::map<std::string, std::vector<std::string>>& headers() const { return _custom_headers; }

    const std::string* content() const { return _content; }

    const std::string& content_type() const { return _content_type; }

    HttpStatus status() const { return _status; }

private:
    HttpStatus _status;
    std::string _content_type;
    const std::string* _content;
    std::map<std::string, std::vector<std::string>> _custom_headers;
};

} // namespace starrocks
