package io.github.mat973252.agentpermit.springai;

import org.springframework.ai.chat.model.ToolContext;

@FunctionalInterface
public interface TrustedToolContextResolver {

  ToolContext resolve(ToolContext suppliedContext);
}
