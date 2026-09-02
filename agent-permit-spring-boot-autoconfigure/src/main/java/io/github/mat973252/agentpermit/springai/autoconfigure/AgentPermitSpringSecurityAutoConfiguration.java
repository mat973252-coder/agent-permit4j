package io.github.mat973252.agentpermit.springai.autoconfigure;

import io.github.mat973252.agentpermit.springai.TrustedToolContextResolver;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.Authentication;

@AutoConfiguration(before = AgentPermitSpringAiAutoConfiguration.class)
@ConditionalOnClass({Authentication.class, TrustedToolContextResolver.class})
public class AgentPermitSpringSecurityAutoConfiguration {

  @Bean
  @ConditionalOnBean(SpringSecurityTenantEnvironmentResolver.class)
  @ConditionalOnMissingBean(TrustedToolContextResolver.class)
  TrustedToolContextResolver agentPermitSpringSecurityToolContextResolver(
      SpringSecurityTenantEnvironmentResolver tenantEnvironmentResolver) {
    return new SpringSecurityToolContextResolver(tenantEnvironmentResolver);
  }
}
