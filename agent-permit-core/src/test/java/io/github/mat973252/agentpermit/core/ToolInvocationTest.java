package io.github.mat973252.agentpermit.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ToolInvocationTest {

  @Test
  void rejectsBlankRequiredText() {
    assertAll(
        () -> assertThrows(IllegalArgumentException.class, () -> new Principal(" ", Map.of())),
        () -> assertThrows(IllegalArgumentException.class, () -> new Action(" ")),
        () ->
            assertThrows(
                IllegalArgumentException.class, () -> new Resource(" ", "resource-1", Map.of())),
        () ->
            assertThrows(
                IllegalArgumentException.class, () -> new Resource("file", " ", Map.of())),
        () ->
            assertThrows(
                IllegalArgumentException.class, () -> new InvocationContext(" ", "test")),
        () ->
            assertThrows(
                IllegalArgumentException.class, () -> new InvocationContext("tenant-a", " ")),
        () -> assertThrows(IllegalArgumentException.class, () -> new ToolDescriptor(" ")));
  }

  @Test
  void rejectsMissingInvocationParts() {
    assertThrows(
        NullPointerException.class,
        () ->
            new ToolInvocation(
                null,
                new Principal("agent-1", Map.of()),
                new Action("file.read"),
                new Resource("file", "/workspace/README.md", Map.of()),
                new InvocationContext("tenant-a", "test"),
                Map.of()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("supportedInvocations")
  void representsDifferentToolCategoriesWithOneDomainModel(
      String resourceType, ToolInvocation invocation) {
    assertAll(
        () -> assertEquals(ToolInvocation.class, invocation.getClass()),
        () -> assertEquals(resourceType, invocation.resource().type()),
        () -> assertEquals("tenant-a", invocation.context().tenantId()));
  }

  @Test
  void protectsPolicyInputsFromCallerMutation() {
    var principalAttributes = mutableInput();
    var resourceAttributes = mutableInput();
    var invocationArguments = mutableInput();
    var invocation =
        new ToolInvocation(
            new ToolDescriptor("workspace.read-file"),
            new Principal("agent-1", principalAttributes),
            new Action("file.read"),
            new Resource("file", "/workspace/README.md", resourceAttributes),
            new InvocationContext("tenant-a", "test"),
            invocationArguments);

    principalAttributes.put("changed", "after-construction");
    resourceAttributes.put("changed", "after-construction");
    invocationArguments.put("changed", "after-construction");

    assertAll(
        () -> assertEquals(Map.of("original", "value"), invocation.principal().attributes()),
        () -> assertEquals(Map.of("original", "value"), invocation.resource().attributes()),
        () -> assertEquals(Map.of("original", "value"), invocation.arguments()),
        () -> assertImmutable(invocation.principal().attributes()),
        () -> assertImmutable(invocation.resource().attributes()),
        () -> assertImmutable(invocation.arguments()));
  }

  private static void assertImmutable(Map<String, String> input) {
    assertThrows(
        UnsupportedOperationException.class, () -> input.put("changed", "through-accessor"));
  }

  private static Stream<Arguments> supportedInvocations() {
    return Stream.of(
        invocation("file", "file.read", "/workspace/README.md", Map.of("path", "README.md")),
        invocation("sql", "sql.query", "db://main", Map.of("statement", "SELECT 1")),
        invocation("messaging", "message.send", "channel://ops", Map.of("body", "ready")),
        invocation("http", "http.request", "https://api.example.test", Map.of("method", "GET")),
        invocation(
            "deployment", "deployment.apply", "service://checkout", Map.of("version", "1.2.3")));
  }

  private static Arguments invocation(
      String resourceType,
      String actionName,
      String resourceIdentifier,
      Map<String, String> arguments) {
    var invocation =
        new ToolInvocation(
            new ToolDescriptor(actionName),
            new Principal("agent-1", Map.of("role", "developer")),
            new Action(actionName),
            new Resource(resourceType, resourceIdentifier, Map.of()),
            new InvocationContext("tenant-a", "test"),
            arguments);
    return Arguments.of(resourceType, invocation);
  }

  private static Map<String, String> mutableInput() {
    var attributes = new LinkedHashMap<String, String>();
    attributes.put("original", "value");
    return attributes;
  }
}
