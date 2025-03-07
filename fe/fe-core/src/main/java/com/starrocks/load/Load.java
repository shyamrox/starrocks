// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/load/Load.java

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

package com.starrocks.load;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.alter.SchemaChangeHandler;
import com.starrocks.analysis.Analyzer;
import com.starrocks.analysis.BinaryPredicate;
import com.starrocks.analysis.CastExpr;
import com.starrocks.analysis.DataDescription;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.ExprSubstitutionMap;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.FunctionName;
import com.starrocks.analysis.FunctionParams;
import com.starrocks.analysis.ImportColumnDesc;
import com.starrocks.analysis.IntLiteral;
import com.starrocks.analysis.IsNullPredicate;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.NullLiteral;
import com.starrocks.analysis.SlotDescriptor;
import com.starrocks.analysis.SlotRef;
import com.starrocks.analysis.StringLiteral;
import com.starrocks.analysis.TupleDescriptor;
import com.starrocks.backup.BlobStorage;
import com.starrocks.backup.Status;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.DdlException;
import com.starrocks.common.Pair;
import com.starrocks.common.UserException;
import com.starrocks.load.loadv2.JobState;
import com.starrocks.persist.ReplicaPersistInfo;
import com.starrocks.thrift.TBrokerScanRangeParams;
import com.starrocks.thrift.TOpType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

public class Load {
    private static final Logger LOG = LogManager.getLogger(Load.class);
    public static final String VERSION = "v1";

    public static final String LOAD_OP_COLUMN = "__op";
    // load job meta
    private LoadErrorHub.Param loadErrorHubParam = new LoadErrorHub.Param();

    public Load() {
    }

    /**
     * When doing schema change, there may have some 'shadow' columns, with prefix '__starrocks_shadow_' in
     * their names. These columns are invisible to user, but we need to generate data for these columns.
     * So we add column mappings for these column.
     * eg1:
     * base schema is (A, B, C), and B is under schema change, so there will be a shadow column: '__starrocks_shadow_B'
     * So the final column mapping should looks like: (A, B, C, __starrocks_shadow_B = substitute(B));
     */
    public static List<ImportColumnDesc> getSchemaChangeShadowColumnDesc(Table tbl, Map<String, Expr> columnExprMap) {
        List<ImportColumnDesc> shadowColumnDescs = Lists.newArrayList();
        for (Column column : tbl.getFullSchema()) {
            if (!column.isNameWithPrefix(SchemaChangeHandler.SHADOW_NAME_PRFIX) && 
                    !column.isNameWithPrefix(SchemaChangeHandler.SHADOW_NAME_PRFIX_V1)) {
                continue;
            }

            String originCol = Column.removeNamePrefix(column.getName());
            if (columnExprMap.containsKey(originCol)) {
                Expr mappingExpr = columnExprMap.get(originCol);
                if (mappingExpr != null) {
                    /*
                     * eg:
                     * (A, C) SET (B = func(xx))
                     * ->
                     * (A, C) SET (B = func(xx), __starrocks_shadow_B = func(xx))
                     */
                    ImportColumnDesc importColumnDesc = new ImportColumnDesc(column.getName(), mappingExpr);
                    shadowColumnDescs.add(importColumnDesc);
                } else {
                    /*
                     * eg:
                     * (A, B, C)
                     * ->
                     * (A, B, C) SET (__starrocks_shadow_B = B)
                     */
                    ImportColumnDesc importColumnDesc = new ImportColumnDesc(column.getName(),
                            new SlotRef(null, originCol));
                    shadowColumnDescs.add(importColumnDesc);
                }
            } else {
                /*
                 * There is a case that if user does not specify the related origin column, eg:
                 * COLUMNS (A, C), and B is not specified, but B is been modified.
                 * So B is a shadow column '__starrocks_shadow_B'.
                 * We can not just add a mapping function "__starrocks_shadow_B = substitute(B)",
                 * because StarRocks can not find column B.
                 * In this case, __starrocks_shadow_B can use its default value, so no need to add it to column mapping
                 */
                // do nothing
            }
        }
        return shadowColumnDescs;
    }

    public static boolean tableSupportOpColumn(Table tbl) {
        return tbl instanceof OlapTable && ((OlapTable) tbl).getKeysType() == KeysType.PRIMARY_KEYS;
    }

    /*
     * used for spark load job
     * not init slot desc and analyze exprs
     */
    public static void initColumns(Table tbl, List<ImportColumnDesc> columnExprs,
                                   Map<String, Pair<String, List<String>>> columnToHadoopFunction)
            throws UserException {
        initColumns(tbl, columnExprs, columnToHadoopFunction, null, null,
                null, null, null, false, false, Lists.newArrayList());
    }

