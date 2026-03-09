package io.github.rocketbunny727.swiftmapper.utils.validation;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Column;
import io.github.rocketbunny727.swiftmapper.annotations.validation.*;
import io.github.rocketbunny727.swiftmapper.exception.ValidationException;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.regex.Pattern;

public class Validator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    public static void validate(Object entity) {
        if (entity == null) {
            throw new ValidationException("Entity cannot be null");
        }

        Class<?> clazz = entity.getClass();
        Field[] fields = getAllFields(clazz);

        for (Field field : fields) {
            field.setAccessible(true);

            try {
                Object value = field.get(entity);

                validateNotNull(field, value);
                validateNotEmpty(field, value);
                validateSize(field, value);
                validateMin(field, value);
                validateMax(field, value);
                validateEmail(field, value);
                validatePattern(field, value);
                validatePositive(field, value);
                validateFuture(field, value);
                validatePast(field, value);
                validateValid(field, value, entity);
                validateCheck(field, value, entity);

            } catch (IllegalAccessException e) {
                throw new ValidationException("Cannot access field: " + field.getName(), e);
            }
        }

        validateClassCheck(entity);
    }

    private static Field[] getAllFields(Class<?> clazz) {
        java.util.List<Field> fields = new java.util.ArrayList<>();
        while (clazz != null && clazz != Object.class) {
            fields.addAll(java.util.Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields.toArray(new Field[0]);
    }

    private static void validateNotNull(Field field, Object value) {
        NotNull annotation = field.getAnnotation(NotNull.class);
        if (annotation != null && value == null) {
            throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
        }
    }

    private static void validateNotEmpty(Field field, Object value) {
        NotEmpty annotation = field.getAnnotation(NotEmpty.class);
        if (annotation == null) return;

        if (value == null) return;

        if (value instanceof String str && str.trim().isEmpty()) {
            throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
        }

        if (value instanceof Collection<?> coll && coll.isEmpty()) {
            throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
        }

        if (value.getClass().isArray() && java.lang.reflect.Array.getLength(value) == 0) {
            throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
        }
    }

    private static void validateSize(Field field, Object value) {
        Size annotation = field.getAnnotation(Size.class);
        if (annotation == null || value == null) return;

        int min = annotation.min();
        int max = annotation.max();

        if (value instanceof String str) {
            int len = str.length();
            if (len < min || len > max) {
                throw new ValidationException(
                        annotation.message() + " (field: " + field.getName() + ", size: " + len + ", expected: " + min + "-" + max + ")"
                );
            }
        } else if (value instanceof Collection<?> coll) {
            int len = coll.size();
            if (len < min || len > max) {
                throw new ValidationException(
                        annotation.message() + " (field: " + field.getName() + ", size: " + len + ", expected: " + min + "-" + max + ")"
                );
            }
        } else if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            if (len < min || len > max) {
                throw new ValidationException(
                        annotation.message() + " (field: " + field.getName() + ", size: " + len + ", expected: " + min + "-" + max + ")"
                );
            }
        }
    }

    private static void validateMin(Field field, Object value) {
        Min annotation = field.getAnnotation(Min.class);
        if (annotation == null || value == null) return;

        long min = annotation.value();
        long actual;

        if (value instanceof Number num) {
            actual = num.longValue();
        } else {
            return;
        }

        if (actual < min) {
            throw new ValidationException(
                    annotation.message() + " (field: " + field.getName() + ", value: " + actual + ", min: " + min + ")"
            );
        }
    }

    private static void validateMax(Field field, Object value) {
        Max annotation = field.getAnnotation(Max.class);
        if (annotation == null || value == null) return;

        long max = annotation.value();
        long actual;

        if (value instanceof Number num) {
            actual = num.longValue();
        } else {
            return;
        }

        if (actual > max) {
            throw new ValidationException(
                    annotation.message() + " (field: " + field.getName() + ", value: " + actual + ", max: " + max + ")"
            );
        }
    }

    private static void validateEmail(Field field, Object value) {
        Email annotation = field.getAnnotation(Email.class);
        if (annotation == null || value == null) return;

        if (!(value instanceof String str)) return;

        if (!EMAIL_PATTERN.matcher(str).matches()) {
            throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
        }
    }

    private static void validatePattern(Field field, Object value) {
        io.github.rocketbunny727.swiftmapper.annotations.validation.Pattern annotation =
                field.getAnnotation(io.github.rocketbunny727.swiftmapper.annotations.validation.Pattern.class);
        if (annotation == null || value == null) return;

        if (!(value instanceof String str)) return;

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(annotation.regexp());
        if (!pattern.matcher(str).matches()) {
            throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
        }
    }

    private static void validatePositive(Field field, Object value) {
        Positive annotation = field.getAnnotation(Positive.class);
        if (annotation == null || value == null) return;

        if (value instanceof Number num) {
            if (num.doubleValue() <= 0) {
                throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
            }
        }
    }

    private static void validateFuture(Field field, Object value) {
        Future annotation = field.getAnnotation(Future.class);
        if (annotation == null || value == null) return;

        LocalDateTime now = LocalDateTime.now();

        if (value instanceof LocalDateTime dateTime) {
            if (!dateTime.isAfter(now)) {
                throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
            }
        } else if (value instanceof LocalDate date) {
            if (!date.isAfter(now.toLocalDate())) {
                throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
            }
        }
    }

    private static void validatePast(Field field, Object value) {
        Past annotation = field.getAnnotation(Past.class);
        if (annotation == null || value == null) return;

        LocalDateTime now = LocalDateTime.now();

        if (value instanceof LocalDateTime dateTime) {
            if (!dateTime.isBefore(now)) {
                throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
            }
        } else if (value instanceof LocalDate date) {
            if (!date.isBefore(now.toLocalDate())) {
                throw new ValidationException(annotation.message() + " (field: " + field.getName() + ")");
            }
        }
    }

    private static void validateValid(Field field, Object value, Object entity) throws IllegalAccessException {
        Valid annotation = field.getAnnotation(Valid.class);
        Column column = field.getAnnotation(Column.class);
        if (annotation == null) return;

        if (value == null) {
            if (column != null && !column.nullable()) {
                throw new ValidationException(field.getName() + " cannot be null");
            }
            return;
        }

        String strValue;
        if (value instanceof String) {
            strValue = (String) value;
        } else {
            strValue = value.toString();
        }

        int len = strValue.length();
        if (len < annotation.minLength()) {
            throw new ValidationException(field.getName() + " too short: min=" + annotation.minLength());
        }
        if (len > annotation.maxLength()) {
            throw new ValidationException(field.getName() + " too long: max=" + annotation.maxLength());
        }

        if (!annotation.regex().isEmpty()) {
            Pattern pattern = Pattern.compile(annotation.regex());
            if (!pattern.matcher(strValue).matches()) {
                throw new ValidationException(field.getName() + " invalid format: " + annotation.regex());
            }
        }
    }

    private static void validateCheck(Field field, Object value, Object entity) {
        Check check = field.getAnnotation(Check.class);
        if (check == null || value == null) return;

        String condition = check.value().toLowerCase();

        if (value instanceof Number num) {
            double val = num.doubleValue();
            if (condition.contains("> 0") && val <= 0) {
                throw new ValidationException(field.getName() + " must be > 0");
            }
            if (condition.contains("< 0") && val >= 0) {
                throw new ValidationException(field.getName() + " must be < 0");
            }
            if (condition.contains(">= 0") && val < 0) {
                throw new ValidationException(field.getName() + " must be >= 0");
            }
        }

        if (value instanceof String str) {
            if (condition.contains("not empty") && str.trim().isEmpty()) {
                throw new ValidationException(field.getName() + " must not be empty");
            }
        }
    }

    private static void validateClassCheck(Object entity) {
        if (entity == null) return;

        Class<?> clazz = entity.getClass();
        Check classCheck = clazz.getAnnotation(Check.class);
        if (classCheck == null) return;
    }
}