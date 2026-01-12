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

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.Table;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlNode;

/**
 * Template table for placeholders (t0, t1, etc.).
 *
 * <p>This table represents a placeholder in rewrite rule templates.
 * It contains generic attribute columns (a0, a1, a2, ...) that can be
 * matched against actual table columns during query rewriting.</p>
 *
 * <p>Example: In the template "Input<t0>", t0 is represented by this class.</p>
 */
@RequiredArgsConstructor
@Getter
public final class TemplateTable implements Table {

    /**
     * Number of generic attribute columns to create (a0, a1, ..., a9).
     */
    private static final int DEFAULT_ATTRIBUTE_COUNT = 10;

    /**
     * Table name (placeholder identifier like t0, t1, etc.).
     */
    private final String tableName;

    @Override
    public RelDataType getRowType(final RelDataTypeFactory typeFactory) {
        // Create generic row type with placeholder columns
        // Contains placeholder columns: a0, a1, a2, ... (for attribute placeholders)
        RelDataTypeFactory.Builder builder = typeFactory.builder();

        // Add generic placeholder columns
        for (int i = 0; i < DEFAULT_ATTRIBUTE_COUNT; i++) {
            builder.add("a" + i, typeFactory.createJavaType(String.class));
        }

        return builder.build();
    }

    @Override
    public Statistic getStatistic() {
        return Statistics.UNKNOWN;
    }

    @Override
    public Schema.TableType getJdbcTableType() {
        return Schema.TableType.TABLE;
    }

    @Override
    public boolean isRolledUp(final String column) {
        return false;
    }

    @Override
    public boolean rolledUpColumnValidInsideAgg(final String column, final SqlCall call,
                                                 final SqlNode parent,
                                                 final CalciteConnectionConfig config) {
        return false;
    }
}

