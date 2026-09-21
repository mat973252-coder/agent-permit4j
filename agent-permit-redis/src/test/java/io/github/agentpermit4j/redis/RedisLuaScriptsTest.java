package io.github.agentpermit4j.redis;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class RedisLuaScriptsTest {

  @Test
  void bundledClaimAndCompletionScriptsCanBeLoaded() {
    assertFalse(RedisLuaScripts.load("claim.lua").isBlank());
    assertFalse(RedisLuaScripts.load("complete.lua").isBlank());
  }
}
