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

package org.apache.shardingsphere.rewriter.poc;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.jdbc.JavaTypeFactoryImpl;
import org.apache.calcite.plan.*;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.plan.hep.HepProgramBuilder;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.validate.SqlConformanceEnum;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.shardingsphere.rewriter.poc.rule.TemplateSqlRewriteRule;
import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.engine.rewriter.visitor.statement.RewriterStatementVisitorFacade;
import org.apache.shardingsphere.sql.parser.statement.core.segment.rewriter.RewriteRuleSegment;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateSqlNodeConverter;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.template.TemplateRewriteRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Template-based query rewriter PoC test.
 * Workflow:
 * 1. User inputs a query (PostgreSQL syntax)
 * 2. Parse query to SqlNode
 * 3. Convert SqlNode to RelNode
 * 4. Apply template-based rewrite rule (custom RelOptRule)
 * 5. Run RBO optimization
 * 6. Verify the rewritten query
 */
public class TemplateRewriterPoCTest {

    private FrameworkConfig config;

    @BeforeEach
    public void setup() {
        // Create a simple schema with test tables
        SchemaPlus rootSchema = Frameworks.createRootSchema(true);
        SchemaPlus testSchema = rootSchema.add("test", new org.apache.calcite.schema.impl.AbstractSchema());

        // Add sample tables: USERS, ORDERS (uppercase to match case-insensitive parsing)
        testSchema.add("USERS", new SimpleTable());
        testSchema.add("ORDERS", new SimpleTable());

        // Add placeholder tables for template matching (t0, t1, t2, t3, etc.)
        for (int i = 0; i < 10; i++) {
            testSchema.add("T" + i, new SimpleTable());
        }

        // Create parser config with case-insensitive settings
        SqlParser.Config parserConfig = SqlParser.config()
                .withCaseSensitive(false)
                .withConformance(SqlConformanceEnum.DEFAULT);

        // Create framework config
        config = Frameworks.newConfigBuilder()
                .defaultSchema(testSchema)
                .parserConfig(parserConfig)
                .build();
    }

    @Test
    public void testSimpleProjectionRewrite() throws Exception {
        System.out.println("\n=== Test: Simple Projection Rewrite ===");

        // Step 1: User input query
        String userQuery = "SELECT id, name FROM users";
        System.out.println("User Query: " + userQuery);

        // Step 2: Parse to SqlNode
        SqlParser parser = SqlParser.create(userQuery, config.getParserConfig());
        SqlNode sqlNode = parser.parseQuery();
        System.out.println("Parsed SqlNode: " + sqlNode);

        // Step 3: Convert to RelNode
        RelNode relNode = sqlToRel(sqlNode);
        System.out.println("Original RelNode: " + relNode);

        // Step 4: Parse template rule
        // Rule: Proj*<a0 s0>(Input<t0>) | Proj<a1 s1>(Input<t1>) | TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)
        // TableEq(t1,t0): target table t1 uses source table t0
        // AttrsEq(a1,a0): target attributes a1 uses source attributes a0
        // SchemaEq(s1,s0): target schema s1 uses source schema s0
        String ruleStr = "Proj*<a0 s0>(Input<t0>)|Proj<a1 s1>(Input<t1>)|TableEq(t1,t0);AttrsEq(a1,a0);SchemaEq(s1,s0)";
        TemplateRewriteRule templateRule = parseTemplateRule(ruleStr);

        // Step 5: Create mock schema for template conversion
        FrameworkConfig templateConfig = createTemplateSchemaConfig(ruleStr);

        // Step 6: Convert template SqlNodes to RelNodes using mock schema
        RelNode sourceTemplate = sqlToRelWithConfig(templateRule.getSourceTemplate(), templateConfig);
        RelNode targetTemplate = sqlToRelWithConfig(templateRule.getTargetTemplate(), templateConfig);

        System.out.println("Source Template RelNode: " + sourceTemplate);
        System.out.println("Target Template RelNode: " + targetTemplate);

        // Step 7: Create custom rewrite rule at RelNode level
        TemplateSqlRewriteRule rewriteRule = new TemplateSqlRewriteRule(
                sourceTemplate,
                targetTemplate,
                templateRule.getConstraints(),
                "SimpleProjectionRewrite");

        // Step 8: Apply RBO with the custom rule
        RelNode optimized = applyRBO(relNode, rewriteRule);
        System.out.println("Optimized RelNode: " + optimized);

        assertNotNull(optimized);
    }