    /*
     * This function should be used for stream load.
     * And it must be called in same db lock when planing.
     */
    public static void initColumns(Table tbl, List<ImportColumnDesc> columnExprs,
                                   Map<String, Pair<String, List<String>>> columnToHadoopFunction,
                                   Map<String, Expr> exprsByName, Analyzer analyzer, TupleDescriptor srcTupleDesc,
                                   Map<String, SlotDescriptor> slotDescByName, TBrokerScanRangeParams params)
            throws UserException {
        initColumns(tbl, columnExprs, columnToHadoopFunction, exprsByName, analyzer,
                srcTupleDesc, slotDescByName, params, true, false, Lists.newArrayList());
    }

    /*
     * This function should be used for broker load v2.
     * And it must be called in same db lock when planing.
     * This function will do followings:
     * 1. fill the column exprs if user does not specify any column or column mapping.
     * 2. For not specified columns, check if they have default value.
     * 3. Add any shadow columns if have.
     * 4. validate hadoop functions
     * 5. init slot descs and expr map for load plan
     */
    public static void initColumns(Table tbl, List<ImportColumnDesc> columnExprs,
            Map<String, Pair<String, List<String>>> columnToHadoopFunction,
            Map<String, Expr> exprsByName, Analyzer analyzer, TupleDescriptor srcTupleDesc,
            Map<String, SlotDescriptor> slotDescByName, TBrokerScanRangeParams params,
            boolean needInitSlotAndAnalyzeExprs, boolean useVectorizedLoad,
            List<String> columnsFromPath) throws UserException {
        initColumns(tbl, columnExprs, columnToHadoopFunction, exprsByName, analyzer,
                srcTupleDesc, slotDescByName, params, needInitSlotAndAnalyzeExprs, useVectorizedLoad,
                columnsFromPath, false, false);
    }

