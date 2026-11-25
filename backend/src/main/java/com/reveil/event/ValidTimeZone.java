package com.reveil.event;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.DateTimeException;
import java.time.ZoneId;

/** Identifiant de fuseau reconnu par java.time (IANA « Europe/Paris », « UTC », « +02:00 »…). Null/vide : ignoré. */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidTimeZone.Validator.class)
public @interface ValidTimeZone {

    String message() default "Le fuseau horaire est inconnu";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidTimeZone, String> {
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null || value.isBlank()) {
                return true;
            }
            try {
                ZoneId.of(value.trim());
                return true;
            } catch (DateTimeException e) {
                return false;
            }
        }
    }
}
