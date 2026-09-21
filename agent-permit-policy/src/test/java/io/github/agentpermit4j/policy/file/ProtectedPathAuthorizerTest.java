package io.github.agentpermit4j.policy.file;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProtectedPathAuthorizerTest {

  private final ProtectedPathAuthorizer authorizer = new ProtectedPathAuthorizer();

  @Test
  void allowsReadingReadmeInsideWorkspace() {
    var decision = authorizer.authorize(invocation("file.read", "/workspace/README.md", false));

    assertAll(
        () -> assertTrue(decision.permitted()),
        () -> assertEquals("PROTECTED_PATH_ALLOWED", decision.reasonCode()));
  }

  @Test
  void doesNotConfuseSiblingPrefixWithProtectedRoot() {
    var decision = authorizer.authorize(invocation("file.delete", "/workspace2", true));

    assertTrue(decision.permitted());
  }

  @ParameterizedTest(name = "denies protected path {0}, recursive={1}")
  @MethodSource("protectedDeletes")
  void deniesDeletionThatCanRemoveProtectedWorkspace(String path, boolean recursive) {
    var decision = authorizer.authorize(invocation("file.delete", path, recursive));

    assertAll(
        () -> assertFalse(decision.permitted()),
        () -> assertEquals("PROTECTED_PATH", decision.reasonCode()));
  }

  @ParameterizedTest(name = "denies invalid path {0}")
  @ValueSource(
      strings = {
        "workspace/README.md",
        "/workspace/../README.md",
        "file:///workspace/README.md",
        "C:/workspace/README.md",
        "//server/share"
      })
  void deniesAmbiguousOrNonPortableFilePath(String path) {
    var decision = authorizer.authorize(invocation("file.read", path, false));

    assertAll(
        () -> assertFalse(decision.permitted()),
        () -> assertEquals("INVALID_FILE_PATH", decision.reasonCode()));
  }

  @Test
  void deniesControlCharactersInFilePath() {
    var decision =
        authorizer.authorize(invocation("file.read", "/workspace" + '\0' + "/README.md", false));

    assertAll(
        () -> assertFalse(decision.permitted()),
        () -> assertEquals("INVALID_FILE_PATH", decision.reasonCode()));
  }

  @Test
  void deniesAmbiguousRecursiveFlag() {
    var decision = authorizer.authorize(invocation("file.delete", "/workspace/docs", "yes"));

    assertAll(
        () -> assertFalse(decision.permitted()),
        () -> assertEquals("INVALID_RECURSIVE_FLAG", decision.reasonCode()));
  }

  @Test
  void deniesFileActionDisguisedAsAnotherResourceType() {
    var decision =
        authorizer.authorize(
            invocation("file.delete", "http", "/workspace", Boolean.toString(true)));

    assertAll(
        () -> assertFalse(decision.permitted()),
        () -> assertEquals("FILE_RESOURCE_REQUIRED", decision.reasonCode()));
  }

  private static Stream<Arguments> protectedDeletes() {
    return Stream.of(
        Arguments.of("/workspace", false),
        Arguments.of("/workspace/", true),
        Arguments.of("/workspace/docs", true),
        Arguments.of("/workspace//docs", true),
        Arguments.of("\\workspace\\docs", true),
        Arguments.of("/WORKSPACE/docs", true));
  }

  private static ToolInvocation invocation(String action, String path, boolean recursive) {
    return invocation(action, path, Boolean.toString(recursive));
  }

  private static ToolInvocation invocation(String action, String path, String recursive) {
    return invocation(action, "file", path, recursive);
  }

  private static ToolInvocation invocation(
      String action, String resourceType, String path, String recursive) {
    return new ToolInvocation(
        new ToolDescriptor(
            "workspace.file",
            ToolEffect.DELETE,
            Reversibility.IRREVERSIBLE,
            DataSensitivity.CONFIDENTIAL),
        new Principal("agent-1", Map.of("role", "developer")),
        new Action(action),
        new Resource(resourceType, path, Map.of()),
        new InvocationContext("tenant-a", "test"),
        Map.of("recursive", recursive));
  }
}
