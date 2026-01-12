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

package org.apache.shardingsphere.sql.parser.engine.rewriter.visitor.statement;

import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.autogen.RewriterStatementBaseVisitor;
import org.apache.shardingsphere.sql.parser.autogen.RewriterStatementParser;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.column.ColumnSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.expr.ExpressionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ColumnProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.predicate.WhereSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.table.SimpleTableSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.table.TableNameSegment;
//import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.table.TableSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintType;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateExpressionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateFilterSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateInSubFilterSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateJoinSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.value.identifier.IdentifierValue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Rewriter statement visitor.
 */
public final class RewriterStatementVisitor extends RewriterStatementBaseVisitor<ASTNode> {

    @Override
    public ASTNode visitRule(final RewriterStatementParser.RuleContext ctx) {
        // Rule: sourceTemplate | targetTemplate | constraints
        ASTNode sourceTemplate = visit(ctx.template(0));
        ASTNode targetTemplate = visit(ctx.template(1));
        Collection<ASTNode> constraints = getConstraintSet(ctx.constraintSet());
        // Return a composite structure - implementation specific
        return new RewriteRuleSegment(sourceTemplate, targetTemplate, constraints);
    }

    @Override
    public ASTNode visitProjectionTemplate(final RewriterStatementParser.ProjectionTemplateContext ctx) {
        // Proj<a0 s0>(child) or Proj*<a0 s0>(child) -> ProjectionSegment
        String operator = ctx.projectionOperator().getText();
        List<String> parameters = getParameterList(ctx.parameterList());
        ASTNode child = visit(ctx.template());

        // Create projection segments from parameters
        List<ProjectionSegment> projections = new ArrayList<>();
        for (String param : parameters) {
            ColumnSegment column = new ColumnSegment(0, 0, new IdentifierValue(param));
            projections.add(new ColumnProjectionSegment(column));
        }

        return new TemplateProjectionSegment(operator, projections, child);
    }

    @Override
    public ASTNode visitInputTemplate(final RewriterStatementParser.InputTemplateContext ctx) {
        // Input<t0> -> SimpleTableSegment
        String tableId = ctx.IDENTIFIER().getText();
        TableNameSegment tableName = new TableNameSegment(0, 0, new IdentifierValue(tableId));
        return new SimpleTableSegment(tableName);
    }

    @Override
    public ASTNode visitJoinTemplate(final RewriterStatementParser.JoinTemplateContext ctx) {
        // InnerJoin<a1 a2>(left, right) or LeftJoin<a1 a2>(left, right)
        // 提取连接属性
        String leftAttribute = ctx.IDENTIFIER(0).getText();   // a1
        String rightAttribute = ctx.IDENTIFIER(1).getText();  // a2

        ASTNode left = visit(ctx.template(0));
        ASTNode right = visit(ctx.template(1));

        // 总是返回TemplateJoinSegment以保留连接属性信息（a1, a2）
        // 这样在转换为SqlNode时才能构造正确的JOIN条件：t0.a1 = t1.a2
        return new TemplateJoinSegment(ctx.joinOperator().getText(), leftAttribute, rightAttribute, left, right);
    }

    @Override
    public ASTNode visitFilterTemplate(final RewriterStatementParser.FilterTemplateContext ctx) {
        // Filter<p0 a0>(child) -> TemplateFilterSegment
        String predicateId = ctx.IDENTIFIER(0).getText();
        String attrId = ctx.IDENTIFIER(1).getText();

        // 递归访问子节点（可能是表、另一个Filter等）
        ASTNode child = visit(ctx.template());

        return new TemplateFilterSegment(predicateId, attrId, child);
    }

