package io.github.agentpermit4j.springai;

import org.springframework.ai.chat.model.ToolContext;

@FunctionalInterface
public interface TrustedToolContextResolver {

  ToolContext resolve(ToolContext suppliedContext);
}