    public static void initColumns(Table tbl, List<ImportColumnDesc> columnExprs,
                                   Map<String, Pair<String, List<String>>> columnToHadoopFunction,
                                   Map<String, Expr> exprsByName, Analyzer analyzer, TupleDescriptor srcTupleDesc,
                                   Map<String, SlotDescriptor> slotDescByName, TBrokerScanRangeParams params,
                                   boolean needInitSlotAndAnalyzeExprs, boolean useVectorizedLoad,
                                   List<String> columnsFromPath, boolean isStreamLoadJson,
                                   boolean partialUpdate) throws UserException {
        // check mapping column exist in schema
        // !! all column mappings are in columnExprs !!
        Set<String> importColumnNames = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        Set<String> mappingColumnNames = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        for (ImportColumnDesc importColumnDesc : columnExprs) {
            String columnName = importColumnDesc.getColumnName();

            if (importColumnDesc.isColumn()) {
                if (importColumnNames.contains(columnName)) {
                    throw new DdlException("Duplicate column: " + columnName);
                }
                importColumnNames.add(columnName);
                continue;
            }

            if (mappingColumnNames.contains(columnName)) {
                throw new DdlException("Duplicate column mapping: " + columnName);
            }

            if (tbl.getColumn(columnName) == null && !columnName.equals(Load.LOAD_OP_COLUMN)) {
                throw new DdlException("Mapping column is not in table. column: " + columnName);
            }
            mappingColumnNames.add(columnName);
        }

        // We make a copy of the columnExprs so that our subsequent changes
        // to the columnExprs will not affect the original columnExprs.
        List<ImportColumnDesc> copiedColumnExprs = Lists.newArrayList(columnExprs);

        // If user does not specify the file field names, generate it by using base schema of table.
        // So that the following process can be unified
        boolean specifyFileFieldNames = copiedColumnExprs.stream().anyMatch(p -> p.isColumn());
        if (!specifyFileFieldNames) {
            List<Column> columns = tbl.getBaseSchema();
            for (Column column : columns) {
                ImportColumnDesc columnDesc = new ImportColumnDesc(column.getName());
                copiedColumnExprs.add(columnDesc);
            }
        }

        if (tableSupportOpColumn(tbl)) {
            boolean found = false;
            for (ImportColumnDesc c : copiedColumnExprs) {
                if (c.getColumnName().equals(Load.LOAD_OP_COLUMN)) {
                    found = true;
                    // change string literal to tinyint enum
                    Expr expr = c.getExpr();
                    if (expr != null && expr instanceof LiteralExpr) {
                        if (expr instanceof StringLiteral) {
                            // convert string "upsert" & "delete" to tinyint enum value
                            StringLiteral opstr = (StringLiteral) c.getExpr();
                            String ops = opstr.getStringValue().toLowerCase();
                            int op = 0;
                            if (ops.equals("upsert")) {
                                op = TOpType.UPSERT.getValue();
                            } else if (ops.equals("delete")) {
                                op = TOpType.DELETE.getValue();
                            } else {
                                throw new AnalysisException("load op type string not supported: " + ops);
                            }
                            c.reset(Load.LOAD_OP_COLUMN, new IntLiteral(op));
                        } else if (!(expr instanceof IntLiteral)) {
                            throw new AnalysisException("const load op type column should only be upsert(0)/delete(1)");
                        }
                    }
                    LOG.info("load __op column expr: " + (expr != null ? expr.toSql() : "null"));
                    break;
                }
            }
            if (!found) {
                // stream load json will automatically check __op field in json object iff:
                // 1. streamload using json
                // 2. __op is not specified
                copiedColumnExprs.add(new ImportColumnDesc(Load.LOAD_OP_COLUMN,
                        isStreamLoadJson ? null : new IntLiteral(TOpType.UPSERT.getValue())));
            }
        }

        // generate a map for checking easily
        // columnExprMap should be case insensitive map, because column list in load sql is case insensitive.
        // for such case:
        //     columns(k1)
        //     set (K1 = k1 * 2)
        // the following 2 loops will make sure (K1 = k1 * 2) will replace (k1)
        Map<String, Expr> columnExprMap = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
        // first put all the columns in column list to columnExprMap
        for (ImportColumnDesc importColumnDesc : copiedColumnExprs) {
            if (importColumnDesc.isColumn()) {
                columnExprMap.put(importColumnDesc.getColumnName(), importColumnDesc.getExpr());
            }
        }
        // second put all the columns in set list to columnExprMap,
        // so that we can make sure the set expr will exist in columnExprMap, if the column appears in both set list and column list
        for (ImportColumnDesc importColumnDesc : copiedColumnExprs) {
            if (!importColumnDesc.isColumn()) {
                columnExprMap.put(importColumnDesc.getColumnName(), importColumnDesc.getExpr());
            }
        }

        // partial update do not check default value
        if (!partialUpdate) {
            // check default value
            for (Column column : tbl.getBaseSchema()) {
                String columnName = column.getName();
                if (columnExprMap.containsKey(columnName)) {
                    continue;
                }
                Column.DefaultValueType defaultValueType = column.getDefaultValueType();
                if (defaultValueType == Column.DefaultValueType.NULL && !column.isAllowNull()) {
                    throw new DdlException("Column has no default value. column: " + columnName);
                }
            }
        }

        // get shadow column desc when table schema change
        copiedColumnExprs.addAll(getSchemaChangeShadowColumnDesc(tbl, columnExprMap));

        // validate hadoop functions
        if (columnToHadoopFunction != null && !columnToHadoopFunction.isEmpty()) {
            Map<String, String> columnNameMap = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
            for (ImportColumnDesc importColumnDesc : copiedColumnExprs) {
                if (importColumnDesc.isColumn()) {
                    columnNameMap.put(importColumnDesc.getColumnName(), importColumnDesc.getColumnName());
                }
            }
            for (Entry<String, Pair<String, List<String>>> entry : columnToHadoopFunction.entrySet()) {
                String mappingColumnName = entry.getKey();
                Column mappingColumn = tbl.getColumn(mappingColumnName);
                Pair<String, List<String>> function = entry.getValue();
                try {
                    DataDescription.validateMappingFunction(function.first, function.second, columnNameMap,
                            mappingColumn, false);
                } catch (AnalysisException e) {
                    throw new DdlException(e.getMessage());
                }
            }
        }

        if (!needInitSlotAndAnalyzeExprs) {
            return;
        }

        // The following scenarios need to use varchar
        // 1. Column from path.
        //    The data from path is varchar type.
        // 2. Column exists in both schema and expr args.
        //    Can not determine whether to use the type in the schema or the type inferred from expr
        //    example: c1 is INT in schema, mapping expr is c1 = year(c1), c1's type in source file is DATETIME.
        //             c1 is VARCHAR, c0 is INT, mapping expr is c0 = c1 + 1, c1's type in source file is VARCHAR.
        // varcharColumns is columns that should be varchar type in those scenarios above when plan.
        Set<String> varcharColumns = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        Set<String> pathColumns = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        if (columnsFromPath != null) {
            pathColumns.addAll(columnsFromPath);
            varcharColumns.addAll(columnsFromPath);
        }
        Set<String> exprArgsColumns = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        for (ImportColumnDesc importColumnDesc : copiedColumnExprs) {
            if (importColumnDesc.isColumn()) {
                continue;
            }
            List<SlotRef> slots = Lists.newArrayList();
            importColumnDesc.getExpr().collect(SlotRef.class, slots);
            for (SlotRef slot : slots) {
                String slotColumnName = slot.getColumnName();
                exprArgsColumns.add(slotColumnName);
            }
        }

        // init slot desc add expr map, also transform hadoop functions
        // if use vectorized load, set src slot desc type with starrocks schema if source column is in starrocks table
        // else set varchar firstly, and try to set specific type later after expr analyzed.
        for (ImportColumnDesc importColumnDesc : copiedColumnExprs) {
            // make column name case match with real column name
            String columnName = importColumnDesc.getColumnName();
            Column tblColumn = tbl.getColumn(columnName);
            String realColName = (tblColumn == null ? columnName : tblColumn.getName());
            if (importColumnDesc.getExpr() != null) {
                Expr expr = transformHadoopFunctionExpr(tbl, realColName, importColumnDesc.getExpr());
                exprsByName.put(realColName, expr);
            } else {
                SlotDescriptor slotDesc = analyzer.getDescTbl().addSlotDescriptor(srcTupleDesc);
                if (useVectorizedLoad) {
                    if (tblColumn != null) {
                        if (pathColumns.contains(columnName) || exprArgsColumns.contains(columnName)) {
                            // columns from path or columns in expr args should be parsed as varchar type
                            slotDesc.setType(Type.VARCHAR);
                            slotDesc.setColumn(new Column(columnName, Type.VARCHAR));
                            varcharColumns.add(columnName);
                        } else {
                            // in vectorized load:
                            // columns from files like orc files should be parsed as the type in table schema
                            slotDesc.setType(tblColumn.getType());
                            slotDesc.setColumn(new Column(columnName, tblColumn.getType()));
                        }
                        slotDesc.setIsNullable(tblColumn.isAllowNull());
                        slotDesc.setIsMaterialized(true);
                    } else if (columnName.equals(Load.LOAD_OP_COLUMN)) {
                        // to support auto mapping, the new grammer for compatible with existing load tool.
                        // columns:pk,col1,col2,__op equals to columns:srccol0,srccol1,srccol2,srccol3,pk=srccol0,col1=srccol1,col2=srccol2,__op=srccol3
                        // columns:pk,__op,col1,col2 equals to columns:srccol0,srccol1,srccol2,srccol3,pk=srccol0,__op=srccol1,col1=srccol2,col2=srccol3
                        // columns:__op,pk,col1,col2 equals to columns:srccol0,srccol1,srccol2,srccol3,__op=srccol0,pk=srccol1,col1=srccol2,col2=srccol3
                        slotDesc.setType(Type.TINYINT);
                        slotDesc.setColumn(new Column(columnName, Type.TINYINT));
                        slotDesc.setIsNullable(false);
                        slotDesc.setIsMaterialized(true);
                    } else {
                        slotDesc.setType(Type.VARCHAR);
                        slotDesc.setColumn(new Column(columnName, Type.VARCHAR));
                        slotDesc.setIsNullable(true);
                        // Will check mapping expr has this slot or not later
                        slotDesc.setIsMaterialized(false);
                    }
                } else {
                    slotDesc.setType(Type.VARCHAR);
                    slotDesc.setColumn(new Column(columnName, Type.VARCHAR));
                    // ISSUE A: src slot should be nullable even if the column is not nullable.
                    // because src slot is what we read from file, not represent to real column value.
                    // If column is not nullable, error will be thrown when filling the dest slot,
                    // which is not nullable.
                    slotDesc.setIsNullable(true);
                    slotDesc.setIsMaterialized(true);
                }
                params.addToSrc_slot_ids(slotDesc.getId().asInt());
                slotDescByName.put(columnName, slotDesc);
            }
        }
        /*
         * The extension column of the materialized view is added to the expression evaluation of load
         * To avoid nested expressions. eg : column(a, tmp_c, c = expr(tmp_c)) ,
         * __starrocks_materialized_view_bitmap_union_c need be analyzed after exprsByName
         * So the columns of the materialized view are stored separately here
         */
        Map<String, Expr> mvDefineExpr = Maps.newHashMap();
        for (Column column : tbl.getFullSchema()) {
            if (column.getDefineExpr() != null) {
                mvDefineExpr.put(column.getName(), column.getDefineExpr());
            }
        }

        LOG.debug("slotDescByName: {}, exprsByName: {}, mvDefineExpr: {}", slotDescByName, exprsByName, mvDefineExpr);

        // analyze all exprs
        // If use vectorized load, try analyze source column type that is not in starrocks table
        // 1. analyze all exprs using varchar type for source columns that are not in starrocks table.
        // 2. replace src slot desc with cast return type after expr analyzed.
        //    If more than one, choose last one except VARCHAR.
        // 3. reanalyze all exprs using new type, and be will transform data from orig data type directly.
        //
        // Column type rules:
        // 1. Column only exists in the schema, directly use the type in the schema.
        // 2. Column only exists in mapping expr args, use the type inferred from expr. If more than one, choose last one except VARCHAR.
        // 3. Column exists in both schema and expr args, use VARCHAR.
        // 4. Column from path, use VARCHAR.
        //
        // Example:
        // CREATE TABLE `expr_test` (
        //    `k1`   DATE,
        //    `k4`   INT,
        //    `k5`   INT,
        //    `k20`  CHAR(20),
        //    `k21`  INT,
        //    `k22`  VARCHAR(20)
        // );
        //
        // LOAD LABEL label0 (
        //    DATA INFILE("hdfs://host:port/path/k5=2021-03-01/file0")
        //    INTO TABLE expr_test
        //    FORMAT AS "orc"
        //    (k1,k2,k3,k4)
        //    COLUMNS FROM PATH AS (k5)
        //    SET (k21=year(k2), k20=k2, k22=substr(k3,1,3), k4=year(k4))
        // ) WITH BROKER broker0;
        //
        // k1: k1 is in expr_test table, uses DATE type. (Rule 1)
        // k2: after exprs are analyzed, k21=year(CAST k2 as DATETIME), k20=SlotRef(k2 as VARCHAR),
        //     k2 maybe DATETIME or VARCHAR type.
        //     If more than one, choose last one except VARCHAR, so replace k2 VARCHAR type with DATETIME. (Rule 2)
        // k3: after exprs are analyzed, k22=substr(SlotRef(k3 as VARCHAR),1,3)), so k3 uses VARCHAR type same as before. (Rule 2)
        // k4: exists in both schema and expr args, so type is VARCHAR. (Rule 3)
        // k5: The data from path is varchar type. so type is VARCHAR. (Rule 4)
        //
        // If use old load, analyze all exprs using varchar type.
        if (useVectorizedLoad) {
            // 1. analyze all exprs using varchar type
            Map<String, Expr> copiedExprsByName = Maps.newHashMap(exprsByName);
            Map<String, Expr> copiedMvDefineExpr = Maps.newHashMap(mvDefineExpr);
            analyzeMappingExprs(tbl, analyzer, copiedExprsByName, copiedMvDefineExpr,
                    slotDescByName, useVectorizedLoad);

            // 2. replace src slot desc with cast return type after expr analyzed
            replaceSrcSlotDescType(copiedExprsByName, srcTupleDesc, varcharColumns);
        }

        // 3. reanalyze all exprs using new type in vectorized load or using varchar in old load
        analyzeMappingExprs(tbl, analyzer, exprsByName, mvDefineExpr, slotDescByName, useVectorizedLoad);
        LOG.debug("after init column, exprMap: {}", exprsByName);
    }

