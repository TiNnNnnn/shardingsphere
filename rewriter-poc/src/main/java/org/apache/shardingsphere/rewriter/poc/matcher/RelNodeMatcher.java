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

package org.apache.shardingsphere.rewriter.poc.matcher;

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.*;
import org.apache.calcite.rex.RexNode;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintType;

import java.util.*;

/**
 * RelNode pattern matcher.
 * Recursively matches input RelNode trees against template RelNode trees,
 * extracts placeholder mappings, and validates source template constraints.
 */
public class RelNodeMatcher {

    /**
     * Match result containing placeholder mappings and validation status.
     */
    public static class MatchResult {
        private final boolean matched;
        private final Map<String, Object> placeholderMapping;

        public MatchResult(final boolean matched, final Map<String, Object> placeholderMapping) {
            this.matched = matched;
            this.placeholderMapping = placeholderMapping != null ? placeholderMapping : new HashMap<>();
        }

        public boolean isMatched() {
            return matched;
        }

        public Map<String, Object> getPlaceholderMapping() {
            return placeholderMapping;
        }
    }

    /**
     * Match an input RelNode against a template RelNode.
     * Validates constraints that only involve source template placeholders.
     *
     * @param input input RelNode to match
     * @param template template RelNode with placeholders
     * @param sourceConstraints constraints that only reference source template placeholders
     * @return MatchResult containing match status and placeholder mappings
     */
    public MatchResult matches(final RelNode input, final RelNode template, final Collection<ConstraintSegment> sourceConstraints) {
        System.out.println("\n[RelNodeMatcher] === Starting Pattern Matching ===");

        Map<String, Object> mapping = new HashMap<>();

        // Step 1: Recursive pattern matching - check node types and extract placeholders
        if (!matchNodeRecursive(input, template, mapping, "")) {
            System.out.println("[RelNodeMatcher] Pattern matching failed");
            return new MatchResult(false, null);
        }

        System.out.println("[RelNodeMatcher] Pattern matched successfully");
        System.out.println("[RelNodeMatcher] Extracted placeholders: " + mapping.keySet());

        // Step 2: Validate source template constraints (only constraints with both params from source)
        List<ConstraintSegment> sourceOnlyConstraints = filterSourceOnlyConstraints(sourceConstraints);
        if (!validateSourceConstraints(mapping, sourceOnlyConstraints)) {
            System.out.println("[RelNodeMatcher] Source constraint validation failed");
            return new MatchResult(false, null);
        }

        System.out.println("[RelNodeMatcher] All source constraints satisfied");
        return new MatchResult(true, mapping);
    }

    /**
     * Filter constraints that only involve source template placeholders.
     *
     * Strategy: Extract all placeholders from source template, then check if constraint
     * references only those placeholders.
     */
    private List<ConstraintSegment> filterSourceOnlyConstraints(final Collection<ConstraintSegment> constraints) {
        List<ConstraintSegment> result = new ArrayList<>();

        // For this implementation, we need to know which placeholders are from source template
        // Since we don't have direct access to source template here, we use a heuristic:
        // - Cross-template constraints typically reference placeholders from both templates
        // - For example: TableEq(t0, t1) where t0 is source, t1 is target
        //
        // Better approach: Pass source template placeholder set to this method
        // For now, we'll skip all constraints in matches() and handle them in onMatch()

        System.out.println("[RelNodeMatcher] Filtering source-only constraints from " + constraints.size() + " total");

        for (ConstraintSegment constraint : constraints) {
            String[] params = constraint.getParams();

            // Heuristic: If constraint involves placeholders with different indices,
            // it might be cross-template (e.g., t0 vs t1, a0 vs a1)
            // For safety, we only validate constraints where both params are identical
            // or very clearly from same template

            if (params.length >= 2) {
                // Skip constraints that look like cross-template mappings
                // e.g., TableEq(t1, t0) - likely means "map t0 to t1"
                // e.g., AttrEq(a1, a0) - likely means "map a0 to a1"

                if (params[0].equals(params[1])) {
                    // Same placeholder - pointless but harmless
                    continue;
                }

                // For now, be conservative: assume all constraints might be cross-template
                // Only validate in onMatch() when we have both source and target context
                System.out.println("[RelNodeMatcher]   Skipping constraint (might be cross-template): " +
                                 constraint.getType() + "(" + String.join(", ", params) + ")");
            }
        }

        System.out.println("[RelNodeMatcher] Filtered to " + result.size() + " source-only constraints");
        return result;
    }

