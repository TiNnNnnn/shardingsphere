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

package org.apache.shardingsphere.sql.parser.engine.rewriter;

import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.engine.rewriter.visitor.statement.RewriterStatementVisitorFacade;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rewriter statement visitor facade test.
 */
public class RewriterRuleParserFacadeTest {

    @Test
    public void testParseSingleRule() {
        String rule = "Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|AttrsSub(a0,t0);Unique(t0,a0);TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)";
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode result = facade.parseRule(rule);

        assertNotNull(result);
        assertTrue(result instanceof RewriteRuleSegment);

        RewriteRuleSegment rewriteRule = (RewriteRuleSegment) result;
        assertNotNull(rewriteRule.getSource());
        assertNotNull(rewriteRule.getTarget());
        assertEquals(5, rewriteRule.getConstraints().size());
    }

    @Test
    public void testParseRulesFromFile() throws IOException {
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        List<ASTNode> rules = facade.parseRulesFromFile("example/6t2n_normal_reduce.txt");

        assertNotNull(rules);
        assertFalse(rules.isEmpty());
        System.out.println("Parsed " + rules.size() + " rules from file");

        // Print first rule as example
        if (!rules.isEmpty()) {
            ASTNode firstRule = rules.get(0);
            System.out.println("First rule: " + firstRule);
            assertTrue(firstRule instanceof RewriteRuleSegment);
        }
    }

    @Test
    public void testParseComplexJoinRule() {
        String rule = "InnerJoin<a1 a2>(Proj*<a0 s0>(Input<t0>),Input<t1>)|LeftJoin<a4 a5>(Proj<a3 s1>(Input<t2>),Input<t3>)|"
                + "TableEq(t0,t1);AttrsEq(a1,a2);AttrsSub(a0,t0);AttrsSub(a1,s0);AttrsSub(a2,t1);Unique(t1,a2);NotNull(t1,a2);"
                + "TableEq(t2,t0);TableEq(t3,t1);AttrsEq(a3,a0);AttrsEq(a4,a1);AttrsEq(a5,a2);SchemaEq(s1,s0)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode result = facade.parseRule(rule);

        assertNotNull(result);
        assertTrue(result instanceof RewriteRuleSegment);

        RewriteRuleSegment rewriteRule = (RewriteRuleSegment) result;
        assertNotNull(rewriteRule.getSource());
        assertNotNull(rewriteRule.getTarget());
        assertTrue(rewriteRule.getConstraints().size() > 10);
    }

    @Test
    public void testParseFilterRule() {
        String rule = "Filter<p0 a2>(LeftJoin<a0 a1>(Input<t0>,Input<t1>))|Filter<p1 a5>(LeftJoin<a3 a4>(Input<t2>,Input<t3>))|"
                + "AttrsEq(a0,a2);AttrsSub(a0,t0);AttrsSub(a1,t1);AttrsSub(a2,t0);Reference(t0,a0,t1,a1);"
                + "TableEq(t2,t0);TableEq(t3,t1);AttrsEq(a3,a0);AttrsEq(a4,a1);AttrsEq(a5,a1);PredicateEq(p1,p0)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode result = facade.parseRule(rule);

        assertNotNull(result);
        assertTrue(result instanceof RewriteRuleSegment);

        RewriteRuleSegment rewriteRule = (RewriteRuleSegment) result;
        assertNotNull(rewriteRule.getSource());
        assertNotNull(rewriteRule.getTarget());
        assertFalse(rewriteRule.getConstraints().isEmpty());
    }
}

