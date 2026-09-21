package io.github.agentpermit4j.springai.autoconfigure;

import org.springframework.security.core.Authentication;

@FunctionalInterface
public interface SpringSecurityTenantEnvironmentResolver {

  TenantEnvironment resolve(Authentication authentication);

  record TenantEnvironment(String tenantId, String environment) {

    public TenantEnvironment {
      tenantId = requiredText(tenantId, "tenantId");
      environment = requiredText(environment, "environment");
    }

    private static String requiredText(String value, String name) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(name + " must not be blank");
      }
      return value;
    }
  }
}
