// This file is licensed under the Elastic License 2.0. Copyright 2021-present, StarRocks Limited.

package com.starrocks.sql.optimizer.rule.transformation;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.common.AnalysisException;
import com.starrocks.planner.PartitionPruner;
import com.starrocks.sql.optimizer.Utils;
import com.starrocks.sql.optimizer.operator.scalar.BinaryPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.CastOperator;
import com.starrocks.sql.optimizer.operator.scalar.ColumnRefOperator;
import com.starrocks.sql.optimizer.operator.scalar.CompoundPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ConstantOperator;
import com.starrocks.sql.optimizer.operator.scalar.InPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.IsNullPredicateOperator;
import com.starrocks.sql.optimizer.operator.scalar.ScalarOperator;
import com.starrocks.sql.plan.ScalarOperatorToExpr;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

public class ListPartitionPruner implements PartitionPruner {
    private static final Logger LOG = LogManager.getLogger(ListPartitionPruner.class);

    // example:
    // partition keys                    partition id
    // date_col=2021-01-01/int_col=0     0
    // date_col=2021-01-01/int_col=1     1
    // date_col=2021-01-01/int_col=2     2
    // date_col=2021-01-02/int_col=0     3
    // date_col=2021-01-02/int_col=null  4
    // date_col=null/int_col=1           5

    // partitionColumnName -> (LiteralExpr -> partitionIds)
    // no null partitions in this map
    //
    // "date_col" -> (2021-01-01 -> set(0,1,2),
    //                2021-01-02 -> set(3,4))
    // "int_col"  -> (0 -> set(0,3),
    //                1 -> set(1,5),
    //                2 -> set(2))

    private final Map<ColumnRefOperator, TreeMap<LiteralExpr, Set<Long>>> columnToPartitionValuesMap;
    // Store partitions with null partition values separately
    // partitionColumnName -> null partitionIds
    //
    // "date_col" -> set(5)
    // "int_col"  -> set(4)
    private final Map<ColumnRefOperator, Set<Long>> columnToNullPartitions;
    private final List<ScalarOperator> partitionConjuncts;
    // Conjuncts that not eval in partition pruner, and will be sent to backend.
    private final List<ScalarOperator> noEvalConjuncts = Lists.newArrayList();

    private final Set<Long> allPartitions;
    private final List<ColumnRefOperator> partitionColumnRefs;

    public ListPartitionPruner(Map<ColumnRefOperator, TreeMap<LiteralExpr, Set<Long>>> columnToPartitionValuesMap,
                               Map<ColumnRefOperator, Set<Long>> columnToNullPartitions,
                               List<ScalarOperator> partitionConjuncts) {
        this.columnToPartitionValuesMap = columnToPartitionValuesMap;
        this.columnToNullPartitions = columnToNullPartitions;
        this.partitionConjuncts = partitionConjuncts;
        this.allPartitions = getAllPartitions();
        this.partitionColumnRefs = getPartitionColumnRefs();
    }

    private Set<Long> getAllPartitions() {
        Set<Long> allPartitions = Sets.newHashSet();
        for (TreeMap<LiteralExpr, Set<Long>> partitionValuesMap : columnToPartitionValuesMap.values()) {
            for (Set<Long> partitions : partitionValuesMap.values()) {
                allPartitions.addAll(partitions);
            }
        }
        for (Set<Long> partitions : columnToNullPartitions.values()) {
            allPartitions.addAll(partitions);
        }
        return allPartitions;
    }

    private List<ColumnRefOperator> getPartitionColumnRefs() {
        List<ColumnRefOperator> partitionColumnRefOperators = Lists.newArrayList();
        partitionColumnRefOperators.addAll(columnToPartitionValuesMap.keySet());
        return partitionColumnRefOperators;
    }

    public List<ScalarOperator> getNoEvalConjuncts() {
        return noEvalConjuncts;
    }

    /**
     * Return a list of partitions left after applying the conjuncts on partition columns.
     * Null is returned if all partitions.
     * An empty set is returned if no match partitions.
     */
    @Override
    public List<Long> prune() throws AnalysisException {
        Preconditions.checkNotNull(columnToPartitionValuesMap);
        Preconditions.checkNotNull(columnToNullPartitions);
        Preconditions.checkArgument(columnToPartitionValuesMap.size() == columnToNullPartitions.size());
        Preconditions.checkNotNull(partitionConjuncts);
        if (columnToPartitionValuesMap.isEmpty() && columnToNullPartitions.isEmpty()) {
            // no partition columns, notEvalConjuncts is same with conjuncts
            noEvalConjuncts.addAll(partitionConjuncts);
            return null;
        }
        if (partitionConjuncts.isEmpty()) {
            // no conjuncts, notEvalConjuncts is empty
            return null;
        }

        Set<Long> matches = null;
        for (ScalarOperator operator : partitionConjuncts) {
            List<ColumnRefOperator> columnRefOperatorList = Utils.extractColumnRef(operator);
            if (columnRefOperatorList.retainAll(this.partitionColumnRefs)) {
                noEvalConjuncts.add(operator);
                continue;
            }

            Set<Long> conjunctMatches = evalPartitionPruneFilter(operator);
            LOG.debug("prune by expr: {}, partitions: {}", operator.toString(), conjunctMatches);
            if (conjunctMatches != null) {
                if (matches == null) {
                    matches = Sets.newHashSet(conjunctMatches);
                } else {
                    matches.retainAll(conjunctMatches);
                }
            } else {
                noEvalConjuncts.add(operator);
            }
        }
        if (matches == null) {
            return null;
        } else {
            return new ArrayList<>(matches);
        }
    }

