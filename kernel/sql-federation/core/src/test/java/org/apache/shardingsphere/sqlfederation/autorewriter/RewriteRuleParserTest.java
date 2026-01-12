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
        assertNotNull(rule.getConstraints());
        assertEquals(3, rule.getConstraints().size());

        System.out.println("✓ Simple projection rule parsed successfully");
        System.out.println("  Source: " + rule.getSourceTemplate().getRelTypeName());
        System.out.println("  Target: " + rule.getTargetTemplate().getRelTypeName());
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
}

