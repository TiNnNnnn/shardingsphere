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
import org.apache.calcite.rel.RelNode;
import org.apache.shardingsphere.sql.parser.api.ASTNode;

import java.util.Collection;

/**
 * Template rewrite rule using RelNode (instead of SqlNode).
 *
 * This is simpler than SqlNode-based approach because:
 * - No SQL validation needed
 * - No virtual schema needed
 * - Direct RelNode manipulation
 */
@RequiredArgsConstructor
@Getter
public final class TemplateRelNodeRule {

    /**
     * Source template as RelNode.
     */
    private final RelNode sourceTemplate;

    /**
     * Target template as RelNode.
     */
    private final RelNode targetTemplate;

    /**
     * Constraints for the rewrite rule.
     */
    private final Collection<? extends ASTNode> constraints;
}

