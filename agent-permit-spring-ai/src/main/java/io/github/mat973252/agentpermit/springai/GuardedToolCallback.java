package io.github.mat973252.agentpermit.springai;

import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.execution.ToolExecutionResult;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Objects;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.util.JsonHelper;

public final class GuardedToolCallback implements ToolCallback {

  private static final String PIPELINE_FAILED = "SPRING_AI_PIPELINE_FAILED";

  private final ToolDefinition definition;
  private final ResultDecisionPipeline pipeline;
  private final SpringAiInvocationMapper mapper;
  private final JsonHelper json = new JsonHelper();

  public GuardedToolCallback(
      ToolDefinition definition,
      ResultDecisionPipeline pipeline,
      SpringAiToolContract contract) {
    this.definition = Objects.requireNonNull(definition, "definition");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    var requiredContract = Objects.requireNonNull(contract, "contract");
    if (!definition.name().equals(requiredContract.descriptor().name())) {
      throw new IllegalArgumentException(
          "definition name must match contract descriptor name");
    }
    mapper = new SpringAiInvocationMapper(requiredContract);
  }

  public static GuardedToolCallback fromAnnotated(
      ResultDecisionPipeline.Dependencies dependencies, Method method) {
    Objects.requireNonNull(method, "method");
    return fromAnnotated(ToolDefinitions.from(method), dependencies, method);
  }

  public static GuardedToolCallback fromAnnotated(
      ToolDefinition definition,
      ResultDecisionPipeline.Dependencies dependencies,
      Method method) {
    var policy = AgentPermitMethodPolicy.from(definition, method);
    var pipeline = new ResultDecisionPipeline(policy.decorate(dependencies));
    return new GuardedToolCallback(definition, pipeline, policy.contract());
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
