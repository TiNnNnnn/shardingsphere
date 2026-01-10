package org.apache.shardingsphere.sql.parser.engine.rewriter.parser;

import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.TokenStream;
import org.apache.shardingsphere.sql.parser.api.ASTNode;
import org.apache.shardingsphere.sql.parser.api.parser.SQLParser;
import org.apache.shardingsphere.sql.parser.autogen.RewriterStatementParser;
import org.apache.shardingsphere.sql.parser.engine.core.ParseASTNode;

public class RewriterParser extends RewriterStatementParser implements SQLParser {
    public RewriterParser(final TokenStream input) {
        super(input);
    }

    @Override
    public ASTNode parse() {
        return new ParseASTNode(execute(), (CommonTokenStream) getTokenStream());
    }
}
