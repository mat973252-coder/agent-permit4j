package io.github.agentpermit4j.springai;

public final class SpringAiToolContextKeys {

  public static final String PRINCIPAL_ID = "agentPermit.principalId";
  public static final String TENANT_ID = "agentPermit.tenantId";
  public static final String ENVIRONMENT = "agentPermit.environment";
  public static final String APPROVAL_REQUEST_ID = "agentPermit.approvalRequestId";
  public static final String IDEMPOTENCY_KEY = "agentPermit.idempotencyKey";

  private SpringAiToolContextKeys() {}
}