    public static List<Column> getPartialUpateColumns(Table tbl, List<ImportColumnDesc> columnExprs) throws UserException {
        Set<String> specified = columnExprs.stream().map(desc -> desc.getColumnName()).collect(Collectors.toSet());
        List<Column> ret = new ArrayList<>();
        for (Column col : tbl.getBaseSchema()) {
            if (specified.contains(col.getName())) {
                ret.add(col);
            } else if (col.isKey()) {
                throw new DdlException("key column " + col.getName() + " not in partial update columns");
            }
        }
        return ret;
    }

    /**
     * @param excludedColumns: columns that the type should not be inferred from expr.
     *                         Such as, the type of column from path is VARCHAR, whether it is in expr args or not,
     *                         and column exists in both schema and expr args.
     */
    private static void replaceSrcSlotDescType(Map<String, Expr> exprsByName, TupleDescriptor srcTupleDesc,
                                               Set<String> excludedColumns) throws UserException {
        for (Map.Entry<String, Expr> entry : exprsByName.entrySet()) {
            List<CastExpr> casts = Lists.newArrayList();
            entry.getValue().collect(Expr.IS_VARCHAR_SLOT_REF_IMPLICIT_CAST, casts);
            if (casts.isEmpty()) {
                continue;
            }

            for (CastExpr cast : casts) {
                Expr child = cast.getChild(0);
                Type type = cast.getType();
                if (type.isVarchar()) {
                    continue;
                }

                SlotRef slotRef = (SlotRef) child;
                String columnName = slotRef.getColumn().getName();
                if (excludedColumns.contains(columnName)) {
                    continue;
                }

                // set src slot desc with cast return type
                int slotId = slotRef.getSlotId().asInt();
                SlotDescriptor srcSlotDesc = srcTupleDesc.getSlot(slotId);
                if (srcSlotDesc == null) {
                    throw new UserException("Unknown source slot descriptor. id: " + slotId);
                }
                srcSlotDesc.setType(type);
                srcSlotDesc.setColumn(new Column(columnName, type));
            }
        }
    }

