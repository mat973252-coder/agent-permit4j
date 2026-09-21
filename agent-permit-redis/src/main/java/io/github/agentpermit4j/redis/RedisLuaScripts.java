package io.github.agentpermit4j.redis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RedisLuaScripts {

  private static final String ROOT =
      "io/github/agentpermit4j/redis/";

  private RedisLuaScripts() {}

  static String load(String name) {
    try (var input =
        RedisLuaScripts.class.getClassLoader().getResourceAsStream(ROOT + name)) {
      if (input == null) {
        throw new IllegalStateException("missing Redis script");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("cannot read Redis script");
    }
  }
}
