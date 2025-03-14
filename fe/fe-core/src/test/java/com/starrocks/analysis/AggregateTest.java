// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/test/java/org/apache/doris/analysis/AggregateTest.java

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

package com.starrocks.analysis;

import com.starrocks.common.AnalysisException;
import com.starrocks.common.FeConstants;
import com.starrocks.qe.ConnectContext;
import com.starrocks.utframe.StarRocksAssert;
import com.starrocks.utframe.UtFrameUtils;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.UUID;

public class AggregateTest {
    private static final String TABLE_NAME = "table1";
    private static final String DB_NAME = "db1";
    private static StarRocksAssert starRocksAssert;

    @BeforeClass
    public static void beforeClass() throws Exception {
        FeConstants.runningUnitTest = true;
        UtFrameUtils.createMinStarRocksCluster();
        starRocksAssert = new StarRocksAssert();
        starRocksAssert.withDatabase(DB_NAME).useDatabase(DB_NAME);
        String createTableSQL = "create table " + DB_NAME + "." + TABLE_NAME + " (empid int, name varchar, " +
                "deptno int, salary int, commission int) "
                + "distributed by hash(empid) buckets 3 properties('replication_num' = '1');";
        starRocksAssert.withTable(createTableSQL);
    }

    /**
     * ISSUE-3492
     */
    @Test
    public void testCountDisintctAnalysisException() throws Exception {
        ConnectContext ctx = UtFrameUtils.createDefaultCtx();

        // NOT support mix distinct, one DistinctAggregationFunction has one column, the other DistinctAggregationFunction has some columns.
        do {
            String query = "select count(distinct empid), count(distinct salary), count(distinct empid, salary) from " +
                    DB_NAME + "." + TABLE_NAME;
            try {
                UtFrameUtils.parseAndAnalyzeStmt(query, ctx);
            } catch (AnalysisException e) {
                Assert.assertTrue(e.getMessage().contains(
                        "The query contains multi count distinct or sum distinct, each can't have multi columns."));
                break;
            } catch (Exception e) {
                Assert.fail("must be AnalysisException.");
            }
            Assert.fail("must be AnalysisException.");
        } while (false);

        // support multi DistinctAggregationFunction, but each DistinctAggregationFunction only has one column
        do {
            String query = "select count(distinct empid), count(distinct salary) from " + DB_NAME + "." + TABLE_NAME;
            try {
                UtFrameUtils.parseAndAnalyzeStmt(query, ctx);
            } catch (Exception e) {
                Assert.fail("should be query, no exception");
            }
        } while (false);

        // support 1 DistinctAggregationFunction with some columns
        do {
            String query = "select count(distinct salary, empid) from " + DB_NAME + "." + TABLE_NAME;
            try {
                UtFrameUtils.parseAndAnalyzeStmt(query, ctx);
            } catch (Exception e) {
                Assert.fail("should be query, no exception");
            }
        } while (false);
    }
}