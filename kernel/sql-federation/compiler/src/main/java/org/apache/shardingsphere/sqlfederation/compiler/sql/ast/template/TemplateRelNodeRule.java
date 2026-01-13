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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.calcite.rel.RelNode;
import org.apache.shardingsphere.sql.parser.api.ASTNode;

import java.util.Collection;

/**
 * Template rewrite rule using RelNode (instead of SqlNode).
 *
 * This is simpler than SqlNode-based approach because:
 * - No SQL validation needed
 * - No virtual schema needed
 * - Direct RelNode manipulation
 *
 * <p>Constraints are split into two categories:</p>
 * <ul>
 *   <li><b>Match Constraints</b>: Used for pattern matching.
 *       All parameters come from the source template only.
 *       Example: AttrsSub(a0, t0), Unique(t0, a0)</li>
 *   <li><b>Rewrite Constraints</b>: Used for rewriting the target template.
 *       Parameters come from both source and target templates.
 *       Example: TableEq(t1, t0) - t0 from source, t1 from target</li>
 * </ul>
 */
@RequiredArgsConstructor
@Getter
public final class TemplateRelNodeRule {

    /**
     * Source template as RelNode.
     */
    private final RelNode sourceTemplate;

    /**
     * Target template as RelNode.
     */
    private final RelNode targetTemplate;

    /**
     * Match constraints: Used for checking if the rule matches a query.
     * All parameters in these constraints come from the source template only.
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>AttrsSub(a0, t0) - check if attribute a0 belongs to table t0</li>
     *   <li>Unique(t0, a0) - check if a0 is unique in t0</li>
     * </ul>
     *
     * <p>These are evaluated during pattern matching to determine if the
     * rule can be applied to a given query.</p>
     */
    private final Collection<? extends ASTNode> matchConstraints;

    /**
     * Rewrite constraints: Used for rewriting the target template.
     * Parameters come from both source and target templates (cross-template).
     *
     * <p>Examples:</p>
     * <ul>
     *   <li>TableEq(t1, t0) - map target table t1 to source table t0</li>
     *   <li>AttrsEq(a1, a0) - map target attribute a1 to source attribute a0</li>
     *   <li>SchemaEq(s1, s0) - map target schema s1 to source schema s0</li>
     *   <li>PredicateEq(p1, p0) - map target predicate p1 to source predicate p0</li>
     * </ul>
     *
     * <p>These are used to fill in the placeholders in the target template
     * with actual values extracted from the matched query, based on the
     * source template mapping.</p>
     */
    private final Collection<? extends ASTNode> rewriteConstraints;
}

