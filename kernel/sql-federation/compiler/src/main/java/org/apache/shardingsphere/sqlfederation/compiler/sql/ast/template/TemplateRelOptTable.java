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

import lombok.RequiredArgsConstructor;
import org.apache.calcite.linq4j.tree.Expression;
import org.apache.calcite.plan.RelOptSchema;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelDistribution;
import org.apache.calcite.rel.RelDistributions;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelReferentialConstraint;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.schema.ColumnStrategy;
import org.apache.calcite.schema.Table;
import org.apache.calcite.util.ImmutableBitSet;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * Template RelOptTable implementation for placeholder tables.
 *
 * <p>RelOptTable is Calcite's representation of a table in the optimizer.
 * It acts as a bridge between the physical table (schema-level) and the
 * relational operators (RelNode) that reference it.</p>
 *
 * <p>This implementation provides the minimal functionality needed for
 * template matching in rewrite rules, without requiring a full schema.</p>
 *
 * <p>Key purposes:</p>
 * <ul>
 *   <li>Provide table metadata to Calcite's optimizer</li>
 *   <li>Support LogicalTableScan creation</li>
 *   <li>Enable template matching without schema validation</li>
 * </ul>
 */
@RequiredArgsConstructor
public final class TemplateRelOptTable implements RelOptTable {

    /**
     * The underlying table (TemplateTable in our case).
     */
    private final Table table;

    /**
     * Row type (schema) of this table.
     */
    private final RelDataType rowType;

    /**
     * Create a TemplateRelOptTable from a Table.
     *
     * @param table underlying table
     * @param typeFactory type factory for creating row types
     * @return new TemplateRelOptTable instance
     */
    public static TemplateRelOptTable create(final Table table, final RelDataTypeFactory typeFactory) {
        return new TemplateRelOptTable(table, table.getRowType(typeFactory));
    }

    /**
     * Get the qualified name of this table.
     *
     * <p>Purpose: Identifies the table in the optimizer's namespace.
     * Used for display in EXPLAIN plans and error messages.</p>
     *
     * <p>Example: ["schema", "table_name"] or just ["table_name"]</p>
     *
     * @return qualified name list
     */
    @Override
    public List<String> getQualifiedName() {
        if (table instanceof TemplateTable) {
            return Collections.singletonList(((TemplateTable) table).getTableName());
        }
        return Collections.singletonList("template");
    }

    /**
     * Get estimated row count for this table.
     *
     * <p>Purpose: Used by the cost-based optimizer to estimate query costs.
     * A larger row count typically means more expensive operations.</p>
     *
     * <p>For template tables: We return a placeholder value (100.0) since
     * templates don't represent actual data. The optimizer won't use this
     * for cost estimation during template matching.</p>
     *
     * @return estimated row count (placeholder: 100.0)
     */
    @Override
    public double getRowCount() {
        return 100.0; // Placeholder value for template matching
    }

    /**
     * Get the row type (schema) of this table.
     *
     * <p>Purpose: Defines the columns and their types for this table.
     * This is CRITICAL for:</p>
     * <ul>
     *   <li>Building projections (SELECT columns)</li>
     *   <li>Building filters (WHERE conditions)</li>
     *   <li>Type checking in joins</li>
     *   <li>Column reference resolution</li>
     * </ul>
     *
     * <p>For template tables: Returns a generic schema with placeholder
     * columns (a0, a1, ..., a9) that can match any actual columns.</p>
     *
     * @return row type with column definitions
     */
    @Override
    public RelDataType getRowType() {
        return rowType;
    }

    /**
     * Get the schema this table belongs to.
     *
     * <p>Purpose: Provides access to the parent schema object, which can
     * be used to resolve other tables, functions, etc.</p>
     *
     * <p>For template tables: We return null because templates don't belong
     * to a real schema. They exist only for pattern matching.</p>
     *
     * @return null (no parent schema for templates)
     */
    @Override
    public RelOptSchema getRelOptSchema() {
        return null;
    }

    /**
     * Convert this table reference to a RelNode (table scan).
     *
     * <p>Purpose: Creates a LogicalTableScan RelNode that reads from this table.
     * This is called when building a query plan from SQL.</p>
     *
     * <p>For template tables: We return null because template tables are
     * constructed directly by TemplateRelNodeBuilder, not through this method.</p>
     *
     * @param context conversion context
     * @return null (templates are built directly)
     */
    @Override
    public RelNode toRel(final ToRelContext context) {
        return null;
    }

    /**
     * Get the collations (sort orders) defined on this table.
     *
     * <p>Purpose: Describes how data is physically sorted in this table.
     * The optimizer can use this to avoid unnecessary sorts.</p>
     *
     * <p>Example: If a table is sorted by (column1 ASC, column2 DESC),
     * a query with "ORDER BY column1, column2 DESC" doesn't need to sort.</p>
     *
     * <p>For template tables: Empty list (no physical sort order).</p>
     *
     * @return empty list (no collations)
     */
    @Override
    public List<RelCollation> getCollationList() {
        return Collections.emptyList();
    }

