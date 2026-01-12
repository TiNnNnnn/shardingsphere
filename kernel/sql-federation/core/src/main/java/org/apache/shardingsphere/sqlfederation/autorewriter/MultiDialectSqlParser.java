package org.apache.shardingsphere.sqlfederation.autorewriter;

import lombok.RequiredArgsConstructor;
import org.apache.calcite.sql.SqlNode;
import org.apache.shardingsphere.sql.parser.engine.api.CacheOption;
import org.apache.shardingsphere.sql.parser.engine.api.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.engine.api.SQLStatementVisitorEngine;
import org.apache.shardingsphere.sql.parser.engine.core.ParseASTNode;
import org.apache.shardingsphere.sql.parser.statement.core.segment.SQLSegment;
import org.apache.shardingsphere.sql.parser.statement.core.statement.SQLStatement;
import org.apache.shardingsphere.sqlfederation.compiler.sql.ast.converter.SQLNodeConverterEngine;

@RequiredArgsConstructor
public class MultiDialectSqlParser {

    private final SQLParserEngine sqlParserEngine;

    private final SQLStatementVisitorEngine statementVisitorEngine;

    public SqlNode parse(String sql) {
        ParseASTNode astNode = sqlParserEngine.parse(sql, false);
        SQLStatement statement = statementVisitorEngine.visit(astNode);
        return SQLNodeConverterEngine.convert(statement);
    }

    public static MultiDialectSqlParser createParser(String dialect) {
        SQLStatementVisitorEngine statementVisitorEngine = new SQLStatementVisitorEngine(dialect);
        SQLParserEngine sqlParserEngine = new SQLParserEngine(dialect, new CacheOption(0, 0));
        return new MultiDialectSqlParser(sqlParserEngine, statementVisitorEngine);
    }

}
