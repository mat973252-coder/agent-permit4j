package io.github.mat973252.agentpermit.playground.springai;

import io.github.mat973252.agentpermit.core.DecisionResult;
import io.github.mat973252.agentpermit.execution.DecisionPipeline;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

final class GuardedToolCallback implements ToolCallback {

  private static final String PIPELINE_FAILED = "SPRING_AI_PIPELINE_FAILED";

  private final ToolDefinition definition;
  private final DecisionPipeline pipeline;
  private final SpringAiInvocationMapper mapper;

  GuardedToolCallback(
      ToolDefinition definition, DecisionPipeline pipeline, SpringAiInvocationMapper mapper) {
    this.definition = Objects.requireNonNull(definition, "definition");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public ToolDefinition getToolDefinition() {
    return definition;
  }

  @Override
  public String call(String toolInput) {
    return call(toolInput, null);
  }

  @Override
  public String call(String toolInput, ToolContext toolContext) {
    try {
      var mapped = mapper.map(toolInput, toolContext);
      var result =
          pipeline.process(
              mapped.invocation(), mapped.approvalRequestId(), mapped.idempotencyKey());
      return decisionJson(result);
    } catch (SpringAiMappingException exception) {
      return decisionJson("DENIED", exception.reasonCode());
    } catch (RuntimeException exception) {
      return decisionJson("FAILED", PIPELINE_FAILED);
    }
  }

  private static String decisionJson(DecisionResult result) {
    return decisionJson(result.outcome().name(), result.reasonCode());
  }

  private static String decisionJson(String outcome, String reasonCode) {
    return "{\"outcome\":\"" + outcome + "\",\"reasonCode\":\"" + reasonCode + "\"}";
  }
}
