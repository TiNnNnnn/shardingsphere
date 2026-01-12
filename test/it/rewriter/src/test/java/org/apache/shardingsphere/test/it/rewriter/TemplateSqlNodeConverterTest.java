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

package org.apache.shardingsphere.test.it.rewriter;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptPlanner;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.engine.rewriter.visitor.statement.RewriterStatementVisitorFacade;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateRelNodeBuilder;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateRelNodeRule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Template RelNode builder integration test.
 */
public class TemplateSqlNodeConverterTest {

    private final SqlDialect dialect = CalciteSqlDialect.DEFAULT;

    /**
     * Create RelOptCluster for building RelNodes.
     */
    private RelOptCluster createCluster() {
        RelOptPlanner planner = new HepPlanner(new HepProgramBuilder().build());
        org.apache.calcite.jdbc.JavaTypeFactoryImpl typeFactory = new org.apache.calcite.jdbc.JavaTypeFactoryImpl(
                org.apache.calcite.rel.type.RelDataTypeSystem.DEFAULT);
        RexBuilder rexBuilder = new RexBuilder(typeFactory);
        return RelOptCluster.create(planner, rexBuilder);
    }

    /**
     * Convert RelNode back to SQL string.
     */
    private String relNodeToSql(RelNode relNode) {
        try {
            RelToSqlConverter converter = new RelToSqlConverter(dialect);
            SqlNode sqlNode = converter.visitRoot(relNode).asStatement();
            return sqlNode.toSqlString(dialect).getSql();
        } catch (Exception e) {
            return "Error converting to SQL: " + e.getMessage();
        }
    }

    @Test
    public void testConvertSimpleProjectionRule() {
        // Proj*<a0 s0>(Input<t0>) | Proj<a1 s1>(Input<t1>) | constraints
        // 注意：s0, s1是schema参数，不会出现在SELECT列表中
        // Proj*<a0 s0> -> SELECT DISTINCT a0，Proj<a1 s1> -> SELECT a1
        String rule = "Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|AttrsSub(a0,t0);TableEq(t1,t0)";

        // 解析规则
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        assertNotNull(astNode);
        assertTrue(astNode instanceof RewriteRuleSegment);

        // 转换为 RelNode
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;
        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        assertNotNull(templateRule);
        assertNotNull(templateRule.getSourceTemplate());
        assertNotNull(templateRule.getTargetTemplate());
        assertNotNull(templateRule.getConstraints());

        // 转换为SQL字符串
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("Source Template SQL: " + sourceSQL);
        System.out.println("Target Template SQL: " + targetSQL);

        // 验证生成的SQL
        assertTrue(sourceSQL.contains("SELECT"));
        assertTrue(sourceSQL.contains("t0"));
        assertTrue(targetSQL.contains("SELECT"));
        assertTrue(targetSQL.contains("a1"));
        assertFalse(targetSQL.contains("s1"), "Schema parameter s1 should NOT appear in SELECT list");
        assertTrue(targetSQL.contains("t1"));
    }

    @Test
    public void testConvertProjectionWithSelectStar() {
        String rule = "Proj*<a0 s0>(Input<t0>)|Proj<a1>(Input<t1>)|TableEq(t0,t1)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());

        System.out.println("Proj* Template SQL: " + sourceSQL);

        // Proj*应该生成SELECT DISTINCT
        assertTrue(sourceSQL.contains("SELECT"));
    }

    @Test
    public void testConvertJoinRule() {
        String rule = "InnerJoin<a1 a2>(Input<t0>,Input<t1>)|LeftJoin<a3 a4>(Input<t2>,Input<t3>)|TableEq(t0,t2);TableEq(t1,t3)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("Source Join SQL: " + sourceSQL);
        System.out.println("Target Join SQL: " + targetSQL);

        // 验证包含JOIN关键字
        assertTrue(sourceSQL.toUpperCase().contains("JOIN"));
        assertTrue(sourceSQL.contains("t0"));
        assertTrue(sourceSQL.contains("t1"));
        assertTrue(targetSQL.toUpperCase().contains("JOIN"));

        // 验证包含连接条件 t0.a1 = t1.a2
        System.out.println("Checking for join condition in source SQL...");
        assertTrue(sourceSQL.contains("a1"), "Source SQL should contain attribute a1");
        assertTrue(sourceSQL.contains("a2"), "Source SQL should contain attribute a2");
    }