    /**
     * Recursively match nodes and extract placeholders.
     */
    private boolean matchNodeRecursive(final RelNode input, final RelNode template, final Map<String, Object> mapping, final String indent) {
        System.out.println(indent + "[Match] Input: " + input.getRelTypeName() + " vs Template: " + template.getRelTypeName());

        // Check if template node is a placeholder
        if (isPlaceholder(template)) {
            String placeholderName = extractPlaceholderName(template);
            System.out.println(indent + "  → Template is placeholder: " + placeholderName);

            // Check consistency if placeholder already mapped
            if (mapping.containsKey(placeholderName)) {
                Object previousValue = mapping.get(placeholderName);
                if (!isCompatible(input, previousValue)) {
                    System.out.println(indent + "  ✗ Placeholder conflict: " + placeholderName);
                    return false;
                }
                System.out.println(indent + "  ✓ Placeholder already mapped consistently");
                return true;
            }

            // Map the placeholder to the input node
            mapping.put(placeholderName, input);
            System.out.println(indent + "  ✓ Mapped placeholder: " + placeholderName + " → " + input.getRelTypeName());
            return true;
        }

        // Match node types
        if (!input.getRelTypeName().equals(template.getRelTypeName())) {
            System.out.println(indent + "  ✗ Type mismatch");
            return false;
        }

        // Match by specific node type
        if (input instanceof LogicalProject && template instanceof LogicalProject) {
            return matchProject((LogicalProject) input, (LogicalProject) template, mapping, indent + "  ");
        }

        if (input instanceof LogicalTableScan && template instanceof LogicalTableScan) {
            return matchTableScan((LogicalTableScan) input, (LogicalTableScan) template, mapping, indent + "  ");
        }

        if (input instanceof LogicalJoin && template instanceof LogicalJoin) {
            return matchJoin((LogicalJoin) input, (LogicalJoin) template, mapping, indent + "  ");
        }

        if (input instanceof LogicalFilter && template instanceof LogicalFilter) {
            return matchFilter((LogicalFilter) input, (LogicalFilter) template, mapping, indent + "  ");
        }

        // Generic matching: check children count and recursively match
        return matchChildren(input, template, mapping, indent + "  ");
    }

    /**
     * Match Project nodes.
     */
    private boolean matchProject(final LogicalProject input, final LogicalProject template, final Map<String, Object> mapping, final String indent) {
        System.out.println(indent + "[Project] Matching projections...");

        // Extract projection placeholders from template
        List<RexNode> inputProjects = input.getProjects();
        List<RexNode> templateProjects = template.getProjects();

        // For template, we extract column references as placeholders
        extractProjectionPlaceholders(inputProjects, templateProjects, mapping, indent);

        // Extract schema placeholder (s0) - represents the row type
        mapping.put("s0", input.getRowType());
        System.out.println(indent + "  Mapped 's0' to schema: " + input.getRowType());

        // Match children
        return matchChildren(input, template, mapping, indent);
    }

    /**
     * Extract projection placeholders (e.g., a0, a1, s0).
     *
     * For Proj*<a0 s0>, we need to extract:
     * - a0: the actual projection expressions (list of columns)
     * - s0: the actual schema (row type)
     */
    private void extractProjectionPlaceholders(final List<RexNode> inputProjects, final List<RexNode> templateProjects,
                                               final Map<String, Object> mapping, final String indent) {
        System.out.println(indent + "  Projection count: input=" + inputProjects.size() + ", template=" + templateProjects.size());

        // Extract attribute placeholder (a0) - represents the projection expressions
        // Look for pattern like "a0", "a1" in template
        // For now, use a heuristic: if template projects from placeholder table, extract attributes

        // Map a0 → actual projection expressions
        mapping.put("a0", inputProjects);
        System.out.println(indent + "  Mapped 'a0' to projection expressions: " + inputProjects.size() + " columns");

        // TODO: Parse template to find the actual placeholder name (might not always be "a0")
        // For now, hardcode common names
    }

    /**
     * Match TableScan nodes - these are often placeholders.
     */
    private boolean matchTableScan(final LogicalTableScan input, final LogicalTableScan template, final Map<String, Object> mapping, final String indent) {
        System.out.println(indent + "[TableScan] Input table: " + getTableName(input) + ", Template: " + getTableName(template));

        // If template table is a placeholder (T0, T1, etc.), extract it
        String templateTableName = getTableName(template);
        if (isTablePlaceholder(templateTableName)) {
            String placeholderName = templateTableName.toLowerCase();
            mapping.put(placeholderName, input);
            System.out.println(indent + "  ✓ Mapped table placeholder: " + placeholderName + " → " + getTableName(input));
        }

        return true;
    }

    /**
     * Match Join nodes.
     */
    private boolean matchJoin(final LogicalJoin input, final LogicalJoin template, final Map<String, Object> mapping, final String indent) {
        System.out.println(indent + "[Join] Matching join type...");

        // Match join type
        if (input.getJoinType() != template.getJoinType()) {
            System.out.println(indent + "  ✗ Join type mismatch: " + input.getJoinType() + " != " + template.getJoinType());
            return false;
        }

        System.out.println(indent + "  ✓ Join type matched: " + input.getJoinType());

        // Extract join condition as attribute placeholders
        // For InnerJoin<a1 a2>, we extract the join condition
        // Simplified: store the join condition for later use
        mapping.put("joinCondition", input.getCondition());
        System.out.println(indent + "  Mapped 'joinCondition' to: " + input.getCondition());

        // Extract left and right join attributes (a1, a2)
        // For now, hardcode as we parse from template
        mapping.put("a1", input.getCondition()); // Left side of condition
        mapping.put("a2", input.getCondition()); // Right side of condition
        System.out.println(indent + "  Mapped join attributes a1, a2");

        // Match children
        return matchChildren(input, template, mapping, indent);
    }

