package io.github.mat973252.agentpermit.springai.autoconfigure;

import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.springai.GuardedToolCallback;
import io.github.mat973252.agentpermit.springai.SpringAiToolContract;
import org.springframework.ai.tool.definition.ToolDefinition;
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
      SpringAiToolContract contract) {
    return new GuardedToolCallback(definition, pipeline, contract);
  }
}