    /**
     * Get the distribution of data across nodes (for distributed systems).
     *
     * <p>Purpose: Describes how table rows are partitioned across multiple
     * nodes in a distributed database. Used for planning distributed queries.</p>
     *
     * <p>Types of distribution:</p>
     * <ul>
     *   <li>HASH_DISTRIBUTED - partitioned by hash of key columns</li>
     *   <li>BROADCAST_DISTRIBUTED - replicated to all nodes</li>
     *   <li>SINGLETON - all data on one node</li>
     *   <li>ANY - unknown/irrelevant</li>
     * </ul>
     *
     * <p>For template tables: RelDistributions.ANY (not a distributed table).</p>
     *
     * @return ANY distribution (not distributed)
     */
    @Override
    public RelDistribution getDistribution() {
        return RelDistributions.ANY;
    }

    /**
     * Check if the given columns form a key (unique identifier).
     *
     * <p>Purpose: Tells the optimizer whether these columns uniquely identify
     * rows. Used for optimization:</p>
     * <ul>
     *   <li>Eliminating unnecessary DISTINCTs</li>
     *   <li>Simplifying joins</li>
     *   <li>Determining if GROUP BY is needed</li>
     * </ul>
     *
     * <p>For template tables: Always false (no key information).</p>
     *
     * @param columns column bit set
     * @return false (no keys defined)
     */
    @Override
    public boolean isKey(final ImmutableBitSet columns) {
        return false;
    }

    /**
     * Get all keys (unique column sets) defined on this table.
     *
     * <p>Purpose: Returns all unique constraints/primary keys for this table.
     * Each key is represented as a bit set of column indices.</p>
     *
     * <p>Example: If columns 0 and 1 together form a unique key,
     * returns [ImmutableBitSet{0, 1}]</p>
     *
     * <p>For template tables: Empty list (no keys defined).</p>
     *
     * @return empty list (no keys)
     */
    @Override
    public List<ImmutableBitSet> getKeys() {
        return Collections.emptyList();
    }

    /**
     * Get foreign key constraints referencing other tables.
     *
     * <p>Purpose: Describes relationships between this table and other tables.
     * Used for join optimization and constraint checking.</p>
     *
     * <p>Example: orders.customer_id references customers.id</p>
     *
     * <p>For template tables: Empty list (no foreign keys).</p>
     *
     * @return empty list (no foreign keys)
     */
    @Override
    public List<RelReferentialConstraint> getReferentialConstraints() {
        return Collections.emptyList();
    }

    /**
     * Get a representation of this table as a Java expression.
     *
     * <p>Purpose: Used when generating Java code from query plans
     * (in Calcite's code generation framework). Returns an expression
     * that evaluates to this table object.</p>
     *
     * <p>For template tables: null (not used in code generation).</p>
     *
     * @param clazz desired expression type class
     * @return null (no expression representation)
     */
    @Override
    public @Nullable Expression getExpression(final Class clazz) {
        return null;
    }

    /**
     * Create an extended version of this table with additional columns.
     *
     * <p>Purpose: Supports dynamic table extension, where computed columns
     * or additional metadata columns are added to the table schema.</p>
     *
     * <p>Example: Adding a computed "full_name" column to a table that
     * has "first_name" and "last_name".</p>
     *
     * <p>For template tables: Returns this (no extension supported).</p>
     *
     * @param extendedFields additional fields to add
     * @return this table (unchanged)
     */
    @Override
    public RelOptTable extend(final List<RelDataTypeField> extendedFields) {
        return this;
    }

    /**
     * Get column strategies (how columns are populated during INSERT).
     *
     * <p>Purpose: Describes how each column gets its value during inserts:</p>
     * <ul>
     *   <li>NULLABLE - can be null if not provided</li>
     *   <li>NOT_NULLABLE - must be provided</li>
     *   <li>DEFAULT - uses default value if not provided</li>
     *   <li>VIRTUAL - computed, cannot be inserted</li>
     *   <li>STORED - computed but stored</li>
     * </ul>
     *
     * <p>For template tables: Empty list (not used for inserts).</p>
     *
     * @return empty list (no column strategies)
     */
    @Override
    public List<ColumnStrategy> getColumnStrategies() {
        return Collections.emptyList();
    }

    /**
     * Unwrap this table to a specific type.
     *
     * <p>Purpose: Provides a type-safe way to access the underlying
     * implementation. Used when you need to cast to a specific table type.</p>
     *
     * <p>Example: table.unwrap(JdbcTable.class) to access JDBC-specific methods.</p>
     *
     * @param clazz target class
     * @param <C> target type
     * @return this object cast to C, or null if not compatible
     */
    @Override
    public <C> C unwrap(final Class<C> clazz) {
        if (clazz.isInstance(this)) {
            return clazz.cast(this);
        }
        return null;
    }
}

