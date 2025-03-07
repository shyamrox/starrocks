// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.optimizer;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.starrocks.analysis.JoinOperator;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.HiveMetaStoreTable;
import com.starrocks.catalog.IcebergTable;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.ScalarType;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.Type;
import com.starrocks.external.hive.HiveColumnStats;
import com.starrocks.external.iceberg.cost.IcebergTableStatisticCalculator;
import com.starrocks.sql.optimizer.base.DistributionProperty;
import com.starrocks.sql.optimizer.base.DistributionSpec;
import com.starrocks.sql.optimizer.base.HashDistributionDesc;
import com.starrocks.sql.optimizer.base.HashDistributionSpec;
import com.starrocks.sql.optimizer.base.PhysicalPropertySet;
import com.starrocks.sql.optimizer.operator.Operator;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.logical.LogicalApplyOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalHiveScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalHudiScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalIcebergScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalJoinOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOlapScanOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalOperator;
import com.starrocks.sql.optimizer.operator.logical.LogicalScanOperator;
import com.starrocks.sql.optimizer.operator.physical.PhysicalJoinOperator;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.optimizer.statistics.ColumnStatistic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class Utils {
    private static final Logger LOG = LogManager.getLogger(Utils.class);

    public static List<ScalarOperator> extractConjuncts(ScalarOperator root) {
        if (null == root) {
            return Lists.newArrayList();
        }

        LinkedList<ScalarOperator> list = new LinkedList<>();
        if (!OperatorType.COMPOUND.equals(root.getOpType())) {
            list.add(root);
            return list;
        }

        CompoundPredicateOperator cpo = (CompoundPredicateOperator) root;
        if (!cpo.isAnd()) {
            list.add(root);
            return list;
        }

        list.addAll(extractConjuncts(cpo.getChild(0)));
        list.addAll(extractConjuncts(cpo.getChild(1)));
        return list;
    }

    public static List<ScalarOperator> extractDisjunctive(ScalarOperator root) {
        if (null == root) {
            return Lists.newArrayList();
        }

        LinkedList<ScalarOperator> list = new LinkedList<>();
        if (!OperatorType.COMPOUND.equals(root.getOpType())) {
            list.add(root);
            return list;
        }

        CompoundPredicateOperator cpo = (CompoundPredicateOperator) root;

        if (cpo.isOr()) {
            list.addAll(extractDisjunctive(cpo.getChild(0)));
            list.addAll(extractDisjunctive(cpo.getChild(1)));
        } else {
            list.add(root);
        }
        return list;
    }

    public static List<ColumnRefOperator> extractColumnRef(ScalarOperator root) {
        if (null == root || !root.isVariable()) {
            return Collections.emptyList();
        }

        LinkedList<ColumnRefOperator> list = new LinkedList<>();
        if (OperatorType.VARIABLE.equals(root.getOpType())) {
            list.add((ColumnRefOperator) root);
            return list;
        }

        for (ScalarOperator child : root.getChildren()) {
            list.addAll(extractColumnRef(child));
        }

        return list;
    }

    public static int countColumnRef(ScalarOperator root) {
        return countColumnRef(root, 0);
    }

    private static int countColumnRef(ScalarOperator root, int count) {
        if (null == root || !root.isVariable()) {
            return 0;
        }

        if (OperatorType.VARIABLE.equals(root.getOpType())) {
            return 1;
        }

        for (ScalarOperator child : root.getChildren()) {
            count += countColumnRef(child, count);
        }

        return count;
    }

    public static void extractOlapScanOperator(GroupExpression groupExpression, List<LogicalOlapScanOperator> list) {
        extractOperator(groupExpression, list, p -> OperatorType.LOGICAL_OLAP_SCAN.equals(p.getOpType()));
    }

    private static <E extends Operator> void extractOperator(GroupExpression root, List<E> list,
                                                             Predicate<Operator> lambda) {
        if (lambda.test(root.getOp())) {
            list.add((E) root.getOp());
            return;
        }

        List<Group> groups = root.getInputs();
        for (Group group : groups) {
            GroupExpression expression = group.getFirstLogicalExpression();
            extractOperator(expression, list, lambda);
        }
    }

    // check the ApplyNode's children contains correlation subquery
    public static boolean containsCorrelationSubquery(GroupExpression groupExpression) {
        if (groupExpression.getOp().isLogical() && OperatorType.LOGICAL_APPLY
                .equals(groupExpression.getOp().getOpType())) {
            LogicalApplyOperator apply = (LogicalApplyOperator) groupExpression.getOp();

            if (apply.getCorrelationColumnRefs().isEmpty()) {
                return false;
            }

            // only check right child
            return checkPredicateContainColumnRef(apply.getCorrelationColumnRefs(),
                    groupExpression.getInputs().get(1).getFirstLogicalExpression());
        }
        return false;
    }

    // GroupExpression
    private static boolean checkPredicateContainColumnRef(List<ColumnRefOperator> cro,
                                                          GroupExpression groupExpression) {
        LogicalOperator logicalOperator = (LogicalOperator) groupExpression.getOp();

        if (containAnyColumnRefs(cro, logicalOperator.getPredicate())) {
            return true;
        }

        for (Group group : groupExpression.getInputs()) {
            if (checkPredicateContainColumnRef(cro, group.getFirstLogicalExpression())) {
                return true;
            }
        }

        return false;
    }

    public static boolean containAnyColumnRefs(List<ColumnRefOperator> refs, ScalarOperator operator) {
        if (refs.isEmpty() || null == operator) {
            return false;
        }

        if (operator.isColumnRef()) {
            return refs.contains(operator);
        }

        for (ScalarOperator so : operator.getChildren()) {
            if (containAnyColumnRefs(refs, so)) {
                return true;
            }
        }

        return false;
    }

    public static boolean containColumnRef(ScalarOperator operator, String column) {
        if (null == column || null == operator) {
            return false;
        }

        if (operator.isColumnRef()) {
            return ((ColumnRefOperator) operator).getName().equalsIgnoreCase(column);
        }

        for (ScalarOperator so : operator.getChildren()) {
            if (containColumnRef(so, column)) {
                return true;
            }
        }

        return false;
    }

    public static ScalarOperator compoundOr(List<ScalarOperator> nodes) {
        return createCompound(CompoundPredicateOperator.CompoundType.OR, nodes);
    }

    public static ScalarOperator compoundOr(ScalarOperator... nodes) {
        return createCompound(CompoundPredicateOperator.CompoundType.OR, Arrays.asList(nodes));
    }

    public static ScalarOperator compoundAnd(List<ScalarOperator> nodes) {
        return createCompound(CompoundPredicateOperator.CompoundType.AND, nodes);
    }

    public static ScalarOperator compoundAnd(ScalarOperator... nodes) {
        return createCompound(CompoundPredicateOperator.CompoundType.AND, Arrays.asList(nodes));
    }

    // Build a compound tree by bottom up
    //
    // Example: compoundType.OR
    // Initial state:
    //  a b c d e
    //
    // First iteration:
    //  or    or
    //  /\    /\   e
    // a  b  c  d
    //
    // Second iteration:
    //     or   e
    //    / \
    //  or   or
    //  /\   /\
    // a  b c  d
    //
    // Last iteration:
    //       or
    //      / \
    //     or  e
    //    / \
    //  or   or
    //  /\   /\
    // a  b c  d
    public static ScalarOperator createCompound(CompoundPredicateOperator.CompoundType type,
                                                List<ScalarOperator> nodes) {
        LinkedList<ScalarOperator> link =
                nodes.stream().filter(Objects::nonNull).collect(Collectors.toCollection(Lists::newLinkedList));

        if (link.size() < 1) {
            return null;
        }

        if (link.size() == 1) {
            return link.get(0);
        }

        while (link.size() > 1) {
            LinkedList<ScalarOperator> buffer = new LinkedList<>();

            // combine pairs of elements
            while (link.size() >= 2) {
                buffer.add(new CompoundPredicateOperator(type, link.poll(), link.poll()));
            }

            // if there's and odd number of elements, just append the last one
            if (!link.isEmpty()) {
                buffer.add(link.remove());
            }

            // continue processing the pairs that were just built
            link = buffer;
        }
        return link.remove();
    }

    public static boolean isInnerOrCrossJoin(Operator operator) {
        if (operator instanceof LogicalJoinOperator) {
            LogicalJoinOperator joinOperator = (LogicalJoinOperator) operator;
            return joinOperator.isInnerOrCrossJoin();
        }
        return false;
    }

    public static int countInnerJoinNodeSize(OptExpression root) {
        int count = 0;
        Operator operator = root.getOp();
        for (OptExpression child : root.getInputs()) {
            if (isInnerOrCrossJoin(operator)) {
                count += countInnerJoinNodeSize(child);
            } else {
                count = Math.max(count, countInnerJoinNodeSize(child));
            }
        }

        if (isInnerOrCrossJoin(operator)) {
            count += 1;
        }
        return count;
    }

    public static boolean capableSemiReorder(OptExpression root, boolean hasSemi, int joinNum, int maxJoin) {
        Operator operator = root.getOp();

        if (operator instanceof LogicalJoinOperator) {
            if (((LogicalJoinOperator) operator).getJoinType().isSemiAntiJoin()) {
                hasSemi = true;
            } else {
                joinNum = joinNum + 1;
            }

            if (joinNum > maxJoin && hasSemi) {
                return false;
            }
        }

        for (OptExpression child : root.getInputs()) {
            if (operator instanceof LogicalJoinOperator) {
                if (!capableSemiReorder(child, hasSemi, joinNum, maxJoin)) {
                    return false;
                }
            } else {
                if (!capableSemiReorder(child, false, 0, maxJoin)) {
                    return false;
                }
            }
        }

        return true;
    }

    public static boolean hasUnknownColumnsStats(OptExpression root) {
        Operator operator = root.getOp();
        if (operator instanceof LogicalScanOperator) {
            LogicalScanOperator scanOperator = (LogicalScanOperator) operator;
            List<String> colNames =
                    scanOperator.getColRefToColumnMetaMap().values().stream().map(Column::getName).collect(
                            Collectors.toList());
            if (operator instanceof LogicalOlapScanOperator) {
                Table table = scanOperator.getTable();
                if (table instanceof OlapTable) {
                    if (KeysType.AGG_KEYS.equals(((OlapTable) table).getKeysType())) {
                        List<String> keyColumnNames =
                                scanOperator.getColRefToColumnMetaMap().values().stream().filter(Column::isKey)
                                        .map(Column::getName)
                                        .collect(Collectors.toList());
                        List<ColumnStatistic> keyColumnStatisticList =
                                Catalog.getCurrentStatisticStorage().getColumnStatistics(table, keyColumnNames);
                        return keyColumnStatisticList.stream().anyMatch(ColumnStatistic::isUnknown);
                    }
                }
                List<ColumnStatistic> columnStatisticList =
                        Catalog.getCurrentStatisticStorage().getColumnStatistics(table, colNames);
                return columnStatisticList.stream().anyMatch(ColumnStatistic::isUnknown);
            } else if (operator instanceof LogicalHiveScanOperator || operator instanceof LogicalHudiScanOperator) {
                HiveMetaStoreTable hiveMetaStoreTable = (HiveMetaStoreTable) scanOperator.getTable();
                try {
                    Map<String, HiveColumnStats> hiveColumnStatisticMap =
                            hiveMetaStoreTable.getTableLevelColumnStats(colNames);
                    return hiveColumnStatisticMap.values().stream().anyMatch(HiveColumnStats::isUnknown);
                } catch (Exception e) {
                    LOG.warn(scanOperator.getTable().getType() + " table {} get column failed. error : {}",
                            scanOperator.getTable().getName(), e);
                    return true;
                }
            } else if (operator instanceof LogicalIcebergScanOperator) {
                IcebergTable table = (IcebergTable) scanOperator.getTable();
                try {
                    List<ColumnStatistic> columnStatisticList = IcebergTableStatisticCalculator.getColumnStatistics(
                            new ArrayList<>(), table.getIcebergTable(),
                            scanOperator.getColRefToColumnMetaMap());
                    return columnStatisticList.stream().anyMatch(ColumnStatistic::isUnknown);
                } catch (Exception e) {
                    LOG.warn("Iceberg table {} get column failed. error : {}", table.getName(), e);
                    return true;
                }
            } else {
                // For other scan operators, we do not know the column statistics.
                return true;
            }
        }

        return root.getInputs().stream().anyMatch(Utils::hasUnknownColumnsStats);
    }

    public static long getLongFromDateTime(LocalDateTime dateTime) {
        return dateTime.atZone(ZoneId.systemDefault()).toInstant().getEpochSecond();
    }

    public static LocalDateTime getDatetimeFromLong(long dateTime) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(dateTime), ZoneId.systemDefault());
    }

    public static long convertBitSetToLong(BitSet bitSet, int length) {
        long gid = 0;
        for (int b = 0; b < length; ++b) {
            gid = gid * 2 + (bitSet.get(b) ? 1 : 0);
        }
        return gid;
    }

    public static ColumnRefOperator findSmallestColumnRef(List<ColumnRefOperator> columnRefOperatorList) {
        Preconditions.checkState(!columnRefOperatorList.isEmpty());
        ColumnRefOperator smallestColumnRef = columnRefOperatorList.get(0);
        int smallestColumnLength = Integer.MAX_VALUE;
        for (ColumnRefOperator columnRefOperator : columnRefOperatorList) {
            Type columnType = columnRefOperator.getType();
            if (columnType.isScalarType()) {
                int columnLength = columnType.getTypeSize();
                if (columnLength < smallestColumnLength) {
                    smallestColumnRef = columnRefOperator;
                    smallestColumnLength = columnLength;
                }
            }
        }
        return smallestColumnRef;
    }

    public static boolean canDoReplicatedJoin(OlapTable table, long selectedIndexId,
                                              Collection<Long> selectedPartitionId,
                                              Collection<Long> selectedTabletId) {
        int backendSize = Catalog.getCurrentSystemInfo().backendSize();
        int aliveBackendSize = Catalog.getCurrentSystemInfo().getBackendIds(true).size();
        int schemaHash = table.getSchemaHashByIndexId(selectedIndexId);
        for (Long partitionId : selectedPartitionId) {
            Partition partition = table.getPartition(partitionId);
            if (partition.isUseStarOS()) {
                // TODO(wyb): necessary to support?
                return false;
            }
            if (table.getPartitionInfo().getReplicationNum(partitionId) < backendSize) {
                return false;
            }
            long visibleVersion = partition.getVisibleVersion();
            MaterializedIndex materializedIndex = partition.getIndex(selectedIndexId);
            // TODO(kks): improve this for loop
            for (Long id : selectedTabletId) {
                LocalTablet tablet = (LocalTablet) materializedIndex.getTablet(id);
                if (tablet != null && tablet.getQueryableReplicasSize(visibleVersion, schemaHash)
                        != aliveBackendSize) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean canOnlyDoBroadcast(PhysicalJoinOperator node,
                                             List<BinaryPredicateOperator> equalOnPredicate, String hint) {
        // Cross join only support broadcast join
        if (node.getJoinType().isCrossJoin() || JoinOperator.NULL_AWARE_LEFT_ANTI_JOIN.equals(node.getJoinType())
                || (node.getJoinType().isInnerJoin() && equalOnPredicate.isEmpty())
                || "BROADCAST".equalsIgnoreCase(hint)) {
            return true;
        }
        return false;
    }

    /**
     * Try cast op to descType, return empty if failed
     */
    public static Optional<ScalarOperator> tryCastConstant(ScalarOperator op, Type descType) {
        // Forbidden cast float, because behavior isn't same with before
        if (!op.isConstantRef() || op.getType().matchesType(descType) || Type.FLOAT.equals(op.getType())
                || descType.equals(Type.FLOAT)) {
            return Optional.empty();
        }

        try {
            if (((ConstantOperator) op).isNull()) {
                return Optional.of(ConstantOperator.createNull(descType));
            }

            ConstantOperator result = ((ConstantOperator) op).castToStrictly(descType);
            if (result.toString().equalsIgnoreCase(op.toString())) {
                return Optional.of(result);
            } else if (descType.isDate() && (op.getType().isIntegerType() || op.getType().isStringType())) {
                if (op.toString().equalsIgnoreCase(result.toString().replaceAll("-", ""))) {
                    return Optional.of(result);
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    // tryDecimalCastConstant is employed by ReduceCastRule to reduce BinaryPredicateOperator involving DecimalV3
    // ReduceCastRule try to reduce 'CAST(Expr<T> as U) BINOP LITERAL<S>' to
    // 'EXPR<T> BINOP CAST(LITERAL<S> as T>', only T->U casting and S->T casting are both legal, then this
    // reduction is legal, so for numerical types, S is not wider than T and T is not wider than U. for examples:
    //     CAST(IntLiteral(100,TINYINT) as DECIMAL32(9,9)) < IntLiteral(0x7f50, SMALLINT) cannot be reduced.
    //     CAST(IntLiteral(100,SMALLINT) as DECIMAL64(13,10)) < IntLiteral(101, TINYINT) can be reduced.
    public static Optional<ScalarOperator> tryDecimalCastConstant(CastOperator lhs, ConstantOperator rhs) {
        Type lhsType = lhs.getType();
        Type rhsType = rhs.getType();
        Type childType = lhs.getChild(0).getType();

        // Only handle Integer or DecimalV3 types
        if (!lhsType.isExactNumericType() ||
                !rhsType.isExactNumericType() ||
                !childType.isExactNumericType()) {
            return Optional.empty();
        }
        // Guarantee that both childType casting to lhsType and rhsType casting to childType are
        // lossless
        if (!Type.isAssignable2Decimal((ScalarType) lhsType, (ScalarType) childType) ||
                !Type.isAssignable2Decimal((ScalarType) childType, (ScalarType) rhsType)) {
            return Optional.empty();
        }

        if (rhs.isNull()) {
            return Optional.of(ConstantOperator.createNull(childType));
        }

        try {
            ConstantOperator result = rhs.castTo(childType);
            return Optional.of(result);
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    // Compute the required properties of shuffle join for children, adjust shuffle columns orders for
    // respect the required properties from parent.
    public static List<PhysicalPropertySet> computeShuffleJoinRequiredProperties(PhysicalPropertySet requiredFromParent,
                                                                                 List<Integer> leftShuffleColumns,
                                                                                 List<Integer> rightShuffleColumns) {
        Optional<HashDistributionDesc> requiredShuffleDescOptional =
                getShuffleJoinHashDistributionDesc(requiredFromParent);
        if (!requiredShuffleDescOptional.isPresent()) {
            // required property is not SHUFFLE_JOIN
            return createShuffleJoinRequiredProperties(leftShuffleColumns, rightShuffleColumns);
        } else {
            // required property type is SHUFFLE_JOIN, adjust the required property shuffle columns based on the column
            // order required by parent
            HashDistributionDesc requiredShuffleDesc = requiredShuffleDescOptional.get();
            boolean adjustBasedOnLeft = leftShuffleColumns.size() == requiredShuffleDesc.getColumns().size() &&
                    leftShuffleColumns.containsAll(requiredShuffleDesc.getColumns()) &&
                    requiredShuffleDesc.getColumns().containsAll(leftShuffleColumns);
            boolean adjustBasedOnRight = rightShuffleColumns.size() == requiredShuffleDesc.getColumns().size() &&
                    rightShuffleColumns.containsAll(requiredShuffleDesc.getColumns()) &&
                    requiredShuffleDesc.getColumns().containsAll(rightShuffleColumns);

            if (adjustBasedOnLeft || adjustBasedOnRight) {
                List<Integer> requiredLeft = Lists.newArrayList();
                List<Integer> requiredRight = Lists.newArrayList();

                for (Integer cid : requiredShuffleDesc.getColumns()) {
                    int idx = adjustBasedOnLeft ? leftShuffleColumns.indexOf(cid) :
                            rightShuffleColumns.indexOf(cid);
                    requiredLeft.add(leftShuffleColumns.get(idx));
                    requiredRight.add(rightShuffleColumns.get(idx));
                }
                return createShuffleJoinRequiredProperties(requiredLeft, requiredRight);
            } else {
                return createShuffleJoinRequiredProperties(leftShuffleColumns, rightShuffleColumns);
            }
        }
    }

    private static Optional<HashDistributionDesc> getShuffleJoinHashDistributionDesc(
            PhysicalPropertySet requiredPropertySet) {
        if (!requiredPropertySet.getDistributionProperty().isShuffle()) {
            return Optional.empty();
        }
        HashDistributionDesc requireDistributionDesc =
                ((HashDistributionSpec) requiredPropertySet.getDistributionProperty().getSpec())
                        .getHashDistributionDesc();
        if (!HashDistributionDesc.SourceType.SHUFFLE_JOIN.equals(requireDistributionDesc.getSourceType())) {
            return Optional.empty();
        }

        return Optional.of(requireDistributionDesc);
    }

    private static List<PhysicalPropertySet> createShuffleJoinRequiredProperties(List<Integer> leftColumns,
                                                                                 List<Integer> rightColumns) {
        HashDistributionSpec leftDistribution = DistributionSpec.createHashDistributionSpec(
                new HashDistributionDesc(leftColumns, HashDistributionDesc.SourceType.SHUFFLE_JOIN));
        HashDistributionSpec rightDistribution = DistributionSpec.createHashDistributionSpec(
                new HashDistributionDesc(rightColumns, HashDistributionDesc.SourceType.SHUFFLE_JOIN));

        PhysicalPropertySet leftRequiredPropertySet =
                new PhysicalPropertySet(new DistributionProperty(leftDistribution));
        PhysicalPropertySet rightRequiredPropertySet =
                new PhysicalPropertySet(new DistributionProperty(rightDistribution));

        return Lists.newArrayList(leftRequiredPropertySet, rightRequiredPropertySet);
    }
}
