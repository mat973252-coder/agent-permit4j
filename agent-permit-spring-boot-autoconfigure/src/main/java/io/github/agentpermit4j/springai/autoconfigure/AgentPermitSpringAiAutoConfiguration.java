package io.github.agentpermit4j.springai.autoconfigure;

import io.github.agentpermit4j.execution.ResultDecisionPipeline;
import io.github.agentpermit4j.springai.GuardedToolCallback;
import io.github.agentpermit4j.springai.SpringAiToolContract;
import io.github.agentpermit4j.springai.TrustedToolContextResolver;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(GuardedToolCallback.class)
public class AgentPermitSpringAiAutoConfiguration {

  @Bean
  @ConditionalOnBean({ToolDefinition.class, ResultDecisionPipeline.class, SpringAiToolContract.class})
  @ConditionalOnMissingBean(GuardedToolCallback.class)
  GuardedToolCallback agentPermitGuardedToolCallback(
      ToolDefinition definition,
      ResultDecisionPipeline pipeline,
      SpringAiToolContract contract,
      ObjectProvider<TrustedToolContextResolver> contextResolverProvider) {
    var contextResolver = contextResolverProvider.getIfAvailable();
    return contextResolver == null
        ? new GuardedToolCallback(definition, pipeline, contract)
        : new GuardedToolCallback(definition, pipeline, contract, contextResolver);
  }
}
