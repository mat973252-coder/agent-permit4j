package io.github.mat973252.agentpermit.springai;

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