    @Test
    public void testJoinRewrite() throws Exception {
        System.out.println("\n=== Test: Join Rewrite ===");

        // User query with join
        String userQuery = "SELECT u.name, o.amount FROM users u INNER JOIN orders o ON u.id = o.user_id";
        System.out.println("User Query: " + userQuery);

        SqlParser parser = SqlParser.create(userQuery, config.getParserConfig());
        SqlNode sqlNode = parser.parseQuery();

        RelNode relNode = sqlToRel(sqlNode);
        System.out.println("Original RelNode: " + relNode);

        // Rule: InnerJoin<a1 a2>(Input<t0>,Input<t1>) | InnerJoin<a3 a4>(Input<t2>,Input<t3>) | TableEq(t1,t2);TableEq(t3,t0);AttrsEq(a3,a2);AttrsEq(a4,a1)
        // Purpose: Swap left and right tables in join
        // t2 = t1 (target left = source right), t3 = t0 (target right = source left)
        // a3 = a2 (target left attr = source right attr), a4 = a1 (target right attr = source left attr)
        String ruleStr = "InnerJoin<a1 a2>(Input<t0>,Input<t1>)|InnerJoin<a3 a4>(Input<t2>,Input<t3>)|TableEq(t1,t2);TableEq(t0,t3);AttrsEq(a3,a2);AttrsEq(a4,a1)";
        TemplateRewriteRule templateRule = parseTemplateRule(ruleStr);

        // Create mock schema for template conversion
        FrameworkConfig templateConfig = createTemplateSchemaConfig(ruleStr);

        // Debug: Print template SQL
        System.out.println("Source Template SQL: " + templateRule.getSourceTemplate());
        System.out.println("Source Template SQL string: " + templateRule.getSourceTemplate().toSqlString(org.apache.calcite.sql.SqlDialect.DatabaseProduct.CALCITE.getDialect()));
        System.out.println("Target Template SQL: " + templateRule.getTargetTemplate());
        System.out.println("Target Template SQL string: " + templateRule.getTargetTemplate().toSqlString(org.apache.calcite.sql.SqlDialect.DatabaseProduct.CALCITE.getDialect()));

        // Convert template SqlNodes to RelNodes using mock schema
        RelNode sourceTemplate = sqlToRelWithConfig(templateRule.getSourceTemplate(), templateConfig);
        RelNode targetTemplate = sqlToRelWithConfig(templateRule.getTargetTemplate(), templateConfig);

        System.out.println("Source Template RelNode: " + sourceTemplate);
        System.out.println("Target Template RelNode: " + targetTemplate);

        TemplateSqlRewriteRule rewriteRule = new TemplateSqlRewriteRule(
                sourceTemplate,
                targetTemplate,
                templateRule.getConstraints(),
                "JoinRewrite");

        RelNode optimized = applyRBO(relNode, rewriteRule);
        System.out.println("Optimized RelNode: " + optimized);

        assertNotNull(optimized);
    }

    /**
     * Parse template rule string to TemplateRewriteRule.
     */
    private TemplateRewriteRule parseTemplateRule(final String ruleStr) {
        RewriterStatementVisitorFacade facade = new RewriterStatementVisitorFacade();
        ASTNode astNode = facade.parseRule(ruleStr);
        RewriteRuleSegment ruleSegment = (RewriteRuleSegment) astNode;
        return TemplateSqlNodeConverter.convert(ruleSegment);
    }

