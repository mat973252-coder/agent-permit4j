package io.github.mat973252.agentpermit.springai;

import io.github.mat973252.agentpermit.core.DataSensitivity;
import io.github.mat973252.agentpermit.core.Reversibility;
import io.github.mat973252.agentpermit.core.RiskLevel;
import io.github.mat973252.agentpermit.core.ToolEffect;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentPermit {

  String resourceType();

  String resourceArg();

  ToolEffect effect();

  RiskLevel risk();

  Reversibility reversibility() default Reversibility.IRREVERSIBLE;

  DataSensitivity dataSensitivity() default DataSensitivity.RESTRICTED;

  String[] environments() default {};

  String[] allowedHosts() default {};

  String[] allowedMethods() default {};

  long maxPayloadBytes() default -1;
}
