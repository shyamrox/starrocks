// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.plan;

import com.starrocks.common.FeConstants;
import com.starrocks.planner.SchemaScanNode;
import org.junit.Assert;
import org.junit.Test;

public class ScanTest extends PlanTestBase {
    @Test
    public void testScan() throws Exception {
        String sql = "select * from t0";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains(" OUTPUT EXPRS:1: v1 | 2: v2 | 3: v3\n"
                + "  PARTITION: RANDOM"));
    }

    @Test
    public void testInColumnPredicate() throws Exception {
        String sql = "select v1 from t0 where v1 in (v1 + v2, sin(v2))";
        String thriftPlan = getThriftPlan(sql);
        Assert.assertFalse(thriftPlan.contains("FILTER_IN"));
    }

    @Test
    public void testOlapScanSelectedIndex() throws Exception {
        String sql = "select v1 from t0";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("rollup: t0"));
    }

    @Test
    public void testSingleTabletOutput() throws Exception {
        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        FeConstants.runningUnitTest = true;
        String sql = "select S_COMMENT from supplier;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" OUTPUT EXPRS:7: S_COMMENT\n"
                + "  PARTITION: RANDOM\n"
                + "\n"
                + "  RESULT SINK\n"
                + "\n"
                + "  0:OlapScanNode\n"
                + "     TABLE: supplier"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testSingleTabletOutput2() throws Exception {
        connectContext.getSessionVariable().setNewPlanerAggStage(2);
        FeConstants.runningUnitTest = true;
        String sql = "select SUM(S_NATIONKEY) from supplier;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains(" OUTPUT EXPRS:9: sum\n"
                + "  PARTITION: UNPARTITIONED\n"
                + "\n"
                + "  RESULT SINK\n"
                + "\n"
                + "  3:AGGREGATE (merge finalize)\n"
                + "  |  output: sum(9: sum)\n"
                + "  |  group by: \n"));
        connectContext.getSessionVariable().setNewPlanerAggStage(0);
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testPreAggregation() throws Exception {
        String sql = "select k1 from t0 inner join baseall on v1 = cast(k8 as int) group by k1";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("1:Project\n" +
                "  |  <slot 4> : 4: k1\n" +
                "  |  <slot 15> : CAST(CAST(13: k8 AS INT) AS BIGINT)\n" +
                "  |  \n" +
                "  0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Predicates include the value column\n" +
                "     partitions=0/1"));

        sql = "select 0 from baseall inner join t0 on v1 = k1 group by (v2 + k2),k1";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Group columns isn't bound table baseall"));
    }

    @Test
    public void testInformationSchema() throws Exception {
        String sql = "select column_name from information_schema.columns limit 1;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  RESULT SINK\n" +
                "\n" +
                "  0:SCAN SCHEMA\n" +
                "     limit: 1\n"));
    }

    @Test
    public void testInformationSchema1() throws Exception {
        String sql = "select column_name, UPPER(DATA_TYPE) from information_schema.columns;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  1:Project\n"
                + "  |  <slot 4> : 4: COLUMN_NAME\n"
                + "  |  <slot 25> : upper(8: DATA_TYPE)\n"
                + "  |  \n"
                + "  0:SCAN SCHEMA\n"));
    }

    @Test
    public void testProject() throws Exception {
        String sql = "select v1 from t0";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PLAN FRAGMENT 0\n"
                + " OUTPUT EXPRS:1: v1\n"
                + "  PARTITION: RANDOM\n"
                + "\n"
                + "  RESULT SINK\n"
                + "\n"
                + "  0:OlapScanNode\n"
                + "     TABLE: t0\n"
                + "     PREAGGREGATION: ON\n"
                + "     partitions=0/1"));
    }

    @Test
    public void testEmptySet() throws Exception {
        String queryStr = "select * from test.colocate1 t1, test.colocate2 t2 " +
                "where NOT NULL IS NULL";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  0:EMPTYSET\n"));

        queryStr = "select * from test.colocate1 t1, test.colocate2 t2 where FALSE";
        explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  0:EMPTYSET\n"));
    }

    @Test
    public void testSingleNodeExecPlan() throws Exception {
        String sql = "select v1,v2,v3 from t0";
        connectContext.getSessionVariable().setSingleNodeExecPlan(true);
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("PLAN FRAGMENT 0\n" +
                " OUTPUT EXPRS:1: v1 | 2: v2 | 3: v3\n" +
                "  PARTITION: RANDOM\n" +
                "\n" +
                "  RESULT SINK\n" +
                "\n" +
                "  0:OlapScanNode\n" +
                "     TABLE: t0\n" +
                "     PREAGGREGATION: ON\n" +
                "     partitions=0/1\n" +
                "     rollup: t0\n" +
                "     tabletRatio=0/0\n" +
                "     tabletList=\n" +
                "     cardinality=1\n" +
                "     avgRowSize=3.0\n" +
                "     numNodes=0"));
        connectContext.getSessionVariable().setSingleNodeExecPlan(false);
    }

    @Test
    public void testSchemaScan() throws Exception {
        String sql = "select * from information_schema.columns";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PARTITION: UNPARTITIONED\n" +
                "\n" +
                "  RESULT SINK\n" +
                "\n" +
                "  0:SCAN SCHEMA\n"));
    }

    @Test
    public void testPreAggregationWithJoin() throws Exception {
        FeConstants.runningUnitTest = true;
        // check left agg table with pre-aggregation
        String sql = "select k2, sum(k9) from baseall join join2 on k1 = id group by k2";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: ON"));

        // check right agg table with pre-agg
        sql = "select k2, sum(k9) from join2 join [broadcast] baseall on k1 = id group by k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("1:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: ON"));

        // check two agg tables only one agg table can pre-aggregation
        sql = "select t1.k2, sum(t1.k9) from baseall t1 join baseall t2 on t1.k1 = t2.k1 group by t1.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: ON"));
        Assert.assertTrue(plan.contains("1:OlapScanNode\n" +
                "  |       TABLE: baseall\n" +
                "  |       PREAGGREGATION: OFF. Reason: Has can not pre-aggregation Join"));

        sql = "select t2.k2, sum(t2.k9) from baseall t1 join [broadcast] baseall t2 on t1.k1 = t2.k1 group by t2.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Has can not pre-aggregation Join"));
        Assert.assertTrue(plan.contains("1:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: ON"));

        // check multi tables only one agg table can pre-aggregation
        sql =
                "select t1.k2, sum(t1.k9) from baseall t1 join join2 t2 on t1.k1 = t2.id join baseall t3 on t1.k1 = t3.k1 group by t1.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("6:OlapScanNode\n" +
                "  |       TABLE: baseall\n" +
                "  |       PREAGGREGATION: OFF. Reason: Has can not pre-aggregation Join"));
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: ON"));

        sql =
                "select t3.k2, sum(t3.k9) from baseall t1 join [broadcast] join2 t2 on t1.k1 = t2.id join [broadcast] baseall t3 on t1.k1 = t3.k1 group by t3.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("6:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: ON"));
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Has can not pre-aggregation Join"));

        // check join predicate with non key columns
        sql = "select t1.k2, sum(t1.k9) from baseall t1 join baseall t2 on t1.k9 = t2.k9 group by t1.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Predicates include the value column"));

        sql =
                "select t1.k2, sum(t1.k9) from baseall t1 join baseall t2 on t1.k1 = t2.k1 where t1.k9 + t2.k9 = 1 group by t1.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Predicates include the value column"));

        // check group by two tables columns
        sql = "select t1.k2, t2.k2, sum(t1.k9) from baseall t1 join baseall t2 on t1.k1 = t2.k1 group by t1.k2, t2.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: ON"));

        // check aggregate two table columns
        sql =
                "select t1.k2, t2.k2, sum(t1.k9), sum(t2.k9) from baseall t1 join baseall t2 on t1.k1 = t2.k1 group by t1.k2, t2.k2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("0:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Has can not pre-aggregation Join"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testPreAggregateForCrossJoin() throws Exception {
        FeConstants.runningUnitTest = true;
        String sql = "select join1.id from join1, join2 group by join1.id";
        String plan = getFragmentPlan(sql);

        Assert.assertTrue(plan.contains("  0:OlapScanNode\n" +
                "     TABLE: join1\n" +
                "     PREAGGREGATION: ON"));
        Assert.assertTrue(plan.contains("  1:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON"));

        // AGGREGATE KEY table PREAGGREGATION should be off
        sql = "select join2.id from baseall, join2 group by join2.id";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  0:OlapScanNode\n" +
                "     TABLE: join2\n" +
                "     PREAGGREGATION: ON"));
        Assert.assertTrue(plan.contains("  1:OlapScanNode\n" +
                "     TABLE: baseall\n" +
                "     PREAGGREGATION: OFF. Reason: Has can not pre-aggregation Join"));
        FeConstants.runningUnitTest = false;
    }

    @Test
    public void testSetVar() throws Exception {
        String sql = "select * from db1.tbl3 as t1 JOIN db1.tbl4 as t2 ON t1.c2 = t2.c2";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (BROADCAST)"));

        sql = "select /*+ SET_VAR(broadcast_row_limit=0) */ * from db1.tbl3 as t1 JOIN db1.tbl4 as t2 ON t1.c2 = t2.c2";
        plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("join op: INNER JOIN (PARTITIONED)"));
    }

    @Test
    public void testFilter() throws Exception {
        String sql = "select v1 from t0 where v2 > 1";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("PREDICATES: 2: v2 > 1"));
    }

    @Test
    public void testMergeTwoFilters() throws Exception {
        String sql = "select v1 from t0 where v2 < null group by v1 HAVING NULL IS NULL;";
        String planFragment = getFragmentPlan(sql);
        Assert.assertTrue(planFragment.contains("  1:AGGREGATE (update finalize)\n"
                + "  |  group by: 1: v1\n"
                + "  |  having: TRUE\n"));

        Assert.assertTrue(planFragment.contains("  0:EMPTYSET\n"));
    }

    @Test
    public void testScalarReuseIsNull() throws Exception {
        String sql =
                getFragmentPlan("SELECT (abs(1) IS NULL) = true AND ((abs(1) IS NULL) IS NOT NULL) as count FROM t1;");
        Assert.assertTrue(sql.contains("1:Project\n"
                + "  |  <slot 4> : (6: expr = TRUE) AND (6: expr IS NOT NULL)\n"
                + "  |  common expressions:\n"
                + "  |  <slot 5> : abs(1)\n"
                + "  |  <slot 6> : 5: abs IS NULL"));
    }

    @Test
    public void testProjectFilterRewrite() throws Exception {
        String queryStr = "select 1 as b, MIN(v1) from t0 having (b + 1) != b;";
        String explainString = getFragmentPlan(queryStr);
        Assert.assertTrue(explainString.contains("  1:AGGREGATE (update finalize)\n"
                + "  |  output: min(1: v1)\n"
                + "  |  group by: \n"
                + "  |  having: TRUE\n"));
    }

    @Test
    public void testMergeProject() throws Exception {
        String sql = "select case when v1 then 2 else 2 end from (select v1, case when true then v1 else v1 end as c2"
                + " from t0 limit 1) as x where c2 > 2 limit 2;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("  2:Project\n"
                + "  |  <slot 4> : 2\n"
                + "  |  limit: 2\n"
                + "  |  \n"
                + "  1:SELECT\n"
                + "  |  predicates: 1: v1 > 2\n"
                + "  |  limit: 2\n"
                + "  |  \n"
                + "  0:OlapScanNode\n"
                + "     TABLE: t0"));
    }

    @Test
    public void testProjectReuse() throws Exception {
        String sql = "select nullif(v1, v1) + (0) as a , nullif(v1, v1) + (1 - 1) as b from t0;";
        String plan = getFragmentPlan(sql);
        Assert.assertTrue(plan.contains("<slot 4> : nullif(1: v1, 1: v1) + 0"));
        Assert.assertTrue(plan.contains(" OUTPUT EXPRS:4: expr | 4: expr"));
    }

    @Test
    public void testSchemaScanWithWhere() throws Exception {
        String sql = "select column_name, table_name from information_schema.columns" +
                " where table_schema = 'information_schema' and table_name = 'columns'";
        ExecPlan plan = getExecPlan(sql);
        Assert.assertTrue(((SchemaScanNode) plan.getScanNodes().get(0)).getSchemaDb().equals("information_schema"));
        Assert.assertTrue(((SchemaScanNode) plan.getScanNodes().get(0)).getSchemaTable().equals("columns"));
    }
}