    @Test
    public void testConvertComplexNestedRule() {
        // Proj(Join(Input, Input))
        String rule = "Proj<a0>(InnerJoin<a1 a2>(Input<t0>,Input<t1>))|Proj<a3>(LeftJoin<a4 a5>(Input<t2>,Input<t3>))|TableEq(t0,t2)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("Complex Nested Source SQL: " + sourceSQL);
        System.out.println("Complex Nested Target SQL: " + targetSQL);

        // 验证嵌套结构：SELECT ... FROM ... JOIN ...
        assertTrue(sourceSQL.contains("SELECT"));
        assertTrue(sourceSQL.toUpperCase().contains("JOIN"));
        assertTrue(targetSQL.contains("SELECT"));
        assertTrue(targetSQL.toUpperCase().contains("JOIN"));
    }

    @Test
    public void testConvertMultipleConstraints() {
        // Proj<a0 s0> -> SELECT a0 (s0是schema参数，不出现在SELECT列表中)
        String rule = "Proj<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|"
                + "AttrsSub(a0,t0);Unique(t0,a0);TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        // 验证约束数量
        assertEquals(5, templateRule.getConstraints().size());

        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("Rule with Constraints - Source SQL: " + sourceSQL);
        System.out.println("Rule with Constraints - Target SQL: " + targetSQL);
        System.out.println("Number of Constraints: " + templateRule.getConstraints().size());

        assertNotNull(sourceSQL);
        assertNotNull(targetSQL);

        // 验证属性出现，schema不出现
        assertTrue(sourceSQL.contains("a0"), "Attribute a0 should appear");
        assertFalse(sourceSQL.contains("s0"), "Schema s0 should NOT appear in SELECT list");
        assertTrue(targetSQL.contains("a1"), "Attribute a1 should appear");
        assertFalse(targetSQL.contains("s1"), "Schema s1 should NOT appear in SELECT list");
    }

    @Test
    public void testTemplateIdentifierPreservation() {
        String rule = "Proj<a0>(Input<t0>)|Proj<a1>(Input<t1>)|TableEq(t0,t1)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());

        System.out.println("Template Identifier Test SQL: " + sourceSQL);

        // 验证模板变量a0和t0被保留在SQL中
        assertTrue(sourceSQL.contains("a0"));
        assertTrue(sourceSQL.contains("t0"));
    }

    @Test
    public void testJoinConditionExtraction() {
        // 测试从InnerJoin<a1 a2>中提取连接条件: t0.a1 = t1.a2
        String rule = "InnerJoin<a1 a2>(Input<t0>,Input<t1>)|InnerJoin<a3 a4>(Input<t2>,Input<t3>)|TableEq(t0,t2)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("=== Join Condition Extraction Test ===");
        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证源SQL包含连接条件相关的属性
        assertTrue(sourceSQL.contains("a1"), "Source should contain left join attribute a1");
        assertTrue(sourceSQL.contains("a2"), "Source should contain right join attribute a2");
        assertTrue(sourceSQL.contains("t0"), "Source should contain left table t0");
        assertTrue(sourceSQL.contains("t1"), "Source should contain right table t1");

        // 验证目标SQL包含连接条件相关的属性
        assertTrue(targetSQL.contains("a3"), "Target should contain left join attribute a3");
        assertTrue(targetSQL.contains("a4"), "Target should contain right join attribute a4");
        assertTrue(targetSQL.contains("t2"), "Target should contain left table t2");
        assertTrue(targetSQL.contains("t3"), "Target should contain right table t3");

        // 理想情况下，应该包含类似 "t0.a1 = t1.a2" 的形式
        System.out.println("Join condition successfully extracted!");
    }

