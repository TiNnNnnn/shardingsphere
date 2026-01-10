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

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.autogen.RewriterStatementLexer;
import org.apache.shardingsphere.sql.parser.autogen.RewriterStatementParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewriter statement visitor facade.
 */
public final class RewriterStatementVisitorFacade {

    /**
     * Parse rewrite rule from string.
     *
     * @param ruleText rule text
     * @return rewrite rule as ASTNode
     */
    public ASTNode parseRule(final String ruleText) {
        CharStream input = CharStreams.fromString(ruleText);
        RewriterStatementLexer lexer = new RewriterStatementLexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        RewriterStatementParser parser = new RewriterStatementParser(tokens);
        RewriterStatementVisitor visitor = new RewriterStatementVisitor();
        return visitor.visitRule(parser.execute().rule_());
    }

    /**
     * Parse rewrite rules from file.
     *
     * @param filePath file path
     * @return list of rewrite rules
     * @throws IOException if file reading fails
     */
    public List<ASTNode> parseRulesFromFile(final String filePath) throws IOException {
        List<ASTNode> rules = new ArrayList<>();
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream(filePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    rules.add(parseRule(line));
                }
            }
        }
        return rules;
    }

    /**
     * Parse rewrite rules from multiple files.
     *
     * @param filePaths file paths
     * @return list of rewrite rules
     * @throws IOException if file reading fails
     */
    public List<ASTNode> parseRulesFromFiles(final String... filePaths) throws IOException {
        List<ASTNode> allRules = new ArrayList<>();
        for (String filePath : filePaths) {
            allRules.addAll(parseRulesFromFile(filePath));
        }
        return allRules;
    }
}

