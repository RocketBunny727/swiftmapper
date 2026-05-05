package io.github.rocketbunny727.swiftmapper.dialect;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class SqlRenderer {
    private static final String SIMPLE_IDENTIFIER =
            "[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*";

    private final SqlDialect dialect;

    public SqlRenderer(SqlDialect dialect) {
        this.dialect = dialect != null ? dialect : SqlDialect.GENERIC;
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public int inClauseLimit() {
        return dialect.inClauseLimit();
    }

    public String identifier(String identifier) {
        return dialect.quoteIdentifier(identifier);
    }

    public String table(String tableName) {
        return renderReference(tableName);
    }

    public String column(String columnName) {
        return renderReference(columnName);
    }

    public String qualify(String alias, String columnName) {
        return alias + "." + column(columnName);
    }

    public String selectAll(String tableName) {
        return "SELECT * FROM " + table(tableName);
    }

    public String selectById(String tableName, String idColumn) {
        return selectAll(tableName) + " WHERE " + column(idColumn) + " = ?";
    }

    public String selectAllWhereEquals(String tableName, String columnName) {
        return selectAll(tableName) + " WHERE " + column(columnName) + " = ?";
    }

    public String selectColumnWhereEquals(String tableName, String selectedColumn, String whereColumn) {
        return "SELECT " + column(selectedColumn)
                + " FROM " + table(tableName)
                + " WHERE " + column(whereColumn) + " = ?";
    }

    public String selectColumnsWhereEquals(String tableName, Collection<String> selectedColumns, String whereColumn) {
        return "SELECT " + columnList(selectedColumns)
                + " FROM " + table(tableName)
                + " WHERE " + column(whereColumn) + " = ?";
    }

    public String selectAllWhereIn(String tableName, String columnName, int count) {
        return selectAll(tableName)
                + " WHERE " + column(columnName)
                + " IN (" + placeholders(count) + ")";
    }

    public String selectColumnsWhereIn(String tableName, Collection<String> selectedColumns,
                                       String whereColumn, int count) {
        return "SELECT " + columnList(selectedColumns)
                + " FROM " + table(tableName)
                + " WHERE " + column(whereColumn)
                + " IN (" + placeholders(count) + ")";
    }

    public String selectManyToMany(String targetTable, String joinTable, String targetIdColumn,
                                   String ownerJoinColumn, String inverseJoinColumn,
                                   String ownerAlias, int count) {
        return "SELECT t.*, j." + column(inverseSafe(ownerJoinColumn)) + " AS " + identifier(ownerAlias)
                + " FROM " + table(targetTable) + " t "
                + "JOIN " + table(joinTable) + " j ON "
                + qualify("t", targetIdColumn) + " = j." + column(inverseSafe(inverseJoinColumn))
                + " WHERE j." + column(inverseSafe(ownerJoinColumn))
                + " IN (" + placeholders(count) + ")";
    }

    public String selectManyToManyForOwner(String targetTable, String joinTable, String targetIdColumn,
                                           String ownerJoinColumn, String targetJoinColumn) {
        return "SELECT t.* FROM " + table(targetTable) + " t "
                + "JOIN " + table(joinTable) + " j ON "
                + qualify("t", targetIdColumn) + " = " + qualify("j", targetJoinColumn)
                + " WHERE " + qualify("j", ownerJoinColumn) + " = ?";
    }

    public String insert(String tableName, Collection<String> columnNames) {
        if (columnNames == null || columnNames.isEmpty()) {
            return dialect.defaultValuesInsert(tableName);
        }
        return "INSERT INTO " + table(tableName)
                + " (" + columnList(columnNames) + ") VALUES ("
                + placeholders(columnNames.size()) + ")";
    }

    public String updateById(String tableName, Collection<String> columnNames, String idColumn) {
        if (columnNames == null || columnNames.isEmpty()) {
            throw new IllegalArgumentException("At least one column is required for UPDATE");
        }
        return "UPDATE " + table(tableName)
                + " SET " + columnNames.stream()
                .map(col -> column(col) + " = ?")
                .collect(Collectors.joining(", "))
                + " WHERE " + column(idColumn) + " = ?";
    }

    public String deleteWhere(String tableName, String columnName) {
        return "DELETE FROM " + table(tableName) + " WHERE " + column(columnName) + " = ?";
    }

    public String orderBy(String columnName, String direction) {
        return column(columnName) + " " + direction;
    }

    public String renderReference(String reference) {
        Objects.requireNonNull(reference, "SQL reference cannot be null");
        String trimmed = reference.trim();
        if (trimmed.equals("*") || trimmed.endsWith(".*") || isAlreadyQuoted(trimmed)) {
            return trimmed;
        }
        if (trimmed.matches(SIMPLE_IDENTIFIER)) {
            return dialect.quoteIdentifier(trimmed);
        }
        return trimmed;
    }

    public String columnList(Collection<String> columns) {
        return columns.stream()
                .map(this::column)
                .collect(Collectors.joining(", "));
    }

    public String placeholders(int count) {
        if (count < 1) {
            throw new IllegalArgumentException("Placeholder count must be positive");
        }
        return String.join(",", Collections.nCopies(count, "?"));
    }

    public List<List<Object>> partitionInValues(List<Object> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        int chunkSize = inClauseLimit();
        return java.util.stream.IntStream.iterate(0, i -> i < values.size(), i -> i + chunkSize)
                .mapToObj(i -> values.subList(i, Math.min(i + chunkSize, values.size())))
                .toList();
    }

    private boolean isAlreadyQuoted(String reference) {
        return (reference.startsWith("\"") && reference.endsWith("\""))
                || (reference.startsWith("`") && reference.endsWith("`"))
                || (reference.startsWith("[") && reference.endsWith("]"));
    }

    private String inverseSafe(String columnName) {
        return columnName;
    }
}