    @Override
    public ASTNode visitInSubFilterTemplate(final RewriterStatementParser.InSubFilterTemplateContext ctx) {
        // InSubFilter<a0>(child, subquery) -> TemplateInSubFilterSegment
        // 提取属性名
        String attrId = ctx.IDENTIFIER().getText();  // a0

        // 提取左子节点和子查询
        ASTNode leftChild = visit(ctx.template(0));   // child
        ASTNode subquery = visit(ctx.template(1));     // subquery

        return new TemplateInSubFilterSegment(attrId, leftChild, subquery);
    }

    private List<String> getParameterList(final RewriterStatementParser.ParameterListContext ctx) {
        return ctx.IDENTIFIER().stream()
                .map(node -> node.getText())
                .collect(Collectors.toList());
    }

    private Collection<ASTNode> getConstraintSet(final RewriterStatementParser.ConstraintSetContext ctx) {
        List<ASTNode> constraints = new ArrayList<>();
        for (RewriterStatementParser.ConstraintContext constraintCtx : ctx.constraint()) {
            constraints.add(visit(constraintCtx));
        }
        return constraints;
    }

    @Override
    public ASTNode visitAttrsSubConstraint(final RewriterStatementParser.AttrsSubConstraintContext ctx) {
        String attr = ctx.IDENTIFIER(0).getText();
        String table = ctx.IDENTIFIER(1).getText();
        return new ConstraintSegment(ConstraintType.ATTRS_SUB, attr, table);
    }

    @Override
    public ASTNode visitAttrsEqConstraint(final RewriterStatementParser.AttrsEqConstraintContext ctx) {
        String attr1 = ctx.IDENTIFIER(0).getText();
        String attr2 = ctx.IDENTIFIER(1).getText();
        return new ConstraintSegment(ConstraintType.ATTRS_EQ, attr1, attr2);
    }

    @Override
    public ASTNode visitTableEqConstraint(final RewriterStatementParser.TableEqConstraintContext ctx) {
        String table1 = ctx.IDENTIFIER(0).getText();
        String table2 = ctx.IDENTIFIER(1).getText();
        return new ConstraintSegment(ConstraintType.TABLE_EQ, table1, table2);
    }

    @Override
    public ASTNode visitSchemaEqConstraint(final RewriterStatementParser.SchemaEqConstraintContext ctx) {
        String schema1 = ctx.IDENTIFIER(0).getText();
        String schema2 = ctx.IDENTIFIER(1).getText();
        return new ConstraintSegment(ConstraintType.SCHEMA_EQ, schema1, schema2);
    }

    @Override
    public ASTNode visitPredicateEqConstraint(final RewriterStatementParser.PredicateEqConstraintContext ctx) {
        String pred1 = ctx.IDENTIFIER(0).getText();
        String pred2 = ctx.IDENTIFIER(1).getText();
        return new ConstraintSegment(ConstraintType.PREDICATE_EQ, pred1, pred2);
    }

    @Override
    public ASTNode visitUniqueConstraint(final RewriterStatementParser.UniqueConstraintContext ctx) {
        String table = ctx.IDENTIFIER(0).getText();
        String attr = ctx.IDENTIFIER(1).getText();
        return new ConstraintSegment(ConstraintType.UNIQUE, table, attr);
    }

    @Override
    public ASTNode visitNotNullConstraint(final RewriterStatementParser.NotNullConstraintContext ctx) {
        String table = ctx.IDENTIFIER(0).getText();
        String attr = ctx.IDENTIFIER(1).getText();
        return new ConstraintSegment(ConstraintType.NOT_NULL, table, attr);
    }

    @Override
    public ASTNode visitReferenceConstraint(final RewriterStatementParser.ReferenceConstraintContext ctx) {
        String table1 = ctx.IDENTIFIER(0).getText();
        String attr1 = ctx.IDENTIFIER(1).getText();
        String table2 = ctx.IDENTIFIER(2).getText();
        String attr2 = ctx.IDENTIFIER(3).getText();
        return new ConstraintSegment(ConstraintType.REFERENCE, table1, attr1, table2, attr2);
    }
}

