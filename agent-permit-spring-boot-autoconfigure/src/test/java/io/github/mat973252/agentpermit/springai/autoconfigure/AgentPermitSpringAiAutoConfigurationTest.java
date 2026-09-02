package io.github.mat973252.agentpermit.springai.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mat973252.agentpermit.core.Action;
import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.GateDecision;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskAssessment;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolDescriptor;
import io.github.mat973252.agentpermit.core.ToolEffect;
import io.github.mat973252.agentpermit.execution.InMemoryResultIdempotencyGuard;
import io.github.mat973252.agentpermit.execution.ResultDecisionPipeline;
import io.github.mat973252.agentpermit.springai.GuardedToolCallback;
import io.github.mat973252.agentpermit.springai.SpringAiToolContextKeys;
import io.github.mat973252.agentpermit.springai.SpringAiToolContract;
import io.github.mat973252.agentpermit.springai.TrustedToolContextResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;

class AgentPermitSpringAiAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(AgentPermitSpringAiAutoConfiguration.class));

  @Test
  void createsCallbackFromUniqueUserProvidedInputs() {
    var definition = definition("http.request");

    contextRunner
        .withBean(ToolDefinition.class, () -> definition)
        .withBean(SpringAiToolContract.class, () -> contract("http.request"))
        .withBean(ResultDecisionPipeline.class, AgentPermitSpringAiAutoConfigurationTest::pipeline)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(1, context.getBeansOfType(GuardedToolCallback.class).size());
              assertSame(
                  definition,
                  context.getBean(GuardedToolCallback.class).getToolDefinition());
            });
  }

  @Test
  void backsOffWithoutAllRequiredInputs() {
    contextRunner
        .withBean(ToolDefinition.class, () -> definition("http.request"))
        .withBean(SpringAiToolContract.class, () -> contract("http.request"))
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertTrue(context.getBeansOfType(GuardedToolCallback.class).isEmpty());
            });
  }

  @Test
  void preservesUserProvidedCallback() {
    var definition = definition("http.request");
    var pipeline = pipeline();
    var contract = contract("http.request");
    var userCallback = new GuardedToolCallback(definition, pipeline, contract);

    contextRunner
        .withBean(ToolDefinition.class, () -> definition)
        .withBean(SpringAiToolContract.class, () -> contract)
        .withBean(ResultDecisionPipeline.class, () -> pipeline)
        .withBean(GuardedToolCallback.class, () -> userCallback)
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(1, context.getBeansOfType(GuardedToolCallback.class).size());
              assertSame(userCallback, context.getBean(GuardedToolCallback.class));
            });
  }

  @Test
  void appliesUniqueTrustedContextResolverToAutoConfiguredCallback() {
    TrustedToolContextResolver contextResolver =
        supplied ->
            new ToolContext(
                Map.of(
                    SpringAiToolContextKeys.PRINCIPAL_ID,
                    "authenticated-user",
                    SpringAiToolContextKeys.TENANT_ID,
                    "tenant-a",
                    SpringAiToolContextKeys.ENVIRONMENT,
                    "production",
                    SpringAiToolContextKeys.IDEMPOTENCY_KEY,
                    "request-42"));

    contextRunner
        .withBean(ToolDefinition.class, () -> definition("http.request"))
        .withBean(SpringAiToolContract.class, () -> contract("http.request"))
        .withBean(ResultDecisionPipeline.class, AgentPermitSpringAiAutoConfigurationTest::pipeline)
        .withBean(TrustedToolContextResolver.class, () -> contextResolver)
        .run(
            context -> {
              var callback = context.getBean(GuardedToolCallback.class);
              var result =
                  callback.call(
                      "{\"uri\":\"https://api.example.com/orders\"}",
                      new ToolContext(Map.of()));

              assertTrue(result.contains("\"outcome\":\"EXECUTED\""));
            });
  }

  @Test
  void failsWhenToolContractIsAmbiguous() {
    contextRunner
        .withBean(ToolDefinition.class, () -> definition("http.request"))
        .withBean("firstContract", SpringAiToolContract.class, () -> contract("http.request"))
        .withBean("secondContract", SpringAiToolContract.class, () -> contract("http.request"))
        .withBean(ResultDecisionPipeline.class, AgentPermitSpringAiAutoConfigurationTest::pipeline)
        .run(
            context -> {
              var failure = context.getStartupFailure();
              assertNotNull(failure);
              assertTrue(hasCause(failure, NoUniqueBeanDefinitionException.class));
            });
  }

  @Test
  void registersAutoConfigurationInBootImports() throws Exception {
    var resource =
        getClass()
            .getClassLoader()
            .getResourceAsStream(
                "META-INF/spring/"
                    + "org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    assertNotNull(resource);
    try (resource) {
      var autoConfigurations =
          new String(resource.readAllBytes()).lines().filter(line -> !line.isBlank()).toList();
      assertEquals(
          List.of(
              AgentPermitSpringSecurityAutoConfiguration.class.getName(),
              AgentPermitSpringAiAutoConfiguration.class.getName()),
          autoConfigurations);
    }
  }

  private static boolean hasCause(Throwable failure, Class<? extends Throwable> type) {
    for (var cause = failure; cause != null; cause = cause.getCause()) {
      if (type.isInstance(cause)) {
        return true;
      }
    }
    return false;
  }

  private static ToolDefinition definition(String name) {
    return ToolDefinition.builder()
        .name(name)
        .description("Call an approved external HTTP API")
        .inputSchema("{\"type\":\"object\"}")
        .build();
  }

  private static SpringAiToolContract contract(String name) {
    return new SpringAiToolContract(
        new ToolDescriptor(
            name,
            ToolEffect.EXECUTE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.INTERNAL),
        new Action("http.request"),
        "http",
        "uri");
  }

  private static ResultDecisionPipeline pipeline() {
    return new ResultDecisionPipeline(
        new ResultDecisionPipeline.Dependencies(
            invocation -> new GateDecision(true, "VALIDATED"),
            invocation -> invocation,
            invocation -> new GateDecision(true, "AUTHORIZED"),
            invocation -> new RiskAssessment(RiskLevel.LOW, "HTTP_READ_ONLY"),
            (requestId, invocation) -> new GateDecision(true, "APPROVED"),
            new InMemoryResultIdempotencyGuard(),
            invocation -> "api-response",
            event -> {}));
  }
}