    private static void analyzeMappingExprs(Table tbl, Analyzer analyzer, Map<String, Expr> exprsByName,
                                            Map<String, Expr> mvDefineExpr, Map<String, SlotDescriptor> slotDescByName,
                                            boolean useVectorizedLoad) throws UserException {
        for (Map.Entry<String, Expr> entry : exprsByName.entrySet()) {
            ExprSubstitutionMap smap = new ExprSubstitutionMap();
            List<SlotRef> slots = Lists.newArrayList();
            entry.getValue().collect(SlotRef.class, slots);
            for (SlotRef slot : slots) {
                SlotDescriptor slotDesc = slotDescByName.get(slot.getColumnName());
                if (slotDesc == null) {
                    throw new UserException("unknown reference column, column=" + entry.getKey()
                            + ", reference=" + slot.getColumnName());
                }
                // set slot desc IsMaterialized true
                if (useVectorizedLoad) {
                    slotDesc.setIsMaterialized(true);
                }
                smap.getLhs().add(slot);
                smap.getRhs().add(new SlotRef(slotDesc));
            }
            Expr expr = entry.getValue().clone(smap);
            expr.analyze(analyzer);

            // check if contain aggregation
            List<FunctionCallExpr> funcs = Lists.newArrayList();
            expr.collect(FunctionCallExpr.class, funcs);
            for (FunctionCallExpr fn : funcs) {
                if (fn.isAggregateFunction()) {
                    throw new UserException("Don't support aggregation function in load expression");
                }
            }
            exprsByName.put(entry.getKey(), expr);
        }

        for (Map.Entry<String, Expr> entry : mvDefineExpr.entrySet()) {
            ExprSubstitutionMap smap = new ExprSubstitutionMap();
            List<SlotRef> slots = Lists.newArrayList();
            entry.getValue().collect(SlotRef.class, slots);
            for (SlotRef slot : slots) {
                SlotDescriptor slotDesc = slotDescByName.get(slot.getColumnName());
                if (slotDesc != null) {
                    // set slot desc IsMaterialized true
                    if (useVectorizedLoad) {
                        slotDesc.setIsMaterialized(true);
                    }
                    smap.getLhs().add(slot);
                    smap.getRhs().add(new CastExpr(tbl.getColumn(slot.getColumnName()).getType(),
                            new SlotRef(slotDesc)));
                } else if (exprsByName.get(slot.getColumnName()) != null) {
                    smap.getLhs().add(slot);
                    smap.getRhs().add(new CastExpr(tbl.getColumn(slot.getColumnName()).getType(),
                            exprsByName.get(slot.getColumnName())));
                } else {
                    throw new UserException("unknown reference column, column=" + entry.getKey()
                            + ", reference=" + slot.getColumnName());
                }
            }
            Expr expr = entry.getValue().clone(smap);
            expr.analyze(analyzer);

            exprsByName.put(entry.getKey(), expr);
        }
    }