    /**
     * Create a mock schema configuration for template SQL conversion.
     * Intelligently extracts placeholders and creates appropriate mock tables.
     */
    private FrameworkConfig createTemplateSchemaConfig(final String ruleStr) {
        SchemaPlus rootSchema = Frameworks.createRootSchema(true);
        SchemaPlus templateSchema = rootSchema.add("template", new org.apache.calcite.schema.impl.AbstractSchema());

        // Extract all placeholders from rule
        PlaceholderInfo placeholders = extractAllPlaceholders(ruleStr);

        System.out.println("[Schema] Creating template schema:");
        System.out.println("[Schema]   Tables: " + placeholders.tables);
        System.out.println("[Schema]   Attributes: " + placeholders.attributes);

        // Create a table for each table placeholder with specific attribute columns
        for (String tableName : placeholders.tables) {
            templateSchema.add(tableName.toLowerCase(),
                             new DynamicFlexibleTable(placeholders.attributes));
        }

        SqlParser.Config parserConfig = SqlParser.config()
                .withCaseSensitive(false)
                .withConformance(SqlConformanceEnum.DEFAULT);

        return Frameworks.newConfigBuilder()
                .defaultSchema(templateSchema)
                .parserConfig(parserConfig)
                .build();
    }

    /**
     * Placeholder information.
     */
    private static class PlaceholderInfo {
        java.util.Set<String> tables = new java.util.HashSet<>();
        java.util.Set<String> attributes = new java.util.HashSet<>();
    }

    /**
     * Extract all placeholders from rule.
     */
    private PlaceholderInfo extractAllPlaceholders(final String ruleStr) {
        PlaceholderInfo info = new PlaceholderInfo();

        // Tables: Input<t0>
        java.util.regex.Pattern tablePattern = java.util.regex.Pattern.compile("Input<(t\\d+)>");
        java.util.regex.Matcher tableMatcher = tablePattern.matcher(ruleStr);
        while (tableMatcher.find()) {
            info.tables.add(tableMatcher.group(1));
        }

        // Attributes: <a0 s0>, <a1 a2>
        java.util.regex.Pattern attrPattern = java.util.regex.Pattern.compile("<([a-z]\\d+)(?:\\s+([a-z]\\d+))?>");
        java.util.regex.Matcher attrMatcher = attrPattern.matcher(ruleStr);
        while (attrMatcher.find()) {
            String attr1 = attrMatcher.group(1);
            if (attr1.startsWith("a")) info.attributes.add(attr1);

            String attr2 = attrMatcher.group(2);
            if (attr2 != null && attr2.startsWith("a")) info.attributes.add(attr2);
        }

        return info;
    }

    /**
     * Convert SqlNode to RelNode using specified configuration.
     */
    private RelNode sqlToRelWithConfig(final SqlNode sqlNode, final FrameworkConfig frameworkConfig) {
        RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl();
        SchemaPlus defaultSchema = frameworkConfig.getDefaultSchema();
        if (defaultSchema == null) {
            throw new IllegalStateException("Default schema is null");
        }
        CalciteSchema rootSchema = CalciteSchema.from(defaultSchema);

        Properties props = new Properties();
        props.setProperty("caseSensitive", "false");
        CalciteConnectionConfig connectionConfig = new CalciteConnectionConfigImpl(props);

        CalciteCatalogReader catalogReader = new CalciteCatalogReader(
                rootSchema,
                Collections.singletonList("template"),
                typeFactory,
                connectionConfig
        );

        SqlValidator validator = SqlValidatorUtil.newValidator(
                frameworkConfig.getOperatorTable(),
                catalogReader,
                typeFactory,
                SqlValidator.Config.DEFAULT.withIdentifierExpansion(true)
        );

        SqlNode validated = validator.validate(sqlNode);

        RelOptCluster cluster = RelOptCluster.create(
                new HepPlanner(new HepProgramBuilder().build()),
                new RexBuilder(typeFactory)
        );

        SqlToRelConverter.Config converterConfig = SqlToRelConverter.config();
        SqlToRelConverter converter = new SqlToRelConverter(
                null,
                validator,
                catalogReader,
                cluster,
                StandardConvertletTable.INSTANCE,
                converterConfig
        );

        RelRoot root = converter.convertQuery(validated, false, true);
        return root.rel;
    }

