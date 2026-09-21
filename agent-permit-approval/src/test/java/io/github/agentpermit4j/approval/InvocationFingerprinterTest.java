package io.github.agentpermit4j.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.core.Action;
import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.InvocationContext;
import io.github.agentpermit4j.core.Principal;
import io.github.agentpermit4j.core.Resource;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.ToolDescriptor;
import io.github.agentpermit4j.core.ToolEffect;
import io.github.agentpermit4j.core.ToolInvocation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InvocationFingerprinterTest {

  private final InvocationFingerprinter fingerprinter = new InvocationFingerprinter();

  @Test
  void producesStableDigestIndependentOfMapInsertionOrder() {
    var first = new LinkedHashMap<String, String>();
    first.put("service", "checkout");
    first.put("version", "1.2.3");
    var second = new LinkedHashMap<String, String>();
    second.put("version", "1.2.3");
    second.put("service", "checkout");

    var firstFingerprint = fingerprinter.fingerprint(invocation("service://checkout", first));
    var secondFingerprint = fingerprinter.fingerprint(invocation("service://checkout", second));

    assertEquals(firstFingerprint, secondFingerprint);
    assertTrue(firstFingerprint.value().matches("[0-9a-f]{64}"));
  }

  @Test
  void changesDigestWhenServiceOrVersionChanges() {
    var original =
        fingerprinter.fingerprint(
            invocation("service://checkout", Map.of("service", "checkout", "version", "1.2.3")));
    var changedService =
        fingerprinter.fingerprint(
            invocation("service://billing", Map.of("service", "billing", "version", "1.2.3")));
    var changedVersion =
        fingerprinter.fingerprint(
            invocation("service://checkout", Map.of("service", "checkout", "version", "2.0.0")));

    assertNotEquals(original, changedService);
    assertNotEquals(original, changedVersion);
  }

  @Test
  void lengthPrefixesPreventFieldConcatenationCollisions() {
    var first = fingerprinter.fingerprint(invocation("service://checkout", Map.of("ab", "c")));
    var second = fingerprinter.fingerprint(invocation("service://checkout", Map.of("a", "bc")));

    assertNotEquals(first, second);
  }

  private static ToolInvocation invocation(String service, Map<String, String> arguments) {
    return new ToolInvocation(
        new ToolDescriptor(
            "deployment.apply",
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Principal("agent-1", Map.of("role", "deployer")),
        new Action("deployment.apply"),
        new Resource("deployment", service, Map.of("region", "cn-test-1")),
        new InvocationContext("tenant-a", "production"),
        arguments);
  }
}
