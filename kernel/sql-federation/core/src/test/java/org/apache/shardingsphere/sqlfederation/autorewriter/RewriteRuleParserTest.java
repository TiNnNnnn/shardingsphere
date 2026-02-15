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

import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateRelNodeRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for RewriteRuleParser using TemplateRelNodeBuilder.
 */
public class RewriteRuleParserTest {

    @Test
    public void testParseSimpleProjectionRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();
        String ruleText = "Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)";

        TemplateRelNodeRule rule = parser.parse(ruleText);

        assertNotNull(rule);
        assertNotNull(rule.getSourceTemplate());
        assertNotNull(rule.getTargetTemplate());
        assertNotNull(rule.getMatchConstraints());
        assertNotNull(rule.getRewriteConstraints());

        // All 3 constraints are rewrite constraints (TableEq, AttrsEq, SchemaEq)
        assertEquals(0, rule.getMatchConstraints().size(), "No match constraints expected");
        assertEquals(3, rule.getRewriteConstraints().size(), "Should have 3 rewrite constraints");

        System.out.println("✓ Simple projection rule parsed successfully");
        System.out.println("  Source: " + rule.getSourceTemplate().getRelTypeName());
        System.out.println("  Target: " + rule.getTargetTemplate().getRelTypeName());
        System.out.println("  Match Constraints: " + rule.getMatchConstraints().size());
        System.out.println("  Rewrite Constraints: " + rule.getRewriteConstraints().size());
    }

    @Test
    public void testValidateRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();

        // Valid rule
        String validRule = "Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0)";
        assertTrue(parser.validate(validRule));

        // Invalid rule
        String invalidRule = "Invalid rule syntax!!!";
        assertFalse(parser.validate(invalidRule));

        System.out.println("✓ Rule validation works correctly");
    }

    @Test
    public void testParseJoinRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();
        String ruleText = "InnerJoin<a1 a2>(Input<t0>,Input<t1>)|InnerJoin<a3 a4>(Input<t2>,Input<t3>)|TableEq(t2,t1);TableEq(t3,t0)";

        TemplateRelNodeRule rule = parser.parse(ruleText);

        assertNotNull(rule);
        assertNotNull(rule.getSourceTemplate());
        assertNotNull(rule.getTargetTemplate());
        assertEquals("LogicalJoin", rule.getSourceTemplate().getRelTypeName());

        System.out.println("✓ Join rule parsed successfully");
    }

    @Test
    public void testParseFilterRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();
        String ruleText = "Filter<p0 a0>(Input<t0>)|Filter<p1 a1>(Input<t1>)|TableEq(t1,t0)";

        TemplateRelNodeRule rule = parser.parse(ruleText);

        assertNotNull(rule);
        assertEquals("LogicalFilter", rule.getSourceTemplate().getRelTypeName());
        assertEquals("LogicalFilter", rule.getTargetTemplate().getRelTypeName());

        System.out.println("✓ Filter rule parsed successfully");
    }

    @Test
    public void testParseInvalidRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();
        String invalidRule = "This is not a valid rule";

        assertThrows(IllegalArgumentException.class, () -> {
            parser.parse(invalidRule);
        });

        System.out.println("✓ Invalid rule throws exception as expected");
    }

    @Test
    public void testConstraintClassification() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();

        // Rule with both match and rewrite constraints
        String ruleText = "Proj<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|"
                + "AttrsSub(a0,t0);Unique(t0,a0);TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)";

        TemplateRelNodeRule rule = parser.parse(ruleText);

        assertNotNull(rule);

        // Match constraints: AttrsSub, Unique (only source template parameters)
        assertEquals(2, rule.getMatchConstraints().size(),
                "Should have 2 match constraints: AttrsSub and Unique");

        // Rewrite constraints: TableEq, AttrsEq, SchemaEq (cross-template parameters)
        assertEquals(3, rule.getRewriteConstraints().size(),
                "Should have 3 rewrite constraints: TableEq, AttrsEq, SchemaEq");

        System.out.println("✓ Constraint classification test passed");
        System.out.println("  Match Constraints (source only): " + rule.getMatchConstraints().size());
        System.out.println("  Rewrite Constraints (cross-template): " + rule.getRewriteConstraints().size());
    }

    @Test
    public void testParseInSubFilterRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();

        // IN subquery filter to semi-join conversion rule
        String ruleText = "InSubFilter<a1>(Input<t0>,Proj<a0 s0>(Input<t1>))|Input<t2>|"
                + "TableEq(t0,t1);AttrsEq(a0,a1);AttrsSub(a0,t1);AttrsSub(a1,t0);NotNull(t1,a0);NotNull(t0,a1);TableEq(t2,t0)";

        TemplateRelNodeRule rule = parser.parse(ruleText);

        assertNotNull(rule);
        assertNotNull(rule.getSourceTemplate());
        assertNotNull(rule.getTargetTemplate());
        assertNotNull(rule.getMatchConstraints());
        assertNotNull(rule.getRewriteConstraints());

        // Verify source template is InSubFilter
        assertEquals("LogicalFilter", rule.getSourceTemplate().getRelTypeName());

        // Verify target template is Input (representing the optimized form)
        assertEquals("LogicalTableScan", rule.getTargetTemplate().getRelTypeName());

        // Match constraints: TableEq(t0,t1), AttrsEq(a0,a1), AttrsSub(a0,t1), AttrsSub(a1,t0), NotNull(t1,a0), NotNull(t0,a1)
        // - all parameters are from source template only
        assertEquals(6, rule.getMatchConstraints().size(),
                "Should have 6 match constraints: TableEq(t0,t1), AttrsEq(a0,a1), AttrsSub(a0,t1), AttrsSub(a1,t0), NotNull(t1,a0), NotNull(t0,a1)");

        // Rewrite constraints: TableEq(t2,t0) - cross-template parameters (t2 from target, t0 from source)
        assertEquals(1, rule.getRewriteConstraints().size(),
                "Should have 1 rewrite constraint: TableEq(t2,t0)");

        System.out.println("✓ InSubFilter rule parsed successfully");
        System.out.println("  Source: " + rule.getSourceTemplate().getRelTypeName());
        System.out.println("  Target: " + rule.getTargetTemplate().getRelTypeName());
        System.out.println("  Match Constraints: " + rule.getMatchConstraints().size());
        System.out.println("  Rewrite Constraints: " + rule.getRewriteConstraints().size());
    }

    @Test
    public void testParseNestedProjectionRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();

        // Nested projection optimization rule: Proj(Proj*(...)) -> Proj*(...)
        // Eliminates redundant outer projection when inner projection is already DISTINCT
        String ruleText = "Proj<a1 s1>(Proj*<a0 s0>(Input<t0>))|Proj*<a2 s2>(Input<t1>)|"
                + "AttrsEq(a0,a1);AttrsSub(a0,t0);AttrsSub(a1,s0);TableEq(t1,t0);AttrsEq(a2,a0);SchemaEq(s2,s1)";

        TemplateRelNodeRule rule = parser.parse(ruleText);

        assertNotNull(rule);
        assertNotNull(rule.getSourceTemplate());
        assertNotNull(rule.getTargetTemplate());
        assertNotNull(rule.getMatchConstraints());
        assertNotNull(rule.getRewriteConstraints());

        // Verify source template is nested projection: Proj(Proj*(...))
        assertEquals("LogicalProject", rule.getSourceTemplate().getRelTypeName());

        // Verify target template is single DISTINCT projection
        assertEquals("LogicalAggregate", rule.getTargetTemplate().getRelTypeName(),
                "Target should be LogicalAggregate (representing Proj* with DISTINCT)");

        // Match constraints: AttrsEq(a0,a1), AttrsSub(a0,t0), AttrsSub(a1,s0)
        // - all parameters (a0, a1, t0, s0) are from source template only
        assertEquals(3, rule.getMatchConstraints().size(),
                "Should have 3 match constraints: AttrsEq(a0,a1), AttrsSub(a0,t0), AttrsSub(a1,s0)");

        // Rewrite constraints: TableEq(t1,t0), AttrsEq(a2,a0), SchemaEq(s2,s1)
        // - cross-template parameters (t1, a2, s2 from target; t0, a0, s1 from source)
        assertEquals(3, rule.getRewriteConstraints().size(),
                "Should have 3 rewrite constraints: TableEq(t1,t0), AttrsEq(a2,a0), SchemaEq(s2,s1)");

        System.out.println("✓ Nested projection rule parsed successfully");
        System.out.println("  Source: " + rule.getSourceTemplate().getRelTypeName() + " (nested projection)");
        System.out.println("  Target: " + rule.getTargetTemplate().getRelTypeName() + " (DISTINCT projection)");
        System.out.println("  Match Constraints: " + rule.getMatchConstraints().size());
        System.out.println("  Rewrite Constraints: " + rule.getRewriteConstraints().size());
    }

    @Test
    public void testParseComplexFilterLeftJoinToInnerJoinRule() {
        RewriteRuleParser parser = RewriteRuleParser.createParser();

        // Complex rule: Filter(LeftJoin(...)) -> InnerJoin(...)
        // Converts a filtered left join to an inner join when the filter condition
        // ensures that the right side must have matching rows
        String ruleText = "Filter<p0 a4>(LeftJoin<a2 a3>(Proj<a0 s0>(Input<t0>),Proj*<a1 s1>(Input<t1>)))|"
                + "InnerJoin<a8 a9>(Proj<a6 s2>(Filter<p1 a5>(Input<t2>)),Proj*<a7 s3>(Input<t3>))|"
                + "TableEq(t0,t1);AttrsEq(a0,a3);AttrsEq(a1,a2);AttrsEq(a1,a4);AttrsEq(a2,a4);"
                + "AttrsSub(a0,t0);AttrsSub(a1,t1);AttrsSub(a2,s0);AttrsSub(a3,s1);AttrsSub(a4,s1);"
                + "Unique(t0,a0);NotNull(t1,a1);"
                + "TableEq(t2,t0);TableEq(t3,t1);AttrsEq(a5,a2);AttrsEq(a6,a2);AttrsEq(a7,a3);"
                + "AttrsEq(a8,a2);AttrsEq(a9,a4);PredicateEq(p1,p0);SchemaEq(s2,s0);SchemaEq(s3,s1)";

        TemplateRelNodeRule rule = parser.parse(ruleText);

        assertNotNull(rule);
        assertNotNull(rule.getSourceTemplate());
        assertNotNull(rule.getTargetTemplate());
        assertNotNull(rule.getMatchConstraints());
        assertNotNull(rule.getRewriteConstraints());

        // Verify source template is Filter on top of LeftJoin
        assertEquals("LogicalFilter", rule.getSourceTemplate().getRelTypeName(),
                "Source should be LogicalFilter");

        // Verify target template is InnerJoin
        assertEquals("LogicalJoin", rule.getTargetTemplate().getRelTypeName(),
                "Target should be LogicalJoin (InnerJoin)");

        // Match constraints: all constraints with only source template parameters
        // Source parameters: t0, t1, a0, a1, a2, a3, a4, s0, s1, p0
        // Match constraints should include:
        // - TableEq(t0,t1), AttrsEq(a0,a3), AttrsEq(a1,a2), AttrsEq(a1,a4), AttrsEq(a2,a4)
        // - AttrsSub(a0,t0), AttrsSub(a1,t1), AttrsSub(a2,s0), AttrsSub(a3,s1), AttrsSub(a4,s1)
        // - Unique(t0,a0), NotNull(t1,a1)
        assertEquals(12, rule.getMatchConstraints().size(),
                "Should have 12 match constraints from source template only");

        // Rewrite constraints: cross-template constraints
        // Target parameters: t2, t3, a5, a6, a7, a8, a9, s2, s3, p1
        // Rewrite constraints should include:
        // - TableEq(t2,t0), TableEq(t3,t1)
        // - AttrsEq(a5,a2), AttrsEq(a6,a2), AttrsEq(a7,a3), AttrsEq(a8,a2), AttrsEq(a9,a4)
        // - PredicateEq(p1,p0), SchemaEq(s2,s0), SchemaEq(s3,s1)
        assertEquals(10, rule.getRewriteConstraints().size(),
                "Should have 10 rewrite constraints (cross-template)");

        System.out.println("✓ Complex Filter+LeftJoin to InnerJoin rule parsed successfully");
        System.out.println("  Source: " + rule.getSourceTemplate().getRelTypeName() + " (Filter on LeftJoin)");
        System.out.println("  Target: " + rule.getTargetTemplate().getRelTypeName() + " (InnerJoin)");
        System.out.println("  Match Constraints: " + rule.getMatchConstraints().size());
        System.out.println("  Rewrite Constraints: " + rule.getRewriteConstraints().size());
        System.out.println("  Optimization: Converts filtered left join to inner join when filter ensures right side match");
    }
}