    /**
     * Convert SqlNode to RelNode.
     */
    private RelNode sqlToRel(final SqlNode sqlNode) {
        RelDataTypeFactory typeFactory = new JavaTypeFactoryImpl();
        SchemaPlus defaultSchema = config.getDefaultSchema();
        if (defaultSchema == null) {
            throw new IllegalStateException("Default schema is null");
        }
        CalciteSchema rootSchema = CalciteSchema.from(defaultSchema);

        Properties props = new Properties();
        props.setProperty("caseSensitive", "false");
        CalciteConnectionConfig connectionConfig = new CalciteConnectionConfigImpl(props);

        CalciteCatalogReader catalogReader = new CalciteCatalogReader(
                rootSchema,
                Collections.singletonList("test"),
                typeFactory,
                connectionConfig
        );

        SqlValidator validator = SqlValidatorUtil.newValidator(
                config.getOperatorTable(),
                catalogReader,
                typeFactory,
                SqlValidator.Config.DEFAULT.withIdentifierExpansion(true)
        );

        SqlNode validated = validator.validate(sqlNode);

        RelOptCluster cluster = RelOptCluster.create(
                new HepPlanner(new HepProgramBuilder().build()),
                new RexBuilder(typeFactory)
        );

        SqlToRelConverter.Config converterConfig = SqlToRelConverter.config();
        SqlToRelConverter converter = new SqlToRelConverter(
                null,
                validator,
                catalogReader,
                cluster,
                StandardConvertletTable.INSTANCE,
                converterConfig
        );

        RelRoot root = converter.convertQuery(validated, false, true);
        return root.rel;
    }

    /**
     * Apply RBO optimization with custom rule.
     */
    private RelNode applyRBO(final RelNode input, final RelOptRule... rules) {
        RelOptPlanner planner = new HepPlanner(
                new HepProgramBuilder()
                        .addRuleCollection(java.util.Arrays.asList(rules))
                        .build()
        );

        planner.setRoot(input);
        return planner.findBestExp();
    }

    /**
     * Simple table implementation for testing.
     */
    private static class SimpleTable extends AbstractTable {
        @Override
        public org.apache.calcite.rel.type.RelDataType getRowType(RelDataTypeFactory typeFactory) {
            return typeFactory.builder()
                    .add("id", typeFactory.createJavaType(Integer.class))
                    .add("name", typeFactory.createJavaType(String.class))
                    .add("amount", typeFactory.createJavaType(Double.class))
                    .add("user_id", typeFactory.createJavaType(Integer.class))
                    .build();
        }
    }

    /**
     * Flexible table for template schemas - accepts any column.
     */
    private static class FlexibleTable extends AbstractTable {
        @Override
        public org.apache.calcite.rel.type.RelDataType getRowType(RelDataTypeFactory typeFactory) {
            org.apache.calcite.rel.type.RelDataTypeFactory.Builder builder = typeFactory.builder();

            // Add columns for common placeholders: a0-a9, s0-s9, c0-c9
            for (int i = 0; i < 10; i++) {
                builder.add("a" + i, typeFactory.createJavaType(String.class));
                builder.add("s" + i, typeFactory.createJavaType(String.class));
                builder.add("c" + i, typeFactory.createJavaType(String.class));
            }

            // Also add some standard columns
            builder.add("id", typeFactory.createJavaType(Integer.class));
            builder.add("name", typeFactory.createJavaType(String.class));
            builder.add("value", typeFactory.createJavaType(String.class));

            return builder.build();
        }
    }

    /**
     * Dynamic flexible table that creates columns based on extracted placeholders.
     */
    private static class DynamicFlexibleTable extends AbstractTable {
        private final java.util.Set<String> attributePlaceholders;

        public DynamicFlexibleTable(final java.util.Set<String> attributePlaceholders) {
            this.attributePlaceholders = attributePlaceholders;
        }

        @Override
        public org.apache.calcite.rel.type.RelDataType getRowType(RelDataTypeFactory typeFactory) {
            org.apache.calcite.rel.type.RelDataTypeFactory.Builder builder = typeFactory.builder();

            // Add columns for extracted attribute placeholders
            for (String attrName : attributePlaceholders) {
                builder.add(attrName, typeFactory.createJavaType(String.class));
            }

            // Add common columns for basic usage
            builder.add("id", typeFactory.createJavaType(Integer.class));
            builder.add("name", typeFactory.createJavaType(String.class));
            builder.add("value", typeFactory.createJavaType(String.class));

            return builder.build();
        }
    }
}
