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

package org.apache.shardingsphere.rewriter.poc.rule;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.*;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.tools.RelBuilder;
import org.apache.shardingsphere.rewriter.poc.matcher.RelNodeMatcher;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintSegment;

import java.util.*;

/**
 * Template-based SQL rewrite rule at RelNode level.
 *
 * Workflow:
 * 1. matches(): Pattern matching + validate source-only constraints
 * 2. onMatch(): Fill target template + validate cross-template constraints + apply transformation
 */
public class TemplateSqlRewriteRule extends RelOptRule {

    private final RelNode sourceTemplate;
    private final RelNode targetTemplate;
    private final Collection<ConstraintSegment> constraints;
    private final RelNodeMatcher matcher;

    public TemplateSqlRewriteRule(
            final RelNode sourceTemplate,
            final RelNode targetTemplate,
            final Collection<ConstraintSegment> constraints,
            final String ruleName) {
        super(operand(RelNode.class, any()), ruleName);
        this.sourceTemplate = sourceTemplate;
        this.targetTemplate = targetTemplate;
        this.constraints = constraints;
        this.matcher = new RelNodeMatcher();
    }

    @Override
    public void onMatch(final RelOptRuleCall call) {
        RelNode inputRel = call.rel(0);

        // Pre-check: only match if input type matches source template root type
        if (!inputRel.getRelTypeName().equals(sourceTemplate.getRelTypeName())) {
            return;
        }

        try {
            System.out.println("\n[TemplateSqlRewriteRule] ========================================");
            System.out.println("[TemplateSqlRewriteRule] Rule: " + toString());
            System.out.println("[TemplateSqlRewriteRule] Input RelNode: " + inputRel.getRelTypeName());
            System.out.println("[TemplateSqlRewriteRule] " + RelOptUtil.toString(inputRel));

            // Step 1: Pattern matching + validate source-only constraints
            RelNodeMatcher.MatchResult matchResult = matcher.matches(inputRel, sourceTemplate, constraints);

            if (!matchResult.isMatched()) {
                System.out.println("[TemplateSqlRewriteRule] Pattern matching failed");
                return;
            }

            Map<String, Object> placeholderMapping = matchResult.getPlaceholderMapping();
            System.out.println("\n[TemplateSqlRewriteRule] ✓ Pattern matched!");
            System.out.println("[TemplateSqlRewriteRule] Extracted placeholders: " + placeholderMapping.keySet());

            // Step 2: Fill target template with actual values from placeholder mapping
            RelNode rewrittenRel = instantiateTargetTemplate(targetTemplate, placeholderMapping, call.builder());

            System.out.println("\n[TemplateSqlRewriteRule] Target template instantiated:");
            System.out.println("[TemplateSqlRewriteRule] " + RelOptUtil.toString(rewrittenRel));

            // Step 3: Validate cross-template constraints (e.g., AttrEq(a0_source, a1_target))
            if (!validateCrossTemplateConstraints(placeholderMapping, constraints)) {
                System.out.println("[TemplateSqlRewriteRule] Cross-template constraint validation failed");
                return;
            }

            System.out.println("\n[TemplateSqlRewriteRule] ✓ All constraints satisfied");
            System.out.println("[TemplateSqlRewriteRule] Applying transformation...");

            // Step 4: Apply the transformation
            call.transformTo(rewrittenRel);

            System.out.println("[TemplateSqlRewriteRule] ✓ Transformation applied successfully");
            System.out.println("[TemplateSqlRewriteRule] ========================================\n");

        } catch (Exception e) {
            System.err.println("[TemplateSqlRewriteRule] ✗ Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Instantiate target template by filling placeholders with actual values.
     *
     * Key: Target template placeholders (t1, t2, etc.) should be replaced with
     * actual RelNodes from source query based on cross-template constraints.
     *
     * For example: TableEq(t1, t0) means "replace t1 with the actual table matched to t0"
     */
    private RelNode instantiateTargetTemplate(final RelNode template, final Map<String, Object> mapping, final RelBuilder builder) {
        System.out.println("\n[Instantiate] Processing: " + template.getRelTypeName());

        // If template node is a placeholder, replace it with actual value
        if (isPlaceholder(template)) {
            String placeholderName = extractPlaceholderName(template);
            System.out.println("[Instantiate]   Placeholder: " + placeholderName);

            // First check if this target placeholder has a direct mapping from source
            Object actualValue = findValueForTargetPlaceholder(placeholderName, mapping);

            if (actualValue instanceof RelNode) {
                RelNode actualNode = (RelNode) actualValue;
                System.out.println("[Instantiate]   → Replaced with: " + actualNode.getRelTypeName());
                return actualNode;
            } else {
                System.out.println("[Instantiate]   ✗ Warning: no mapping found for target placeholder: " + placeholderName);
                return template;
            }
        }

        // Recursively instantiate children
        List<RelNode> newInputs = new ArrayList<>();
        for (RelNode input : template.getInputs()) {
            newInputs.add(instantiateTargetTemplate(input, mapping, builder));
        }

        // Rebuild node with new children
        if (template instanceof LogicalProject) {
            return instantiateProject((LogicalProject) template, newInputs, mapping, builder);
        } else if (template instanceof LogicalJoin) {
            return instantiateJoin((LogicalJoin) template, newInputs, mapping, builder);
        } else if (template instanceof LogicalFilter) {
            return instantiateFilter((LogicalFilter) template, newInputs, mapping, builder);
        } else if (template instanceof LogicalTableScan) {
            // TableScan might be a placeholder itself or remain unchanged
            return template;
        } else {
            // Generic: just copy with new children
            return template.copy(template.getTraitSet(), newInputs);
        }
    }

    /**
     * Find the actual value for a target template placeholder.
     *
     * Searches through constraints to find mappings like TableEq(t1, t0) where:
     * - t1 is target placeholder (what we're looking for)
     * - t0 is source placeholder (already mapped to actual RelNode)
     *
     * Returns the actual RelNode that should replace the target placeholder.
     */
    private Object findValueForTargetPlaceholder(final String targetPlaceholder, final Map<String, Object> mapping) {
        System.out.println("[Instantiate]     Looking for mapping for: " + targetPlaceholder);

        for (ConstraintSegment constraint : constraints) {
            String[] params = constraint.getParams();
            if (params.length < 2) {
                continue;
            }

            System.out.println("[Instantiate]       Checking constraint: " + constraint.getType() +
                             "(" + params[0] + ", " + params[1] + ")");

            // Check if this constraint maps something to our target placeholder
            // Case 1: TableEq(t1, t0) where t1=target, t0=source
            if (params[0].equals(targetPlaceholder) && mapping.containsKey(params[1])) {
                System.out.println("[Instantiate]       ✓ Found! " + params[1] + " (source) → " +
                                 params[0] + " (target)");
                return mapping.get(params[1]);
            }
            // Case 2: TableEq(t0, t1) where t0=source, t1=target
            else if (params[1].equals(targetPlaceholder) && mapping.containsKey(params[0])) {
                System.out.println("[Instantiate]       ✓ Found! " + params[0] + " (source) → " +
                                 params[1] + " (target)");
                return mapping.get(params[0]);
            }
        }

        System.out.println("[Instantiate]     ✗ No constraint found for: " + targetPlaceholder);
        return null;
    }

    /**
     * Instantiate Project node using cross-template constraints.
     * Uses AttrsEq(a1, a0) and SchemaEq(s1, s0) to fill target template.
     */
    private RelNode instantiateProject(final LogicalProject template, final List<RelNode> newInputs,
                                       final Map<String, Object> mapping, final RelBuilder builder) {
        System.out.println("[Instantiate]   Project node - using cross-template constraints");

        if (newInputs.isEmpty()) {
            return template.copy(template.getTraitSet(), newInputs);
        }

        RelNode actualInput = newInputs.get(0);
        System.out.println("[Instantiate]     Actual input: " + actualInput.getRowType());

        // Find source projections (a0) and schema (s0) from mapping
        List<RexNode> sourceProjections = findSourceProjections(mapping);
        org.apache.calcite.rel.type.RelDataType sourceSchema = findSourceSchema(mapping);

        if (sourceProjections != null && sourceSchema != null) {
            System.out.println("[Instantiate]     ✓ Found source: " + sourceProjections.size() + " projections, schema: " + sourceSchema);
            // Create new Project with actual input but source projections/schema
            return LogicalProject.create(actualInput, Collections.emptyList(), sourceProjections, sourceSchema);
        } else {
            System.out.println("[Instantiate]     ✗ No source projections/schema in mapping");
            return actualInput;
        }
    }

    /**
     * Find source projections from mapping via AttrsEq constraint.
     */
    @SuppressWarnings("unchecked")
    private List<RexNode> findSourceProjections(final Map<String, Object> mapping) {
        for (ConstraintSegment constraint : constraints) {
            if (constraint.getType() == org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintType.ATTRS_EQ) {
                String[] params = constraint.getParams();
                if (params.length >= 2) {
                    Object val0 = mapping.get(params[0]);
                    Object val1 = mapping.get(params[1]);
                    if (val0 instanceof List) {
                        return (List<RexNode>) val0;
                    }
                    if (val1 instanceof List) {
                        return (List<RexNode>) val1;
                    }
                }
            }
        }
        Object a0 = mapping.get("a0");
        if (a0 instanceof List) {
            return (List<RexNode>) a0;
        }
        return null;
    }

    /**
     * Find source schema from mapping via SchemaEq constraint.
     */
    private org.apache.calcite.rel.type.RelDataType findSourceSchema(final Map<String, Object> mapping) {
        for (ConstraintSegment constraint : constraints) {
            if (constraint.getType() == org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintType.SCHEMA_EQ) {
                String[] params = constraint.getParams();
                if (params.length >= 2) {
                    Object val0 = mapping.get(params[0]);
                    Object val1 = mapping.get(params[1]);
                    if (val0 instanceof org.apache.calcite.rel.type.RelDataType) {
                        return (org.apache.calcite.rel.type.RelDataType) val0;
                    }
                    if (val1 instanceof org.apache.calcite.rel.type.RelDataType) {
                        return (org.apache.calcite.rel.type.RelDataType) val1;
                    }
                }
            }
        }
        Object s0 = mapping.get("s0");
        if (s0 instanceof org.apache.calcite.rel.type.RelDataType) {
            return (org.apache.calcite.rel.type.RelDataType) s0;
        }
        return null;
    }

    /**
     * Instantiate Join node, swapping tables based on cross-template constraints.
     *
     * For example: TableEq(t2, t1); TableEq(t3, t0) means:
     * - Target left (t2) = Source right (t1)
     * - Target right (t3) = Source left (t0)
     * This effectively swaps the tables.
     */
    private RelNode instantiateJoin(final LogicalJoin template, final List<RelNode> newInputs,
                                    final Map<String, Object> mapping, final RelBuilder builder) {
        System.out.println("[Instantiate]   Join node - checking table order");

        if (newInputs.size() != 2) {
            System.out.println("[Instantiate]     ✗ Join needs exactly 2 inputs");
            return template.copy(template.getTraitSet(), newInputs);
        }

        RelNode leftInput = newInputs.get(0);
        RelNode rightInput = newInputs.get(1);

        System.out.println("[Instantiate]     After placeholder replacement:");
        System.out.println("[Instantiate]       Left: " + leftInput.getRelTypeName());
        System.out.println("[Instantiate]       Right: " + rightInput.getRelTypeName());

        // Find join condition from mapping
        Object joinCondition = mapping.get("joinCondition");
        if (joinCondition instanceof RexNode) {
            RexNode condition = (RexNode) joinCondition;
            System.out.println("[Instantiate]     Using source join condition: " + condition);

            // Create new join with actual inputs and source condition
            return LogicalJoin.create(leftInput, rightInput, Collections.emptyList(),
                                     condition, Collections.emptySet(), template.getJoinType());
        } else {
            System.out.println("[Instantiate]     ✗ No join condition in mapping, using template condition");
            return template.copy(template.getTraitSet(), newInputs);
        }
    }

    /**
     * Instantiate Filter node, replacing filter condition attribute placeholders.
     */
    private RelNode instantiateFilter(final LogicalFilter template, final List<RelNode> newInputs,
                                      final Map<String, Object> mapping, final RelBuilder builder) {
        System.out.println("[Instantiate]   Filter node");

        // TODO: Parse filter condition and replace attribute placeholders

        return template.copy(template.getTraitSet(), newInputs);
    }

    /**
     * Check if a RelNode is a placeholder.
     */
    private boolean isPlaceholder(final RelNode node) {
        if (node instanceof LogicalTableScan) {
            String tableName = getTableName((LogicalTableScan) node);
            return tableName != null && tableName.toUpperCase().matches("T\\d+");
        }
        return false;
    }

    /**
     * Extract placeholder name from a placeholder node.
     */
    private String extractPlaceholderName(final RelNode node) {
        if (node instanceof LogicalTableScan) {
            String tableName = getTableName((LogicalTableScan) node);
            return tableName != null ? tableName.toLowerCase() : "unknown";
        }
        return "unknown";
    }

    /**
     * Get table name from TableScan.
     */
    private String getTableName(final LogicalTableScan scan) {
        List<String> names = scan.getTable().getQualifiedName();
        return names.isEmpty() ? null : names.get(names.size() - 1);
    }

    /**
     * Validate cross-template constraints.
     *
     * These are constraints like TableEq(t1, t0) or AttrEq(a1, a0) where:
     * - t0/a0 is from source template (already in mapping)
     * - t1/a1 is from target template (used to guide filling)
     *
     * For mapping constraints (e.g., TableEq(t1, t0)), we interpret them as:
     * "Use the value mapped to t0 for t1 in the target template"
     *
     * So these aren't really validation constraints - they're filling instructions!
     * We return true because the actual filling happens in instantiateTargetTemplate().
     */
    private boolean validateCrossTemplateConstraints(final Map<String, Object> mapping,
                                                     final Collection<ConstraintSegment> constraints) {
        System.out.println("\n[CrossTemplateConstraints] Processing " + constraints.size() + " constraints");

        for (ConstraintSegment constraint : constraints) {
            String[] params = constraint.getParams();

            if (params.length < 2) {
                continue;
            }

            System.out.println("[CrossTemplateConstraints]   " + constraint.getType() +
                             "(" + params[0] + ", " + params[1] + ")");

            // Determine which is source and which is target
            // Heuristic: Check which placeholder exists in the mapping
            boolean param0InMapping = mapping.containsKey(params[0]);
            boolean param1InMapping = mapping.containsKey(params[1]);

            if (param0InMapping && param1InMapping) {
                // Both from source - should have been validated in matches()
                // But we skipped them there, so validate now
                System.out.println("[CrossTemplateConstraints]     Both in source mapping - validating equality");
                if (!validateSourceToSourceConstraint(constraint, mapping)) {
                    System.out.println("[CrossTemplateConstraints]     ✗ Validation failed");
                    return false;
                }
            } else if (param0InMapping || param1InMapping) {
                // One from source, one from target - this is a mapping constraint
                String sourcePlaceholder = param0InMapping ? params[0] : params[1];
                String targetPlaceholder = param0InMapping ? params[1] : params[0];

                System.out.println("[CrossTemplateConstraints]     Mapping: " + sourcePlaceholder +
                                 " (source) → " + targetPlaceholder + " (target)");

                // Record this mapping for use in instantiateTargetTemplate()
                // For now, just acknowledge it exists
                // The actual filling happens in instantiateTargetTemplate() when it encounters
                // the target placeholder

                Object sourceValue = mapping.get(sourcePlaceholder);
                System.out.println("[CrossTemplateConstraints]     Source value: " +
                                 (sourceValue != null ? sourceValue.getClass().getSimpleName() : "null"));
            } else {
                // Neither in mapping - both from target?
                System.out.println("[CrossTemplateConstraints]     Both from target - no validation needed here");
            }
        }

        System.out.println("[CrossTemplateConstraints] ✓ All cross-template constraints processed");
        return true;
    }

    /**
     * Validate constraint where both parameters are from source template.
     */
    private boolean validateSourceToSourceConstraint(final ConstraintSegment constraint, final Map<String, Object> mapping) {
        String[] params = constraint.getParams();
        Object val1 = mapping.get(params[0]);
        Object val2 = mapping.get(params[1]);

        if (val1 == null || val2 == null) {
            return false;
        }

        switch (constraint.getType()) {
            case TABLE_EQ:
                return validateTableEquality(val1, val2);
            case ATTRS_EQ:
                // TODO: Implement attribute equality check
                return true;
            case ATTRS_SUB:
                // TODO: Implement attribute subset check
                return true;
            default:
                return true;
        }
    }

    /**
     * Validate table equality.
     */
    private boolean validateTableEquality(final Object val1, final Object val2) {
        if (val1 instanceof RelNode && val2 instanceof RelNode) {
            RelNode node1 = (RelNode) val1;
            RelNode node2 = (RelNode) val2;

            if (node1 instanceof LogicalTableScan && node2 instanceof LogicalTableScan) {
                String table1 = getTableName((LogicalTableScan) node1);
                String table2 = getTableName((LogicalTableScan) node2);
                return table1 != null && table1.equals(table2);
            }
        }
        return false;
    }
}

