package io.github.agentpermit4j.springai;

final class SpringAiMappingException extends RuntimeException {

  private final String reasonCode;

  SpringAiMappingException(String reasonCode) {
    super(reasonCode);
    this.reasonCode = reasonCode;
  }

  String reasonCode() {
    return reasonCode;
  }
}
