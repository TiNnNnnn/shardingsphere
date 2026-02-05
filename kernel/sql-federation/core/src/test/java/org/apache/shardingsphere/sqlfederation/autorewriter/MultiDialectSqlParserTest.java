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

import org.apache.calcite.sql.SqlNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test for MultiDialectSqlParser using PostgreSQL dialect.
 */
public class MultiDialectSqlParserTest {

    @Test
    public void testParsePostgreSQLSelectStatement() {
        MultiDialectSqlParser parser = MultiDialectSqlParser.createParser("PostgreSQL");
        String sql = "SELECT id, name FROM users WHERE age > 18";

        SqlNode sqlNode = parser.parse(sql);

        assertNotNull(sqlNode);
        System.out.println("✓ PostgreSQL SELECT statement parsed successfully");
        System.out.println("  SQL: " + sql);
        System.out.println("  Parsed SqlNode: " + sqlNode);
    }

    @Test
    public void testParsePostgreSQLJoinStatement() {
        MultiDialectSqlParser parser = MultiDialectSqlParser.createParser("PostgreSQL");
        String sql = "SELECT u.id, u.name, o.order_id FROM users u INNER JOIN orders o ON u.id = o.user_id WHERE u.status = 'active'";

        SqlNode sqlNode = parser.parse(sql);

        assertNotNull(sqlNode);
        System.out.println("✓ PostgreSQL JOIN statement parsed successfully");
        System.out.println("  SQL: " + sql);
        System.out.println("  Parsed SqlNode: " + sqlNode);
    }
}
