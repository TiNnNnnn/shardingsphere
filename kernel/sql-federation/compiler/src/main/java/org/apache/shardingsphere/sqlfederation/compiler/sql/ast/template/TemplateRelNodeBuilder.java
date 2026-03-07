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
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintType;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateFilterSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateInSubFilterSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateJoinSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateProjectionSegment;

import java.util.*;

/**
 * Template RelNode builder from rewrite rule segment.
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TemplateRelNodeBuilder {

    private static final ThreadLocal<Collection<ASTNode>> currentMatchConstraints = new ThreadLocal<>();
    private static final ThreadLocal<Collection<ASTNode>> currentRewriteConstraints = new ThreadLocal<>();
    private static final ThreadLocal<Map<String, Integer>> attrToParamIndexMap = ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<Integer> paramIndexCounter = ThreadLocal.withInitial(() -> 0);

    /**
     * Get or create parameter index for an attribute name.
     * This is used for RexDynamicParam placeholders.
     */
    private static int getOrCreateParamIndex(final String attrName) {
        Map<String, Integer> map = attrToParamIndexMap.get();
        return map.computeIfAbsent(attrName, k -> {
            int index = paramIndexCounter.get();
            paramIndexCounter.set(index + 1);
            return index;
        });
    }

    private static final ThreadLocal<String> sourceTemplateString = new ThreadLocal<>();
    private static final ThreadLocal<String> targetTemplateString = new ThreadLocal<>();

    /**
     * Build template RelNode from rewrite rule segment.
     *
     * @param ruleSegment rewrite rule segment
     * @param cluster RelOptCluster for creating RelNodes
     * @return template rewrite rule with RelNodes
     */
    public static TemplateRelNodeRule buildRule(final RewriteRuleSegment ruleSegment, final RelOptCluster cluster) {
        // Store template strings from RewriteRuleSegment for constraint classification
        sourceTemplateString.set(ruleSegment.getSourceTemplateString());
        targetTemplateString.set(ruleSegment.getTargetTemplateString());

        try {
            // Split constraints into two categories
            Collection<ASTNode> matchConstraints = new ArrayList<>();
            Collection<ASTNode> rewriteConstraints = new ArrayList<>();

            for (ASTNode constraint : ruleSegment.getConstraints()) {
                if (isMatchConstraint(constraint)) {
                    matchConstraints.add(constraint);
                } else {
                    rewriteConstraints.add(constraint);
                }
            }

            // Store constraints in ThreadLocal for access during RelNode building
            currentMatchConstraints.set(matchConstraints);
            currentRewriteConstraints.set(rewriteConstraints);

            // Build RelNodes with constraints available
            RelNode sourceTemplate = buildRelNode(ruleSegment.getSource(), cluster);
            RelNode targetTemplate = buildRelNode(ruleSegment.getTarget(), cluster);
            return new TemplateRelNodeRule(sourceTemplate, targetTemplate, matchConstraints, rewriteConstraints);
        } finally {
            currentMatchConstraints.remove();
            currentRewriteConstraints.remove();
            sourceTemplateString.remove();
            targetTemplateString.remove();
        }
    }

    /**
     * Check if a constraint is a match constraint (all parameters from source template only).
     *
     * Strategy: String matching - check if all constraint parameters appear in source template string.
     */
    private static boolean isMatchConstraint(final ASTNode constraint) {
        if (!(constraint instanceof ConstraintSegment)) {
            return false;
        }

        ConstraintSegment constraintSegment = (ConstraintSegment) constraint;
        String[] params = constraintSegment.getParams();

        if (params == null || params.length == 0) {
            return false;
        }

        String sourceStr = sourceTemplateString.get();
        if (sourceStr == null) {
            return false;
        }

        // Check if ALL parameters can be found in source template string
        for (String param : params) {
            if (!sourceStr.contains(param)) {
                // This parameter is not in source template, so it's a cross-template constraint
                return false;
            }
        }

        // All parameters found in source template
        return true;
    }

    /**
     * Extract numeric index from parameter name.
     * Example: a0 -> 0, a1 -> 1, t0 -> 0, s0 -> 0
     * Returns -1 if not a valid parameter name.
     */
    private static int extractIndexFromParam(final String param) {
        if (param != null && param.length() > 1 && param.matches("[ats]\\d+")) {
            return Integer.parseInt(param.substring(1));
        }
        return -1;
    }

    /**
     * Create a simple attribute reference for template matching.
     * This creates a RexInputRef using the attribute name's index directly,
     * which will be displayed as the attribute name (e.g., "a0", "a1") in SQL
     * without table prefixes.
     *
     * @param attrName attribute name (e.g., "a0", "a1")
     * @param cluster RelOptCluster
     * @return RexNode representing the attribute
     */
    private static RexNode makeAttributeRef(final String attrName, final RelOptCluster cluster) {
        int attrIndex = extractIndexFromParam(attrName);
        if (attrIndex < 0) {
            throw new IllegalArgumentException("Invalid attribute name: " + attrName);
        }
        // Create RexInputRef with flexible ANY type and use attribute index directly
        return cluster.getRexBuilder().makeInputRef(
                cluster.getTypeFactory().createSqlType(org.apache.calcite.sql.type.SqlTypeName.ANY),
                attrIndex
        );
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
        List<RexNode> projects = new ArrayList<>();
        List<String> fieldNames = new ArrayList<>();

        // Proj* means DISTINCT
        boolean isDistinct = "Proj*".equals(segment.getOperator());

        // Proj<a0, a1, ...> or  Proj*<a0, a1, ...>
        int projectionIndex = 0;
        for (ProjectionSegment projSegment : segment.getProjections()) {
            if (projSegment instanceof ColumnProjectionSegment) {
                ColumnSegment column = ((ColumnProjectionSegment) projSegment).getColumn();
                String columnName = column.getIdentifier().getValue();

                // TODO: collect schema
                if (columnName.matches("s\\d+")) {
                    continue;
                }

                // For template projections, map attribute to input field
                // Try to find the field in input by name first
                int inputFieldIndex = findFieldIndex(input, columnName);

                // If not found by name, use sequential mapping (projectionIndex maps to input field)
                if (inputFieldIndex < 0) {
                    inputFieldIndex = Math.min(projectionIndex, input.getRowType().getFieldCount() - 1);
                    log.debug("Field '{}' not found in input, using sequential index {}", columnName, inputFieldIndex);
                }

                // Create reference to the actual field in input
                RexNode projectExpr = cluster.getRexBuilder().makeInputRef(
                        input.getRowType().getFieldList().get(inputFieldIndex).getType(),
                        inputFieldIndex
                );

                projects.add(projectExpr);
                fieldNames.add(columnName);
                log.info("Projection: attribute '{}' -> input field index {}", columnName, inputFieldIndex);

                projectionIndex++;
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

        log.info("Building join with attributes: {} = {}", leftAttr, rightAttr);

        // Create simple attribute references without table prefixes
        // This will display as "a1 = a2" in SQL, not "table.a1 = table.a2"
        RexNode leftRef = makeAttributeRef(leftAttr, cluster);
        RexNode rightRef = makeAttributeRef(rightAttr, cluster);

        RexNode condition = rexBuilder.makeCall(
                org.apache.calcite.sql.fun.SqlStdOperatorTable.EQUALS,
                leftRef,
                rightRef
        );

        log.info("Join condition created: {} = {}", leftAttr, rightAttr);

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
                    cluster,
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
     * @param input input RelNode to get field from
     * @param cluster RelOptCluster
     * @param rexBuilder RexBuilder
     * @return RexNode representing p(a)
     */
    private static RexNode buildPredicateCall(final String predicateName, final String attributeName,
                                               final RelNode input, final RelOptCluster cluster, final RexBuilder rexBuilder) {
        // Find the field in input by name
        int fieldIndex = findFieldIndex(input, attributeName);
        if (fieldIndex < 0) {
            // If not found by name, try to extract index from attribute name and use it if valid
            int attrIndex = extractIndexFromParam(attributeName);
            if (attrIndex >= 0 && attrIndex < input.getRowType().getFieldCount()) {
                fieldIndex = attrIndex;
            } else {
                // Fallback to first field
                fieldIndex = 0;
            }
            log.debug("Field '{}' not found in input, using index {}", attributeName, fieldIndex);
        }

        // Create reference to the actual field in input
        RexNode attrRef = rexBuilder.makeInputRef(
                input.getRowType().getFieldList().get(fieldIndex).getType(),
                fieldIndex
        );
        log.info("Filter predicate: attribute '{}' -> input field index {}", attributeName, fieldIndex);

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
        String filterAttr = segment.getAttribute(); // a1

        log.info("InSubFilter: attribute '{}'", filterAttr);

        // Find the field index in leftInput for the filter attribute
        int leftFieldIndex = findFieldIndex(leftInput, filterAttr);
        if (leftFieldIndex < 0) {
            // If not found by name, use the first field as fallback
            leftFieldIndex = 0;
            log.debug("Field '{}' not found in leftInput, using index 0", filterAttr);
        }

        // Create reference to the field in leftInput
        RexNode leftRef = cluster.getRexBuilder().makeInputRef(
                leftInput.getRowType().getFieldList().get(leftFieldIndex).getType(),
                leftFieldIndex
        );

        // Build subquery for IN clause
        // If rightInput is already single-column (e.g., from an explicit Proj in template), use it directly
        // Otherwise, wrap with a projection to select the filter attribute column
        RelNode subquery;
        if (rightInput.getRowType().getFieldCount() == 1) {
            subquery = rightInput;
        } else {
            int rightFieldIndex = findFieldIndex(rightInput, filterAttr);
            if (rightFieldIndex < 0) {
                rightFieldIndex = 0;
                log.debug("Field '{}' not found in rightInput, using index 0", filterAttr);
            }
            RexNode rightProjectExpr = cluster.getRexBuilder().makeInputRef(
                    rightInput.getRowType().getFieldList().get(rightFieldIndex).getType(),
                    rightFieldIndex
            );
            subquery = LogicalProject.create(
                    rightInput,
                    Collections.emptyList(),
                    Collections.singletonList(rightProjectExpr),
                    Collections.singletonList(filterAttr)
            );
        }

        // Create IN subquery: L.field IN (SELECT field FROM R)
        RexNode condition = org.apache.calcite.rex.RexSubQuery.in(
                subquery,
                com.google.common.collect.ImmutableList.of(leftRef)
        );

        log.info("InSubFilter condition created: {} IN (subquery)", filterAttr);

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

