package io.github.agentpermit4j.approval;

import io.github.agentpermit4j.core.ToolInvocation;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

public final class InvocationFingerprinter {

  private static final String SCHEMA = "agent-permit4j/invocation-fingerprint/v1";

  public InvocationFingerprint fingerprint(ToolInvocation invocation) {
    Objects.requireNonNull(invocation, "invocation");
    var digest = sha256();
    putField(digest, "schema", SCHEMA);
    putField(digest, "descriptor.name", invocation.descriptor().name());
    putField(digest, "descriptor.effect", invocation.descriptor().effect().name());
    putField(
        digest, "descriptor.reversibility", invocation.descriptor().reversibility().name());
    putField(
        digest, "descriptor.dataSensitivity", invocation.descriptor().dataSensitivity().name());
    putField(digest, "principal.id", invocation.principal().id());
    putMap(digest, "principal.attributes", invocation.principal().attributes());
    putField(digest, "action.name", invocation.action().name());
    putField(digest, "resource.type", invocation.resource().type());
    putField(digest, "resource.identifier", invocation.resource().identifier());
    putMap(digest, "resource.attributes", invocation.resource().attributes());
    putField(digest, "context.tenantId", invocation.context().tenantId());
    putField(digest, "context.environment", invocation.context().environment());
    putMap(digest, "arguments", invocation.arguments());
    return new InvocationFingerprint(HexFormat.of().formatHex(digest.digest()));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static void putMap(MessageDigest digest, String name, Map<String, String> values) {
    putField(digest, name + ".size", Integer.toString(values.size()));
    var keys = values.keySet().stream().sorted(InvocationFingerprinter::compareUtf8).toList();
    for (var index = 0; index < keys.size(); index++) {
      var key = keys.get(index);
      putField(digest, name + "." + index + ".key", key);
      putField(digest, name + "." + index + ".value", values.get(key));
    }
  }

  private static int compareUtf8(String left, String right) {
    return Arrays.compareUnsigned(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static void putField(MessageDigest digest, String name, String value) {
    putString(digest, name);
    putString(digest, value);
  }

  private static void putString(MessageDigest digest, String value) {
    var bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