    private Set<Long> evalPartitionPruneFilter(ScalarOperator operator) {
        if (operator instanceof BinaryPredicateOperator) {
            return evalBinaryPredicate((BinaryPredicateOperator) operator);
        } else if (operator instanceof InPredicateOperator) {
            return evalInPredicate((InPredicateOperator) operator);
        } else if (operator instanceof IsNullPredicateOperator) {
            return evalIsNullPredicate((IsNullPredicateOperator) operator);
        } else if (operator instanceof CompoundPredicateOperator) {
            return evalCompoundPredicate((CompoundPredicateOperator) operator);
        }
        return null;
    }

    private boolean isSinglePartitionColumn(ScalarOperator predicate) {
        List<ColumnRefOperator> columnRefOperatorList = Utils.extractColumnRef(predicate);
        if (columnRefOperatorList.size() == 1 && partitionColumnRefs.contains(columnRefOperatorList.get(0))) {
            // such int_part_column + 1 = 11 can't prune partition
            if (predicate.getChild(0).isColumnRef() ||
                    (predicate.getChild(0) instanceof CastOperator &&
                            predicate.getChild(0).getChild(0).isColumnRef())) {
                return true;
            }
        }
        return false;
    }

    private Set<Long> evalBinaryPredicate(BinaryPredicateOperator binaryPredicate) {
        Preconditions.checkNotNull(binaryPredicate);
        ScalarOperator left = binaryPredicate.getChild(0);
        ScalarOperator right = binaryPredicate.getChild(1);

        if (!(right.isConstantRef())) {
            return null;
        }
        ConstantOperator rightChild = (ConstantOperator) binaryPredicate.getChild(1);

        if (!isSinglePartitionColumn(binaryPredicate)) {
            return null;
        }
        ColumnRefOperator leftChild = Utils.extractColumnRef(binaryPredicate).get(0);

        Set<Long> matches = Sets.newHashSet();
        TreeMap<LiteralExpr, Set<Long>> partitionValueMap = columnToPartitionValuesMap.get(leftChild);
        Set<Long> nullPartitions = columnToNullPartitions.get(leftChild);
        if (partitionValueMap == null || nullPartitions == null) {
            return null;
        }

        ScalarOperatorToExpr.FormatterContext formatterContext =
                new ScalarOperatorToExpr.FormatterContext(new HashMap<>());
        LiteralExpr literal = (LiteralExpr) ScalarOperatorToExpr.buildExecExpression(rightChild, formatterContext);

        BinaryPredicateOperator.BinaryType type = binaryPredicate.getBinaryType();
        switch (type) {
            case EQ:
                // SlotRef = Literal
                if (partitionValueMap.containsKey(literal)) {
                    matches.addAll(partitionValueMap.get(literal));
                }
                return matches;
            case EQ_FOR_NULL:
                // SlotRef <=> Literal
                if (Expr.IS_NULL_LITERAL.apply(literal)) {
                    // null
                    matches.addAll(nullPartitions);
                } else {
                    // same as EQ
                    if (partitionValueMap.containsKey(literal)) {
                        matches.addAll(partitionValueMap.get(literal));
                    }
                }
                return matches;
            case NE:
                // SlotRef != Literal
                matches.addAll(allPartitions);
                // remove null partitions
                matches.removeAll(nullPartitions);
                // remove partition matches literal
                if (partitionValueMap.containsKey(literal)) {
                    matches.removeAll(partitionValueMap.get(literal));
                }
                return matches;
            case LE:
            case LT:
            case GE:
            case GT:
                NavigableMap<LiteralExpr, Set<Long>> rangeValueMap = null;
                LiteralExpr firstKey = partitionValueMap.firstKey();
                LiteralExpr lastKey = partitionValueMap.lastKey();
                boolean upperInclusive = false;
                boolean lowerInclusive = false;
                LiteralExpr upperBoundKey = null;
                LiteralExpr lowerBoundKey = null;

                if (type == BinaryPredicateOperator.BinaryType.LE || type == BinaryPredicateOperator.BinaryType.LT) {
                    // SlotRef <[=] Literal
                    if (literal.compareLiteral(firstKey) < 0) {
                        return Sets.newHashSet();
                    }
                    if (type == BinaryPredicateOperator.BinaryType.LE) {
                        upperInclusive = true;
                    }
                    if (literal.compareLiteral(lastKey) <= 0) {
                        upperBoundKey = literal;
                    } else {
                        upperBoundKey = lastKey;
                        upperInclusive = true;
                    }
                    lowerBoundKey = firstKey;
                    lowerInclusive = true;
                } else {
                    // SlotRef >[=] Literal
                    if (literal.compareLiteral(lastKey) > 0) {
                        return Sets.newHashSet();
                    }
                    if (type == BinaryPredicateOperator.BinaryType.GE) {
                        lowerInclusive = true;
                    }
                    if (literal.compareLiteral(firstKey) >= 0) {
                        lowerBoundKey = literal;
                    } else {
                        lowerBoundKey = firstKey;
                        lowerInclusive = true;
                    }
                    upperBoundKey = lastKey;
                    upperInclusive = true;
                }

                rangeValueMap = partitionValueMap.subMap(lowerBoundKey, lowerInclusive, upperBoundKey, upperInclusive);
                for (Set<Long> partitions : rangeValueMap.values()) {
                    if (partitions != null) {
                        matches.addAll(partitions);
                    }
                }
                return matches;
            default:
                break;
        }
        return null;
    }

