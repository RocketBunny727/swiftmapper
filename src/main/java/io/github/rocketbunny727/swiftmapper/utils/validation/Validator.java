package io.github.rocketbunny727.swiftmapper.utils.validation;

import io.github.rocketbunny727.swiftmapper.annotations.entity.Column;
import io.github.rocketbunny727.swiftmapper.annotations.validation.*;
import io.github.rocketbunny727.swiftmapper.exception.ValidationException;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
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

                validateNull(field, value);
                validateNotNull(field, value);
                validateNotBlank(field, value);
                validateNotEmpty(field, value);

                validateMin(field, value);
                validateMax(field, value);
                validateDecimalMin(field, value);
                validateDecimalMax(field, value);
                validateDigits(field, value);
                validatePositive(field, value);
                validatePositiveOrZero(field, value);
                validateNegative(field, value);
                validateNegativeOrZero(field, value);
                validateRange(field, value);

                validateSize(field, value);
                validateEmail(field, value);
                validatePattern(field, value);

                validateFuture(field, value);
                validateFutureOrPresent(field, value);
                validatePast(field, value);
                validatePastOrPresent(field, value);

                validateValid(field, value);
                validateCheck(field, value);

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

    private static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof BigInteger bi) return new BigDecimal(bi);
        if (value instanceof Number n)      return BigDecimal.valueOf(n.doubleValue());
        if (value instanceof String s) {
            try { return new BigDecimal(s); } catch (NumberFormatException ignored) {}
        }
        throw new ValidationException("Cannot convert to BigDecimal: " + value);
    }

    private static int collectionOrStringSize(Object value) {
        if (value instanceof String s)        return s.length();
        if (value instanceof Collection<?> c) return c.size();
        if (value != null && value.getClass().isArray())
            return java.lang.reflect.Array.getLength(value);
        return -1;
    }

    private static String fieldLabel(Field field) {
        return " (field: " + field.getName() + ")";
    }

    private static void validateNull(Field field, Object value) {
        Null annotation = field.getAnnotation(Null.class);
        if (annotation != null && value != null) {
            throw new ValidationException(annotation.message() + fieldLabel(field));
        }
    }

    private static void validateNotNull(Field field, Object value) {
        NotNull annotation = field.getAnnotation(NotNull.class);
        if (annotation != null && value == null) {
            throw new ValidationException(annotation.message() + fieldLabel(field));
        }
    }

    private static void validateNotBlank(Field field, Object value) {
        NotBlank annotation = field.getAnnotation(NotBlank.class);
        if (annotation == null) return;
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new ValidationException(annotation.message() + fieldLabel(field));
        }
    }

    private static void validateNotEmpty(Field field, Object value) {
        NotEmpty annotation = field.getAnnotation(NotEmpty.class);
        if (annotation == null) return;
        if (value == null) return;
        int size = collectionOrStringSize(value);
        if (size == 0) {
            throw new ValidationException(annotation.message() + fieldLabel(field));
        }
    }

    private static void validateSize(Field field, Object value) {
        Size annotation = field.getAnnotation(Size.class);
        if (annotation == null || value == null) return;
        int size = collectionOrStringSize(value);
        if (size < 0) return;
        if (size < annotation.min() || size > annotation.max()) {
            throw new ValidationException(
                    annotation.message()
                            .replace("{min}", String.valueOf(annotation.min()))
                            .replace("{max}", String.valueOf(annotation.max()))
                            + fieldLabel(field) + ", actual: " + size);
        }
    }

    private static void validateEmail(Field field, Object value) {
        Email annotation = field.getAnnotation(Email.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof String str)) return;
        if (!EMAIL_PATTERN.matcher(str).matches()) {
            throw new ValidationException(annotation.message() + fieldLabel(field));
        }
    }

    private static void validatePattern(Field field, Object value) {
        io.github.rocketbunny727.swiftmapper.annotations.validation.Pattern annotation =
                field.getAnnotation(io.github.rocketbunny727.swiftmapper.annotations.validation.Pattern.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof String str)) return;
        if (!Pattern.compile(annotation.regexp()).matcher(str).matches()) {
            throw new ValidationException(annotation.message() + fieldLabel(field));
        }
    }

    private static void validateMin(Field field, Object value) {
        Min annotation = field.getAnnotation(Min.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof Number)) return;
        long actual = ((Number) value).longValue();
        if (actual < annotation.value()) {
            throw new ValidationException(
                    annotation.message().replace("{value}", String.valueOf(annotation.value()))
                            + fieldLabel(field) + ", actual: " + actual);
        }
    }

    private static void validateMax(Field field, Object value) {
        Max annotation = field.getAnnotation(Max.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof Number)) return;
        long actual = ((Number) value).longValue();
        if (actual > annotation.value()) {
            throw new ValidationException(
                    annotation.message().replace("{value}", String.valueOf(annotation.value()))
                            + fieldLabel(field) + ", actual: " + actual);
        }
    }

    private static void validateRange(Field field, Object value) {
        Range annotation = field.getAnnotation(Range.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof Number)) return;
        long actual = ((Number) value).longValue();
        if (actual < annotation.min() || actual > annotation.max()) {
            throw new ValidationException(
                    annotation.message()
                            .replace("{min}", String.valueOf(annotation.min()))
                            .replace("{max}", String.valueOf(annotation.max()))
                            + fieldLabel(field) + ", actual: " + actual);
        }
    }

    private static void validateDecimalMin(Field field, Object value) {
        DecimalMin annotation = field.getAnnotation(DecimalMin.class);
        if (annotation == null || value == null) return;
        BigDecimal threshold = new BigDecimal(annotation.value());
        BigDecimal actual    = toBigDecimal(value);
        int cmp = actual.compareTo(threshold);
        boolean ok = annotation.inclusive() ? cmp >= 0 : cmp > 0;
        if (!ok) {
            throw new ValidationException(
                    annotation.message().replace("{value}", annotation.value())
                            + (annotation.inclusive() ? " (inclusive)" : " (exclusive)")
                            + fieldLabel(field) + ", actual: " + actual);
        }
    }

    private static void validateDecimalMax(Field field, Object value) {
        DecimalMax annotation = field.getAnnotation(DecimalMax.class);
        if (annotation == null || value == null) return;
        BigDecimal threshold = new BigDecimal(annotation.value());
        BigDecimal actual    = toBigDecimal(value);
        int cmp = actual.compareTo(threshold);
        boolean ok = annotation.inclusive() ? cmp <= 0 : cmp < 0;
        if (!ok) {
            throw new ValidationException(
                    annotation.message().replace("{value}", annotation.value())
                            + (annotation.inclusive() ? " (inclusive)" : " (exclusive)")
                            + fieldLabel(field) + ", actual: " + actual);
        }
    }

    private static void validateDigits(Field field, Object value) {
        Digits annotation = field.getAnnotation(Digits.class);
        if (annotation == null || value == null) return;
        BigDecimal bd    = toBigDecimal(value).stripTrailingZeros();
        int scale        = Math.max(bd.scale(), 0);
        int intDigits    = Math.max(bd.precision() - bd.scale(), 0);
        int fracDigits   = scale;
        if (intDigits > annotation.integer() || fracDigits > annotation.fraction()) {
            throw new ValidationException(
                    annotation.message()
                            .replace("{integer}",  String.valueOf(annotation.integer()))
                            .replace("{fraction}", String.valueOf(annotation.fraction()))
                            + fieldLabel(field)
                            + ", actual integer: " + intDigits + ", fraction: " + fracDigits);
        }
    }

    private static void validatePositive(Field field, Object value) {
        Positive annotation = field.getAnnotation(Positive.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof Number)) return;
        if (((Number) value).doubleValue() <= 0)
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static void validatePositiveOrZero(Field field, Object value) {
        PositiveOrZero annotation = field.getAnnotation(PositiveOrZero.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof Number)) return;
        if (((Number) value).doubleValue() < 0)
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static void validateNegative(Field field, Object value) {
        Negative annotation = field.getAnnotation(Negative.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof Number)) return;
        if (((Number) value).doubleValue() >= 0)
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static void validateNegativeOrZero(Field field, Object value) {
        NegativeOrZero annotation = field.getAnnotation(NegativeOrZero.class);
        if (annotation == null || value == null) return;
        if (!(value instanceof Number)) return;
        if (((Number) value).doubleValue() > 0)
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static boolean isAfterNow(Object value, boolean orEqual) {
        LocalDateTime now = LocalDateTime.now();
        if (value instanceof LocalDateTime ldt)  return orEqual ? !ldt.isBefore(now)  : ldt.isAfter(now);
        if (value instanceof LocalDate ld)        return orEqual ? !ld.isBefore(now.toLocalDate())  : ld.isAfter(now.toLocalDate());
        if (value instanceof LocalTime lt)        return orEqual ? !lt.isBefore(now.toLocalTime())  : lt.isAfter(now.toLocalTime());
        if (value instanceof OffsetDateTime odt)  return isAfterNow(odt.toLocalDateTime(), orEqual);
        if (value instanceof ZonedDateTime zdt)   return isAfterNow(zdt.toLocalDateTime(), orEqual);
        return true;
    }

    private static boolean isBeforeNow(Object value, boolean orEqual) {
        LocalDateTime now = LocalDateTime.now();
        if (value instanceof LocalDateTime ldt)  return orEqual ? !ldt.isAfter(now)  : ldt.isBefore(now);
        if (value instanceof LocalDate ld)        return orEqual ? !ld.isAfter(now.toLocalDate())  : ld.isBefore(now.toLocalDate());
        if (value instanceof LocalTime lt)        return orEqual ? !lt.isAfter(now.toLocalTime())  : lt.isBefore(now.toLocalTime());
        if (value instanceof OffsetDateTime odt)  return isBeforeNow(odt.toLocalDateTime(), orEqual);
        if (value instanceof ZonedDateTime zdt)   return isBeforeNow(zdt.toLocalDateTime(), orEqual);
        return true;
    }

    private static void validateFuture(Field field, Object value) {
        Future annotation = field.getAnnotation(Future.class);
        if (annotation == null || value == null) return;
        if (!isAfterNow(value, false))
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static void validateFutureOrPresent(Field field, Object value) {
        FutureOrPresent annotation = field.getAnnotation(FutureOrPresent.class);
        if (annotation == null || value == null) return;
        if (!isAfterNow(value, true))
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static void validatePast(Field field, Object value) {
        Past annotation = field.getAnnotation(Past.class);
        if (annotation == null || value == null) return;
        if (!isBeforeNow(value, false))
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static void validatePastOrPresent(Field field, Object value) {
        PastOrPresent annotation = field.getAnnotation(PastOrPresent.class);
        if (annotation == null || value == null) return;
        if (!isBeforeNow(value, true))
            throw new ValidationException(annotation.message() + fieldLabel(field));
    }

    private static void validateValid(Field field, Object value) {
        Valid annotation = field.getAnnotation(Valid.class);
        Column column    = field.getAnnotation(Column.class);
        if (annotation == null) return;
        if (value == null) {
            if (column != null && !column.nullable())
                throw new ValidationException(field.getName() + " cannot be null");
            return;
        }
        String str = value instanceof String s ? s : value.toString();
        int len = str.length();
        if (len < annotation.minLength())
            throw new ValidationException(field.getName() + " too short: min=" + annotation.minLength());
        if (len > annotation.maxLength())
            throw new ValidationException(field.getName() + " too long: max=" + annotation.maxLength());
        if (!annotation.regex().isEmpty() && !Pattern.compile(annotation.regex()).matcher(str).matches())
            throw new ValidationException(field.getName() + " invalid format: " + annotation.regex());
    }

    private static void validateCheck(Field field, Object value) {
        Check check = field.getAnnotation(Check.class);
        if (check == null || value == null) return;
        String condition = check.value().toLowerCase();
        if (value instanceof Number num) {
            double val = num.doubleValue();
            if (condition.contains("> 0")  && val <= 0) throw new ValidationException(field.getName() + " must be > 0");
            if (condition.contains("< 0")  && val >= 0) throw new ValidationException(field.getName() + " must be < 0");
            if (condition.contains(">= 0") && val < 0)  throw new ValidationException(field.getName() + " must be >= 0");
            if (condition.contains("<= 0") && val > 0)  throw new ValidationException(field.getName() + " must be <= 0");
        }
        if (value instanceof String str && condition.contains("not empty") && str.trim().isEmpty())
            throw new ValidationException(field.getName() + " must not be empty");
    }

    private static void validateClassCheck(Object entity) {
    }
}