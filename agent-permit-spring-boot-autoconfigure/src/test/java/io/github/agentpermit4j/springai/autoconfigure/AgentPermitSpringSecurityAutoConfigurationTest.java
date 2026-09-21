package io.github.agentpermit4j.springai.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.agentpermit4j.springai.SpringAiToolContextKeys;
import io.github.agentpermit4j.springai.TrustedToolContextResolver;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AgentPermitSpringSecurityAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(AgentPermitSpringSecurityAutoConfiguration.class));

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void createsResolverOnlyFromExplicitTenantAndEnvironmentResolver() {
    contextRunner
        .withBean(
            SpringSecurityTenantEnvironmentResolver.class,
            () ->
                authentication ->
                    new SpringSecurityTenantEnvironmentResolver.TenantEnvironment(
                        "tenant-a", "production"))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(1, context.getBeansOfType(TrustedToolContextResolver.class).size());
              assertTrue(
                  context.getBean(TrustedToolContextResolver.class)
                      instanceof SpringSecurityToolContextResolver);
            });
  }

  @Test
  void backsOffWithoutApplicationTenantAndEnvironmentResolver() {
    contextRunner.run(
        context ->
            assertTrue(context.getBeansOfType(TrustedToolContextResolver.class).isEmpty()));
  }

  @Test
  void backsOffWhenSpringSecurityIsUnavailable() {
    contextRunner
        .withClassLoader(new FilteredClassLoader(Authentication.class))
        .withBean(
            SpringSecurityTenantEnvironmentResolver.class,
            () ->
                authentication ->
                    new SpringSecurityTenantEnvironmentResolver.TenantEnvironment(
                        "tenant-a", "production"))
        .run(
            context ->
                assertTrue(context.getBeansOfType(TrustedToolContextResolver.class).isEmpty()));
  }

  @Test
  void authenticatedContextOverridesSuppliedIdentityAndPreservesExecutionMetadata() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice",
                "unused",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    var resolver =
        new SpringSecurityToolContextResolver(
            authentication ->
                new SpringSecurityTenantEnvironmentResolver.TenantEnvironment(
                    "tenant-a", "production"));

    var resolved =
        resolver.resolve(
            new ToolContext(
                Map.of(
                    SpringAiToolContextKeys.PRINCIPAL_ID,
                    "spoofed-user",
                    SpringAiToolContextKeys.TENANT_ID,
                    "spoofed-tenant",
                    SpringAiToolContextKeys.ENVIRONMENT,
                    "staging",
                    SpringAiToolContextKeys.APPROVAL_REQUEST_ID,
                    "approval-7",
                    SpringAiToolContextKeys.IDEMPOTENCY_KEY,
                    "request-42")));

    assertEquals("alice", resolved.getContext().get(SpringAiToolContextKeys.PRINCIPAL_ID));
    assertEquals("tenant-a", resolved.getContext().get(SpringAiToolContextKeys.TENANT_ID));
    assertEquals(
        "production", resolved.getContext().get(SpringAiToolContextKeys.ENVIRONMENT));
    assertEquals(
        "approval-7",
        resolved.getContext().get(SpringAiToolContextKeys.APPROVAL_REQUEST_ID));
    assertEquals(
        "request-42", resolved.getContext().get(SpringAiToolContextKeys.IDEMPOTENCY_KEY));
  }

  @Test
  void snapshotsAuthenticatedPrincipalNameOnce() {
    var reads = new AtomicInteger();
    var authentication =
        new UsernamePasswordAuthenticationToken(
            "unused",
            "unused",
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))) {
          @Override
          public String getName() {
            return reads.incrementAndGet() == 1 ? "alice" : "changed-user";
          }
        };
    SecurityContextHolder.getContext().setAuthentication(authentication);
    var resolver =
        new SpringSecurityToolContextResolver(
            ignored ->
                new SpringSecurityTenantEnvironmentResolver.TenantEnvironment(
                    "tenant-a", "production"));

    var resolved = resolver.resolve(null);

    assertEquals("alice", resolved.getContext().get(SpringAiToolContextKeys.PRINCIPAL_ID));
    assertEquals(1, reads.get());
  }

  @Test
  void missingUnauthenticatedAndAnonymousContextsFailClosed() {
    var resolver =
        new SpringSecurityToolContextResolver(
            authentication ->
                new SpringSecurityTenantEnvironmentResolver.TenantEnvironment(
                    "tenant-a", "production"));
    var supplied =
        new ToolContext(Map.of(SpringAiToolContextKeys.IDEMPOTENCY_KEY, "request-42"));

    assertNull(resolver.resolve(supplied));

    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken("alice", "unused"));
    assertNull(resolver.resolve(supplied));

    SecurityContextHolder.getContext()
        .setAuthentication(
            new AnonymousAuthenticationToken(
                "key",
                "anonymous",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
    assertNull(resolver.resolve(supplied));
  }

  @Test
  void invalidApplicationResolutionFailsClosed() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "alice",
                "unused",
                List.of(new SimpleGrantedAuthority("ROLE_OPERATOR"))));
    var supplied =
        new ToolContext(Map.of(SpringAiToolContextKeys.IDEMPOTENCY_KEY, "request-42"));

    assertNull(new SpringSecurityToolContextResolver(authentication -> null).resolve(supplied));
    assertNull(
        new SpringSecurityToolContextResolver(
                authentication -> {
                  throw new IllegalStateException("resolver unavailable");
                })
            .resolve(supplied));
  }
}
