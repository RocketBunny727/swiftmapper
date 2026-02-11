package ru.nsu.swiftmapper.utils.naming;

import ru.nsu.swiftmapper.annotations.entity.Column;
import ru.nsu.swiftmapper.annotations.entity.Table;
import ru.nsu.swiftmapper.annotations.relationship.JoinColumn;
import ru.nsu.swiftmapper.annotations.relationship.JoinTable;
import ru.nsu.swiftmapper.utils.converters.CamelToSnakeConverter;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Collection;

public final class NamingStrategy {
    private NamingStrategy() {
        // Utility class, only for static use
    }

    public static String getTableName(Class<?> entityClass) {
        Table table = entityClass.getAnnotation(Table.class);
        if (table != null && !table.name().isEmpty()) {
            return table.name().toLowerCase();
        }

        String className = entityClass.getSimpleName();
        String plural = pluralize(className);
        return CamelToSnakeConverter.convert(plural);
    }

    public static String getJoinTableName(Class<?> ownerClass, Class<?> targetClass, JoinTable joinTable) {
        if (joinTable != null && !joinTable.name().isEmpty()) {
            return joinTable.name().toLowerCase();
        }

        String ownerTable = getTableName(ownerClass);
        String targetTable = getTableName(targetClass);

        return ownerTable.compareTo(targetTable) < 0
                ? ownerTable + "_" + targetTable
                : targetTable + "_" + ownerTable;
    }

    public static String getColumnName(Field field) {
        if (field.isAnnotationPresent(Column.class)) {
            String name = field.getAnnotation(Column.class).name();
            if (!name.isEmpty()) {
                return name.toLowerCase();
            }
        }

        return CamelToSnakeConverter.convert(field.getName());
    }

    public static String getIdColumnName(Field idField) {
        if (idField.isAnnotationPresent(Column.class)) {
            String name = idField.getAnnotation(Column.class).name();
            if (!name.isEmpty()) {
                return name.toLowerCase();
            }
        }

        return CamelToSnakeConverter.convert(idField.getName());
    }

    public static String getForeignKeyColumn(Field field) {
        if (field.isAnnotationPresent(JoinColumn.class)) {
            String name = field.getAnnotation(JoinColumn.class).name();
            if (!name.isEmpty()) {
                return name.toLowerCase();
            }
        }

        return CamelToSnakeConverter.convert(field.getName()) + "_id";
    }

    public static String getOneToOneFkColumn(Field field) {
        return getForeignKeyColumn(field);
    }

    public static String getOneToManyFkColumn(Class<?> ownerClass, String mappedBy) {
        String fieldName = (mappedBy == null || mappedBy.isEmpty())
                ? ownerClass.getSimpleName()
                : mappedBy;

        return CamelToSnakeConverter.convert(fieldName) + "_id";
    }

    public static String getJoinColumnName(JoinTable joinTable, Class<?> ownerClass, boolean isOwnerSide) {
        if (joinTable != null) {
            String name = isOwnerSide ? joinTable.joinColumn() : joinTable.inverseJoinColumn();
            if (!name.isEmpty()) {
                return name.toLowerCase();
            }
        }

        String className = ownerClass.getSimpleName();
        return CamelToSnakeConverter.convert(className) + "_id";
    }

    public static String getFkConstraintName(String tableName, String fkColumn) {
        return "fk_" + tableName + "_" + fkColumn;
    }

    public static String getPkConstraintName(String tableName) {
        return "pk_" + tableName;
    }

    public static String getUniqueConstraintName(String tableName, String columnName) {
        return "uk_" + tableName + "_" + columnName;
    }

    public static String getSequenceName(String tableName, String idColumn) {
        return (tableName + "_" + idColumn + "_seq").toLowerCase();
    }

    private static String pluralize(String word) {
        if (word == null || word.isEmpty()) {
            return word;
        }

        String lower = word.toLowerCase();

        if (lower.endsWith("s") || lower.endsWith("x") || lower.endsWith("z") ||
                lower.endsWith("ch") || lower.endsWith("sh")) {
            return word + "es";
        }

        if (lower.endsWith("y") && !isVowel(lower.charAt(lower.length() - 2))) {
            return word.substring(0, word.length() - 1) + "ies";
        }

        if (lower.endsWith("f")) {
            return word.substring(0, word.length() - 1) + "ves";
        }
        if (lower.endsWith("fe")) {
            return word.substring(0, word.length() - 2) + "ves";
        }

        return word + "s";
    }

    private static boolean isVowel(char c) {
        return "aeiou".indexOf(Character.toLowerCase(c)) != -1;
    }

    public static Class<?> getTargetClass(Field field) {
        Class<?> type = field.getType();

        if (Collection.class.isAssignableFrom(type)) {
            ParameterizedType pt = (ParameterizedType) field.getGenericType();
            return (Class<?>) pt.getActualTypeArguments()[0];
        }

        return type;
    }

    public static String normalizeColumnLabel(String label) {
        if (label == null) return null;
        return CamelToSnakeConverter.convert(label);
    }
}