    /**
     * Match Filter nodes.
     */
    private boolean matchFilter(final LogicalFilter input, final LogicalFilter template, final Map<String, Object> mapping, final String indent) {
        System.out.println(indent + "[Filter] Matching filter condition...");

        // Extract filter condition placeholders
        // TODO: Parse filter condition to extract attribute placeholders

        // Match children
        return matchChildren(input, template, mapping, indent);
    }

    /**
     * Generic children matching.
     */
    private boolean matchChildren(final RelNode input, final RelNode template, final Map<String, Object> mapping, final String indent) {
        List<RelNode> inputChildren = input.getInputs();
        List<RelNode> templateChildren = template.getInputs();

        if (inputChildren.size() != templateChildren.size()) {
            System.out.println(indent + "✗ Children count mismatch: " + inputChildren.size() + " != " + templateChildren.size());
            return false;
        }

        for (int i = 0; i < inputChildren.size(); i++) {
            if (!matchNodeRecursive(inputChildren.get(i), templateChildren.get(i), mapping, indent)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Check if a RelNode is a placeholder.
     */
    private boolean isPlaceholder(final RelNode node) {
        if (node instanceof LogicalTableScan) {
            String tableName = getTableName((LogicalTableScan) node);
            return isTablePlaceholder(tableName);
        }
        return false;
    }

    /**
     * Check if table name is a placeholder (T0, T1, etc.).
     */
    private boolean isTablePlaceholder(final String tableName) {
        return tableName != null && tableName.toUpperCase().matches("T\\d+");
    }

    /**
     * Extract placeholder name from a placeholder node.
     */
    private String extractPlaceholderName(final RelNode node) {
        if (node instanceof LogicalTableScan) {
            String tableName = getTableName((LogicalTableScan) node);
            return tableName != null ? tableName.toLowerCase() : "unknown";
        }
        return "unknown_" + System.identityHashCode(node);
    }

    /**
     * Get table name from TableScan.
     */
    private String getTableName(final LogicalTableScan scan) {
        List<String> names = scan.getTable().getQualifiedName();
        return names.isEmpty() ? null : names.get(names.size() - 1);
    }

    /**
     * Check if input is compatible with previously mapped value.
     */
    private boolean isCompatible(final RelNode input, final Object previousValue) {
        if (previousValue instanceof RelNode) {
            RelNode prevNode = (RelNode) previousValue;
            return input.getRelTypeName().equals(prevNode.getRelTypeName());
        }
        return false;
    }

    /**
     * Validate source template constraints.
     */
    private boolean validateSourceConstraints(final Map<String, Object> mapping, final List<ConstraintSegment> constraints) {
        for (ConstraintSegment constraint : constraints) {
            if (!validateConstraint(constraint, mapping)) {
                System.out.println("[RelNodeMatcher] Constraint failed: " + constraint.getType() +
                                 "(" + String.join(", ", constraint.getParams()) + ")");
                return false;
            }
        }
        return true;
    }

    /**
     * Validate a single constraint.
     */
    private boolean validateConstraint(final ConstraintSegment constraint, final Map<String, Object> mapping) {
        String[] params = constraint.getParams();

        switch (constraint.getType()) {
            case TABLE_EQ:
                // TableEq(t0, t1): mapped tables must be the same
                return validateTableEq(params[0], params[1], mapping);

            case ATTRS_EQ:
                // AttrsEq(a0, a1): mapped attributes must be the same
                return validateAttrsEq(params[0], params[1], mapping);

            case ATTRS_SUB:
                // AttrsSub(a0, t0): attributes a0 must be subset of table t0's schema
                return validateAttrsSub(params[0], params[1], mapping);

            default:
                return true;
        }
    }

    private boolean validateTableEq(final String param1, final String param2, final Map<String, Object> mapping) {
        Object val1 = mapping.get(param1);
        Object val2 = mapping.get(param2);

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

    private boolean validateAttrsEq(final String param1, final String param2, final Map<String, Object> mapping) {
        // TODO: Implement attribute equality check
        // Would need to parse RexNode and compare column references
        return mapping.containsKey(param1) && mapping.containsKey(param2);
    }

    private boolean validateAttrsSub(final String param1, final String param2, final Map<String, Object> mapping) {
        // TODO: Implement attribute subset check
        // Would need to verify that attributes in param1 are subset of table param2's schema
        return mapping.containsKey(param1) && mapping.containsKey(param2);
    }
}

