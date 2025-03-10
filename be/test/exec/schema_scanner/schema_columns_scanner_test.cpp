// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

#include "exec/vectorized/schema_scanner/schema_columns_scanner.h"

#include <gtest/gtest.h>

#include <string>
#include <tuple>
#include <vector>

namespace starrocks::vectorized {
class SchemaColumnsScannerTest : public ::testing::Test {};

TEST_F(SchemaColumnsScannerTest, test_to_decimal_to_type_string) {
    SchemaColumnsScanner scanner;
    std::vector<std::tuple<TPrimitiveType::type, int32_t, int32_t, bool, std::string, std::string>> test_cases = {
            {TPrimitiveType::DECIMAL32, 9, 2, true, std::string("decimal"), std::string("decimal(9,2)")},
            {TPrimitiveType::DECIMAL32, 9, 2, false, std::string("decimal"), std::string("decimal(-1,-1)")},
            {TPrimitiveType::DECIMAL64, 13, 7, true, std::string("decimal"), std::string("decimal(13,7)")},
            {TPrimitiveType::DECIMAL64, 13, 7, false, std::string("decimal"), std::string("decimal(-1,-1)")},
            {TPrimitiveType::DECIMAL128, 27, 9, true, std::string("decimal"), std::string("decimal(27,9)")},
            {TPrimitiveType::DECIMAL128, 27, 9, false, std::string("decimal"), std::string("decimal(-1,-1)")},
    };
    for (auto& tc : test_cases) {
        auto [ptype, precision, scale, is_set, mysql_type_string, type_string] = tc;
        TColumnDesc columnDesc;
        columnDesc.columnType = ptype;
        if (is_set) {
            columnDesc.__set_columnPrecision(precision);
            columnDesc.__set_columnScale(scale);
        }
        auto actual_mysql_type_string = scanner.to_mysql_data_type_string(columnDesc);
        auto actual_type_string = scanner.type_to_string(columnDesc);
        ASSERT_EQ(actual_mysql_type_string, mysql_type_string);
        ASSERT_EQ(actual_type_string, type_string);
    }
}
} // namespace starrocks::vectorized

int main(int argc, char** argv) {
    ::testing::InitGoogleTest(&argc, argv);
    return RUN_ALL_TESTS();
}
