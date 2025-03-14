// This file is licensed under the Elastic License 2.0. Copyright 2021 StarRocks Limited.
package com.starrocks.sql.plan;

import org.junit.Test;

public class SelectConstTest extends PlanTestBase {
    @Test
    public void testSelectConst() throws Exception {
        assertPlanContains("select 1,2", "  1:Project\n" +
                "  |  <slot 2> : 1\n" +
                "  |  <slot 3> : 2\n" +
                "  |  \n" +
                "  0:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select a from (select 1 as a, 2 as b) t", "  1:Project\n" +
                "  |  <slot 2> : 1\n" +
                "  |  \n" +
                "  0:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select v1,v2 from t0 union all select 1,2", "  4:Project\n" +
                "  |  <slot 7> : CAST(1 AS BIGINT)\n" +
                "  |  <slot 8> : CAST(2 AS BIGINT)\n" +
                "  |  \n" +
                "  3:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select v1,v2 from t0 union select 1,2", "  4:Project\n" +
                "  |  <slot 7> : CAST(1 AS BIGINT)\n" +
                "  |  <slot 8> : CAST(2 AS BIGINT)\n" +
                "  |  \n" +
                "  3:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select v1,v2 from t0 except select 1,2", "EXCEPT", "  4:Project\n" +
                "  |  <slot 7> : CAST(1 AS BIGINT)\n" +
                "  |  <slot 8> : CAST(2 AS BIGINT)\n" +
                "  |  \n" +
                "  3:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select v1,v2 from t0 intersect select 1,2", "INTERSECT", "  4:Project\n" +
                "  |  <slot 7> : CAST(1 AS BIGINT)\n" +
                "  |  <slot 8> : CAST(2 AS BIGINT)\n" +
                "  |  \n" +
                "  3:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select v1,v2,b from t0 inner join (select 1 as a,2 as b) t on v1 = a", "  2:Project\n" +
                "  |  <slot 6> : 2\n" +
                "  |  <slot 7> : CAST(1 AS BIGINT)\n" +
                "  |  \n" +
                "  1:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
    }

    @Test
    public void testValuesNodePredicate() throws Exception {
        assertPlanContains("select database()", "<slot 2> : DATABASE()");
        assertPlanContains("select schema()", "<slot 2> : SCHEMA()");
        assertPlanContains("select user()", "<slot 2> : USER()");
        assertPlanContains("select current_user()", "<slot 2> : CURRENT_USER()");
        assertPlanContains("select connection_id()", "<slot 2> : CONNECTION_ID()");
    }

    @Test
    public void testAggWithConstant() throws Exception {
        assertPlanContains(
                "select case when c1=1 then 1 end from (select '1' c1  union  all select '2') a group by rollup(case  when c1=1 then 1 end, 1 + 1)",
                "<slot 4> : '2'",
                "<slot 2> : '1'",
                "  8:REPEAT_NODE\n" +
                        "  |  repeat: repeat 2 lines [[], [6], [6, 7]]\n" +
                        "  |  \n" +
                        "  7:Project\n" +
                        "  |  <slot 6> : if(5: expr = '1', 1, NULL)\n" +
                        "  |  <slot 7> : 2");
    }

    @Test
    public void testSubquery() throws Exception {
        assertPlanContains("select * from t0 where v3 in (select 2)", "LEFT SEMI JOIN", "<slot 7> : CAST(2 AS BIGINT)");
        assertPlanContains("select * from t0 where v3 not in (select 2)", "NULL AWARE LEFT ANTI JOIN", "<slot 7> : CAST(2 AS BIGINT)");
        assertPlanContains("select * from t0 where exists (select 9)", "  1:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select * from t0 where exists (select 9,10)", "  1:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select * from t0 where not exists (select 9)", "  1:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select * from t0 where v3 = (select 6)", "  5:Project\n" +
                "  |  <slot 7> : CAST(5: expr AS BIGINT)", "equal join conjunct: 3: v3 = 7: cast");
        assertPlanContains("select case when (select max(v4) from t1) > 1 then 2 else 3 end", "  5:Project\n" +
                "  |  <slot 7> : if(5: max > 1, 2, 3)\n" +
                "  |  \n" +
                "  4:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates is NULL.\n" +
                "  |  \n" +
                "  |----3:EXCHANGE\n" +
                "  |    \n" +
                "  0:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
        assertPlanContains("select 1, 2, case when (select max(v4) from t1) > 1 then 4 else 5 end", "  5:Project\n" +
                "  |  <slot 2> : 1\n" +
                "  |  <slot 3> : 2\n" +
                "  |  <slot 9> : if(7: max > 1, 4, 5)\n" +
                "  |  \n" +
                "  4:CROSS JOIN\n" +
                "  |  cross join:\n" +
                "  |  predicates is NULL.\n" +
                "  |  \n" +
                "  |----3:EXCHANGE\n" +
                "  |    \n" +
                "  0:UNION\n" +
                "     constant exprs: \n" +
                "         NULL");
    }

    @Test
    public void testDoubleCastWithoutScientificNotation() throws Exception {
        String sql = "SELECT * FROM t0 WHERE CAST(CAST(CASE WHEN TRUE THEN -1229625855 WHEN false THEN 1 ELSE 2 / 3 END AS STRING ) AS BOOLEAN );";
        assertPlanContains(sql, "PREDICATES: CAST('-1229625855' AS BOOLEAN)");
    }
}
