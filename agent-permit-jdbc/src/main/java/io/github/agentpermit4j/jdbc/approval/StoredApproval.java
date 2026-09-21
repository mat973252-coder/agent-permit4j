package io.github.agentpermit4j.jdbc.approval;

record StoredApproval(String fingerprint, long expiresAtEpochMillis, int approvalState) {
  boolean expiredAt(long now) {
    return now >= expiresAtEpochMillis;
  }

  boolean isPending() {
    return approvalState == 0 || isReviewPending();
  }

  boolean isReviewPending() {
    return approvalState == 2;
  }

  boolean isApproved() {
    return approvalState == 1 || approvalState == 3;
  }
}
