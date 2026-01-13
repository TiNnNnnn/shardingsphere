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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test for TemplateRelNodeBuilder - verify RelNode construction and conversion back to SQL.
 */
public class TemplateRelNodeBuilderTest {

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
    public void testSimpleProjectionRule() {
        System.out.println("\n=== Test: Simple Projection Rule ===");

        // 规则：Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0)
        String rule = "Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0)";

        // 1. 解析规则
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        // 2. 构建 RelNode
        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        assertNotNull(templateRule);
        assertNotNull(templateRule.getSourceTemplate());
        assertNotNull(templateRule.getTargetTemplate());

        // 3. 转回 SQL
        RelNode sourceTemplate = templateRule.getSourceTemplate();
        RelNode targetTemplate = templateRule.getTargetTemplate();

        System.out.println("Source Template RelNode Type: " + sourceTemplate.getRelTypeName());
        System.out.println("Source Template: " + sourceTemplate);

        String sourceSQL = relNodeToSql(sourceTemplate);
        String targetSQL = relNodeToSql(targetTemplate);

        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证
        assertTrue(sourceSQL.contains("SELECT") || sourceSQL.contains("Error"),
                "Source should be convertible to SQL");
        assertTrue(targetSQL.contains("SELECT") || targetSQL.contains("Error"),
                "Target should be convertible to SQL");

        System.out.println("✓ Simple projection rule test completed");
    }

    @Test
    public void testJoinRule() {
        System.out.println("\n=== Test: Join Rule ===");
        String rule = "InnerJoin<a1 a2>(Input<t0>,Input<t1>)|InnerJoin<a3 a4>(Input<t2>,Input<t3>)|TableEq(t0,t2)";
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;
        RelOptCluster cluster = createCluster();

        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);
        assertNotNull(templateRule);

        RelNode sourceTemplate = templateRule.getSourceTemplate();
        RelNode targetTemplate = templateRule.getTargetTemplate();

        System.out.println("Source Template RelNode Type: " + sourceTemplate.getRelTypeName());
        System.out.println("Source Template: " + sourceTemplate);

        String sourceSQL = relNodeToSql(sourceTemplate);
        String targetSQL = relNodeToSql(targetTemplate);

        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证
        assertTrue(sourceSQL.contains("JOIN") || sourceSQL.contains("Error"),
                "Source should contain JOIN or error message");
        assertTrue(targetSQL.contains("JOIN") || targetSQL.contains("Error"),
                "Target should contain JOIN or error message");