    /**
     * This method is used to transform hadoop function.
     * The hadoop function includes: replace_value, strftime, time_format, alignment_timestamp, default_value, now.
     * It rewrites those function with real function name and param.
     * For the other function, the expr only go through this function and the origin expr is returned.
     *
     * @param columnName
     * @param originExpr
     * @return
     * @throws UserException
     */
    private static Expr transformHadoopFunctionExpr(Table tbl, String columnName, Expr originExpr)
            throws UserException {
        Column column = tbl.getColumn(columnName);
        if (column == null) {
            // the unknown column will be checked later.
            return originExpr;
        }

        // To compatible with older load version
        if (originExpr instanceof FunctionCallExpr) {
            FunctionCallExpr funcExpr = (FunctionCallExpr) originExpr;
            String funcName = funcExpr.getFnName().getFunction();

            if (funcName.equalsIgnoreCase("replace_value")) {
                List<Expr> exprs = Lists.newArrayList();
                SlotRef slotRef = new SlotRef(null, columnName);
                // We will convert this to IF(`col` != child0, `col`, child1),
                // because we need the if return type equal to `col`, we use NE

                /*
                 * We will convert this based on different cases:
                 * case 1: k1 = replace_value(null, anyval);
                 *     to: k1 = if (k1 is not null, k1, anyval);
                 *
                 * case 2: k1 = replace_value(anyval1, anyval2);
                 *     to: k1 = if (k1 is not null, if(k1 != anyval1, k1, anyval2), null);
                 */
                if (funcExpr.getChild(0) instanceof NullLiteral) {
                    // case 1
                    exprs.add(new IsNullPredicate(slotRef, true));
                    exprs.add(slotRef);
                    if (funcExpr.hasChild(1)) {
                        exprs.add(funcExpr.getChild(1));
                    } else {
                        Column.DefaultValueType defaultValueType = column.getDefaultValueType();
                        if (defaultValueType == Column.DefaultValueType.CONST) {
                            exprs.add(new StringLiteral(column.calculatedDefaultValue()));
                        } else if (defaultValueType == Column.DefaultValueType.VARY) {
                            throw new UserException("Column(" + columnName + ") has unsupported default value:"
                                    + column.getDefaultExpr().getExpr());
                        } else if (defaultValueType == Column.DefaultValueType.NULL) {
                            if (column.isAllowNull()) {
                                exprs.add(NullLiteral.create(Type.VARCHAR));
                            } else {
                                throw new UserException("Column(" + columnName + ") has no default value.");
                            }
                        }
                    }
                } else {
                    // case 2
                    exprs.add(new IsNullPredicate(slotRef, true));
                    List<Expr> innerIfExprs = Lists.newArrayList();
                    innerIfExprs.add(new BinaryPredicate(BinaryPredicate.Operator.NE, slotRef, funcExpr.getChild(0)));
                    innerIfExprs.add(slotRef);
                    if (funcExpr.hasChild(1)) {
                        innerIfExprs.add(funcExpr.getChild(1));
                    } else {
                        Column.DefaultValueType defaultValueType = column.getDefaultValueType();
                        if (defaultValueType == Column.DefaultValueType.CONST) {
                            innerIfExprs.add(new StringLiteral(column.calculatedDefaultValue()));
                        } else if (defaultValueType == Column.DefaultValueType.VARY) {
                            throw new UserException("Column(" + columnName + ") has unsupported default value:"
                                    + column.getDefaultExpr().getExpr());
                        } else if (defaultValueType == Column.DefaultValueType.NULL) {
                            if (column.isAllowNull()) {
                                innerIfExprs.add(NullLiteral.create(Type.VARCHAR));
                            } else {
                                throw new UserException("Column(" + columnName + ") has no default value.");
                            }
                        }
                    }
                    FunctionCallExpr innerIfFn = new FunctionCallExpr("if", innerIfExprs);
                    exprs.add(innerIfFn);
                    exprs.add(NullLiteral.create(Type.VARCHAR));
                }

                LOG.debug("replace_value expr: {}", exprs);
                FunctionCallExpr newFn = new FunctionCallExpr("if", exprs);
                return newFn;
            } else if (funcName.equalsIgnoreCase("strftime")) {
                // FROM_UNIXTIME(val)
                FunctionName fromUnixName = new FunctionName("FROM_UNIXTIME");
                List<Expr> fromUnixArgs = Lists.newArrayList(funcExpr.getChild(1));
                FunctionCallExpr fromUnixFunc = new FunctionCallExpr(
                        fromUnixName, new FunctionParams(false, fromUnixArgs));

                return fromUnixFunc;
            } else if (funcName.equalsIgnoreCase("time_format")) {
                // DATE_FORMAT(STR_TO_DATE(dt_str, dt_fmt))
                FunctionName strToDateName = new FunctionName("STR_TO_DATE");
                List<Expr> strToDateExprs = Lists.newArrayList(funcExpr.getChild(2), funcExpr.getChild(1));
                FunctionCallExpr strToDateFuncExpr = new FunctionCallExpr(
                        strToDateName, new FunctionParams(false, strToDateExprs));

                FunctionName dateFormatName = new FunctionName("DATE_FORMAT");
                List<Expr> dateFormatArgs = Lists.newArrayList(strToDateFuncExpr, funcExpr.getChild(0));
                FunctionCallExpr dateFormatFunc = new FunctionCallExpr(
                        dateFormatName, new FunctionParams(false, dateFormatArgs));

                return dateFormatFunc;
            } else if (funcName.equalsIgnoreCase("alignment_timestamp")) {
                /*
                 * change to:
                 * UNIX_TIMESTAMP(DATE_FORMAT(FROM_UNIXTIME(ts), "%Y-01-01 00:00:00"));
                 *
                 */

                // FROM_UNIXTIME
                FunctionName fromUnixName = new FunctionName("FROM_UNIXTIME");
                List<Expr> fromUnixArgs = Lists.newArrayList(funcExpr.getChild(1));
                FunctionCallExpr fromUnixFunc = new FunctionCallExpr(
                        fromUnixName, new FunctionParams(false, fromUnixArgs));

                // DATE_FORMAT
                StringLiteral precision = (StringLiteral) funcExpr.getChild(0);
                StringLiteral format;
                if (precision.getStringValue().equalsIgnoreCase("year")) {
                    format = new StringLiteral("%Y-01-01 00:00:00");
                } else if (precision.getStringValue().equalsIgnoreCase("month")) {
                    format = new StringLiteral("%Y-%m-01 00:00:00");
                } else if (precision.getStringValue().equalsIgnoreCase("day")) {
                    format = new StringLiteral("%Y-%m-%d 00:00:00");
                } else if (precision.getStringValue().equalsIgnoreCase("hour")) {
                    format = new StringLiteral("%Y-%m-%d %H:00:00");
                } else {
                    throw new UserException("Unknown precision(" + precision.getStringValue() + ")");
                }
                FunctionName dateFormatName = new FunctionName("DATE_FORMAT");
                List<Expr> dateFormatArgs = Lists.newArrayList(fromUnixFunc, format);
                FunctionCallExpr dateFormatFunc = new FunctionCallExpr(
                        dateFormatName, new FunctionParams(false, dateFormatArgs));

                // UNIX_TIMESTAMP
                FunctionName unixTimeName = new FunctionName("UNIX_TIMESTAMP");
                List<Expr> unixTimeArgs = Lists.newArrayList();
                unixTimeArgs.add(dateFormatFunc);
                FunctionCallExpr unixTimeFunc = new FunctionCallExpr(
                        unixTimeName, new FunctionParams(false, unixTimeArgs));

                return unixTimeFunc;
            } else if (funcName.equalsIgnoreCase("default_value")) {
                return funcExpr.getChild(0);
            } else if (funcName.equalsIgnoreCase("now")) {
                FunctionName nowFunctionName = new FunctionName("NOW");
                FunctionCallExpr newFunc = new FunctionCallExpr(nowFunctionName, new FunctionParams(null));
                return newFunc;
            } else if (funcName.equalsIgnoreCase("substitute")) {
                return funcExpr.getChild(0);
            }
        }
        return originExpr;
    }

