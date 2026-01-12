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

package org.apache.shardingsphere.sqlfederation.autorewriter;

import lombok.RequiredArgsConstructor;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rex.RexBuilder;
import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.engine.rewriter.visitor.statement.RewriterStatementVisitorFacade;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateRelNodeBuilder;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateRelNodeRule;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Rewrite rule parser.
 * Parses rewrite rule strings into TemplateRelNodeRule objects that can be used for query rewriting.
 *
 * <p>This parser uses TemplateRelNodeBuilder to directly construct RelNode templates,
 * which is simpler and more efficient than going through SqlNode conversion.</p>
 *
 * <p>Example rule format:
 * {@code Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)}
 * </p>
 */
@RequiredArgsConstructor
public class RewriteRuleParser {

    private final RewriterStatementVisitorFacade visitorFacade;

    private final RelOptCluster cluster;

    /**
     * Parse a single rewrite rule from string.
     *
     * @param ruleText rewrite rule text in format: source|target|constraints
     * @return parsed template rewrite rule (RelNode version)
     */
    public TemplateRelNodeRule parse(final String ruleText) {
        // Step 1: Parse rule text to AST using ANTLR4
        ASTNode astNode = visitorFacade.parseRule(ruleText);

        // Step 2: Convert AST to RewriteRuleSegment
        if (!(astNode instanceof RewriteRuleSegment)) {
            throw new IllegalArgumentException("Invalid rule format: " + ruleText);
        }
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        // Step 3: Convert RewriteRuleSegment to TemplateRelNodeRule (directly build RelNode)
        return TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
    }

    /**
     * Validate a rewrite rule without parsing it completely.
     *
     * @param ruleText rewrite rule text
     * @return true if the rule syntax is valid, false otherwise
     */
    public boolean validate(final String ruleText) {
        try {
            ASTNode astNode = visitorFacade.parseRule(ruleText);
            return astNode instanceof RewriteRuleSegment;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract constraints from a rewrite rule.
     *
     * @param ruleText rewrite rule text
     * @return collection of constraints
     */
    public Collection<ConstraintSegment> extractConstraints(final String ruleText) {
        ASTNode astNode = visitorFacade.parseRule(ruleText);
        if (astNode instanceof RewriteRuleSegment) {
            RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;
            Collection<ConstraintSegment> constraints = new ArrayList<>();
            for (ASTNode node : ruleSegment.getConstraints()) {
                if (node instanceof ConstraintSegment) {
                    constraints.add((ConstraintSegment) node);
                }
            }
            return constraints;
        }
        throw new IllegalArgumentException("Invalid rule format: " + ruleText);
    }

    /**
     * Create a default RewriteRuleParser instance with default RelOptCluster.
     *
     * @return new parser instance
     */
    public static RewriteRuleParser createParser() {
        return new RewriteRuleParser(new RewriterStatementVisitorFacade(), createDefaultCluster());
    }

    /**
     * Create RelOptCluster for building RelNodes.
     */
    private static RelOptCluster createDefaultCluster() {
        RelOptPlanner planner = new HepPlanner(new HepProgramBuilder().build());
        org.apache.calcite.jdbc.JavaTypeFactoryImpl typeFactory = new org.apache.calcite.jdbc.JavaTypeFactoryImpl(
                org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
        RexBuilder rexBuilder = new RexBuilder(typeFactory);
        return RelOptCluster.create(planner, rexBuilder);
    }
}
