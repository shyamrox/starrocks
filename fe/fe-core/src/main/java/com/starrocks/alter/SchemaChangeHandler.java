// This file is made available under Elastic License 2.0.
// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/alter/SchemaChangeHandler.java

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

package com.starrocks.alter;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.alter.AlterJob.JobState;
import com.starrocks.analysis.AddColumnClause;
import com.starrocks.analysis.AddColumnsClause;
import com.starrocks.analysis.AlterClause;
import com.starrocks.analysis.CancelAlterTableStmt;
import com.starrocks.analysis.CancelStmt;
import com.starrocks.analysis.ColumnPosition;
import com.starrocks.analysis.CreateIndexClause;
import com.starrocks.analysis.DropColumnClause;
import com.starrocks.analysis.DropIndexClause;
import com.starrocks.analysis.IndexDef;
import com.starrocks.analysis.ModifyColumnClause;
import com.starrocks.analysis.ModifyTablePropertiesClause;
import com.starrocks.analysis.ReorderColumnsClause;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.DistributionInfo;
import com.starrocks.catalog.DistributionInfo.DistributionInfoType;
import com.starrocks.catalog.HashDistributionInfo;
import com.starrocks.catalog.Index;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MaterializedIndex.IndexExtState;
import com.starrocks.catalog.MaterializedIndex.IndexState;
import com.starrocks.catalog.MaterializedIndexMeta;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.OlapTable.OlapTableState;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionInfo;
import com.starrocks.catalog.PartitionType;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Replica.ReplicaState;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Tablet;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.catalog.Type;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.Config;
import com.starrocks.common.DdlException;
import com.starrocks.common.ErrorCode;
import com.starrocks.common.ErrorReport;
import com.starrocks.common.FeConstants;
import com.starrocks.common.MarkedCountDownLatch;
import com.starrocks.common.Pair;
import com.starrocks.common.UserException;
import com.starrocks.common.util.DynamicPartitionUtil;
import com.starrocks.common.util.ListComparator;
import com.starrocks.common.util.PropertyAnalyzer;
import com.starrocks.common.util.Util;
import com.starrocks.mysql.privilege.PrivPredicate;
import com.starrocks.qe.ConnectContext;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTaskExecutor;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.task.ClearAlterTask;
import com.starrocks.task.UpdateTabletMetaInfoTask;
import com.starrocks.thrift.TStorageFormat;
import com.starrocks.thrift.TStorageMedium;
import com.starrocks.thrift.TTabletMetaType;
import com.starrocks.thrift.TTaskType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class SchemaChangeHandler extends AlterHandler {
    private static final Logger LOG = LogManager.getLogger(SchemaChangeHandler.class);

    // all shadow indexes should have this prefix in name
    public static final String SHADOW_NAME_PRFIX = "__starrocks_shadow_";
    // before version 1.18, use "__doris_shadow_" as shadow index prefix
    // check "__doris_shadow_" to prevent compatibility problems
    public static final String SHADOW_NAME_PRFIX_V1 = "__doris_shadow_";

    public SchemaChangeHandler() {
        super("schema change");
    }

    private void processAddColumn(AddColumnClause alterClause, OlapTable olapTable,
                                  Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        Column column = alterClause.getColumn();
        ColumnPosition columnPos = alterClause.getColPos();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        long baseIndexId = olapTable.getBaseIndexId();
        long targetIndexId = -1L;
        if (targetIndexName != null) {
            targetIndexId = olapTable.getIndexIdByName(targetIndexName);
        }

        Set<String> newColNameSet = Sets.newHashSet(column.getName());
        addColumnInternal(olapTable, column, columnPos, targetIndexId, baseIndexId,
                indexSchemaMap, newColNameSet);
    }

    private void processAddColumns(AddColumnsClause alterClause, OlapTable olapTable,
                                   Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        List<Column> columns = alterClause.getColumns();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        Set<String> newColNameSet = Sets.newHashSet();
        for (Column column : columns) {
            newColNameSet.add(column.getName());
        }

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        long baseIndexId = olapTable.getBaseIndexId();
        long targetIndexId = -1L;
        if (targetIndexName != null) {
            targetIndexId = olapTable.getIndexIdByName(targetIndexName);
        }

        for (Column column : columns) {
            addColumnInternal(olapTable, column, null, targetIndexId, baseIndexId,
                    indexSchemaMap, newColNameSet);
        }
    }

    private void processDropColumn(DropColumnClause alterClause, OlapTable olapTable,
                                   Map<Long, LinkedList<Column>> indexSchemaMap, List<Index> indexes)
            throws DdlException {
        String dropColName = alterClause.getColName();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        /*
         * PRIMARY:
         *      Can not drop any key column.
         * UNIQUE:
         *      Can not drop any key column.
         * AGGREGATION:
         *      Can not drop any key column is has value with REPLACE method
         */
        if (KeysType.PRIMARY_KEYS == olapTable.getKeysType()) {
            long baseIndexId = olapTable.getBaseIndexId();
            List<Column> baseSchema = indexSchemaMap.get(baseIndexId);
            boolean isKey = false;
            for (Column column : baseSchema) {
                if (column.isKey() && column.getName().equalsIgnoreCase(dropColName)) {
                    isKey = true;
                    break;
                }
            }
            if (isKey) {
                throw new DdlException("Can not drop key column in primary data model table");
            }
        } else if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            long baseIndexId = olapTable.getBaseIndexId();
            List<Column> baseSchema = indexSchemaMap.get(baseIndexId);
            boolean isKey = false;
            for (Column column : baseSchema) {
                if (column.isKey() && column.getName().equalsIgnoreCase(dropColName)) {
                    isKey = true;
                    break;
                }
            }

            if (isKey) {
                throw new DdlException("Can not drop key column in Unique data model table");
            }

        } else if (KeysType.AGG_KEYS == olapTable.getKeysType()) {
            if (null == targetIndexName) {
                // drop column in base table
                long baseIndexId = olapTable.getBaseIndexId();
                List<Column> baseSchema = indexSchemaMap.get(baseIndexId);
                boolean isKey = false;
                boolean hasReplaceColumn = false;
                for (Column column : baseSchema) {
                    if (column.isKey() && column.getName().equalsIgnoreCase(dropColName)) {
                        isKey = true;
                    } else if (AggregateType.REPLACE == column.getAggregationType() ||
                            AggregateType.REPLACE_IF_NOT_NULL == column.getAggregationType()) {
                        hasReplaceColumn = true;
                    }
                }
                if (isKey && hasReplaceColumn) {
                    throw new DdlException(
                            "Can not drop key column when table has value column with REPLACE aggregation method");
                }
            } else {
                // drop column in rollup and base index
                long targetIndexId = olapTable.getIndexIdByName(targetIndexName);
                // find column
                List<Column> targetIndexSchema = indexSchemaMap.get(targetIndexId);
                boolean isKey = false;
                boolean hasReplaceColumn = false;
                for (Column column : targetIndexSchema) {
                    if (column.isKey() && column.getName().equalsIgnoreCase(dropColName)) {
                        isKey = true;
                    } else if (AggregateType.REPLACE == column.getAggregationType() ||
                            AggregateType.REPLACE_IF_NOT_NULL == column.getAggregationType()) {
                        hasReplaceColumn = true;
                    }
                }
                if (isKey && hasReplaceColumn) {
                    throw new DdlException(
                            "Can not drop key column when rollup has value column with REPLACE aggregation method");
                }
            }
        }

        Iterator<Index> it = indexes.iterator();
        while (it.hasNext()) {
            Index index = it.next();
            for (String indexCol : index.getColumns()) {
                if (dropColName.equalsIgnoreCase(indexCol)) {
                    it.remove();
                    break;
                }
            }
        }

        long baseIndexId = olapTable.getBaseIndexId();
        if (targetIndexName == null) {
            // if not specify rollup index, column should be dropped from both base and rollup indexes.
            List<Long> indexIds = new ArrayList<Long>();
            indexIds.add(baseIndexId);
            indexIds.addAll(olapTable.getIndexIdListExceptBaseIndex());

            // find column in base index and remove it
            List<Column> baseSchema = indexSchemaMap.get(baseIndexId);
            boolean found = false;
            Iterator<Column> baseIter = baseSchema.iterator();
            while (baseIter.hasNext()) {
                Column column = baseIter.next();
                if (column.getName().equalsIgnoreCase(dropColName)) {
                    baseIter.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new DdlException("Column does not exists: " + dropColName);
            }

            // remove column in rollup index if exists (i = 1 to skip base index)
            for (int i = 1; i < indexIds.size(); i++) {
                List<Column> rollupSchema = indexSchemaMap.get(indexIds.get(i));
                Iterator<Column> iter = rollupSchema.iterator();
                while (iter.hasNext()) {
                    Column column = iter.next();
                    if (column.getName().equalsIgnoreCase(dropColName)) {
                        iter.remove();
                        break;
                    }
                }
            } // end for index names
        } else {
            // if specify rollup index, only drop column from specified rollup index
            long targetIndexId = olapTable.getIndexIdByName(targetIndexName);
            // find column
            List<Column> targetIndexSchema = indexSchemaMap.get(targetIndexId);
            boolean found = false;
            Iterator<Column> iter = targetIndexSchema.iterator();
            while (iter.hasNext()) {
                Column column = iter.next();
                if (column.getName().equalsIgnoreCase(dropColName)) {
                    iter.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new DdlException("Column does not exists: " + dropColName);
            }
        }
    }

    // User can modify column type and column position
    private void processModifyColumn(ModifyColumnClause alterClause, OlapTable olapTable,
                                     Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        Column modColumn = alterClause.getColumn();
        if (KeysType.PRIMARY_KEYS == olapTable.getKeysType()) {
            if (modColumn.isKey()) {
                throw new DdlException("Can not modify key column: " + modColumn.getName() + " for primary key table");
            }
            if (modColumn.getAggregationType() != null) {
                throw new DdlException("Can not assign aggregation method on column in Primary data model table: " +
                        modColumn.getName());
            }
            modColumn.setAggregationType(AggregateType.REPLACE, true);
        } else if (KeysType.AGG_KEYS == olapTable.getKeysType()) {
            if (modColumn.isKey() && null != modColumn.getAggregationType()) {
                throw new DdlException("Can not assign aggregation method on key column: " + modColumn.getName());
            } else if (null == modColumn.getAggregationType()) {
                Preconditions.checkArgument(modColumn.getType().isScalarType());
                // in aggregate key table, no aggregation method indicate key column
                modColumn.setIsKey(true);
            }
        } else if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            if (null != modColumn.getAggregationType()) {
                throw new DdlException("Can not assign aggregation method on column in Unique data model table: " +
                        modColumn.getName());
            }
            if (!modColumn.isKey()) {
                modColumn.setAggregationType(AggregateType.REPLACE, true);
            }
        } else {
            if (null != modColumn.getAggregationType()) {
                throw new DdlException("Can not assign aggregation method on column in Duplicate data model table: " +
                        modColumn.getName());
            }
            if (!modColumn.isKey()) {
                modColumn.setAggregationType(AggregateType.NONE, true);
            }
        }
        // TODO(mofei) support it
        if (modColumn.getType().isJsonType() && olapTable.getKeysType() != KeysType.DUP_KEYS) {
            throw new DdlException(modColumn.getType() + " must be used in duplicate key");
        }

        ColumnPosition columnPos = alterClause.getColPos();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        if (targetIndexName != null && columnPos == null) {
            throw new DdlException("Do not need to specify index name when just modifying column type");
        }

        String indexNameForFindingColumn = targetIndexName;
        if (indexNameForFindingColumn == null) {
            indexNameForFindingColumn = baseIndexName;
        }

        long indexIdForFindingColumn = olapTable.getIndexIdByName(indexNameForFindingColumn);

        // find modified column
        List<Column> schemaForFinding = indexSchemaMap.get(indexIdForFindingColumn);
        String newColName = modColumn.getName();
        boolean hasColPos = (columnPos != null && !columnPos.isFirst());
        boolean found = false;
        boolean typeChanged = false;
        int modColIndex = -1;
        int lastColIndex = -1;
        for (int i = 0; i < schemaForFinding.size(); i++) {
            Column col = schemaForFinding.get(i);
            if (col.getName().equalsIgnoreCase(newColName)) {
                modColIndex = i;
                found = true;
                if (!col.equals(modColumn)) {
                    typeChanged = true;
                }
            }
            if (hasColPos) {
                if (col.getName().equalsIgnoreCase(columnPos.getLastCol())) {
                    lastColIndex = i;
                }
            } else {
                // save the last Key position
                if (col.isKey()) {
                    lastColIndex = i;
                }
            }
        }
        // mod col not find
        if (!found) {
            throw new DdlException("Column[" + newColName + "] does not exists");
        }

        // last col not find
        if (hasColPos && lastColIndex == -1) {
            throw new DdlException("Column[" + columnPos.getLastCol() + "] does not exists");
        }

        // check if add to first
        if (columnPos != null && columnPos.isFirst()) {
            lastColIndex = -1;
            hasColPos = true;
        }

        Column oriColumn = schemaForFinding.get(modColIndex);
        // retain old column name
        modColumn.setName(oriColumn.getName());

        // handle the move operation in 'indexForFindingColumn' if has
        if (hasColPos) {
            // move col
            if (lastColIndex > modColIndex) {
                schemaForFinding.add(lastColIndex + 1, modColumn);
                schemaForFinding.remove(modColIndex);
            } else if (lastColIndex < modColIndex) {
                schemaForFinding.remove(modColIndex);
                schemaForFinding.add(lastColIndex + 1, modColumn);
            } else {
                throw new DdlException("Column[" + columnPos.getLastCol() + "] modify position is invalid");
            }
        } else {
            schemaForFinding.set(modColIndex, modColumn);
        }

        // check if column being mod
        if (!modColumn.equals(oriColumn)) {
            // column is mod. we have to mod this column in all indices

            // handle other indices
            // 1 find other indices which contain this column
            List<Long> otherIndexIds = new ArrayList<Long>();
            for (Map.Entry<Long, List<Column>> entry : olapTable.getIndexIdToSchema().entrySet()) {
                if (entry.getKey() == indexIdForFindingColumn) {
                    // skip the index we used to find column. it has been handled before
                    continue;
                }
                List<Column> schema = entry.getValue();
                for (Column column : schema) {
                    if (column.getName().equalsIgnoreCase(modColumn.getName())) {
                        otherIndexIds.add(entry.getKey());
                        break;
                    }
                }
            }

            if (KeysType.AGG_KEYS == olapTable.getKeysType() || KeysType.UNIQUE_KEYS == olapTable.getKeysType() ||
                    KeysType.PRIMARY_KEYS == olapTable.getKeysType()) {
                for (Long otherIndexId : otherIndexIds) {
                    List<Column> otherIndexSchema = indexSchemaMap.get(otherIndexId);
                    modColIndex = -1;
                    for (int i = 0; i < otherIndexSchema.size(); i++) {
                        if (otherIndexSchema.get(i).getName().equalsIgnoreCase(modColumn.getName())) {
                            modColIndex = i;
                            break;
                        }
                    }
                    Preconditions.checkState(modColIndex != -1);
                    // replace the old column
                    otherIndexSchema.set(modColIndex, modColumn);
                } //  end for other indices
            } else {
                // DUPLICATE data model has a little
                for (Long otherIndexId : otherIndexIds) {
                    List<Column> otherIndexSchema = indexSchemaMap.get(otherIndexId);
                    modColIndex = -1;
                    for (int i = 0; i < otherIndexSchema.size(); i++) {
                        if (otherIndexSchema.get(i).getName().equalsIgnoreCase(modColumn.getName())) {
                            modColIndex = i;
                            break;
                        }
                    }

                    Preconditions.checkState(modColIndex != -1);
                    // replace the old column
                    Column oldCol = otherIndexSchema.get(modColIndex);
                    Column otherCol = new Column(modColumn);
                    otherCol.setIsKey(oldCol.isKey());
                    if (null != oldCol.getAggregationType()) {
                        otherCol.setAggregationType(oldCol.getAggregationType(), oldCol.isAggregationTypeImplicit());
                    } else {
                        otherCol.setAggregationType(null, oldCol.isAggregationTypeImplicit());
                    }
                    otherIndexSchema.set(modColIndex, otherCol);
                }
            }
        } // end for handling other indices

        if (typeChanged) {
            /*
             * In new alter table process (AlterJobV2), any modified columns are treated as new columns.
             * But the modified columns' name does not changed. So in order to distinguish this, we will add
             * a prefix in the name of these modified columns.
             * This prefix only exist during the schema change process. Once the schema change is finished,
             * it will be removed.
             *
             * After adding this prefix, modify a column is just same as 'add' a column.
             *
             * And if the column type is not changed, the same column name is still to the same column type,
             * so no need to add prefix.
             */
            modColumn.setName(SHADOW_NAME_PRFIX + modColumn.getName());
        }
    }

    private void processReorderColumn(ReorderColumnsClause alterClause, OlapTable olapTable,
                                      Map<Long, LinkedList<Column>> indexSchemaMap) throws DdlException {
        List<String> orderedColNames = alterClause.getColumnsByPos();
        String targetIndexName = alterClause.getRollupName();
        checkIndexExists(olapTable, targetIndexName);

        String baseIndexName = olapTable.getName();
        checkAssignedTargetIndexName(baseIndexName, targetIndexName);

        if (targetIndexName == null) {
            targetIndexName = baseIndexName;
        }

        long targetIndexId = olapTable.getIndexIdByName(targetIndexName);

        LinkedList<Column> newSchema = new LinkedList<Column>();
        LinkedList<Column> targetIndexSchema = indexSchemaMap.get(targetIndexId);

        // check and create new ordered column list
        Set<String> colNameSet = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        for (String colName : orderedColNames) {
            Column oneCol = null;
            for (Column column : targetIndexSchema) {
                if (column.getName().equalsIgnoreCase(colName)) {
                    oneCol = column;
                    break;
                }
            }
            if (oneCol == null) {
                throw new DdlException("Column[" + colName + "] not exists");
            }
            newSchema.add(oneCol);
            if (colNameSet.contains(colName)) {
                throw new DdlException("Reduplicative column[" + colName + "]");
            } else {
                colNameSet.add(colName);
            }
        }
        if (newSchema.size() != targetIndexSchema.size()) {
            throw new DdlException("Reorder stmt should contains all columns");
        }
        // replace the old column list
        indexSchemaMap.put(targetIndexId, newSchema);
    }

    /*
     * Add 'newColumn' to specified index.
     * Modified schema will be saved in 'indexSchemaMap'
     */
    private void addColumnInternal(OlapTable olapTable, Column newColumn, ColumnPosition columnPos,
                                   long targetIndexId, long baseIndexId,
                                   Map<Long, LinkedList<Column>> indexSchemaMap,
                                   Set<String> newColNameSet) throws DdlException {

        Column.DefaultValueType defaultValueType = newColumn.getDefaultValueType();
        // expr like uuid() will support later
        if (defaultValueType == Column.DefaultValueType.VARY) {
            throw new DdlException("Schema change currently not supported default expr:"
                    + newColumn.getDefaultExpr().getExpr());
        }
        String newColName = newColumn.getName();
        // check the validation of aggregation method on column.
        // also fill the default aggregation method if not specified.
        if (KeysType.PRIMARY_KEYS == olapTable.getKeysType()) {
            if (newColumn.isKey()) {
                throw new DdlException("Can not add key column: " + newColName + " for primary key table");
            }
            if (newColumn.getAggregationType() != null) {
                throw new DdlException(
                        "Can not assign aggregation method on column in Primary data model table: " + newColName);
            }
            newColumn.setAggregationType(AggregateType.REPLACE, true);
        } else if (KeysType.AGG_KEYS == olapTable.getKeysType()) {
            if (newColumn.isKey() && newColumn.getAggregationType() != null) {
                throw new DdlException("Can not assign aggregation method on key column: " + newColName);
            } else if (null == newColumn.getAggregationType()) {
                Type type = newColumn.getType();
                if (!type.isKeyType()) {
                    throw new DdlException(
                            "column without agg function will be treated as key column for aggregate table, " + type +
                                    " type can not be key column");
                }
                newColumn.setIsKey(true);
            } else if (newColumn.getAggregationType() == AggregateType.SUM
                    && newColumn.getDefaultValue() != null && !newColumn.getDefaultValue().equals("0")) {
                throw new DdlException(
                        "The default value of '" + newColName + "' with SUM aggregation function must be zero");
            }
        } else if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            if (newColumn.getAggregationType() != null) {
                throw new DdlException(
                        "Can not assign aggregation method on column in Unique data model table: " + newColName);
            }
            if (!newColumn.isKey()) {
                newColumn.setAggregationType(AggregateType.REPLACE, true);
            }
        } else {
            if (newColumn.getAggregationType() != null) {
                throw new DdlException(
                        "Can not assign aggregation method on column in Duplicate data model table: " + newColName);
            }
            if (!newColumn.isKey()) {
                if (targetIndexId != -1L &&
                        olapTable.getIndexMetaByIndexId(targetIndexId).getKeysType() == KeysType.AGG_KEYS) {
                    throw new DdlException("Please add non-key column on base table directly");
                }
                newColumn.setAggregationType(AggregateType.NONE, true);
            }
        }

        // hll must be used in agg_keys
        if (newColumn.getType().isHllType() && KeysType.AGG_KEYS != olapTable.getKeysType()) {
            throw new DdlException("HLL type column can only be in Aggregation data model table: " + newColName);
        }

        if (newColumn.getAggregationType() == AggregateType.BITMAP_UNION &&
                KeysType.AGG_KEYS != olapTable.getKeysType()) {
            throw new DdlException("BITMAP_UNION must be used in AGG_KEYS");
        }

        if (newColumn.getAggregationType() == AggregateType.PERCENTILE_UNION &&
                KeysType.AGG_KEYS != olapTable.getKeysType()) {
            throw new DdlException("PERCENTILE_UNION must be used in AGG_KEYS");
        }

        if (newColumn.getType().isComplexType() && KeysType.DUP_KEYS != olapTable.getKeysType()) {
            throw new DdlException(newColumn.getType() + "must be used in DUP_KEYS");
        }

        if (newColumn.getType().isJsonType() && KeysType.DUP_KEYS != olapTable.getKeysType()) {
            throw new DdlException(newColumn.getType() + " must be used in duplicate key");
        }

        // check if the new column already exist in base schema.
        // do not support adding new column which already exist in base schema.
        List<Column> baseSchema = olapTable.getBaseSchema();
        boolean found = false;
        for (Column column : baseSchema) {
            if (column.getName().equalsIgnoreCase(newColName)) {
                found = true;
                break;
            }
        }
        if (found) {
            throw new DdlException("Can not add column which already exists in base table: " + newColName);
        }

        /*
         * add new column to indexes.
         * PRIMARY:
         *      1. add the new column to base index
         * UNIQUE:
         *      1. If new column is key, it should be added to all indexes.
         *      2. Else, add the new column to base index and specified rollup index.
         * DUPLICATE:
         *      1. If not specify rollup index, just add it to base index.
         *      2. Else, first add it to specify rollup index. Then if the new column is key, add it to base
         *          index, at the end of all other existing key columns. If new new column is value, add it to
         *          base index by user specified position.
         * AGGREGATION:
         *      1. Add it to base index, as well as specified rollup index.
         */
        if (KeysType.PRIMARY_KEYS == olapTable.getKeysType()) {
            List<Column> modIndexSchema = indexSchemaMap.get(baseIndexId);
            checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, true);
            if (targetIndexId != -1L) {
                throw new DdlException("Can not add column: " + newColName + " to rollup index");
            }
        } else if (KeysType.UNIQUE_KEYS == olapTable.getKeysType()) {
            List<Column> modIndexSchema;
            if (newColumn.isKey()) {
                // add key column to unique key table
                // add to all indexes including base and rollup
                for (Map.Entry<Long, LinkedList<Column>> entry : indexSchemaMap.entrySet()) {
                    modIndexSchema = entry.getValue();
                    boolean isBaseIdex = entry.getKey() == baseIndexId;
                    checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, isBaseIdex);
                }
            } else {
                // 1. add to base table
                modIndexSchema = indexSchemaMap.get(baseIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, true);
                if (targetIndexId == -1L) {
                    return;
                }
                // 2. add to rollup
                modIndexSchema = indexSchemaMap.get(targetIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, false);
            }
        } else if (KeysType.DUP_KEYS == olapTable.getKeysType()) {
            if (targetIndexId == -1L) {
                // add to base index
                List<Column> modIndexSchema = indexSchemaMap.get(baseIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, true);
                // no specified target index. return
                return;
            } else {
                // add to rollup index
                List<Column> modIndexSchema = indexSchemaMap.get(targetIndexId);
                checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, false);

                if (newColumn.isKey()) {
                    /*
                     * if add column in rollup is key,
                     * then put the column in base table as the last key column
                     */
                    modIndexSchema = indexSchemaMap.get(baseIndexId);
                    checkAndAddColumn(modIndexSchema, newColumn, null, newColNameSet, true);
                } else {
                    modIndexSchema = indexSchemaMap.get(baseIndexId);
                    checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, true);
                }
            }
        } else {
            // check if has default value. this should be done in Analyze phase
            // 1. add to base index first
            List<Column> modIndexSchema = indexSchemaMap.get(baseIndexId);
            checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, true);

            if (targetIndexId == -1L) {
                // no specified target index. return
                return;
            }

            // 2. add to rollup index
            modIndexSchema = indexSchemaMap.get(targetIndexId);
            checkAndAddColumn(modIndexSchema, newColumn, columnPos, newColNameSet, false);
        }
    }

    /*
     * add new column to specified index schema('modIndexSchema').
     * if 'isBaseIndex' is true, which means 'modIndexSchema' is base index's schema.
     * so we will not check repeat adding of column.
     * For example, user want to add column k1 to both rollup1 and rollup2 in one alter stmt:
     *      ADD COLUMN k1 int to rollup1,
     *      ADD COLUMN k1 int to rollup2
     * So that k1 will be added to base index 'twice', and we just ignore this repeat adding.
     */
    private void checkAndAddColumn(List<Column> modIndexSchema, Column newColumn, ColumnPosition columnPos,
                                   Set<String> newColNameSet, boolean isBaseIndex) throws DdlException {
        int posIndex = -1;
        String newColName = newColumn.getName();
        boolean hasPos = (columnPos != null && !columnPos.isFirst());
        for (int i = 0; i < modIndexSchema.size(); i++) {
            Column col = modIndexSchema.get(i);
            if (col.getName().equalsIgnoreCase(newColName)) {
                if (!isBaseIndex || !newColNameSet.contains(newColName)) {
                    // if this is not a base index, we should check if user repeatedly add columns
                    throw new DdlException("Repeatedly add column: " + newColName);
                }
                // this is a base index, and the column we check here is added by previous 'add column clause'
                // in same ALTER stmt.
                // so here we will check if the 2 columns is exactly same. if not, throw exception
                if (!col.equals(newColumn)) {
                    throw new DdlException("Repeatedly add same column with different definition: " + newColName);
                }

                // column already exist, return
                return;
            }

            if (hasPos) {
                // after the field
                if (col.getName().equalsIgnoreCase(columnPos.getLastCol())) {
                    posIndex = i;
                }
            } else {
                // save the last Key position
                if (col.isKey()) {
                    posIndex = i;
                }
            }
        }

        // check if lastCol was found
        if (hasPos && posIndex == -1) {
            throw new DdlException("Column[" + columnPos.getLastCol() + "] does not found");
        }

        // check if add to first
        if (columnPos != null && columnPos.isFirst()) {
            posIndex = -1;
            hasPos = true;
        }

        if (hasPos) {
            modIndexSchema.add(posIndex + 1, newColumn);
        } else {
            if (newColumn.isKey()) {
                // key
                modIndexSchema.add(posIndex + 1, newColumn);
            } else {
                // value
                modIndexSchema.add(newColumn);
            }
        }

        checkRowLength(modIndexSchema);
    }

    // row length can not large than limit
    private void checkRowLength(List<Column> modIndexSchema) throws DdlException {
        int rowLengthBytes = 0;
        for (Column column : modIndexSchema) {
            rowLengthBytes += column.getType().getStorageLayoutBytes();
        }

        if (rowLengthBytes > Config.max_layout_length_per_row) {
            throw new DdlException("The size of a row (" + rowLengthBytes + ") exceed the maximal row size: "
                    + Config.max_layout_length_per_row);
        }
    }

    private void checkIndexExists(OlapTable olapTable, String targetIndexName) throws DdlException {
        if (targetIndexName != null && !olapTable.hasMaterializedIndex(targetIndexName)) {
            throw new DdlException("Index[" + targetIndexName + "] does not exist in table[" + olapTable.getName()
                    + "]");
        }
    }

    private void checkAssignedTargetIndexName(String baseIndexName, String targetIndexName) throws DdlException {
        // user cannot assign base index to do schema change
        if (targetIndexName != null) {
            if (targetIndexName.equals(baseIndexName)) {
                throw new DdlException("Do not need to assign base index[" + baseIndexName + "] to do schema change");
            }
        }
    }

    private void createJob(long dbId, OlapTable olapTable, Map<Long, LinkedList<Column>> indexSchemaMap,
                           Map<String, String> propertyMap, List<Index> indexes) throws UserException {
        if (olapTable.getState() == OlapTableState.ROLLUP) {
            throw new DdlException("Table[" + olapTable.getName() + "]'s is doing ROLLUP job");
        }

        if (this.hasUnfinishedAlterJob(olapTable.getId())) {
            throw new DdlException("Table[" + olapTable.getName() + "]'s is doing ALTER job");
        }

        // for now table's state can only be NORMAL
        Preconditions.checkState(olapTable.getState() == OlapTableState.NORMAL, olapTable.getState().name());

        // process properties first
        // for now. properties has 3 options
        // property 1. to specify short key column count.
        // eg.
        //     "indexname1#short_key" = "3"
        //     "indexname2#short_key" = "4"
        Map<Long, Map<String, String>> indexIdToProperties = new HashMap<Long, Map<String, String>>();
        if (propertyMap.size() > 0) {
            for (String key : propertyMap.keySet()) {
                if (key.endsWith(PropertyAnalyzer.PROPERTIES_SHORT_KEY)) {
                    // short key
                    String[] keyArray = key.split("#");
                    if (keyArray.length != 2 || keyArray[0].isEmpty()
                            || !keyArray[1].equals(PropertyAnalyzer.PROPERTIES_SHORT_KEY)) {
                        throw new DdlException("Invalid alter table property: " + key);
                    }

                    HashMap<String, String> prop = new HashMap<String, String>();

                    if (!olapTable.hasMaterializedIndex(keyArray[0])) {
                        throw new DdlException("Index[" + keyArray[0] + "] does not exist");
                    }

                    prop.put(PropertyAnalyzer.PROPERTIES_SHORT_KEY, propertyMap.get(key));
                    indexIdToProperties.put(olapTable.getIndexIdByName(keyArray[0]), prop);
                }
            } // end for property keys
        }

        // for bitmapIndex
        boolean hasIndexChange = false;
        Set<Index> newSet = new HashSet<>(indexes);
        Set<Index> oriSet = new HashSet<>(olapTable.getIndexes());
        if (!newSet.equals(oriSet)) {
            hasIndexChange = true;
        }

        // property 2. bloom filter
        // eg. "bloom_filter_columns" = "k1,k2", "bloom_filter_fpp" = "0.05"
        Set<String> bfColumns = null;
        double bfFpp = 0;
        try {
            bfColumns = PropertyAnalyzer
                    .analyzeBloomFilterColumns(propertyMap, indexSchemaMap.get(olapTable.getBaseIndexId()),
                    olapTable.getKeysType() == KeysType.PRIMARY_KEYS);
            bfFpp = PropertyAnalyzer.analyzeBloomFilterFpp(propertyMap);
        } catch (AnalysisException e) {
            throw new DdlException(e.getMessage());
        }

        // check bloom filter has change
        boolean hasBfChange = false;
        Set<String> oriBfColumns = olapTable.getCopiedBfColumns();
        double oriBfFpp = olapTable.getBfFpp();
        if (bfColumns != null) {
            if (bfFpp == 0) {
                // columns: yes, fpp: no
                if (bfColumns.equals(oriBfColumns)) {
                    throw new DdlException("Bloom filter index has no change");
                }

                if (oriBfColumns == null) {
                    bfFpp = FeConstants.default_bloom_filter_fpp;
                } else {
                    bfFpp = oriBfFpp;
                }
            } else {
                // columns: yes, fpp: yes
                if (bfColumns.equals(oriBfColumns) && bfFpp == oriBfFpp) {
                    throw new DdlException("Bloom filter index has no change");
                }
            }

            hasBfChange = true;
        } else {
            if (bfFpp == 0) {
                // columns: no, fpp: no
                bfFpp = oriBfFpp;
            } else {
                // columns: no, fpp: yes
                if (bfFpp == oriBfFpp) {
                    throw new DdlException("Bloom filter index has no change");
                }
                if (oriBfColumns == null) {
                    throw new DdlException("Bloom filter index has no change");
                }

                hasBfChange = true;
            }

            bfColumns = oriBfColumns;
        }

        if (bfColumns != null && bfColumns.isEmpty()) {
            bfColumns = null;
        }
        if (bfColumns == null) {
            bfFpp = 0;
        }

        // property 3: timeout
        long timeoutSecond = PropertyAnalyzer.analyzeTimeout(propertyMap, Config.alter_table_timeout_second);

        TStorageFormat storageFormat = PropertyAnalyzer.analyzeStorageFormat(propertyMap);

        // create job
        Catalog catalog = Catalog.getCurrentCatalog();
        long jobId = catalog.getNextId();
        SchemaChangeJobV2 schemaChangeJob =
                new SchemaChangeJobV2(jobId, dbId, olapTable.getId(), olapTable.getName(), timeoutSecond * 1000);
        schemaChangeJob.setBloomFilterInfo(hasBfChange, bfColumns, bfFpp);
        schemaChangeJob.setAlterIndexInfo(hasIndexChange, indexes);
        schemaChangeJob.setStartTime(ConnectContext.get().getStartTime());

        // If StorageFormat is set to TStorageFormat.V2
        // which will create tablet with preferred_rowset_type set to BETA
        // for both base table and rollup index
        schemaChangeJob.setStorageFormat(storageFormat);

        // begin checking each table
        // ATTN: DO NOT change any meta in this loop
        long tableId = olapTable.getId();
        Map<Long, Short> indexIdToShortKeyColumnCount = Maps.newHashMap();
        Map<Long, List<Column>> changedIndexIdToSchema = Maps.newHashMap();
        for (Long alterIndexId : indexSchemaMap.keySet()) {
            List<Column> originSchema = olapTable.getSchemaByIndexId(alterIndexId);
            List<Column> alterSchema = indexSchemaMap.get(alterIndexId);

            // 0. check if unchanged
            boolean hasColumnChange = false;
            if (alterSchema.size() != originSchema.size()) {
                hasColumnChange = true;
            } else {
                for (int i = 0; i < alterSchema.size(); i++) {
                    Column alterColumn = alterSchema.get(i);
                    if (!alterColumn.equals(originSchema.get(i))) {
                        hasColumnChange = true;
                        break;
                    }
                }
            }

            // if has column changed, alter it.
            // else:
            //     if no bf change, no alter
            //     if has bf change, should check
            boolean needAlter = false;
            if (hasColumnChange) {
                needAlter = true;
            } else if (hasBfChange) {
                for (Column alterColumn : alterSchema) {
                    String columnName = alterColumn.getName();

                    boolean isOldBfColumn = false;
                    if (oriBfColumns != null && oriBfColumns.contains(columnName)) {
                        isOldBfColumn = true;
                    }

                    boolean isNewBfColumn = false;
                    if (bfColumns != null && bfColumns.contains(columnName)) {
                        isNewBfColumn = true;
                    }

                    if (isOldBfColumn != isNewBfColumn) {
                        // bf column change
                        needAlter = true;
                    } else if (isOldBfColumn && isNewBfColumn && oriBfFpp != bfFpp) {
                        // bf fpp change
                        needAlter = true;
                    }

                    if (needAlter) {
                        break;
                    }
                }
            } else if (hasIndexChange) {
                needAlter = true;
            } else if (storageFormat == TStorageFormat.V2) {
                if (olapTable.getStorageFormat() != TStorageFormat.V2) {
                    needAlter = true;
                }
            }

            if (!needAlter) {
                LOG.debug("index[{}] is not changed. ignore", alterIndexId);
                continue;
            }

            LOG.debug("index[{}] is changed. start checking...", alterIndexId);
            // 1. check order: a) has key; b) value after key
            boolean meetValue = false;
            boolean hasKey = false;
            for (Column column : alterSchema) {
                if (column.isKey() && meetValue) {
                    throw new DdlException("Invalid column order. value should be after key. index["
                            + olapTable.getIndexNameById(alterIndexId) + "]");
                }
                if (!column.isKey()) {
                    meetValue = true;
                } else {
                    hasKey = true;
                }
            }
            if (!hasKey) {
                throw new DdlException("No key column left. index[" + olapTable.getIndexNameById(alterIndexId) + "]");
            }

            // 2. check compatible
            for (Column alterColumn : alterSchema) {
                for (Column oriColumn : originSchema) {
                    if (alterColumn.nameEquals(oriColumn.getName(), true /* ignore prefix */)) {
                        if (!alterColumn.equals(oriColumn)) {
                            // 3.1 check type
                            oriColumn.checkSchemaChangeAllowed(alterColumn);
                        }
                    }
                } // end for ori
            } // end for alter

            // 3. check partition key
            PartitionInfo partitionInfo = olapTable.getPartitionInfo();
            if (partitionInfo.getType() == PartitionType.RANGE) {
                RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) partitionInfo;
                List<Column> partitionColumns = rangePartitionInfo.getPartitionColumns();
                for (Column partitionCol : partitionColumns) {
                    boolean found = false;
                    for (Column alterColumn : alterSchema) {
                        if (alterColumn.nameEquals(partitionCol.getName(), true)) {
                            // 2.1 partition column cannot be modified
                            if (!alterColumn.equals(partitionCol)) {
                                throw new DdlException("Can not modify partition column["
                                        + partitionCol.getName() + "]. index["
                                        + olapTable.getIndexNameById(alterIndexId) + "]");
                            }
                            found = true;
                            break;
                        }
                    } // end for alterColumns

                    if (!found && alterIndexId == olapTable.getBaseIndexId()) {
                        // 2.1 partition column cannot be deleted.
                        throw new DdlException("Partition column[" + partitionCol.getName()
                                + "] cannot be dropped. index[" + olapTable.getIndexNameById(alterIndexId) + "]");
                        // ATTN. partition columns' order also need remaining unchanged.
                        // for now, we only allow one partition column, so no need to check order.
                    }
                } // end for partitionColumns
            }

            // 4. check distribution key:
            DistributionInfo distributionInfo = olapTable.getDefaultDistributionInfo();
            if (distributionInfo.getType() == DistributionInfoType.HASH) {
                List<Column> distributionColumns = ((HashDistributionInfo) distributionInfo).getDistributionColumns();
                for (Column distributionCol : distributionColumns) {
                    boolean found = false;
                    for (Column alterColumn : alterSchema) {
                        if (alterColumn.nameEquals(distributionCol.getName(), true)) {
                            // 3.1 distribution column cannot be modified
                            if (!alterColumn.equals(distributionCol)) {
                                throw new DdlException("Can not modify distribution column["
                                        + distributionCol.getName() + "]. index["
                                        + olapTable.getIndexNameById(alterIndexId) + "]");
                            }
                            found = true;
                            break;
                        }
                    } // end for alterColumns

                    if (!found && alterIndexId == olapTable.getBaseIndexId()) {
                        // 2.2 distribution column cannot be deleted.
                        throw new DdlException("Distribution column[" + distributionCol.getName()
                                + "] cannot be dropped. index[" + olapTable.getIndexNameById(alterIndexId) + "]");
                    }
                } // end for distributionCols
            }

            // 5. calc short key
            short newShortKeyColumnCount = Catalog.calcShortKeyColumnCount(alterSchema,
                    indexIdToProperties.get(alterIndexId));
            LOG.debug("alter index[{}] short key column count: {}", alterIndexId, newShortKeyColumnCount);
            indexIdToShortKeyColumnCount.put(alterIndexId, newShortKeyColumnCount);

            // 6. store the changed columns for edit log
            changedIndexIdToSchema.put(alterIndexId, alterSchema);

            LOG.debug("schema change[{}-{}-{}] check pass.", dbId, tableId, alterIndexId);
        } // end for indices

        if (changedIndexIdToSchema.isEmpty() && !hasIndexChange) {
            throw new DdlException("Nothing is changed. please check your alter stmt.");
        }

        // the following operations are done outside the 'for indices' loop
        // to avoid partial check success

        /*
         * Create schema change job
         * 1. For each index which has been changed, create a SHADOW index, and save the mapping of origin index to SHADOW index.
         * 2. Create all tablets and replicas of all SHADOW index, add them to tablet inverted index.
         * 3. Change table's state as SCHEMA_CHANGE
         */
        for (Map.Entry<Long, List<Column>> entry : changedIndexIdToSchema.entrySet()) {
            long originIndexId = entry.getKey();
            MaterializedIndexMeta currentIndexMeta = olapTable.getIndexMetaByIndexId(originIndexId);
            // 1. get new schema version/schema version hash, short key column count
            int currentSchemaVersion = currentIndexMeta.getSchemaVersion();
            int newSchemaVersion = currentSchemaVersion + 1;
            // generate schema hash for new index has to generate a new schema hash not equal to current schema hash
            int currentSchemaHash = currentIndexMeta.getSchemaHash();
            int newSchemaHash = Util.generateSchemaHash();
            while (currentSchemaHash == newSchemaHash) {
                newSchemaHash = Util.generateSchemaHash();
            }
            String newIndexName = SHADOW_NAME_PRFIX + olapTable.getIndexNameById(originIndexId);
            short newShortKeyColumnCount = indexIdToShortKeyColumnCount.get(originIndexId);
            long shadowIndexId = catalog.getNextId();

            // create SHADOW index for each partition
            List<Tablet> addedTablets = Lists.newArrayList();
            for (Partition partition : olapTable.getPartitions()) {
                long partitionId = partition.getId();
                TStorageMedium medium = olapTable.getPartitionInfo().getDataProperty(partitionId).getStorageMedium();
                // index state is SHADOW
                MaterializedIndex shadowIndex = new MaterializedIndex(shadowIndexId, IndexState.SHADOW);
                MaterializedIndex originIndex = partition.getIndex(originIndexId);
                TabletMeta shadowTabletMeta =
                        new TabletMeta(dbId, tableId, partitionId, shadowIndexId, newSchemaHash, medium);
                short replicationNum = olapTable.getPartitionInfo().getReplicationNum(partitionId);
                for (Tablet originTablet : originIndex.getTablets()) {
                    long originTabletId = originTablet.getId();
                    long shadowTabletId = catalog.getNextId();

                    LocalTablet shadowTablet = new LocalTablet(shadowTabletId);
                    shadowIndex.addTablet(shadowTablet, shadowTabletMeta);
                    addedTablets.add(shadowTablet);

                    schemaChangeJob.addTabletIdMap(partitionId, shadowIndexId, shadowTabletId, originTabletId);
                    List<Replica> originReplicas = ((LocalTablet) originTablet).getReplicas();

                    int healthyReplicaNum = 0;
                    for (Replica originReplica : originReplicas) {
                        long shadowReplicaId = catalog.getNextId();
                        long backendId = originReplica.getBackendId();

                        if (originReplica.getState() == Replica.ReplicaState.CLONE
                                || originReplica.getState() == Replica.ReplicaState.DECOMMISSION
                                || originReplica.getLastFailedVersion() > 0) {
                            LOG.info(
                                    "origin replica {} of tablet {} state is {}, and last failed version is {}, " +
                                            "skip creating shadow replica",
                                    originReplica.getId(), originReplica, originReplica.getState(),
                                    originReplica.getLastFailedVersion());
                            continue;
                        }
                        Preconditions
                                .checkState(originReplica.getState() == ReplicaState.NORMAL, originReplica.getState());
                        // replica's init state is ALTER, so that tablet report process will ignore its report
                        Replica shadowReplica = new Replica(shadowReplicaId, backendId, ReplicaState.ALTER,
                                Partition.PARTITION_INIT_VERSION,
                                newSchemaHash);
                        shadowTablet.addReplica(shadowReplica);
                        healthyReplicaNum++;
                    }

                    if (healthyReplicaNum < replicationNum / 2 + 1) {
                        /*
                         * TODO(cmy): This is a bad design.
                         * Because in the schema change job, we will only send tasks to the shadow replicas that
                         * have been created, without checking whether the quorum of replica number are satisfied.
                         * This will cause the job to fail until we find that the quorum of replica number
                         * is not satisfied until the entire job is done.
                         * So here we check the replica number strictly and do not allow to submit the job
                         * if the quorum of replica number is not satisfied.
                         */
                        for (Tablet tablet : addedTablets) {
                            Catalog.getCurrentInvertedIndex().deleteTablet(tablet.getId());
                        }
                        throw new DdlException(
                                "tablet " + originTabletId + " has few healthy replica: " + healthyReplicaNum);
                    }
                }

                schemaChangeJob.addPartitionShadowIndex(partitionId, shadowIndexId, shadowIndex);
            } // end for partition
            schemaChangeJob.addIndexSchema(shadowIndexId, originIndexId, newIndexName, newSchemaVersion, newSchemaHash,
                    newShortKeyColumnCount, entry.getValue());
        } // end for index

        // set table state
        olapTable.setState(OlapTableState.SCHEMA_CHANGE);

        // 2. add schemaChangeJob
        addAlterJobV2(schemaChangeJob);

        // 3. write edit log
        Catalog.getCurrentCatalog().getEditLog().logAlterJob(schemaChangeJob);
        LOG.info("finished to create schema change job: {}", schemaChangeJob.getJobId());
    }

    @Override
    protected void runAfterCatalogReady() {
        super.runAfterCatalogReady();
        runOldAlterJob();
        runAlterJobV2();
    }

    private void runAlterJobV2() {
        alterJobsV2.values().forEach(AlterJobV2::run);
    }

    @Deprecated
    private void runOldAlterJob() {
        List<AlterJob> cancelledJobs = Lists.newArrayList();
        List<AlterJob> finishedJobs = Lists.newArrayList();

        for (AlterJob alterJob : alterJobs.values()) {
            SchemaChangeJob schemaChangeJob = (SchemaChangeJob) alterJob;
            if (schemaChangeJob.getState() != JobState.FINISHING
                    && schemaChangeJob.getState() != JobState.FINISHED
                    && schemaChangeJob.getState() != JobState.CANCELLED) {
                // cancel the old alter table job
                cancelledJobs.add(schemaChangeJob);
                continue;
            }
            // it means this is an old type job and current version is real time load version
            // then kill this job
            if (alterJob.getTransactionId() < 0) {
                cancelledJobs.add(alterJob);
                continue;
            }
            JobState state = alterJob.getState();
            switch (state) {
                case PENDING: {
                    if (!alterJob.sendTasks()) {
                        cancelledJobs.add(alterJob);
                        LOG.warn("sending schema change job {} tasks failed. cancel it.", alterJob.getTableId());
                    }
                    break;
                }
                case RUNNING: {
                    if (alterJob.isTimeout()) {
                        cancelledJobs.add(alterJob);
                    } else {
                        int res = alterJob.tryFinishJob();
                        if (res == -1) {
                            cancelledJobs.add(alterJob);
                            LOG.warn("cancel bad schema change job[{}]", alterJob.getTableId());
                        }
                    }
                    break;
                }
                case FINISHING: {
                    // check if previous load job finished
                    if (alterJob.isPreviousLoadFinished()) {
                        LOG.info("schema change job has finished, send clear tasks to all be {}", alterJob);
                        // if all previous load job finished, then send clear alter tasks to all related be
                        int res = schemaChangeJob.checkOrResendClearTasks();
                        if (res != 0) {
                            if (res == -1) {
                                LOG.warn("schema change job is in finishing state,but could not finished, "
                                        + "just finish it, maybe a fatal error {}", alterJob);
                            } else {
                                LOG.info("send clear tasks to all be for job [{}] successfully, "
                                        + "set status to finished", alterJob);
                            }

                            finishedJobs.add(alterJob);
                        }
                    } else {
                        LOG.info("previous load jobs are not finished. can not finish schema change job: {}",
                                alterJob.getTableId());
                    }
                    break;
                }
                case FINISHED: {
                    break;
                }
                case CANCELLED: {
                    // the alter job could be cancelled in 3 ways
                    // 1. the table or db is dropped
                    // 2. user cancels the job
                    // 3. the job meets errors when running
                    // for the previous 2 scenarios, user will call jobdone to finish the job and set its state to cancelled
                    // so that there exists alter job whose state is cancelled
                    // for the third scenario, the thread will add to cancelled job list and will be dealt by call jobdone
                    // Preconditions.checkState(false);
                    break;
                }
                default:
                    Preconditions.checkState(false);
                    break;
            }
        } // end for jobs

        // handle cancelled schema change jobs
        for (AlterJob alterJob : cancelledJobs) {
            Database db = Catalog.getCurrentCatalog().getDb(alterJob.getDbId());
            if (db == null) {
                cancelInternal(alterJob, null, null);
                continue;
            }

            db.writeLock();
            try {
                OlapTable olapTable = (OlapTable) db.getTable(alterJob.getTableId());
                alterJob.cancel(olapTable, "cancelled");
            } finally {
                db.writeUnlock();
            }
            jobDone(alterJob);
        }

        // handle finished schema change jobs
        for (AlterJob alterJob : finishedJobs) {
            alterJob.setState(JobState.FINISHED);
            // has to remove here, because check is running every interval, it maybe finished but also in job list
            // some check will failed
            ((SchemaChangeJob) alterJob).deleteAllTableHistorySchema();
            ((SchemaChangeJob) alterJob).finishJob();
            jobDone(alterJob);
            Catalog.getCurrentCatalog().getEditLog().logFinishSchemaChange((SchemaChangeJob) alterJob);
        }
    }

    @Override
    public List<List<Comparable>> getAlterJobInfosByDb(Database db) {
        List<List<Comparable>> schemaChangeJobInfos = new LinkedList<>();
        getOldAlterJobInfos(db, schemaChangeJobInfos);
        getAlterJobV2Infos(db, schemaChangeJobInfos);

        // sort by "JobId", "PartitionName", "CreateTime", "FinishTime", "IndexName", "IndexState"
        ListComparator<List<Comparable>> comparator = new ListComparator<List<Comparable>>(0, 1, 2, 3, 4, 5);
        schemaChangeJobInfos.sort(comparator);
        return schemaChangeJobInfos;
    }

    private void getAlterJobV2Infos(Database db, List<AlterJobV2> alterJobsV2,
                                    List<List<Comparable>> schemaChangeJobInfos) {
        ConnectContext ctx = ConnectContext.get();
        for (AlterJobV2 alterJob : alterJobsV2) {
            if (alterJob.getDbId() != db.getId()) {
                continue;
            }
            if (ctx != null) {
                if (!Catalog.getCurrentCatalog().getAuth()
                        .checkTblPriv(ctx, db.getFullName(), alterJob.getTableName(), PrivPredicate.ALTER)) {
                    continue;
                }
            }
            alterJob.getInfo(schemaChangeJobInfos);
        }
    }

    private void getAlterJobV2Infos(Database db, List<List<Comparable>> schemaChangeJobInfos) {
        getAlterJobV2Infos(db, ImmutableList.copyOf(alterJobsV2.values()), schemaChangeJobInfos);
    }

    @Deprecated
    private void getOldAlterJobInfos(Database db, List<List<Comparable>> schemaChangeJobInfos) {
        List<AlterJob> selectedJobs = Lists.newArrayList();

        lock();
        try {
            // init or running
            for (AlterJob alterJob : this.alterJobs.values()) {
                if (alterJob.getDbId() == db.getId()) {
                    selectedJobs.add(alterJob);
                }
            }

            // finished or cancelled
            for (AlterJob alterJob : this.finishedOrCancelledAlterJobs) {
                if (alterJob.getDbId() == db.getId()) {
                    selectedJobs.add(alterJob);
                }
            }

        } finally {
            unlock();
        }

        db.readLock();
        try {
            for (AlterJob selectedJob : selectedJobs) {
                OlapTable olapTable = (OlapTable) db.getTable(selectedJob.getTableId());
                if (olapTable == null) {
                    continue;
                }
                selectedJob.getJobInfo(schemaChangeJobInfos, olapTable);
            }
        } finally {
            db.readUnlock();
        }
    }

    @Override
    public void process(List<AlterClause> alterClauses, String clusterName, Database db, OlapTable olapTable)
            throws UserException {

        // index id -> index schema
        Map<Long, LinkedList<Column>> indexSchemaMap = new HashMap<>();
        for (Map.Entry<Long, List<Column>> entry : olapTable.getIndexIdToSchema().entrySet()) {
            indexSchemaMap.put(entry.getKey(), new LinkedList<>(entry.getValue()));
        }
        List<Index> newIndexes = olapTable.getCopiedIndexes();
        Map<String, String> propertyMap = new HashMap<>();
        for (AlterClause alterClause : alterClauses) {
            Map<String, String> properties = alterClause.getProperties();
            if (properties != null) {
                if (propertyMap.isEmpty()) {
                    propertyMap.putAll(properties);
                } else {
                    throw new DdlException("reduplicated PROPERTIES");
                }

                // modification of colocate property is handle alone.
                // And because there should be only one colocate property modification clause in stmt,
                // so just return after finished handling.
                if (properties.containsKey(PropertyAnalyzer.PROPERTIES_COLOCATE_WITH)) {
                    String colocateGroup = properties.get(PropertyAnalyzer.PROPERTIES_COLOCATE_WITH);
                    Catalog.getCurrentCatalog().modifyTableColocate(db, olapTable, colocateGroup, false, null);
                    return;
                } else if (properties.containsKey(PropertyAnalyzer.PROPERTIES_DISTRIBUTION_TYPE)) {
                    Catalog.getCurrentCatalog().convertDistributionType(db, olapTable);
                    return;
                } else if (properties.containsKey(PropertyAnalyzer.PROPERTIES_SEND_CLEAR_ALTER_TASK)) {
                    /*
                     * This is only for fixing bug when upgrading StarRocks from 0.9.x to 0.10.x.
                     */
                    sendClearAlterTask(db, olapTable);
                    return;
                } else if (DynamicPartitionUtil.checkDynamicPartitionPropertiesExist(properties)) {
                    if (!olapTable.dynamicPartitionExists()) {
                        try {
                            DynamicPartitionUtil
                                    .checkInputDynamicPartitionProperties(properties, olapTable.getPartitionInfo());
                        } catch (DdlException e) {
                            // This table is not a dynamic partition table and didn't supply all dynamic partition properties
                            throw new DdlException("Table " + db.getFullName() + "." +
                                    olapTable.getName() +
                                    " is not a dynamic partition table. Use command `HELP ALTER TABLE` " +
                                    "to see how to change a normal table to a dynamic partition table.");
                        }
                    }
                    Catalog.getCurrentCatalog().modifyTableDynamicPartition(db, olapTable, properties);
                    return;
                } else if (properties.containsKey("default." + PropertyAnalyzer.PROPERTIES_REPLICATION_NUM)) {
                    Preconditions.checkNotNull(properties.get(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM));
                    Catalog.getCurrentCatalog().modifyTableDefaultReplicationNum(db, olapTable, properties);
                    return;
                } else if (properties.containsKey(PropertyAnalyzer.PROPERTIES_REPLICATION_NUM)) {
                    Catalog.getCurrentCatalog().modifyTableReplicationNum(db, olapTable, properties);
                    return;
                }
            }

            // the following operations can not be done when there are temp partitions exist.
            if (olapTable.existTempPartitions()) {
                throw new DdlException("Can not alter table when there are temp partitions in table");
            }

            if (alterClause instanceof AddColumnClause) {
                // add column
                processAddColumn((AddColumnClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof AddColumnsClause) {
                // add columns
                processAddColumns((AddColumnsClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof DropColumnClause) {
                // drop column and drop indexes on this column
                processDropColumn((DropColumnClause) alterClause, olapTable, indexSchemaMap, newIndexes);
            } else if (alterClause instanceof ModifyColumnClause) {
                // modify column
                processModifyColumn((ModifyColumnClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof ReorderColumnsClause) {
                // reorder column
                if (olapTable.getKeysType() == KeysType.PRIMARY_KEYS) {
                    throw new DdlException("Primary key table do not support reorder column");
                }
                processReorderColumn((ReorderColumnsClause) alterClause, olapTable, indexSchemaMap);
            } else if (alterClause instanceof ModifyTablePropertiesClause) {
                // modify table properties
                // do nothing, properties are already in propertyMap
            } else if (alterClause instanceof CreateIndexClause) {
                processAddIndex((CreateIndexClause) alterClause, olapTable, newIndexes);
            } else if (alterClause instanceof DropIndexClause) {
                processDropIndex((DropIndexClause) alterClause, olapTable, newIndexes);
            } else {
                Preconditions.checkState(false);
            }
        } // end for alter clauses

        createJob(db.getId(), olapTable, indexSchemaMap, propertyMap, newIndexes);
    }

    private void sendClearAlterTask(Database db, OlapTable olapTable) {
        AgentBatchTask batchTask = new AgentBatchTask();
        db.readLock();
        try {
            for (Partition partition : olapTable.getPartitions()) {
                for (MaterializedIndex index : partition.getMaterializedIndices(IndexExtState.VISIBLE)) {
                    int schemaHash = olapTable.getSchemaHashByIndexId(index.getId());
                    for (Tablet tablet : index.getTablets()) {
                        for (Replica replica : ((LocalTablet) tablet).getReplicas()) {
                            ClearAlterTask alterTask = new ClearAlterTask(replica.getBackendId(), db.getId(),
                                    olapTable.getId(), partition.getId(), index.getId(), tablet.getId(), schemaHash);
                            batchTask.addTask(alterTask);
                        }
                    }
                }
            }
        } finally {
            db.readUnlock();
        }

        AgentTaskExecutor.submit(batchTask);
        LOG.info("send clear alter task for table {}, number: {}", olapTable.getName(), batchTask.getTaskNum());
    }

    public void updateTableMeta(Database db, String tableName, Map<String, String> properties,
                                TTabletMetaType metaType)
            throws DdlException {
        List<Partition> partitions = Lists.newArrayList();
        OlapTable olapTable;
        db.readLock();
        try {
            olapTable = (OlapTable) db.getTable(tableName);
            partitions.addAll(olapTable.getPartitions());
        } finally {
            db.readUnlock();
        }

        boolean metaValue = false;
        if (metaType == TTabletMetaType.INMEMORY) {
            metaValue = Boolean.parseBoolean(properties.get(PropertyAnalyzer.PROPERTIES_INMEMORY));
            if (metaValue == olapTable.isInMemory()) {
                return;
            }
        } else if (metaType == TTabletMetaType.ENABLE_PERSISTENT_INDEX) {
            metaValue = Boolean.parseBoolean(properties.get(PropertyAnalyzer.PROPERTIES_ENABLE_PERSISTENT_INDEX));
            if (metaValue == olapTable.enablePersistentIndex()) {
                return;
            }
        } else {
            LOG.warn("meta type: {} does not support", metaType);
            return;
        }

        for (Partition partition : partitions) {
            updatePartitionTabletMeta(db, olapTable.getName(), partition.getName(), metaValue, metaType);
        }

        db.writeLock();
        try {
            Catalog.getCurrentCatalog().modifyTableMeta(db, olapTable, properties, metaType);
        } finally {
            db.writeUnlock();
        }
    }

    /**
     * Update some specified partitions' in-memory property of table
     */
    public void updatePartitionsInMemoryMeta(Database db,
                                             String tableName,
                                             List<String> partitionNames,
                                             Map<String, String> properties) throws DdlException {
        OlapTable olapTable;
        db.readLock();
        try {
            olapTable = (OlapTable) db.getTable(tableName);
            for (String partitionName : partitionNames) {
                Partition partition = olapTable.getPartition(partitionName);
                if (partition == null) {
                    throw new DdlException("Partition[" + partitionName + "] does not exist in " +
                            "table[" + olapTable.getName() + "]");
                }
            }
        } finally {
            db.readUnlock();
        }

        boolean isInMemory = Boolean.parseBoolean(properties.get(PropertyAnalyzer.PROPERTIES_INMEMORY));
        if (isInMemory == olapTable.isInMemory()) {
            return;
        }

        for (String partitionName : partitionNames) {
            try {
                //updatePartitionInMemoryMeta(db, olapTable.getName(), partitionName, isInMemory);
                updatePartitionTabletMeta(db, olapTable.getName(), partitionName, isInMemory, TTabletMetaType.INMEMORY);
            } catch (Exception e) {
                String errMsg = "Failed to update partition[" + partitionName + "]'s 'in_memory' property. " +
                        "The reason is [" + e.getMessage() + "]";
                throw new DdlException(errMsg);
            }
        }
    }

    /**
     * Update one specified partition's in-memory property by partition name of table
     * This operation may return partial successfully, with a exception to inform user to retry
     */
    public void updatePartitionTabletMeta(Database db,
                                          String tableName,
                                          String partitionName,
                                          boolean metaValue,
                                          TTabletMetaType metaType) throws DdlException {
        // be id -> <tablet id,schemaHash>
        Map<Long, Set<Pair<Long, Integer>>> beIdToTabletIdWithHash = Maps.newHashMap();
        db.readLock();
        try {
            OlapTable olapTable = (OlapTable) db.getTable(tableName);
            Partition partition = olapTable.getPartition(partitionName);
            if (partition == null) {
                throw new DdlException(
                        "Partition[" + partitionName + "] does not exist in table[" + olapTable.getName() + "]");
            }

            for (MaterializedIndex index : partition.getMaterializedIndices(IndexExtState.VISIBLE)) {
                int schemaHash = olapTable.getSchemaHashByIndexId(index.getId());
                for (Tablet tablet : index.getTablets()) {
                    for (Replica replica : ((LocalTablet) tablet).getReplicas()) {
                        Set<Pair<Long, Integer>> tabletIdWithHash =
                                beIdToTabletIdWithHash.computeIfAbsent(replica.getBackendId(), k -> Sets.newHashSet());
                        tabletIdWithHash.add(new Pair<>(tablet.getId(), schemaHash));
                    }
                }
            }
        } finally {
            db.readUnlock();
        }

        int totalTaskNum = beIdToTabletIdWithHash.keySet().size();
        MarkedCountDownLatch<Long, Set<Pair<Long, Integer>>> countDownLatch = new MarkedCountDownLatch<>(totalTaskNum);
        AgentBatchTask batchTask = new AgentBatchTask();
        for (Map.Entry<Long, Set<Pair<Long, Integer>>> kv : beIdToTabletIdWithHash.entrySet()) {
            countDownLatch.addMark(kv.getKey(), kv.getValue());
            UpdateTabletMetaInfoTask task = new UpdateTabletMetaInfoTask(kv.getKey(), kv.getValue(),
                    metaValue, countDownLatch, metaType);
            batchTask.addTask(task);
        }
        if (!FeConstants.runningUnitTest) {
            // send all tasks and wait them finished
            AgentTaskQueue.addBatchTask(batchTask);
            AgentTaskExecutor.submit(batchTask);
            LOG.info("send update tablet meta task for table {}, partitions {}, number: {}",
                    tableName, partitionName, batchTask.getTaskNum());

            // estimate timeout
            long timeout = Config.tablet_create_timeout_second * 1000L * totalTaskNum;
            timeout = Math.min(timeout, Config.max_create_table_timeout_second * 1000L);
            boolean ok = false;
            try {
                ok = countDownLatch.await(timeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                LOG.warn("InterruptedException: ", e);
            }

            if (!ok || !countDownLatch.getStatus().ok()) {
                String errMsg = "Failed to update partition[" + partitionName + "]. tablet meta.";
                // clear tasks
                AgentTaskQueue.removeBatchTask(batchTask, TTaskType.UPDATE_TABLET_META_INFO);

                if (!countDownLatch.getStatus().ok()) {
                    errMsg += " Error: " + countDownLatch.getStatus().getErrorMsg();
                } else {
                    List<Map.Entry<Long, Set<Pair<Long, Integer>>>> unfinishedMarks = countDownLatch.getLeftMarks();
                    // only show at most 3 results
                    List<Map.Entry<Long, Set<Pair<Long, Integer>>>> subList =
                            unfinishedMarks.subList(0, Math.min(unfinishedMarks.size(), 3));
                    if (!subList.isEmpty()) {
                        errMsg += " Unfinished mark: " + Joiner.on(", ").join(subList);
                    }
                }
                errMsg += ". This operation maybe partial successfully, You should retry until success.";
                LOG.warn(errMsg);
                throw new DdlException(errMsg);
            }
        }
    }

    @Override
    public void cancel(CancelStmt stmt) throws DdlException {
        CancelAlterTableStmt cancelAlterTableStmt = (CancelAlterTableStmt) stmt;

        String dbName = cancelAlterTableStmt.getDbName();
        String tableName = cancelAlterTableStmt.getTableName();
        Preconditions.checkState(!Strings.isNullOrEmpty(dbName));
        Preconditions.checkState(!Strings.isNullOrEmpty(tableName));

        Database db = Catalog.getCurrentCatalog().getDb(dbName);
        if (db == null) {
            throw new DdlException("Database[" + dbName + "] does not exist");
        }

        AlterJob schemaChangeJob = null;
        AlterJobV2 schemaChangeJobV2 = null;
        db.writeLock();
        try {
            Table table = db.getTable(tableName);
            if (table == null) {
                ErrorReport.reportDdlException(ErrorCode.ERR_BAD_TABLE_ERROR, tableName);
            }
            if (!(table instanceof OlapTable)) {
                ErrorReport.reportDdlException(ErrorCode.ERR_NOT_OLAP_TABLE, tableName);
            }
            OlapTable olapTable = (OlapTable) table;
            if (olapTable.getState() != OlapTableState.SCHEMA_CHANGE) {
                throw new DdlException("Table[" + tableName + "] is not under SCHEMA_CHANGE.");
            }

            // find from new alter jobs first
            List<AlterJobV2> schemaChangeJobV2List = getUnfinishedAlterJobV2ByTableId(olapTable.getId());
            // current schemaChangeJob job doesn't support batch operation,so just need to get one job
            schemaChangeJobV2 =
                    schemaChangeJobV2List.size() == 0 ? null : Iterables.getOnlyElement(schemaChangeJobV2List);
            if (schemaChangeJobV2 == null) {
                schemaChangeJob = getAlterJob(olapTable.getId());
                Preconditions.checkNotNull(schemaChangeJob, olapTable.getId());
                if (schemaChangeJob.getState() == JobState.FINISHING
                        || schemaChangeJob.getState() == JobState.FINISHED
                        || schemaChangeJob.getState() == JobState.CANCELLED) {
                    throw new DdlException(
                            "job is already " + schemaChangeJob.getState().name() + ", can not cancel it");
                }
                schemaChangeJob.cancel(olapTable, "user cancelled");
            }
        } finally {
            db.writeUnlock();
        }

        // alter job v2's cancel must be called outside the database lock
        if (schemaChangeJobV2 != null) {
            if (!schemaChangeJobV2.cancel("user cancelled")) {
                throw new DdlException("Job can not be cancelled. State: " + schemaChangeJobV2.getJobState());
            }
            return;
        }

        // handle old alter job
        if (schemaChangeJob != null && schemaChangeJob.getState() == JobState.CANCELLED) {
            jobDone(schemaChangeJob);
        }
    }

    private void processAddIndex(CreateIndexClause alterClause, OlapTable olapTable, List<Index> newIndexes)
            throws UserException {
        if (alterClause.getIndex() == null) {
            return;
        }

        List<Index> existedIndexes = olapTable.getIndexes();
        IndexDef indexDef = alterClause.getIndexDef();
        Set<String> newColset = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
        newColset.addAll(indexDef.getColumns());
        for (Index existedIdx : existedIndexes) {
            if (existedIdx.getIndexName().equalsIgnoreCase(indexDef.getIndexName())) {
                throw new DdlException("index `" + indexDef.getIndexName() + "` already exist.");
            }
            Set<String> existedIdxColSet = Sets.newTreeSet(String.CASE_INSENSITIVE_ORDER);
            existedIdxColSet.addAll(existedIdx.getColumns());
            if (newColset.equals(existedIdxColSet)) {
                throw new DdlException(
                        "index for columns (" + String.join(",", indexDef.getColumns()) + " ) already exist.");
            }
        }

        for (String col : indexDef.getColumns()) {
            Column column = olapTable.getColumn(col);
            if (column != null) {
                indexDef.checkColumn(column, olapTable.getKeysType());
            } else {
                throw new DdlException("BITMAP column does not exist in table. invalid column: " + col);
            }
        }

        newIndexes.add(alterClause.getIndex());
    }

    private void processDropIndex(DropIndexClause alterClause, OlapTable olapTable, List<Index> indexes)
            throws DdlException {
        String indexName = alterClause.getIndexName();
        List<Index> existedIndexes = olapTable.getIndexes();
        Index found = null;
        for (Index existedIdx : existedIndexes) {
            if (existedIdx.getIndexName().equalsIgnoreCase(indexName)) {
                found = existedIdx;
                break;
            }
        }
        if (found == null) {
            throw new DdlException("index " + indexName + " does not exist");
        }

        Iterator<Index> itr = indexes.iterator();
        while (itr.hasNext()) {
            Index idx = itr.next();
            if (idx.getIndexName().equalsIgnoreCase(alterClause.getIndexName())) {
                itr.remove();
                break;
            }
        }
    }
}
