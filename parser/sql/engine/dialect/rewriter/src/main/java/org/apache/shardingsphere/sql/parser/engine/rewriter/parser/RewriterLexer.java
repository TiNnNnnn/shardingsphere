package org.apache.shardingsphere.sql.parser.engine.rewriter.parser;

import org.antlr.v4.runtime.CharStream;
import org.apache.shardingsphere.sql.parser.api.parser.SQLLexer;
import org.apache.shardingsphere.sql.parser.autogen.RewriterStatementLexer;

public class RewriterLexer extends RewriterStatementLexer implements SQLLexer {
    public RewriterLexer(final CharStream input) {
        super(input);
    }
}
