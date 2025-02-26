# This file is made available under Elastic License 2.0.
# This file is based on code available under the Apache license here:
#   https://github.com/apache/incubator-doris/blob/master/be/src/gen_cpp/CMakeLists.txt

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

# where to put generated libraries
set(LIBRARY_OUTPUT_PATH "${BUILD_DIR}/src/gen_cpp")

set (GEN_CPP_DIR ${GENSRC_DIR}/gen_cpp)
set(SRC_FILES
    ${GEN_CPP_DIR}/AgentService_constants.cpp
    ${GEN_CPP_DIR}/AgentService_types.cpp
    ${GEN_CPP_DIR}/BackendService_constants.cpp
    ${GEN_CPP_DIR}/BackendService.cpp
    ${GEN_CPP_DIR}/BackendService_types.cpp
    ${GEN_CPP_DIR}/FileBrokerService_types.cpp
    ${GEN_CPP_DIR}/TStarrocksExternalService.cpp
    ${GEN_CPP_DIR}/StarrocksExternalService_types.cpp
    ${GEN_CPP_DIR}/StarrocksExternalService_constants.cpp
    ${GEN_CPP_DIR}/QueryPlanExtra_types.cpp
    ${GEN_CPP_DIR}/QueryPlanExtra_constants.cpp
    ${GEN_CPP_DIR}/TFileBrokerService.cpp
    ${GEN_CPP_DIR}/HeartbeatService_constants.cpp
    ${GEN_CPP_DIR}/HeartbeatService.cpp
    ${GEN_CPP_DIR}/WorkGroup_types.cpp
    ${GEN_CPP_DIR}/WorkGroup_constants.cpp
    ${GEN_CPP_DIR}/HeartbeatService_types.cpp
    ${GEN_CPP_DIR}/InternalService_constants.cpp
    ${GEN_CPP_DIR}/InternalService_types.cpp
    ${GEN_CPP_DIR}/FrontendService.cpp
    ${GEN_CPP_DIR}/FrontendService_constants.cpp
    ${GEN_CPP_DIR}/FrontendService_types.cpp
    ${GEN_CPP_DIR}/Data_constants.cpp
    ${GEN_CPP_DIR}/Data_types.cpp
    ${GEN_CPP_DIR}/DataSinks_constants.cpp
    ${GEN_CPP_DIR}/DataSinks_types.cpp
    ${GEN_CPP_DIR}/Descriptors_constants.cpp
    ${GEN_CPP_DIR}/Descriptors_types.cpp
    ${GEN_CPP_DIR}/Exprs_constants.cpp
    ${GEN_CPP_DIR}/Exprs_types.cpp
    ${GEN_CPP_DIR}/MasterService_constants.cpp
    ${GEN_CPP_DIR}/MasterService_types.cpp
    ${GEN_CPP_DIR}/Metrics_constants.cpp
    ${GEN_CPP_DIR}/Metrics_types.cpp
    ${GEN_CPP_DIR}/Opcodes_constants.cpp
    ${GEN_CPP_DIR}/Opcodes_types.cpp
    ${GEN_CPP_DIR}/PlanNodes_constants.cpp
    ${GEN_CPP_DIR}/PlanNodes_types.cpp
    ${GEN_CPP_DIR}/Partitions_constants.cpp
    ${GEN_CPP_DIR}/Partitions_types.cpp
    ${GEN_CPP_DIR}/Planner_constants.cpp
    ${GEN_CPP_DIR}/Planner_types.cpp
    ${GEN_CPP_DIR}/RuntimeProfile_constants.cpp
    ${GEN_CPP_DIR}/RuntimeProfile_types.cpp
    ${GEN_CPP_DIR}/Status_constants.cpp
    ${GEN_CPP_DIR}/Status_types.cpp
    ${GEN_CPP_DIR}/StatusCode_constants.cpp
    ${GEN_CPP_DIR}/StatusCode_types.cpp
    ${GEN_CPP_DIR}/Types_constants.cpp
    ${GEN_CPP_DIR}/Types_types.cpp
    ${GEN_CPP_DIR}/parquet_types.cpp
    ${GEN_CPP_DIR}/olap_common.pb.cc
    ${GEN_CPP_DIR}/olap_file.pb.cc
    ${GEN_CPP_DIR}/data.pb.cc
    ${GEN_CPP_DIR}/descriptors.pb.cc
    ${GEN_CPP_DIR}/internal_service.pb.cc
    ${GEN_CPP_DIR}/doris_internal_service.pb.cc
    ${GEN_CPP_DIR}/types.pb.cc
    ${GEN_CPP_DIR}/snapshot.pb.cc
    ${GEN_CPP_DIR}/status.pb.cc
    ${GEN_CPP_DIR}/segment.pb.cc
    ${GEN_CPP_DIR}/persistent_index.pb.cc
    #$${GEN_CPP_DIR}/opcode/functions.cc
    #$${GEN_CPP_DIR}/opcode/vector-functions.cc
    #$${GEN_CPP_DIR}/opcode/opcode-registry-init.cc
    ${GEN_CPP_DIR}/opcode/builtin_functions.cpp
    ${GEN_CPP_DIR}/RuntimeFilter_types.cpp
    ${GEN_CPP_DIR}/RuntimeFilter_constants.cpp
    ${GEN_CPP_DIR}/version.cpp
)

# keep everything in one library, the object files reference
# each other
add_library(StarRocksGen STATIC ${SRC_FILES})

# Setting these files as code-generated lets make clean and incremental builds work
# correctly
set_source_files_properties(${SRC_FILES} PROPERTIES GENERATED TRUE)

#add_dependencies(StarRocksGen thrift-cpp)
#add_dependencies(Opcode function)

