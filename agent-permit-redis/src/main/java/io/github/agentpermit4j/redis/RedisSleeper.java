package io.github.agentpermit4j.redis;

import java.time.Duration;

@FunctionalInterface
interface RedisSleeper {

  void sleep(Duration duration) throws InterruptedException;
}