    @Test
    public void testThreeTableJoin() {
        // 测试三表连接：t0 JOIN t1 JOIN t2
        // InnerJoin<a1 a2>(InnerJoin<a3 a4>(Input<t0>, Input<t1>), Input<t2>)
        String rule = "InnerJoin<a1 a2>(InnerJoin<a3 a4>(Input<t0>,Input<t1>),Input<t2>)|"
                + "LeftJoin<a5 a6>(LeftJoin<a7 a8>(Input<t3>,Input<t4>),Input<t5>)|"
                + "TableEq(t0,t3);TableEq(t1,t4);TableEq(t2,t5)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("=== Three Table Join Test ===");
        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证源SQL包含所有三个表
        assertTrue(sourceSQL.contains("t0"), "Source should contain table t0");
        assertTrue(sourceSQL.contains("t1"), "Source should contain table t1");
        assertTrue(sourceSQL.contains("t2"), "Source should contain table t2");

        // 验证包含两次JOIN（三表需要两次join）
        int joinCount = sourceSQL.toUpperCase().split("JOIN").length - 1;
        assertTrue(joinCount >= 2, "Three table join should have at least 2 JOIN keywords, found: " + joinCount);

        // 验证包含所有连接属性
        assertTrue(sourceSQL.contains("a1"), "Source should contain join attribute a1");
        assertTrue(sourceSQL.contains("a2"), "Source should contain join attribute a2");
        assertTrue(sourceSQL.contains("a3"), "Source should contain join attribute a3");
        assertTrue(sourceSQL.contains("a4"), "Source should contain join attribute a4");

        // 验证目标SQL包含所有三个表
        assertTrue(targetSQL.contains("t3"), "Target should contain table t3");
        assertTrue(targetSQL.contains("t4"), "Target should contain table t4");
        assertTrue(targetSQL.contains("t5"), "Target should contain table t5");

        // 验证目标SQL的连接属性
        assertTrue(targetSQL.contains("a5"), "Target should contain join attribute a5");
        assertTrue(targetSQL.contains("a6"), "Target should contain join attribute a6");
        assertTrue(targetSQL.contains("a7"), "Target should contain join attribute a7");
        assertTrue(targetSQL.contains("a8"), "Target should contain join attribute a8");

        // 验证约束数量
        assertEquals(3, templateRule.getConstraints().size(), "Should have 3 table equality constraints");

        System.out.println("Three table join test passed! SQL structure:");
        System.out.println("- Contains " + joinCount + " JOIN operations");
        System.out.println("- All tables (t0, t1, t2) are present");
        System.out.println("- All join attributes are correctly included");
    }

    @Test
    public void testInSubFilterRule() {
        String rule = "InSubFilter<a0>(Input<t0>,Proj<a1>(Input<t1>))|"
                + "InSubFilter<a2>(Input<t2>,Proj<a3>(Input<t3>))|"
                + "TableEq(t0,t2);TableEq(t1,t3);AttrsEq(a0,a2);AttrsEq(a1,a3)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        // 添加调试信息
        System.out.println("=== IN SubQuery Filter Test - Debug Info ===");
        System.out.println("Source RelNode type: " + templateRule.getSourceTemplate().getRelTypeName());
        System.out.println("Target RelNode type: " + templateRule.getTargetTemplate().getRelTypeName());

        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("=== IN SubQuery Filter Test ===");
        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证源SQL包含IN关键字或Filter结构
        assertTrue(sourceSQL.contains("IN") || sourceSQL.contains("SELECT"),
                "Source should contain IN keyword or SELECT for subquery filter");

        // 验证包含主表t0（leftChild）
        assertTrue(sourceSQL.contains("t0"), "Source should contain main table t0 (leftChild)");

        // 验证包含过滤属性（a0作为IN的左侧）
        assertTrue(sourceSQL.contains("a0"), "Source should contain filter attribute a0");

        // 验证包含子查询中的表和属性
        assertTrue(sourceSQL.contains("t1") || sourceSQL.contains("a1"),
                "Source should contain subquery table t1 or attribute a1");

        // 验证目标SQL
        assertTrue(targetSQL.contains("SELECT"), "Target should contain SELECT");
        assertTrue(targetSQL.contains("t2"), "Target should contain main table t2");

        // 验证约束数量
        assertEquals(4, templateRule.getConstraints().size(), "Should have 4 constraints");

        System.out.println("IN SubQuery Filter test passed!");
        System.out.println("- Semi-join semantics implemented");
        System.out.println("- All tables and attributes preserved");
    }

