package io.github.mat973252.agentpermit.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RiskAssessmentTest {

  @Test
  void carriesMachineReadableRiskDecision() {
    var assessment = new RiskAssessment(RiskLevel.HIGH, "SQL_SELECTIVE_UPDATE");

    assertAll(
        () -> assertEquals(RiskLevel.HIGH, assessment.level()),
        () -> assertEquals("SQL_SELECTIVE_UPDATE", assessment.reasonCode()));
  }

  @Test
  void rejectsInvalidRiskDecision() {
    assertAll(
        () ->
            assertThrows(
                NullPointerException.class,
                () -> new RiskAssessment(null, "SQL_SELECTIVE_UPDATE")),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> new RiskAssessment(RiskLevel.HIGH, " ")),
        () ->
            assertThrows(
                IllegalArgumentException.class,
                () -> new RiskAssessment(RiskLevel.HIGH, "free form reason")));
  }
}
