package com.cowork.roadmap.global.validation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;

/**
 * http, https 스킴의 URL만 허용한다. 화면이 값을 링크로 그대로 쓰므로 javascript: 같은 스킴을 막는다. null과
 * 공백은 허용해 값을 비우는 요청을 그대로 처리한다.
 */
@Documented
@Constraint(validatedBy = {})
@Pattern(regexp = "\\s*|(?i)https?://[^\\s/?#]+\\S*")
@ReportAsSingleViolation
@Target({FIELD, PARAMETER})
@Retention(RUNTIME)
public @interface HttpUrl {

    String message() default "링크는 http 또는 https URL이어야 합니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
