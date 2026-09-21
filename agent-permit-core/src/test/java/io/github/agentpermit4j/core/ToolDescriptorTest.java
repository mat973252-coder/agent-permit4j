package io.github.agentpermit4j.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ToolDescriptorTest {

  @Test
  void carriesStaticRiskMetadata() {
    var descriptor =
        new ToolDescriptor(
            "database.sql",
            ToolEffect.WRITE,
            Reversibility.COMPENSATABLE,
            DataSensitivity.CONFIDENTIAL);

    assertAll(
        () -> assertEquals("database.sql", descriptor.name()),
        () -> assertEquals(ToolEffect.WRITE, descriptor.effect()),
        () -> assertEquals(Reversibility.COMPENSATABLE, descriptor.reversibility()),
        () -> assertEquals(DataSensitivity.CONFIDENTIAL, descriptor.dataSensitivity()));
  }

  @Test
  void rejectsMissingStaticRiskMetadata() {
    assertAll(
        () ->
            assertThrows(
                NullPointerException.class,
                () ->
                    new ToolDescriptor(
                        "database.sql",
                        null,
                        Reversibility.REVERSIBLE,
                        DataSensitivity.INTERNAL)),
        () ->
            assertThrows(
                NullPointerException.class,
                () ->
                    new ToolDescriptor(
                        "database.sql", ToolEffect.WRITE, null, DataSensitivity.INTERNAL)),
        () ->
            assertThrows(
                NullPointerException.class,
                () ->
                    new ToolDescriptor(
                        "database.sql", ToolEffect.WRITE, Reversibility.REVERSIBLE, null)));
  }
}
