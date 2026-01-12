/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rel.logical.LogicalTableScan;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.Table;
import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.column.ColumnSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ColumnProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.table.SimpleTableSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateFilterSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateInSubFilterSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateJoinSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateProjectionSegment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Template RelNode builder - directly constructs RelNode from AST.
 *
 * This approach is simpler than SqlNode conversion because:
 * 1. No need for SqlValidator (no schema validation)
 * 2. No need for virtual schema creation
 * 3. More direct mapping from template AST to RelNode
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TemplateRelNodeBuilder {

    /**
     * Build template RelNode from rewrite rule segment.
     *
     * @param ruleSegment rewrite rule segment
     * @param cluster RelOptCluster for creating RelNodes
     * @return template rewrite rule with RelNodes
     */
    public static TemplateRelNodeRule buildRule(final RewriteRuleSegment ruleSegment, final RelOptCluster cluster) {
        RelNode sourceTemplate = buildRelNode(ruleSegment.getSource(), cluster);
        RelNode targetTemplate = buildRelNode(ruleSegment.getTarget(), cluster);
        return new TemplateRelNodeRule(sourceTemplate, targetTemplate, ruleSegment.getConstraints());
    }

    /**
     * Build RelNode from template AST node.
     */
    private static RelNode buildRelNode(final ASTNode template, final RelOptCluster cluster) {
        if (template instanceof TemplateProjectionSegment) {
            return buildProjection((TemplateProjectionSegment) template, cluster);
        }
        if (template instanceof SimpleTableSegment) {
            return buildTableScan((SimpleTableSegment) template, cluster);
        }
        if (template instanceof TemplateJoinSegment) {
            return buildJoin((TemplateJoinSegment) template, cluster);
        }
        if (template instanceof TemplateFilterSegment) {
            return buildFilter((TemplateFilterSegment) template, cluster);
        }
        if (template instanceof TemplateInSubFilterSegment) {
            return buildInSubFilter((TemplateInSubFilterSegment) template, cluster);
        }
        throw new UnsupportedOperationException("Unsupported template type: " + template.getClass().getName());
    }

    /**
     * Build LogicalProject from projection template.
     *
     * Important semantics:
     * - Proj<a0 s0>(...)  : SELECT a0 FROM ... (normal projection)
     * - Proj*<a0 s0>(...) : SELECT DISTINCT a0 FROM ... (projection with DISTINCT)
     *
     * The '*' in Proj* does NOT mean "SELECT *" (select all columns).
     * It means the result should be DISTINCT (deduplicated).
     * The selected columns are always the specified attributes (a0, a1, etc.).
     *
     * Example:
     * - Proj<a0>(Input<t0>)  → SELECT a0 FROM t0
     * - Proj*<a0>(Input<t0>) → SELECT DISTINCT a0 FROM t0
     */
    private static RelNode buildProjection(final TemplateProjectionSegment segment, final RelOptCluster cluster) {
        // 1. build child input
        RelNode input = buildRelNode(segment.getChild(), cluster);

        // 2. build projection expressions
        RexBuilder rexBuilder = cluster.getRexBuilder();
        List<RexNode> projects = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();

        // Proj* means DISTINCT
        boolean isDistinct = "Proj*".equals(segment.getOperator());

        // Proj<a0, a1, ...> or  Proj*<a0, a1, ...>
        for (ProjectionSegment projSegment : segment.getProjections()) {
            if (projSegment instanceof ColumnProjectionSegment) {
                ColumnSegment column = ((ColumnProjectionSegment) projSegment).getColumn();
                String columnName = column.getIdentifier().getValue();

                // TODO: collect schema
                if (columnName.matches("s\\d+")) {
                    continue;
                }

                int fieldIndex = findFieldIndex(input, columnName);
                if (fieldIndex >= 0) {
                    projects.add(rexBuilder.makeInputRef(input, fieldIndex));
                    fieldNames.add(columnName);
                    log.info("Column '{}' found in input, adding to projection.", columnName);
                } else {
                    projects.add(rexBuilder.makeLiteral(columnName));
                    fieldNames.add(columnName);
                    log.warn("Column '{}' not found in input, using placeholder.", columnName);
                }
            } else {
                throw new UnsupportedOperationException("Unsupported projection segment: " + projSegment.getClass().getName());
            }
        }

        RelNode project = LogicalProject.create(input, Collections.emptyList(), projects, fieldNames);
        if (isDistinct) {
            /*use aggregate to implement distinct*/
            org.apache.calcite.util.ImmutableBitSet groupSet = org.apache.calcite.util.ImmutableBitSet.range(project.getRowType().getFieldCount());
            return org.apache.calcite.rel.logical.LogicalAggregate.create(
                    project,
                    Collections.emptyList(), // hints
                    groupSet,               // GROUP BY all columns
                    null,                   // no groupSets
                    Collections.emptyList() // no aggregate calls
            );
        }
        return project;
    }

    /**
     * Find field index by name in RelNode's row type.
     */
    private static int findFieldIndex(final RelNode input, final String fieldName) {
        List<String> fieldNames = input.getRowType().getFieldNames();
        for (int i = 0; i < fieldNames.size(); i++) {
            if (fieldNames.get(i).equals(fieldName)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Build LogicalTableScan from table template.
     */
    private static RelNode buildTableScan(final SimpleTableSegment segment, final RelOptCluster cluster) {
        String tableName = segment.getTableName().getIdentifier().getValue();
        /* create TemplateTable */
        TemplateTable templateTable = new TemplateTable(tableName);
        /* create RelOptTable */
        RelOptTable relOptTable = createRelOptTable(templateTable, cluster);
        return LogicalTableScan.create(cluster, relOptTable, Collections.emptyList());
    }

    /**
     * Build LogicalJoin from join template.
     *
     * Join semantics:
     * - InnerJoin<a1 a2>(L, R) means: L JOIN R ON L.a1 = R.a2
     * - a1 is the attribute from the left side
     * - a2 is the attribute from the right side
     */
    private static RelNode buildJoin(final TemplateJoinSegment segment, final RelOptCluster cluster) {
        RelNode left = buildRelNode(segment.getLeft(), cluster);
        RelNode right = buildRelNode(segment.getRight(), cluster);

        RexBuilder rexBuilder = cluster.getRexBuilder();
        String leftAttr = segment.getLeftAttribute();   // a1
        String rightAttr = segment.getRightAttribute(); // a2
        int leftFieldIndex = findFieldIndex(left, leftAttr);
        int rightFieldIndex = findFieldIndex(right, rightAttr);

        RexNode condition;
        if (leftFieldIndex >= 0 && rightFieldIndex >= 0) {
            // create L.a1 = R.a2
            RexNode leftRef = rexBuilder.makeInputRef(left, leftFieldIndex);
            RexNode rightRef = rexBuilder.makeInputRef(
                    right.getRowType().getFieldList().get(rightFieldIndex).getType(),
                    left.getRowType().getFieldCount() + rightFieldIndex
            );
            condition = rexBuilder.makeCall(
                    org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS,
                    leftRef,
                    rightRef
            );
        } else {
            condition = rexBuilder.makeLiteral(true);
        }
        JoinRelType joinType = parseJoinType(segment.getJoinType());
        return LogicalJoin.create(
                left,
                right,
                Collections.emptyList(),
                condition,
                Collections.emptySet(),
                joinType
        );
    }

    /**
     * Build LogicalFilter from filter template.
     *
     * Filter semantics:
     * - Filter<p0 a0>(Input<t0>) means: WHERE p0(a0)
     * - p0 is a predicate function (UDF)
     * - a0 is the input attribute (parameter to the function)
     *
     * For nested filters:
     * - Filter<p1 a1>(Filter<p0 a0>(Input<t0>)) means: WHERE p0(a0) AND p1(a1)
     */
    private static RelNode buildFilter(final TemplateFilterSegment segment, final RelOptCluster cluster) {
        // 1. collect all filter conditions from nested filters
        List<FilterCondition> conditions = new ArrayList<>();
        ASTNode current = segment;

        while (current instanceof TemplateFilterSegment) {
            TemplateFilterSegment filterSeg = (TemplateFilterSegment) current;
            conditions.add(new FilterCondition(filterSeg.getPredicate(), filterSeg.getAttribute()));
            current = filterSeg.getChild();
        }

        RelNode input = buildRelNode(current, cluster);

        // 3. build combined filter condition
        RexBuilder rexBuilder = cluster.getRexBuilder();
        List<RexNode> conditionNodes = new ArrayList<>();

        for (FilterCondition condition : conditions) {
            // build predicate call p(a)
            RexNode predicateCall = buildPredicateCall(
                    condition.predicate,
                    condition.attribute,
                    input,
                    rexBuilder
            );
            conditionNodes.add(predicateCall);
        }

        // use AND to combine multiple conditions
        RexNode combinedCondition;
        if (conditionNodes.size() == 1) {
            combinedCondition = conditionNodes.get(0);
        } else {
            combinedCondition = rexBuilder.makeCall(
                    org.apache.calcite.sql.fun.SqlStdOperatorTable.AND,
                    conditionNodes
            );
        }
        return LogicalFilter.create(input, combinedCondition);
    }

    /**
     * Build predicate function call: p(a).
     *
     * @param predicateName predicate function name (p0, p1, ...)
     * @param attributeName attribute name (a0, a1, ...)
     * @param input input RelNode
     * @param rexBuilder RexBuilder
     * @return RexNode representing p(a)
     */
    private static RexNode buildPredicateCall(final String predicateName, final String attributeName,
                                               final RelNode input, final RexBuilder rexBuilder) {
        // 查找属性在 input 中的索引
        int fieldIndex = findFieldIndex(input, attributeName);

        RexNode attrRef;
        if (fieldIndex >= 0) {
            // crete attribute reference
            attrRef = rexBuilder.makeInputRef(input, fieldIndex);
        } else {
            throw new IllegalArgumentException("Attribute '" + attributeName + "' not found in input for predicate '" + predicateName + "'");
        }

        // create predicate function call p(attrRef)
        org.apache.calcite.sql.SqlOperator predicateOperator = new org.apache.calcite.sql.SqlUnresolvedFunction(
                new org.apache.calcite.sql.SqlIdentifier(predicateName, org.apache.calcite.sql.parser.SqlParserPos.ZERO),
                null, null, null, null,
                org.apache.calcite.sql.SqlFunctionCategory.USER_DEFINED_FUNCTION
        );

        return rexBuilder.makeCall(predicateOperator, Collections.singletonList(attrRef));
    }

    /**
     * Helper class to store filter condition (predicate + attribute).
     */
    private static class FilterCondition {
        final String predicate;
        final String attribute;

        FilterCondition(String predicate, String attribute) {
            this.predicate = predicate;
            this.attribute = attribute;
        }
    }

    /**
     * Build semi-join (InSubFilter) as LogicalFilter with IN subquery.
     *
     * InSubFilter semantics:
     * - InSubFilter<a2>(L, R) means: keep rows from L where L.a2 IN (SELECT a2 FROM R)
     * - This is a semi-join: only rows from L that have matching values in R
     *
     * Example:
     * - InSubFilter<a2>(LeftJoin<a0 a1>(Input<t0>,Input<t1>), Input<t2>)
     *   → SELECT * FROM (t0 LEFT JOIN t1 ON t0.a0 = t1.a1) AS L
     *     WHERE L.a2 IN (SELECT a2 FROM t2)
     */
    private static RelNode buildInSubFilter(final TemplateInSubFilterSegment segment, final RelOptCluster cluster) {
        // 1. build left and right inputs
        RelNode leftInput = buildRelNode(segment.getLeftChild(), cluster);
        RelNode rightInput = buildRelNode(segment.getSubquery(), cluster);

        // 2. get filter attribute
        String filterAttr = segment.getAttribute(); // a2
        int leftFieldIndex = findFieldIndex(leftInput, filterAttr);

        RexBuilder rexBuilder = cluster.getRexBuilder();
        RexNode condition;

        if (leftFieldIndex >= 0) {
            // create left attribute reference: L.a2
            RexNode leftRef = rexBuilder.makeInputRef(leftInput, leftFieldIndex);

            // find right attribute index
            int rightFieldIndex = findFieldIndex(rightInput, filterAttr);
            if (rightFieldIndex >= 0) {
                // build right projection: SELECT a2 FROM R
                List<RexNode> rightProjects = Collections.singletonList(
                        rexBuilder.makeInputRef(rightInput, rightFieldIndex)
                );
                List<String> rightFieldNames = Collections.singletonList(filterAttr);
                RelNode rightProjection = LogicalProject.create(
                        rightInput,
                        Collections.emptyList(),
                        rightProjects,
                        rightFieldNames
                );

                // create IN subquery：L.a2 IN (SELECT a2 FROM R)
                condition = org.apache.calcite.rex.RexSubQuery.in(
                        rightProjection,
                        com.google.common.collect.ImmutableList.of(leftRef)
                );
            } else {
                throw new IllegalArgumentException("Attribute '" + filterAttr + "' not found in subquery for InSubFilter.");
            }
        } else {
            throw new IllegalArgumentException("Attribute '" + filterAttr + "' not found in left input for InSubFilter.");
        }
        return LogicalFilter.create(leftInput, condition);
    }

    /**
     * Parse join type string to JoinRelType.
     */
    private static JoinRelType parseJoinType(final String joinTypeStr) {
        if ("InnerJoin".equals(joinTypeStr) || "INNER".equalsIgnoreCase(joinTypeStr)) {
            return JoinRelType.INNER;
        } else if ("LeftJoin".equals(joinTypeStr) || "LEFT".equalsIgnoreCase(joinTypeStr)) {
            return JoinRelType.LEFT;
        } else if ("RightJoin".equals(joinTypeStr) || "RIGHT".equalsIgnoreCase(joinTypeStr)) {
            return JoinRelType.RIGHT;
        } else if ("FullJoin".equals(joinTypeStr) || "FULL".equalsIgnoreCase(joinTypeStr)) {
            return JoinRelType.FULL;
        }
        return JoinRelType.INNER;
    }

    /**
     * Create RelOptTable for template table.
     */
    private static RelOptTable createRelOptTable(final Table table, final RelOptCluster cluster) {
        return TemplateRelOptTable.create(table, cluster.getTypeFactory());
    }
}