    public LoadErrorHub.Param getLoadErrorHubInfo() {
        return loadErrorHubParam;
    }

    public void setLoadErrorHubInfo(LoadErrorHub.Param info) {
        this.loadErrorHubParam = info;
    }

    public void setLoadErrorHubInfo(Map<String, String> properties) throws DdlException {
        String type = properties.get("type");
        if (type.equalsIgnoreCase("MYSQL")) {
            String host = properties.get("host");
            if (Strings.isNullOrEmpty(host)) {
                throw new DdlException("mysql host is missing");
            }

            int port = -1;
            try {
                port = Integer.parseInt(properties.get("port"));
            } catch (NumberFormatException e) {
                throw new DdlException("invalid mysql port: " + properties.get("port"));
            }

            String user = properties.get("user");
            if (Strings.isNullOrEmpty(user)) {
                throw new DdlException("mysql user name is missing");
            }

            String db = properties.get("database");
            if (Strings.isNullOrEmpty(db)) {
                throw new DdlException("mysql database is missing");
            }

            String tbl = properties.get("table");
            if (Strings.isNullOrEmpty(tbl)) {
                throw new DdlException("mysql table is missing");
            }

            String pwd = Strings.nullToEmpty(properties.get("password"));

            MysqlLoadErrorHub.MysqlParam param = new MysqlLoadErrorHub.MysqlParam(host, port, user, pwd, db, tbl);
            loadErrorHubParam = LoadErrorHub.Param.createMysqlParam(param);
        } else if (type.equalsIgnoreCase("BROKER")) {
            String brokerName = properties.get("name");
            if (Strings.isNullOrEmpty(brokerName)) {
                throw new DdlException("broker name is missing");
            }
            properties.remove("name");

            if (!Catalog.getCurrentCatalog().getBrokerMgr().containsBroker(brokerName)) {
                throw new DdlException("broker does not exist: " + brokerName);
            }

            String path = properties.get("path");
            if (Strings.isNullOrEmpty(path)) {
                throw new DdlException("broker path is missing");
            }
            properties.remove("path");

            // check if broker info is invalid
            BlobStorage blobStorage = new BlobStorage(brokerName, properties);
            Status st = blobStorage.checkPathExist(path);
            if (!st.ok()) {
                throw new DdlException("failed to visit path: " + path + ", err: " + st.getErrMsg());
            }

            BrokerLoadErrorHub.BrokerParam param = new BrokerLoadErrorHub.BrokerParam(brokerName, path, properties);
            loadErrorHubParam = LoadErrorHub.Param.createBrokerParam(param);
        } else if (type.equalsIgnoreCase("null")) {
            loadErrorHubParam = LoadErrorHub.Param.createNullParam();
        }

        Catalog.getCurrentCatalog().getEditLog().logSetLoadErrorHub(loadErrorHubParam);

        LOG.info("set load error hub info: {}", loadErrorHubParam);
    }

    public static class JobInfo {
        public String dbName;
        public Set<String> tblNames = Sets.newHashSet();
        public String label;
        public String clusterName;
        public JobState state;
        public String failMsg;
        public String trackingUrl;

        public JobInfo(String dbName, String label, String clusterName) {
            this.dbName = dbName;
            this.label = label;
            this.clusterName = clusterName;
        }
    }

    public static void replayClearRollupInfo(ReplicaPersistInfo info, Catalog catalog) {
        Database db = catalog.getDb(info.getDbId());
        db.writeLock();
        try {
            OlapTable olapTable = (OlapTable) db.getTable(info.getTableId());
            Partition partition = olapTable.getPartition(info.getPartitionId());
            MaterializedIndex index = partition.getIndex(info.getIndexId());
            index.clearRollupIndexInfo();
        } finally {
            db.writeUnlock();
        }
    }

}
