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

import org.apache.calcite.sql.*;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateSqlIdentifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * SqlNode pattern matcher.
 * Matches actual SqlNode against template SqlNode and extracts placeholder mappings.
 */
public class SqlNodeMatcher {

    /**
     * Match actual SqlNode against template.
     *
     * @param actual actual SQL node
     * @param template template SQL node (contains placeholders)
     * @return placeholder mapping if matched, empty otherwise
     */
    public Optional<Map<String, Object>> match(final SqlNode actual, final SqlNode template) {
        Map<String, Object> mapping = new HashMap<>();
        MatchContext context = new MatchContext(mapping);

        boolean matched = matchNode(actual, template, context);

        return matched ? Optional.of(mapping) : Optional.empty();
    }

    /**
     * Match two SqlNode recursively.
     */
    private boolean matchNode(final SqlNode actual, final SqlNode template, final MatchContext context) {
        if (template == null && actual == null) {
            return true;
        }
        if (template == null || actual == null) {
            return false;
        }

        // If template is a placeholder (TemplateSqlIdentifier), record the mapping
        if (template instanceof TemplateSqlIdentifier) {
            return matchPlaceholder(actual, (TemplateSqlIdentifier) template, context);
        }

        // Both must be same type
        if (!template.getClass().equals(actual.getClass())) {
            return false;
        }

        // Match by node type
        if (template instanceof SqlSelect) {
            return matchSelect((SqlSelect) actual, (SqlSelect) template, context);
        } else if (template instanceof SqlJoin) {
            return matchJoin((SqlJoin) actual, (SqlJoin) template, context);
        } else if (template instanceof SqlIdentifier) {
            return matchIdentifier((SqlIdentifier) actual, (SqlIdentifier) template, context);
        } else if (template instanceof SqlBasicCall) {
            return matchBasicCall((SqlBasicCall) actual, (SqlBasicCall) template, context);
        }

        // Default: exact match
        return template.toString().equals(actual.toString());
    }

    /**
     * Match placeholder and record mapping.
     */
    private boolean matchPlaceholder(final SqlNode actual, final TemplateSqlIdentifier template, final MatchContext context) {
        String placeholder = template.getSimple();

        // Extract actual value
        Object actualValue = extractValue(actual, template.getVarType());

        // Check if this placeholder was already mapped
        if (context.mapping.containsKey(placeholder)) {
            // Must match existing mapping
            return context.mapping.get(placeholder).equals(actualValue);
        }

        // Record new mapping
        context.mapping.put(placeholder, actualValue);
        return true;
    }

    /**
     * Extract value from actual node based on placeholder type.
     */
    private Object extractValue(final SqlNode actual, final TemplateSqlIdentifier.TemplateVarType varType) {
        switch (varType) {
            case TABLE:
                // Extract table name
                if (actual instanceof SqlIdentifier) {
                    return ((SqlIdentifier) actual).getSimple();
                }
                return actual.toString();

            case ATTRIBUTE:
                // Extract attribute name
                if (actual instanceof SqlIdentifier) {
                    return ((SqlIdentifier) actual).getSimple();
                }
                return actual.toString();

            case SCHEMA:
                // Schema is metadata, extract if available
                if (actual instanceof SqlIdentifier) {
                    SqlIdentifier id = (SqlIdentifier) actual;
                    if (id.names.size() > 1) {
                        return id.names.get(0);
                    }
                }
                return "default_schema";

            default:
                return actual.toString();
        }
    }

    /**
     * Match SELECT nodes.
     */
    private boolean matchSelect(final SqlSelect actual, final SqlSelect template, final MatchContext context) {
        // Match SELECT list
        if (!matchNodeList(actual.getSelectList(), template.getSelectList(), context)) {
            return false;
        }

        // Match FROM clause
        if (!matchNode(actual.getFrom(), template.getFrom(), context)) {
            return false;
        }

        // Match WHERE clause
        if (!matchNode(actual.getWhere(), template.getWhere(), context)) {
            return false;
        }

        // For simplicity, ignore GROUP BY, HAVING, ORDER BY in this PoC
        return true;
    }

    /**
     * Match JOIN nodes.
     */
    private boolean matchJoin(final SqlJoin actual, final SqlJoin template, final MatchContext context) {
        // Match join type
        if (!actual.getJoinType().toString().equals(template.getJoinType().toString())) {
            return false;
        }

        // Match left and right
        if (!matchNode(actual.getLeft(), template.getLeft(), context)) {
            return false;
        }
        if (!matchNode(actual.getRight(), template.getRight(), context)) {
            return false;
        }

        // Match condition
        return matchNode(actual.getCondition(), template.getCondition(), context);
    }

    /**
     * Match Identifier nodes.
     */
    private boolean matchIdentifier(final SqlIdentifier actual, final SqlIdentifier template, final MatchContext context) {
        // Exact match for non-template identifiers
        return actual.toString().equals(template.toString());
    }

    /**
     * Match SqlBasicCall nodes (operators, functions).
     */
    private boolean matchBasicCall(final SqlBasicCall actual, final SqlBasicCall template, final MatchContext context) {
        // Match operator
        if (!actual.getOperator().equals(template.getOperator())) {
            return false;
        }

        // Match operands
        java.util.List<SqlNode> actualOperands = actual.getOperandList();
        java.util.List<SqlNode> templateOperands = template.getOperandList();

        if (actualOperands.size() != templateOperands.size()) {
            return false;
        }

        for (int i = 0; i < actualOperands.size(); i++) {
            if (!matchNode(actualOperands.get(i), templateOperands.get(i), context)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Match node lists.
     */
    private boolean matchNodeList(final SqlNodeList actual, final SqlNodeList template, final MatchContext context) {
        if (actual == null && template == null) {
            return true;
        }
        if (actual == null || template == null) {
            return false;
        }

        // Check if template contains wildcard (*)
        if (template.size() == 1 && "*".equals(template.get(0).toString())) {
            // Wildcard matches any select list
            return true;
        }

        if (actual.size() != template.size()) {
            return false;
        }

        for (int i = 0; i < actual.size(); i++) {
            if (!matchNode(actual.get(i), template.get(i), context)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Match context holding the mapping state.
     */
    private static class MatchContext {
        private final Map<String, Object> mapping;

        MatchContext(final Map<String, Object> mapping) {
            this.mapping = mapping;
        }
    }
}

