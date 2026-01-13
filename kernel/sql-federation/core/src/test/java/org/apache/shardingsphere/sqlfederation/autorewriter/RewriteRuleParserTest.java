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
}

