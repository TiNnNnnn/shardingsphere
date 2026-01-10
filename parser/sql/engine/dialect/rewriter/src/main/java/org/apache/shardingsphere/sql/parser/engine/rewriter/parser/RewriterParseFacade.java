package org.apache.shardingsphere.sql.parser.engine.rewriter.parser;

import org.apache.shardingsphere.sql.parser.api.parser.SQLLexer;
import org.apache.shardingsphere.sql.parser.api.parser.SQLParser;
import org.apache.shardingsphere.sql.parser.spi.DialectSQLParserFacade;

public class RewriterParseFacade implements DialectSQLParserFacade {

    @Override
    public Class<? extends SQLLexer> getLexerClass() {
        return RewriterLexer.class;
    }

    @Override
    public Class<? extends SQLParser> getParserClass() {
        return RewriterParser.class;
    }

    @Override
    public String getDatabaseType() {
        return "Rewriter";
    }
}
