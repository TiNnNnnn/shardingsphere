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

package org.apache.shardingsphere.rewriter.poc.converter;

import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.RelBuilder;

/**
 * Wrapper for SqlToRelConverter.
 * Converts SqlNode back to RelNode after template rewriting.
 */
public class SqlToRelNodeConverter {

    private final CalciteCatalogReader catalogReader;
    private final SqlValidator validator;
    private final RelOptCluster cluster;

    /**
     * Constructor.
     */
    public SqlToRelNodeConverter(
            final CalciteCatalogReader catalogReader,
            final SqlValidator validator,
            final RelOptCluster cluster) {
        this.catalogReader = catalogReader;
        this.validator = validator;
        this.cluster = cluster;
    }

    /**
     * Convert SqlNode to RelNode.
     *
     * @param sqlNode SQL node to convert
     * @param relBuilder RelBuilder from the optimization context
     * @return converted RelNode
     */
    public RelNode convertToRelNode(final SqlNode sqlNode, final RelBuilder relBuilder) {
        try {
            // Validate the SqlNode
            SqlNode validatedNode = validator.validate(sqlNode);

            // Create SqlToRelConverter
            SqlToRelConverter.Config config = SqlToRelConverter.config()
                    .withTrimUnusedFields(true)
                    .withExpand(false);

            RexBuilder rexBuilder = cluster.getRexBuilder();

            SqlToRelConverter converter = new SqlToRelConverter(
                    null, // view expander
                    validator,
                    catalogReader,
                    cluster,
                    StandardConvertletTable.INSTANCE,
                    config
            );

            // Convert to RelNode
            RelRoot relRoot = converter.convertQuery(validatedNode, false, true);
            return relRoot.rel;

        } catch (Exception e) {
            System.err.println("[SqlToRelNodeConverter] Conversion failed: " + e.getMessage());
            throw new RuntimeException("Failed to convert SqlNode to RelNode", e);
        }
    }
}

