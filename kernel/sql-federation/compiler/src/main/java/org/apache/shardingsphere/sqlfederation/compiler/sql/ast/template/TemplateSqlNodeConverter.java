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

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.calcite.sql.JoinConditionType;
import org.apache.calcite.sql.JoinType;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.column.ColumnSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ColumnProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.item.ProjectionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.dml.predicate.WhereSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.generic.table.SimpleTableSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.ConstraintSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateExpressionSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateInSubFilterSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateJoinSegment;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.TemplateProjectionSegment;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Template SQL node converter for rewrite rules.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class TemplateSqlNodeConverter {

    private static final SqlParserPos POS = SqlParserPos.ZERO;

    /**
     * Convert rewrite rule segment to template rewrite rule.
     *
     * @param ruleSegment rewrite rule segment
     * @return template rewrite rule
     */
    public static TemplateRewriteRule convert(final RewriteRuleSegment ruleSegment) {
        SqlNode sourceTemplate = convertTemplate(ruleSegment.getSource());
        SqlNode targetTemplate = convertTemplate(ruleSegment.getTarget());

        // 转换约束集合
        Collection<ConstraintSegment> constraints = new ArrayList<>();
        for (ASTNode node : ruleSegment.getConstraints()) {
            if (node instanceof ConstraintSegment) {
                constraints.add((ConstraintSegment) node);
            }
        }

        return new TemplateRewriteRule(sourceTemplate, targetTemplate, constraints);
    }

    /**
     * Convert template AST node to SQL node.
     *
     * @param template template AST node
     * @return SQL node
     */
    public static SqlNode convertTemplate(final ASTNode template) {
        if (template instanceof TemplateProjectionSegment) {
            return convertProjectionTemplate((TemplateProjectionSegment) template);
        }
        if (template instanceof SimpleTableSegment) {
            return convertSimpleTable((SimpleTableSegment) template);
        }
        if (template instanceof TemplateJoinSegment) {
            return convertJoinTemplate((TemplateJoinSegment) template);
        }
        if (template instanceof TemplateInSubFilterSegment) {
            return convertInSubFilterTemplate((TemplateInSubFilterSegment) template);
        }
        if (template instanceof WhereSegment) {
            return convertFilterTemplate((WhereSegment) template);
        }
        throw new UnsupportedOperationException("Unsupported template type: " + template.getClass().getName());
    }

    private static SqlNode convertProjectionTemplate(final TemplateProjectionSegment segment) {
        // 构建SELECT列表
        SqlNodeList selectList = new SqlNodeList(POS);
        boolean isSelectAll = "Proj*".equals(segment.getOperator());

        if (!isSelectAll) {
            for (ProjectionSegment projSegment : segment.getProjections()) {
                if (projSegment instanceof ColumnProjectionSegment) {
                    ColumnSegment column = ((ColumnProjectionSegment) projSegment).getColumn();
                    String columnName = column.getIdentifier().getValue();

                    // 判断是否为模板变量（以'a'或's'开头+数字）
                    boolean isTemplate = columnName.matches("[as]\\d+");

                    // schema参数（s开头）不应该出现在SELECT列表中，只有属性（a开头）才出现
                    // 例如：Proj<a0 s0> 应该生成 SELECT a0，而不是 SELECT a0, s0
                    if (columnName.startsWith("s") && isTemplate) {
                        // 跳过schema参数
                        continue;
                    }

                    TemplateSqlIdentifier.TemplateVarType varType = columnName.startsWith("a")
                            ? TemplateSqlIdentifier.TemplateVarType.ATTRIBUTE
                            : TemplateSqlIdentifier.TemplateVarType.SCHEMA;

                    selectList.add(new TemplateSqlIdentifier(columnName, isTemplate, varType));
                }
            }
        } else {
            // Proj* 表示 SELECT *
            selectList.add(SqlIdentifier.star(POS));
        }

        // 递归转换子模板（FROM子句）
        SqlNode from = convertTemplate(segment.getChild());

        return new SqlSelect(
                POS,
                null,              // keywordList
                selectList,        // selectList
                from,              // from
                null,              // where
                null,              // groupBy
                null,              // having
                null,              // windowDecls
                null,              // qualify
                null,              // orderBy
                null,              // offset
                null               // fetch
        );
    }

    private static SqlNode convertSimpleTable(final SimpleTableSegment segment) {
        String tableName = segment.getTableName().getIdentifier().getValue();

        // 判断是否为模板变量（以't'开头+数字）
        boolean isTemplate = tableName.matches("t\\d+");

        return new TemplateSqlIdentifier(
                tableName,
                isTemplate,
                TemplateSqlIdentifier.TemplateVarType.TABLE
        );
    }

    private static SqlNode convertJoinTemplate(final TemplateJoinSegment segment) {
        SqlNode left = convertTemplate(segment.getLeft());
        SqlNode right = convertTemplate(segment.getRight());

        // 解析JOIN类型
        JoinType joinType = parseJoinType(segment.getJoinType());

        // 例如: InnerJoin<a1 a2>(Input<t0>, Input<t1>) -> a1 = a2
        SqlNode condition = createJoinCondition(segment);

        return new SqlJoin(
                POS,
                left,
                SqlLiteral.createBoolean(false, POS), // isNatural
                SqlLiteral.createSymbol(joinType, POS),
                right,
                SqlLiteral.createSymbol(JoinConditionType.ON, POS),
                condition
        );
    }

    private static SqlNode convertInSubFilterTemplate(final TemplateInSubFilterSegment segment) {
        // InSubFilter<a>(R_left, R_right) 实现半连接语义：
        // 保留R_left中那些在R_right中存在的元组（基于属性a的值检查）
        // 转换为: SELECT * FROM R_left WHERE a IN (SELECT ... FROM R_right)

        // 1. 转换左子节点（主查询的输入）
        SqlNode fromClause = convertTemplate(segment.getLeftChild());

        // 2. 转换右子节点（子查询）
        SqlNode subqueryNode = convertTemplate(segment.getSubquery());

        // 3. 创建IN条件: a IN (subquery)
        String attribute = segment.getAttribute();
        SqlIdentifier attrIdentifier = new SqlIdentifier(attribute, POS);
        SqlNode inCondition = SqlStdOperatorTable.IN.createCall(POS, attrIdentifier, subqueryNode);

        // 4. 构建完整的半连接查询: SELECT * FROM R_left WHERE a IN (R_right)
        SqlNodeList selectList = new SqlNodeList(POS);
        selectList.add(SqlIdentifier.star(POS));

        return new SqlSelect(
                POS,
                null,              // keywordList
                selectList,        // SELECT *
                fromClause,        // FROM R_left
                inCondition,       // WHERE a IN (subquery)
                null,              // groupBy
                null,              // having
                null,              // windowDecls
                null,              // qualify
                null,              // orderBy
                null,              // offset
                null               // fetch
        );
    }

    private static SqlNode createJoinCondition(final TemplateJoinSegment segment) {
        String leftAttribute = segment.getLeftAttribute();
        String rightAttribute = segment.getRightAttribute();
        SqlIdentifier leftColumn = new SqlIdentifier(leftAttribute, POS);
        SqlIdentifier rightColumn = new SqlIdentifier(rightAttribute, POS);
        return SqlStdOperatorTable.EQUALS.createCall(POS, leftColumn, rightColumn);
    }


    private static JoinType parseJoinType(final String joinTypeStr) {
        if ("InnerJoin".equals(joinTypeStr) || "INNER".equalsIgnoreCase(joinTypeStr)) {
            return JoinType.INNER;
        } else if ("LeftJoin".equals(joinTypeStr) || "LEFT".equalsIgnoreCase(joinTypeStr)) {
            return JoinType.LEFT;
        } else if ("RightJoin".equals(joinTypeStr) || "RIGHT".equalsIgnoreCase(joinTypeStr)) {
            return JoinType.RIGHT;
        } else if ("FULL".equalsIgnoreCase(joinTypeStr)) {
            return JoinType.FULL;
        } else {
            return JoinType.INNER; // 默认
        }
    }

    private static SqlNode convertFilterTemplate(final WhereSegment segment) {
        if (segment.getExpr() instanceof TemplateExpressionSegment) {
            TemplateExpressionSegment exprSeg = (TemplateExpressionSegment) segment.getExpr();
            SqlIdentifier predicateId = new TemplateSqlIdentifier(
                    exprSeg.getOperator(),
                    true,
                    TemplateSqlIdentifier.TemplateVarType.PREDICATE
            );

            SqlIdentifier attrId = new TemplateSqlIdentifier(
                    exprSeg.getAttribute(),
                    true,
                    TemplateSqlIdentifier.TemplateVarType.ATTRIBUTE
            );
            return SqlStdOperatorTable.EQUALS.createCall(POS, predicateId, attrId);
        }
        return SqlLiteral.createBoolean(true, POS);
    }
}

