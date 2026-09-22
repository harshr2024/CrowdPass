package com.crowdpass.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.charset.StandardCharsets;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

@Target({ ElementType.FIELD, ElementType.PARAMETER })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = MaxUtf8Bytes.Validator.class)
@interface MaxUtf8Bytes {

	int value();

	String message() default "must be at most {value} bytes";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};

	class Validator implements ConstraintValidator<MaxUtf8Bytes, String> {

		private int maxBytes;

		@Override
		public void initialize(MaxUtf8Bytes annotation) {
			this.maxBytes = annotation.value();
		}

		@Override
		public boolean isValid(String value, ConstraintValidatorContext context) {
			return value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
		}

	}

}
