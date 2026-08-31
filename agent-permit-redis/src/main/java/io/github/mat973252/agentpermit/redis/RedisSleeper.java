package io.github.mat973252.agentpermit.redis;

import java.time.Duration;

@FunctionalInterface
interface RedisSleeper {

  void sleep(Duration duration) throws InterruptedException;
}
