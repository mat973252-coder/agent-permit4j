package io.github.mat973252.agentpermit.springai.autoconfigure;

import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import io.github.mat973252.agentpermit.springai.TrustedToolContextResolver;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SpringSecurityToolContextResolver implements TrustedToolContextResolver {

  private final SpringSecurityTenantEnvironmentResolver tenantEnvironmentResolver;

  public SpringSecurityToolContextResolver(
      SpringSecurityTenantEnvironmentResolver tenantEnvironmentResolver) {
    this.tenantEnvironmentResolver =
        Objects.requireNonNull(tenantEnvironmentResolver, "tenantEnvironmentResolver");
  }

  @Override
  public ToolContext resolve(ToolContext suppliedContext) {
    try {
      var authentication = SecurityContextHolder.getContext().getAuthentication();
      var principalId = authenticatedPrincipalId(authentication);
      if (principalId == null) {
        return null;
      }
      var tenantEnvironment = tenantEnvironmentResolver.resolve(authentication);
      if (tenantEnvironment == null) {
        return null;
      }
      var values = suppliedValues(suppliedContext);
      values.put(SpringAiToolContextKeys.PRINCIPAL_ID, principalId);
      values.put(SpringAiToolContextKeys.TENANT_ID, tenantEnvironment.tenantId());
      values.put(SpringAiToolContextKeys.ENVIRONMENT, tenantEnvironment.environment());
      return new ToolContext(Map.copyOf(values));
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private static String authenticatedPrincipalId(Authentication authentication) {
    if (authentication == null
        || !authentication.isAuthenticated()
        || authentication instanceof AnonymousAuthenticationToken) {
      return null;
    }
    var principalId = authentication.getName();
    return principalId == null || principalId.isBlank() ? null : principalId;
  }

  private static HashMap<String, Object> suppliedValues(ToolContext suppliedContext) {
    return suppliedContext == null
        ? new HashMap<>()
        : new HashMap<>(suppliedContext.getContext());
  }
}