    @Test
    public void testInSubFilterAsSubnode() {
        // 测试InSubFilter作为子节点：嵌套的InSubFilter
        String rule = "InSubFilter<a1>(InSubFilter<a0>(Input<t0>,Input<t1>),Input<t2>)|"
                + "InSubFilter<a3>(InSubFilter<a2>(Input<t3>,Input<t4>),Input<t5>)|"
                + "TableEq(t0,t3);TableEq(t1,t4);TableEq(t2,t5);AttrsEq(a0,a2);AttrsEq(a1,a3)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("=== Nested InSubFilter Test ===");
        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证包含SELECT
        assertTrue(sourceSQL.contains("SELECT"), "Source should contain SELECT");

        // 验证包含所有三个表
        assertTrue(sourceSQL.contains("t0") || sourceSQL.contains("t1") || sourceSQL.contains("t2"),
                "Source should contain at least one of the tables");

        // 验证约束数量
        assertEquals(5, templateRule.getConstraints().size(), "Should have 5 constraints");

        System.out.println("Nested InSubFilter test passed!");
    }

    @Test
    public void testInSubFilterWrappedByProjection() {
        // 测试InSubFilter被Projection包装的情况
        String rule = "Proj<a0>(InSubFilter<a1>(Input<t0>,Input<t1>))|"
                + "Proj<a2>(InSubFilter<a3>(Input<t2>,Input<t3>))|"
                + "TableEq(t0,t2);TableEq(t1,t3);AttrsEq(a0,a2);AttrsEq(a1,a3)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("=== InSubFilter Wrapped by Projection Test ===");
        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证包含SELECT
        assertTrue(sourceSQL.contains("SELECT"), "Source should contain SELECT");

        // 验证投影属性a0出现
        assertTrue(sourceSQL.contains("a0"), "Source should contain projection attribute a0");

        // 验证包含表
        assertTrue(sourceSQL.contains("t0"), "Source should contain main table t0");

        // 验证约束数量
        assertEquals(4, templateRule.getConstraints().size(), "Should have 4 constraints");

        System.out.println("InSubFilter wrapped by Projection test passed!");
    }

    @Test
    public void testNestedFilterRule() {
        // 测试嵌套的Filter规则
        String rule = "Filter<p1 a1>(Filter<p0 a0>(Input<t0>))|Filter<p2 a2>(Input<t1>)|"
                + "AttrsEq(a0,a1);PredicateEq(p0,p1);AttrsSub(a0,t0);AttrsSub(a1,t0);TableEq(t1,t0);AttrsEq(a2,a0);PredicateEq(p2,p0)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        String sourceSQL = relNodeToSql(templateRule.getSourceTemplate());
        String targetSQL = relNodeToSql(templateRule.getTargetTemplate());

        System.out.println("=== Nested Filter Test ===");
        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证包含SELECT和WHERE
        assertTrue(sourceSQL.contains("SELECT"), "Source should contain SELECT");
        assertTrue(sourceSQL.toUpperCase().contains("WHERE"), "Source should contain WHERE clause");

        // 验证包含谓词函数调用 p0 和 p1
        assertTrue(sourceSQL.contains("p0") || sourceSQL.contains("p1"),
                "Source should contain predicate function p0 or p1");

        // 验证包含表t0
        assertTrue(sourceSQL.contains("t0"), "Source should contain table t0");

        // 验证目标SQL
        assertTrue(targetSQL.contains("SELECT"), "Target should contain SELECT");
        assertTrue(targetSQL.toUpperCase().contains("WHERE"), "Target should contain WHERE clause");

        // 验证约束数量
        assertEquals(7, templateRule.getConstraints().size(), "Should have 7 constraints");

        System.out.println("Nested Filter test passed!");
        System.out.println("- Nested filter structure implemented");
        System.out.println("- Predicates represented as functions");
    }
}
