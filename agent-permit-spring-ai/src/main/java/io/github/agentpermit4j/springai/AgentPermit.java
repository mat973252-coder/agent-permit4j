package io.github.agentpermit4j.springai;

import io.github.agentpermit4j.core.DataSensitivity;
import io.github.agentpermit4j.core.Reversibility;
import io.github.agentpermit4j.core.RiskLevel;
import io.github.agentpermit4j.core.ToolEffect;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares fixed tool limits with conservative defaults for a high-risk HTTP write. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentPermit {

  String resourceType() default "http";

  String resourceArg() default "uri";

  ToolEffect effect() default ToolEffect.WRITE;

  RiskLevel risk() default RiskLevel.HIGH;

  Reversibility reversibility() default Reversibility.IRREVERSIBLE;

  DataSensitivity dataSensitivity() default DataSensitivity.RESTRICTED;

  String[] environments() default {};

  String[] hosts() default {};

  String[] methods() default {};

  long maxBytes() default -1;

  String errorCode() default "";

  String errorMessage() default "";
}
