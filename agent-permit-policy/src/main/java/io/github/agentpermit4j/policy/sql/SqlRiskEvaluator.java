package io.github.agentpermit4j.policy.sql;

import io.github.agentpermit4j.core.RiskAssessment;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolInvocation;
import io.github.agentpermit4j.policy.RiskEvaluator;
import java.util.Objects;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.drop.Drop;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.truncate.Truncate;
import net.sf.jsqlparser.statement.update.Update;

public final class SqlRiskEvaluator implements RiskEvaluator {

  private static final String STATEMENT_ARGUMENT = "statement";

  @Override
  public RiskAssessment evaluate(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    if (!"sql".equals(invocation.resource().type())) {
      return assessment(RiskLevel.DENY, "SQL_RESOURCE_REQUIRED");
    }

    var sql = invocation.arguments().get(STATEMENT_ARGUMENT);
    if (sql == null || sql.isBlank()) {
      return assessment(RiskLevel.DENY, "SQL_MISSING_STATEMENT");
    }

    return parseAndClassify(sql);
  }

  private static RiskAssessment parseAndClassify(String sql) {
    try {
      var statements = CCJSqlParserUtil.parseStatements(sql);
      if (statements.size() != 1) {
        return assessment(RiskLevel.DENY, "SQL_MULTIPLE_STATEMENTS");
      }
      return classify(statements.get(0));
    } catch (JSQLParserException exception) {
      return assessment(RiskLevel.DENY, "SQL_PARSE_FAILED");
    }
  }

  private static RiskAssessment classify(Statement statement) {
    if (statement instanceof Select) {
      return assessment(RiskLevel.LOW, "SQL_READ_ONLY");
    }
    if (statement instanceof Update update) {
      return isUnbounded(update)
          ? assessment(RiskLevel.CRITICAL, "SQL_UNBOUNDED_UPDATE")
          : assessment(RiskLevel.HIGH, "SQL_SELECTIVE_UPDATE");
    }
    if (statement instanceof Drop || statement instanceof Truncate) {
      return assessment(RiskLevel.DENY, "SQL_DESTRUCTIVE");
    }
    return assessment(RiskLevel.DENY, "SQL_UNSUPPORTED");
  }

  private static boolean isUnbounded(Update update) {
    var where = update.getWhere();
    if (where == null) {
      return true;
    }
    if (!(where instanceof EqualsTo equalsTo)) {
      return false;
    }
    if (!(equalsTo.getLeftExpression() instanceof LongValue left)
        || !(equalsTo.getRightExpression() instanceof LongValue right)) {
      return false;
    }
    return left.getValue() == right.getValue();
  }

  private static RiskAssessment assessment(RiskLevel level, String reasonCode) {
    return new RiskAssessment(level, reasonCode);
  }
}