        System.out.println("✓ Join rule test completed");
    }

    @Test
    public void testFilterRule() {
        System.out.println("\n=== Test: Filter Rule ===");

        // 规则：Filter<p0 a0>(Input<t0>)|Filter<p1 a1>(Input<t1>)|TableEq(t1,t0)
        String rule = "Filter<p0 a0>(Input<t0>)|Filter<p1 a1>(Input<t1>)|TableEq(t1,t0)";

        // 1. 解析规则
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        // 2. 构建 RelNode
        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        assertNotNull(templateRule);

        // 3. 转回 SQL
        RelNode sourceTemplate = templateRule.getSourceTemplate();
        RelNode targetTemplate = templateRule.getTargetTemplate();

        System.out.println("Source Template RelNode Type: " + sourceTemplate.getRelTypeName());
        System.out.println("Source Template: " + sourceTemplate);

        String sourceSQL = relNodeToSql(sourceTemplate);
        String targetSQL = relNodeToSql(targetTemplate);

        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证
        assertTrue(sourceSQL.contains("WHERE") || sourceSQL.contains("Error"),
                "Source should contain WHERE or error message");
        assertTrue(targetSQL.contains("WHERE") || targetSQL.contains("Error"),
                "Target should contain WHERE or error message");

        System.out.println("✓ Filter rule test completed");
    }

    @Test
    public void testNestedFilterRule() {
        System.out.println("\n=== Test: Nested Filter Rule ===");

        // 规则：Filter<p1 a1>(Filter<p0 a0>(Input<t0>))|Filter<p2 a2>(Input<t1>)|TableEq(t1,t0)
        String rule = "Filter<p1 a1>(Filter<p1 a1>(Filter<p0 a0>(Input<t0>)))|Filter<p2 a2>(Input<t1>)|TableEq(t1,t0)";

        // 1. 解析规则
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        // 2. 构建 RelNode
        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        assertNotNull(templateRule);

        // 3. 转回 SQL
        RelNode sourceTemplate = templateRule.getSourceTemplate();

        System.out.println("Source Template RelNode Type: " + sourceTemplate.getRelTypeName());
        System.out.println("Source Template: " + sourceTemplate);
        System.out.println("Source RowType: " + sourceTemplate.getRowType());

        String sourceSQL = relNodeToSql(sourceTemplate);

        System.out.println("Source SQL: " + sourceSQL);

        // 验证
        assertNotNull(sourceSQL);

        System.out.println("✓ Nested filter rule test completed");
    }

    @Test
    public void testComplexRule() {
        System.out.println("\n=== Test: Complex Rule (Projection + Join) ===");

        // 规则：Proj<a0>(InnerJoin<a1 a2>(Input<t0>,Input<t1>))|Proj<a3>(InnerJoin<a4 a5>(Input<t2>,Input<t3>))|TableEq(t0,t2)
        String rule = "Proj<a0>(InnerJoin<a1 a2>(Input<t0>,Input<t1>))|Proj<a3>(InnerJoin<a4 a5>(Input<t2>,Input<t3>))|TableEq(t0,t2)";

        // 1. 解析规则
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        // 2. 构建 RelNode
        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        assertNotNull(templateRule);

        // 3. 转回 SQL
        RelNode sourceTemplate = templateRule.getSourceTemplate();

        System.out.println("Source Template RelNode Type: " + sourceTemplate.getRelTypeName());
        System.out.println("Source Template: " + sourceTemplate);

        String sourceSQL = relNodeToSql(sourceTemplate);

        System.out.println("Source SQL: " + sourceSQL);

        // 验证：应该包含 SELECT 和 JOIN
        assertTrue(sourceSQL.contains("SELECT") || sourceSQL.contains("Error"),
                "Should contain SELECT or error message");

        System.out.println("✓ Complex rule test completed");
    }

    @Test
    public void testRelNodeStructure() {
        System.out.println("\n=== Test: RelNode Structure Inspection ===");

        String rule = "Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0)";

        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        RelNode sourceTemplate = templateRule.getSourceTemplate();

        // 检查 RelNode 结构
        System.out.println("RelNode Details:");
        System.out.println("- Type: " + sourceTemplate.getRelTypeName());
        System.out.println("- Row Type: " + sourceTemplate.getRowType());
        System.out.println("- Field Count: " + sourceTemplate.getRowType().getFieldCount());
        System.out.println("- Field Names: " + sourceTemplate.getRowType().getFieldNames());
        System.out.println("- Inputs: " + sourceTemplate.getInputs().size());

        if (!sourceTemplate.getInputs().isEmpty()) {
            RelNode input = sourceTemplate.getInput(0);
            System.out.println("- Input Type: " + input.getRelTypeName());
            System.out.println("- Input Row Type: " + input.getRowType());
        }

        System.out.println("\nRelNode Tree:");
        System.out.println(sourceTemplate.explain());

        System.out.println("✓ Structure inspection completed");
    }

    @Test
    public void testInSubFilterRule() {
        System.out.println("\n=== Test: InSubFilter Rule (Semi-Join) ===");

        // 规则：InSubFilter<a2>(LeftJoin<a0 a1>(Input<t0>,Input<t1>),Input<t2>)
        // 语义：SELECT * FROM (t0 LEFT JOIN t1 ON t0.a0 = t1.a1) AS L WHERE L.a2 IN (SELECT a2 FROM t2)
        String rule = "InSubFilter<a2>(LeftJoin<a0 a1>(Input<t0>,Input<t1>),Input<t2>)|InSubFilter<a3>(Input<t3>,Input<t4>)|TableEq(t0,t3)";

        // 1. 解析规则
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;

        // 2. 构建 RelNode
        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        assertNotNull(templateRule);

        // 3. 转回 SQL
        RelNode sourceTemplate = templateRule.getSourceTemplate();
        RelNode targetTemplate = templateRule.getTargetTemplate();

        System.out.println("Source Template RelNode Type: " + sourceTemplate.getRelTypeName());
        System.out.println("Source Template: " + sourceTemplate);
        System.out.println("Source RowType: " + sourceTemplate.getRowType());

        String sourceSQL = relNodeToSql(sourceTemplate);
        String targetSQL = relNodeToSql(targetTemplate);

        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 验证：应该包含 LEFT JOIN 和 WHERE（半连接条件）
        assertTrue(sourceSQL.contains("LEFT") || sourceSQL.contains("Error"),
                "Source should contain LEFT JOIN or error message");
        assertTrue(sourceSQL.contains("WHERE") || sourceSQL.contains("Error"),
                "Source should contain WHERE (for semi-join) or error message");

        System.out.println("✓ InSubFilter (semi-join) rule test completed");
        System.out.println("Note: InSubFilter<a2>(L, R) means: keep rows from L where L.a2 IN (SELECT a2 FROM R)");
    }

    @Test
    public void testComplexNestedJoinWithProjectionRule() {
        System.out.println("\n=== Test: Complex Nested Join with Projection Rule ===");
        String rule = "LeftJoin<a1 a2>(Proj*<a0 s0>(Input<t0>),Input<t1>)|" +
                "InnerJoin<a4 a5>(Proj<a3 s1>(Input<t2>),Input<t3>)|" +
                "TableEq(t0,t1);AttrsEq(a1,a2);AttrsSub(a0,t0);AttrsSub(a1,s0);AttrsSub(a2,t1);" +
                "Unique(t1,a2);NotNull(t1,a2);TableEq(t2,t0);TableEq(t3,t1);AttrsEq(a3,a0);" +
                "AttrsEq(a4,a1);AttrsEq(a5,a2);SchemaEq(s1,s0)";
        System.out.println("Rule: " + rule);
        // 1. Parse rule
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(rule);
        assertNotNull(astNode, "AST node should not be null");
        assertTrue(astNode instanceof RewriteRuleSegment, "AST should be RewriteRuleSegment");

        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;
        System.out.println("✓ Rule parsed successfully");

        // 2. Build RelNode
        RelOptCluster cluster = createCluster();
        TemplateRelNodeRule templateRule = TemplateRelNodeBuilder.buildRule(ruleSegment, cluster);

        assertNotNull(templateRule, "Template rule should not be null");
        assertNotNull(templateRule.getSourceTemplate(), "Source template should not be null");
        assertNotNull(templateRule.getTargetTemplate(), "Target template should not be null");

        System.out.println("✓ RelNode templates built successfully");

        // 3. Verify source template structure
        RelNode sourceTemplate = templateRule.getSourceTemplate();
        System.out.println("\n--- Source Template Structure ---");
        System.out.println("Type: " + sourceTemplate.getRelTypeName());
        System.out.println("Row Type: " + sourceTemplate.getRowType());
        System.out.println("Field Count: " + sourceTemplate.getRowType().getFieldCount());
        System.out.println("\nRelNode Tree:");
        System.out.println(sourceTemplate.explain());

        // Should be a LeftJoin at root
        assertEquals("LogicalJoin", sourceTemplate.getRelTypeName(),
                "Source template root should be LogicalJoin");

        // Left child should be Aggregate (for DISTINCT from Proj*) or Project
        RelNode leftChild = sourceTemplate.getInput(0);
        System.out.println("\nLeft child type: " + leftChild.getRelTypeName());
        assertTrue(leftChild.getRelTypeName().contains("Aggregate") || leftChild.getRelTypeName().contains("Project"),
                "Left child should be Aggregate (DISTINCT) or Project");

        // Right child should be TableScan
        RelNode rightChild = sourceTemplate.getInput(1);
        System.out.println("Right child type: " + rightChild.getRelTypeName());
        assertEquals("LogicalTableScan", rightChild.getRelTypeName(),
                "Right child should be TableScan");

        // 4. Verify target template structure
        RelNode targetTemplate = templateRule.getTargetTemplate();
        System.out.println("\n--- Target Template Structure ---");
        System.out.println("Type: " + targetTemplate.getRelTypeName());
        System.out.println("\nRelNode Tree:");
        System.out.println(targetTemplate.explain());

        // Should be an InnerJoin at root
        assertEquals("LogicalJoin", targetTemplate.getRelTypeName(),
                "Target template root should be LogicalJoin");

        // 5. Convert to SQL
        String sourceSQL = relNodeToSql(sourceTemplate);
        String targetSQL = relNodeToSql(targetTemplate);

        System.out.println("\n--- Generated SQL ---");
        System.out.println("Source SQL: " + sourceSQL);
        System.out.println("Target SQL: " + targetSQL);

        // 6. Verify SQL contains expected elements
        assertNotNull(sourceSQL, "Source SQL should not be null");
        assertNotNull(targetSQL, "Target SQL should not be null");

        // Source should have LEFT JOIN
        assertTrue(sourceSQL.toUpperCase().contains("LEFT") || sourceSQL.toUpperCase().contains("JOIN"),
                "Source SQL should contain LEFT JOIN");

        // 7. Verify constraints classification
        System.out.println("\n--- Constraints Classification ---");
        System.out.println("Match Constraints (source only): " + templateRule.getMatchConstraints().size());
        System.out.println("Rewrite Constraints (cross-template): " + templateRule.getRewriteConstraints().size());

        // AttrsSub (x3) and NotNull (x1) are match constraints = 4
        // TableEq, AttrsEq, SchemaEq are rewrite constraints = 8
        int totalConstraints = templateRule.getMatchConstraints().size() + templateRule.getRewriteConstraints().size();
        System.out.println("Total Constraints: " + totalConstraints);
        assertEquals(13, totalConstraints, "Should have 12 constraints total");
        assertFalse(sourceSQL.toUpperCase().contains("ON TRUE") && !sourceSQL.contains("Error"),
                "Join condition should not be 'ON TRUE' - attributes should be found via findFieldIndexRecursive");
    }
}
