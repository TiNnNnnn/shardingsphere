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
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.parser.SqlParserPos;

import java.util.Collections;

/**
 * Template SQL identifier for rewrite rules.
 * This identifier can represent template variables like a0, t0, s0, p0.
 */
@Getter
public final class TemplateSqlIdentifier extends SqlIdentifier {

    private final boolean isTemplate;

    private final TemplateVarType varType;

    public TemplateSqlIdentifier(final String name, final boolean isTemplate, final TemplateVarType varType) {
        super(Collections.singletonList(name), SqlParserPos.ZERO);
        this.isTemplate = isTemplate;
        this.varType = varType;
    }

    @Override
    public void unparse(final SqlWriter writer, final int leftPrec, final int rightPrec) {
        // 输出时保持原样，不做任何转换
        writer.identifier(getSimple(), false);
    }

    /**
     * Template variable type enum.
     */
    public enum TemplateVarType {
        ATTRIBUTE,   // a0, a1, a2...
        TABLE,       // t0, t1, t2...
        SCHEMA,      // s0, s1, s2...
        PREDICATE    // p0, p1, p2...
    }
}

