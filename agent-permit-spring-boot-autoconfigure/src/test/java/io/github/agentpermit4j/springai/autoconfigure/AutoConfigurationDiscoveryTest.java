package io.github.agentpermit4j.springai.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

class AutoConfigurationDiscoveryTest {

  @Test
  void bootDiscoversBothGuardedToolAutoConfigurations() {
    var candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
        .getCandidates();
    assertTrue(candidates.contains(AgentPermitSpringAiAutoConfiguration.class.getName()));
    assertTrue(candidates.contains(AgentPermitSpringSecurityAutoConfiguration.class.getName()));
  }
}
