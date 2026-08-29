package io.github.mat973252.agentpermit.playground.springai;

import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.execution.ToolExecutionResult;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.util.JsonHelper;

final class GuardedToolCallback implements ToolCallback {

  private static final String PIPELINE_FAILED = "SPRING_AI_PIPELINE_FAILED";

  private final ToolDefinition definition;
  private final ResultDecisionPipeline pipeline;
  private final SpringAiInvocationMapper mapper;
  private final JsonHelper json = new JsonHelper();

  GuardedToolCallback(
      ToolDefinition definition, ResultDecisionPipeline pipeline, SpringAiInvocationMapper mapper) {
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

  private String decisionJson(ToolExecutionResult result) {
    return decisionJson(
        result.decision().outcome().name(), result.decision().reasonCode(), result.output());
  }

  private String decisionJson(String outcome, String reasonCode) {
    return decisionJson(outcome, reasonCode, null);
  }

  private String decisionJson(String outcome, String reasonCode, String output) {
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("outcome", outcome);
    envelope.put("reasonCode", reasonCode);
    if (output != null) {
      envelope.put("output", output);
    }
    return json.toJson(envelope);
  }
}
