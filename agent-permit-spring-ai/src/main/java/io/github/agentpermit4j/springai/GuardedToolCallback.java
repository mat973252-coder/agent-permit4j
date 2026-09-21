package io.github.agentpermit4j.springai;

import io.github.agentpermit4j.execution.ResultDecisionPipeline;
import io.github.agentpermit4j.execution.ToolExecutionResult;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Function;
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
  private final TrustedToolContextResolver contextResolver;
  private final Function<String, String> errorMessageResolver;
  private final JsonHelper json = new JsonHelper();

  public GuardedToolCallback(
      ToolDefinition definition,
      ResultDecisionPipeline pipeline,
      SpringAiToolContract contract) {
    this(definition, pipeline, contract, supplied -> supplied, reasonCode -> null);
  }

  public GuardedToolCallback(
      ToolDefinition definition,
      ResultDecisionPipeline pipeline,
      SpringAiToolContract contract,
      TrustedToolContextResolver contextResolver) {
    this(definition, pipeline, contract, contextResolver, reasonCode -> null);
  }

  GuardedToolCallback(
      ToolDefinition definition,
      ResultDecisionPipeline pipeline,
      SpringAiToolContract contract,
      TrustedToolContextResolver contextResolver,
      Function<String, String> errorMessageResolver) {
    this.definition = Objects.requireNonNull(definition, "definition");
    this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
    this.contextResolver = Objects.requireNonNull(contextResolver, "contextResolver");
    this.errorMessageResolver =
        Objects.requireNonNull(errorMessageResolver, "errorMessageResolver");
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
    return new GuardedToolCallback(
        definition, pipeline, policy.contract(), supplied -> supplied, policy::errorMessage);
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
      var mapped = mapper.map(toolInput, resolveContext(toolContext));
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

  private ToolContext resolveContext(ToolContext suppliedContext) {
    try {
      return contextResolver.resolve(suppliedContext);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private String decisionJson(ToolExecutionResult result) {
    var outcome = result.decision().outcome().name();
    var reasonCode = result.decision().reasonCode();
    var message = "DENIED".equals(outcome) ? errorMessageResolver.apply(reasonCode) : null;
    return decisionJson(outcome, reasonCode, result.output(), message);
  }

  private String decisionJson(String outcome, String reasonCode) {
    return decisionJson(outcome, reasonCode, null, null);
  }

  private String decisionJson(
      String outcome, String reasonCode, String output, String message) {
    var envelope = new LinkedHashMap<String, Object>();
    envelope.put("outcome", outcome);
    envelope.put("reasonCode", reasonCode);
    if (message != null) {
      envelope.put("message", message);
    }
    if (output != null) {
      envelope.put("output", output);
    }
    return json.toJson(envelope);
  }
}
