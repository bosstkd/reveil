package com.reveil.event;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.HashSet;
import java.util.List;

/**
 * Liste de délais de rappel valide : au plus {@link ReminderOptions#MAX}, tous parmi {@link ReminderOptions#ALLOWED},
 * sans doublon. Null accepté (délai par défaut à la création, configuration inchangée en modification).
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ValidReminders.Validator.class)
public @interface ValidReminders {

    String message() default "Choisissez au plus 3 rappels différents parmi : 15 min, 1 h, 1 jour, 1 semaine";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<ValidReminders, List<Integer>> {
        @Override
        public boolean isValid(List<Integer> value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            if (value.size() > ReminderOptions.MAX
                    || value.stream().anyMatch(v -> v == null || !ReminderOptions.ALLOWED.contains(v))) {
                return false;
            }
            return new HashSet<>(value).size() == value.size();
        }
    }
}
