// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.analysis;

import com.google.common.collect.Maps;
import com.starrocks.catalog.Catalog;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.UserException;
import com.starrocks.mysql.privilege.Auth;
import com.starrocks.mysql.privilege.PrivPredicate;
import com.starrocks.qe.ConnectContext;
import mockit.Expectations;
import mockit.Injectable;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

public class AlterResourceStmtTest {

    private Analyzer analyzer;

    @Before()
    public void setUp() {
        analyzer = AccessTestUtil.fetchAdminAnalyzer(true);
    }

    @Test
    public void testAlterResourceProperties(@Mocked Catalog catalog, @Injectable Auth auth) throws UserException {
        this.expectations(catalog, auth);
        Map<String, String> properties = Maps.newHashMap();
        properties.put("hive.metastore.uris", "thrift://10.10.44.98:9083");
        AlterResourceStmt stmt = new AlterResourceStmt("hive0", properties);
        stmt.analyze(analyzer);
        Assert.assertEquals("hive0", stmt.getResourceName());
        Assert.assertEquals(
                "ALTER RESOURCE 'hive0' SET PROPERTIES(\"hive.metastore.uris\" = \"thrift://10.10.44.98:9083\")",
                stmt.toSql());
    }

    @Test(expected = AnalysisException.class)
    public void testNotAllowModifyResourceType(@Mocked Catalog catalog, @Injectable Auth auth) throws UserException {
        this.expectations(catalog, auth);
        Map<String, String> properties = Maps.newHashMap();
        properties.put("type", "hive");
        AlterResourceStmt stmt = new AlterResourceStmt("hive0", properties);
        stmt.analyze(analyzer);
    }

    @Test(expected = AnalysisException.class)
    public void testResourcePropertiesNotEmpty(@Mocked Catalog catalog, @Injectable Auth auth) throws UserException {
        this.expectations(catalog, auth);
        Map<String, String> properties = Maps.newHashMap();
        AlterResourceStmt stmt = new AlterResourceStmt("hive0", properties);
        stmt.analyze(analyzer);
    }

    private void expectations(Catalog catalog, Auth auth) {
        new Expectations() {
            {
                catalog.getAuth();
                result = auth;
                auth.checkGlobalPriv((ConnectContext) any, PrivPredicate.ADMIN);
                result = true;
            }
        };
    }

}
