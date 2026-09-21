package io.github.agentpermit4j.policy.sql;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SqlRiskEvaluatorTest {

  private final SqlRiskEvaluator evaluator = new SqlRiskEvaluator();

  @ParameterizedTest(name = "{0}")
  @MethodSource("classifiedSql")
  void classifiesSqlByParsedStatementShape(
      String scenario, String sql, RiskLevel expectedLevel, String expectedReasonCode) {
    var assessment = evaluator.evaluate(sqlInvocation(sql));

    assertEquals(expectedLevel, assessment.level());
    assertEquals(expectedReasonCode, assessment.reasonCode());
  }

  @ParameterizedTest(name = "fails closed for {0}")
  @MethodSource("deniedInputs")
  void deniesInputsThatCannotBeSafelyClassified(
      String scenario, ToolInvocation invocation, String expectedReasonCode) {
    var assessment = evaluator.evaluate(invocation);

    assertEquals(RiskLevel.DENY, assessment.level());
    assertEquals(expectedReasonCode, assessment.reasonCode());
  }

  private static Stream<Arguments> classifiedSql() {
    return Stream.of(
        Arguments.of("read-only select", "SELECT * FROM orders", RiskLevel.LOW, "SQL_READ_ONLY"),
        Arguments.of(
            "selective update",
            "UPDATE orders SET status = 'PAID' WHERE id = 42",
            RiskLevel.HIGH,
            "SQL_SELECTIVE_UPDATE"),
        Arguments.of(
            "unbounded update",
            "UPDATE orders SET status = 'PAID'",
            RiskLevel.CRITICAL,
            "SQL_UNBOUNDED_UPDATE"),
        Arguments.of(
            "where in string",
            "UPDATE orders SET note = 'WHERE id = 42'",
            RiskLevel.CRITICAL,
            "SQL_UNBOUNDED_UPDATE"),
        Arguments.of(
            "where in comment",
            "UPDATE orders SET status = 'PAID' /* WHERE id = 42 */",
            RiskLevel.CRITICAL,
            "SQL_UNBOUNDED_UPDATE"),
        Arguments.of(
            "always-true predicate",
            "UPDATE orders SET status = 'PAID' WHERE 1 = 1",
            RiskLevel.CRITICAL,
            "SQL_UNBOUNDED_UPDATE"),
        Arguments.of("drop table", "DROP TABLE orders", RiskLevel.DENY, "SQL_DESTRUCTIVE"),
        Arguments.of(
            "truncate table", "TRUNCATE TABLE orders", RiskLevel.DENY, "SQL_DESTRUCTIVE"));
  }

  private static Stream<Arguments> deniedInputs() {
    return Stream.of(
        Arguments.of("missing statement", sqlInvocation(null), "SQL_MISSING_STATEMENT"),
        Arguments.of("blank statement", sqlInvocation(" "), "SQL_MISSING_STATEMENT"),
        Arguments.of(
            "multiple statements",
            sqlInvocation("SELECT 1; DROP TABLE orders"),
            "SQL_MULTIPLE_STATEMENTS"),
        Arguments.of("malformed SQL", sqlInvocation("SELECT FROM"), "SQL_PARSE_FAILED"),
        Arguments.of(
            "unsupported statement",
            sqlInvocation("INSERT INTO orders VALUES (1)"),
            "SQL_UNSUPPORTED"),
        Arguments.of(
            "non-SQL resource",
            invocation("http", Map.of("statement", "SELECT 1")),
            "SQL_RESOURCE_REQUIRED"));
  }

  private static ToolInvocation sqlInvocation(String sql) {
    return invocation("sql", sql == null ? Map.of() : Map.of("statement", sql));
  }

  private static ToolInvocation invocation(
      String resourceType, Map<String, String> arguments) {
    return new ToolInvocation(
        new ToolDescriptor(
            "database.sql",
            ToolEffect.WRITE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.CONFIDENTIAL),
        new Principal("agent-1", Map.of("role", "developer")),
        new Action("sql.execute"),
        new Resource(resourceType, "db://main", Map.of()),
        new InvocationContext("tenant-a", "test"),
        arguments);
  }
}
