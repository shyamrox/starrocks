// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.analysis;

import com.starrocks.catalog.Catalog;
import com.starrocks.common.AnalysisException;
import com.starrocks.mysql.privilege.Auth;
import com.starrocks.mysql.privilege.MockedAuth;
import com.starrocks.mysql.privilege.PrivPredicate;
import com.starrocks.mysql.privilege.UserPrivTable;
import com.starrocks.qe.ConnectContext;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class ShowGrantsStmtTest {

    @Mocked
    private Analyzer analyzer;
    @Mocked
    private Catalog catalog;
    @Mocked
    private Auth auth;
    @Mocked
    private UserPrivTable userPrivTable;
    @Mocked
    private ConnectContext ctx;

    @Before
    public void setUp() {
        MockedAuth.mockedConnectContext(ctx, "root", "192.168.1.1");
        new Expectations(analyzer) {
            {
                analyzer.getClusterName();
                minTimes = 0;
                result = "test_cluster";
            }
        };
        new Expectations() {
            {
                Catalog.getCurrentCatalog();
                minTimes = 0;
                result = catalog;
            }
        };
        new Expectations(catalog) {
            {
                catalog.getAuth();
                minTimes = 0;
                result = auth;
            }
        };
        new Expectations(auth) {
            {
                auth.getUserPrivTable();
                minTimes = 0;
                result = userPrivTable;

                auth.checkGlobalPriv(ctx, PrivPredicate.GRANT);
                minTimes = 0;
                result = true;
            }
        };
    }

    @Test
    public void testNormal() throws Exception {
        // suppose current user exists
        new Expectations(userPrivTable) {
            {
                userPrivTable.doesUserExist((UserIdentity)any);
                minTimes = 0;
                result = true;
            }
        };
        ShowGrantsStmt stmt = new ShowGrantsStmt(new UserIdentity("test_user", "localhost"), false);
        stmt.analyze(analyzer);
    }

    @Test(expected = AnalysisException.class)
    public void testUserNotExist() throws Exception {
        // suppose current user doesn't exist, check for exception
        new Expectations(userPrivTable) {
            {
                userPrivTable.doesUserExist((UserIdentity)any);
                minTimes = 0;
                result = false;
            }
        };
        ShowGrantsStmt stmt = new ShowGrantsStmt(new UserIdentity("fake_user", "localhost"), false);
        stmt.analyze(analyzer);
        Assert.fail("No exception throws.");
    }
}