    private Set<Long> evalInPredicate(InPredicateOperator inPredicate) {
        Preconditions.checkNotNull(inPredicate);
        if (!inPredicate.allValuesMatch(ScalarOperator::isConstantRef)) {
            return null;
        }
        if (inPredicate.getChild(0).isConstant()) {
            // If child(0) of the in predicate is a constant expression,
            // then other children of in predicate should not be used as a condition for partition prune.
            // Such as "where  'Hi' in ('Hi', 'hello') and ... "
            return null;
        }

        if (!isSinglePartitionColumn(inPredicate)) {
            return null;
        }
        ColumnRefOperator child = Utils.extractColumnRef(inPredicate).get(0);

        Set<Long> matches = Sets.newHashSet();
        TreeMap<LiteralExpr, Set<Long>> partitionValueMap = columnToPartitionValuesMap.get(child);
        Set<Long> nullPartitions = columnToNullPartitions.get(child);
        if (partitionValueMap == null || nullPartitions == null) {
            return null;
        }

        if (inPredicate.isNotIn()) {
            // Column NOT IN (Literal, ..., Literal)
            // If there is a NullLiteral, return an empty set.
            if (inPredicate.hasAnyNullValues()) {
                return Sets.newHashSet();
            }

            // all partitions but remove null partitions
            matches.addAll(allPartitions);
            matches.removeAll(nullPartitions);
        }

        for (int i = 1; i < inPredicate.getChildren().size(); ++i) {
            ScalarOperatorToExpr.FormatterContext formatterContext =
                    new ScalarOperatorToExpr.FormatterContext(new HashMap<>());
            LiteralExpr literal =
                    (LiteralExpr) ScalarOperatorToExpr.buildExecExpression(inPredicate.getChild(i), formatterContext);
            Set<Long> partitions = partitionValueMap.get(literal);
            if (partitions != null) {
                if (inPredicate.isNotIn()) {
                    matches.removeAll(partitions);
                } else {
                    matches.addAll(partitions);
                }
            }
        }

        return matches;
    }

    private Set<Long> evalIsNullPredicate(IsNullPredicateOperator isNullPredicate) {
        Preconditions.checkNotNull(isNullPredicate);
        if (!isSinglePartitionColumn(isNullPredicate)) {
            return null;
        }
        ColumnRefOperator child = Utils.extractColumnRef(isNullPredicate).get(0);

        Set<Long> matches = Sets.newHashSet();
        Set<Long> nullPartitions = columnToNullPartitions.get(child);
        if (nullPartitions == null) {
            return null;
        }
        if (isNullPredicate.isNotNull()) {
            // is not null
            matches.addAll(allPartitions);
            matches.removeAll(nullPartitions);
        } else {
            // is null
            matches.addAll(nullPartitions);
        }
        return matches;
    }

    private Set<Long> evalCompoundPredicate(CompoundPredicateOperator compoundPredicate) {
        Preconditions.checkNotNull(compoundPredicate);
        if (compoundPredicate.getCompoundType() == CompoundPredicateOperator.CompoundType.NOT) {
            return null;
        }

        Set<Long> lefts = evalPartitionPruneFilter(compoundPredicate.getChild(0));
        Set<Long> rights = evalPartitionPruneFilter(compoundPredicate.getChild(1));
        if (lefts == null && rights == null) {
            return null;
        } else if (lefts == null) {
            return rights;
        } else if (rights == null) {
            return lefts;
        }

        if (compoundPredicate.getCompoundType() == CompoundPredicateOperator.CompoundType.AND) {
            lefts.retainAll(rights);
        } else if (compoundPredicate.getCompoundType() == CompoundPredicateOperator.CompoundType.OR) {
            lefts.addAll(rights);
        }
        return lefts;
    }
}
