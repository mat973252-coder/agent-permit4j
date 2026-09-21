package io.github.agentpermit4j.policy.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MessagingRiskEvaluatorTest {

  private final MessagingRiskEvaluator evaluator =
      new MessagingRiskEvaluator(new MessagingRiskPolicy(Set.of("channel://ops"), 8));

  @Test
  void classifiesAllowedSendAsHighRisk() {
    var assessment =
        evaluator.evaluate(invocation("messaging", "message.send", "channel://ops", "12345678"));

    assertEquals(RiskLevel.HIGH, assessment.level());
    assertEquals("MESSAGING_SEND", assessment.reasonCode());
  }

  @ParameterizedTest(name = "fails closed for {0}")
  @MethodSource("deniedMessages")
  void deniesMessagesThatViolateDestinationOrContentPolicy(
      String scenario, ToolInvocation invocation, String expectedReason) {
    var assessment = evaluator.evaluate(invocation);

    assertEquals(RiskLevel.DENY, assessment.level());
    assertEquals(expectedReason, assessment.reasonCode());
  }

  @Test
  void rejectsInvalidRuntimePolicy() {
    assertThrows(IllegalArgumentException.class, () -> new MessagingRiskPolicy(Set.of(), 8));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MessagingRiskPolicy(Set.of("channel://ops"), -1));
    assertThrows(
        IllegalArgumentException.class, () -> new MessagingRiskPolicy(Set.of(" "), 8));
  }

  private static Stream<Arguments> deniedMessages() {
    return Stream.of(
        Arguments.of(
            "non-messaging resource",
            invocation("http", "message.send", "channel://ops", "ready"),
            "MESSAGING_RESOURCE_REQUIRED"),
        Arguments.of(
            "different action",
            invocation("messaging", "message.delete", "channel://ops", "ready"),
            "MESSAGING_SEND_ACTION_REQUIRED"),
        Arguments.of(
            "unlisted destination",
            invocation("messaging", "message.send", "channel://external", "ready"),
            "MESSAGING_DESTINATION_NOT_ALLOWED"),
        Arguments.of(
            "missing body",
            invocation("messaging", "message.send", "channel://ops", null),
            "MESSAGING_BODY_REQUIRED"),
        Arguments.of(
            "blank body",
            invocation("messaging", "message.send", "channel://ops", "  "),
            "MESSAGING_BODY_REQUIRED"),
        Arguments.of(
            "oversized UTF-8 body",
            invocation("messaging", "message.send", "channel://ops", "你好你"),
            "MESSAGING_BODY_TOO_LARGE"));
  }

  private static ToolInvocation invocation(
      String resourceType, String action, String destination, String body) {
    var arguments = new HashMap<String, String>();
    if (body != null) {
      arguments.put("body", body);
    }
    return new ToolInvocation(
        new ToolDescriptor(
            "message.send",
            ToolEffect.WRITE,
            Reversibility.IRREVERSIBLE,
            DataSensitivity.CONFIDENTIAL),
        new Principal("agent-1", Map.of("role", "developer")),
        new Action(action),
        new Resource(resourceType, destination, Map.of()),
        new InvocationContext("tenant-a", "test"),
        arguments);
  }
}
