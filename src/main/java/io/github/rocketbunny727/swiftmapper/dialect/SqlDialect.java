package io.github.rocketbunny727.swiftmapper.dialect;

import io.github.rocketbunny727.swiftmapper.annotations.entity.GeneratedValue;
import io.github.rocketbunny727.swiftmapper.annotations.entity.Strategy;

import java.util.Locale;
import java.util.Optional;

public enum SqlDialect {
    POSTGRESQL("\"", "\"") {
        @Override
        public String dropTableIfExists(String tableName) {
            return "DROP TABLE IF EXISTS " + quoteIdentifier(tableName) + " CASCADE";
        }

        @Override
        public String sequenceExistsSql() {
            return "SELECT 1 FROM pg_sequences WHERE schemaname = current_schema() AND sequencename = ?";
        }

        @Override
        public String createSequenceSql(String sequenceName, String tableName, String idColumn, long startValue) {
            return "CREATE SEQUENCE " + quoteIdentifier(sequenceName)
                    + " START WITH " + startValue
                    + " INCREMENT BY 1 NO MINVALUE NO MAXVALUE OWNED BY "
                    + quoteIdentifier(tableName) + "." + quoteIdentifier(idColumn);
        }

        @Override
        public String nextSequenceValueSql(String sequenceName) {
            return "SELECT nextval('" + escapeSqlLiteral(sequenceName) + "')";
        }

        @Override
        public String restartSequenceSql(String sequenceName, long startValue) {
            return "ALTER SEQUENCE " + quoteIdentifier(sequenceName) + " RESTART WITH " + startValue;
        }

        @Override
        public String binaryType() {
            return "BYTEA";
        }
    },

    MYSQL("`", "`") {
        @Override
        public String identityColumnType(Class<?> javaType) {
            return isLong(javaType) ? "BIGINT AUTO_INCREMENT" : "INTEGER AUTO_INCREMENT";
        }

        @Override
        public String defaultValuesInsert(String tableName) {
            return "INSERT INTO " + quoteIdentifier(tableName) + " () VALUES ()";
        }

        @Override
        public String doubleType() {
            return "DOUBLE";
        }

        @Override
        public String binaryType() {
            return "BLOB";
        }

        @Override
        public String sequenceExistsSql() {
            return unsupportedSequences();
        }
    },

    MARIADB("`", "`") {
        @Override
        public String identityColumnType(Class<?> javaType) {
            return MYSQL.identityColumnType(javaType);
        }

        @Override
        public String defaultValuesInsert(String tableName) {
            return MYSQL.defaultValuesInsert(tableName);
        }

        @Override
        public String doubleType() {
            return MYSQL.doubleType();
        }

        @Override
        public String binaryType() {
            return MYSQL.binaryType();
        }

        @Override
        public String sequenceExistsSql() {
            return unsupportedSequences();
        }
    },

    H2("\"", "\"") {
        @Override
        public String sequenceExistsSql() {
            return "SELECT 1 FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = ?";
        }

        @Override
        public String sequenceLookupValue(String sequenceName) {
            return sequenceName.toUpperCase(Locale.ROOT);
        }

        @Override
        public String createSequenceSql(String sequenceName, String tableName, String idColumn, long startValue) {
            return "CREATE SEQUENCE " + quoteIdentifier(sequenceName)
                    + " START WITH " + startValue + " INCREMENT BY 1";
        }

        @Override
        public String nextSequenceValueSql(String sequenceName) {
            return "SELECT NEXT VALUE FOR " + quoteIdentifier(sequenceName);
        }

        @Override
        public String restartSequenceSql(String sequenceName, long startValue) {
            return "ALTER SEQUENCE " + quoteIdentifier(sequenceName)
                    + " RESTART WITH " + startValue + " INCREMENT BY 1";
        }

        @Override
        public String binaryType() {
            return "BLOB";
        }
    },

    SQLITE("\"", "\"") {
        @Override
        public String idColumnDefinition(String columnName, Class<?> javaType, GeneratedValue generatedValue) {
            if (generatedValue != null && generatedValue.strategy() == Strategy.IDENTITY) {
                return quoteIdentifier(columnName) + " INTEGER PRIMARY KEY AUTOINCREMENT";
            }
            return super.idColumnDefinition(columnName, javaType, generatedValue);
        }

        @Override
        public String sqlType(Class<?> javaType) {
            if (isInteger(javaType) || isLong(javaType)) return "INTEGER";
            if (isDouble(javaType) || isFloat(javaType)) return "REAL";
            if (isBoolean(javaType)) return "INTEGER";
            if (isBinary(javaType)) return "BLOB";
            return "TEXT";
        }

        @Override
        public String addForeignKeySql(String table, String constraintName, String column,
                                       String referencedTable, String referencedColumn, boolean cascade) {
            return "";
        }

        @Override
        public String sequenceExistsSql() {
            return unsupportedSequences();
        }
    },

    SQLSERVER("[", "]") {
        @Override
        public String quoteIdentifier(String identifier) {
            validateIdentifier(identifier);
            if (identifier.contains(".")) {
                String[] parts = identifier.split("\\.");
                StringBuilder quoted = new StringBuilder();
                for (int i = 0; i < parts.length; i++) {
                    if (i > 0) quoted.append(".");
                    quoted.append("[").append(parts[i].replace("]", "]]")).append("]");
                }
                return quoted.toString();
            }
            return "[" + identifier.replace("]", "]]") + "]";
        }

        @Override
        public String dropTableIfExists(String tableName) {
            return "IF OBJECT_ID(N'" + escapeSqlLiteral(tableName) + "', N'U') IS NOT NULL DROP TABLE "
                    + quoteIdentifier(tableName);
        }

        @Override
        public String createTableIfNotExists(String tableName, String definition) {
            return "IF OBJECT_ID(N'" + escapeSqlLiteral(tableName) + "', N'U') IS NULL CREATE TABLE "
                    + quoteIdentifier(tableName) + " (" + definition + ")";
        }

        @Override
        public String identityColumnType(Class<?> javaType) {
            return (isLong(javaType) ? "BIGINT" : "INT") + " IDENTITY(1,1)";
        }

        @Override
        public String applyLimitOffset(String sql, Integer limit, Integer offset) {
            if (limit == null && offset == null) {
                return sql;
            }
            int safeOffset = offset == null ? 0 : offset;
            String result = containsOrderBy(sql) ? sql : sql + " ORDER BY (SELECT NULL)";
            result += " OFFSET " + safeOffset + " ROWS";
            if (limit != null) {
                result += " FETCH NEXT " + limit + " ROWS ONLY";
            }
            return result;
        }

        @Override
        public String forUpdateClause(boolean nowait) {
            return "";
        }

        @Override
        public String sequenceExistsSql() {
            return "SELECT 1 FROM sys.sequences WHERE name = ?";
        }

        @Override
        public String createSequenceSql(String sequenceName, String tableName, String idColumn, long startValue) {
            return "CREATE SEQUENCE " + quoteIdentifier(sequenceName)
                    + " START WITH " + startValue + " INCREMENT BY 1";
        }

        @Override
        public String nextSequenceValueSql(String sequenceName) {
            return "SELECT NEXT VALUE FOR " + quoteIdentifier(sequenceName);
        }

        @Override
        public String restartSequenceSql(String sequenceName, long startValue) {
            return "ALTER SEQUENCE " + quoteIdentifier(sequenceName) + " RESTART WITH " + startValue;
        }

        @Override
        public String stringType(int length) {
            return "NVARCHAR(" + length + ")";
        }

        @Override
        public String integerType() {
            return "INT";
        }

        @Override
        public String doubleType() {
            return "FLOAT";
        }

        @Override
        public String booleanType() {
            return "BIT";
        }

        @Override
        public String timestampType() {
            return "DATETIME2";
        }

        @Override
        public String binaryType() {
            return "VARBINARY(MAX)";
        }
    },

    ORACLE("\"", "\"") {
        @Override
        public String dropTableIfExists(String tableName) {
            String ddl = "DROP TABLE " + quoteIdentifier(tableName) + " CASCADE CONSTRAINTS";
            return oracleIgnoreErrorBlock(ddl, -942);
        }

        @Override
        public String createTableIfNotExists(String tableName, String definition) {
            String ddl = "CREATE TABLE " + quoteIdentifier(tableName) + " (" + definition + ")";
            return oracleIgnoreErrorBlock(ddl, -955);
        }

        @Override
        public String identityColumnType(Class<?> javaType) {
            return (isLong(javaType) ? "NUMBER(19)" : "NUMBER(10)") + " GENERATED BY DEFAULT AS IDENTITY";
        }

        @Override
        public String applyLimitOffset(String sql, Integer limit, Integer offset) {
            if (limit == null && offset == null) {
                return sql;
            }
            StringBuilder result = new StringBuilder(sql);
            if (offset != null) {
                result.append(" OFFSET ").append(offset).append(" ROWS");
            }
            if (limit != null) {
                result.append(offset == null ? " FETCH FIRST " : " FETCH NEXT ")
                        .append(limit).append(" ROWS ONLY");
            }
            return result.toString();
        }

        @Override
        public String sequenceExistsSql() {
            return "SELECT 1 FROM user_sequences WHERE sequence_name = ?";
        }

        @Override
        public String sequenceLookupValue(String sequenceName) {
            return sequenceName.toUpperCase(Locale.ROOT);
        }

        @Override
        public String createSequenceSql(String sequenceName, String tableName, String idColumn, long startValue) {
            return "CREATE SEQUENCE " + quoteIdentifier(sequenceName)
                    + " START WITH " + startValue + " INCREMENT BY 1";
        }

        @Override
        public String nextSequenceValueSql(String sequenceName) {
            return "SELECT " + quoteIdentifier(sequenceName) + ".NEXTVAL FROM dual";
        }

        @Override
        public String restartSequenceSql(String sequenceName, long startValue) {
            return "ALTER SEQUENCE " + quoteIdentifier(sequenceName) + " RESTART START WITH " + startValue;
        }

        @Override
        public String stringType(int length) {
            return "VARCHAR2(" + length + ")";
        }

        @Override
        public String longType() {
            return "NUMBER(19)";
        }

        @Override
        public String integerType() {
            return "NUMBER(10)";
        }

        @Override
        public String doubleType() {
            return "BINARY_DOUBLE";
        }

        @Override
        public String floatType() {
            return "BINARY_FLOAT";
        }

        @Override
        public String booleanType() {
            return "NUMBER(1)";
        }

        @Override
        public String binaryType() {
            return "BLOB";
        }

        @Override
        public String clobType() {
            return "CLOB";
        }
    },

    DB2("\"", "\"") {
        @Override
        public String applyLimitOffset(String sql, Integer limit, Integer offset) {
            if (limit == null && offset == null) {
                return sql;
            }
            StringBuilder result = new StringBuilder(sql);
            if (offset != null) {
                result.append(" OFFSET ").append(offset).append(" ROWS");
            }
            if (limit != null) {
                result.append(" FETCH NEXT ").append(limit).append(" ROWS ONLY");
            }
            return result.toString();
        }

        @Override
        public String sequenceExistsSql() {
            return "SELECT 1 FROM SYSCAT.SEQUENCES WHERE SEQNAME = ?";
        }

        @Override
        public String sequenceLookupValue(String sequenceName) {
            return sequenceName.toUpperCase(Locale.ROOT);
        }

        @Override
        public String createSequenceSql(String sequenceName, String tableName, String idColumn, long startValue) {
            return "CREATE SEQUENCE " + quoteIdentifier(sequenceName)
                    + " START WITH " + startValue + " INCREMENT BY 1";
        }

        @Override
        public String nextSequenceValueSql(String sequenceName) {
            return "VALUES NEXT VALUE FOR " + quoteIdentifier(sequenceName);
        }

        @Override
        public String restartSequenceSql(String sequenceName, long startValue) {
            return "ALTER SEQUENCE " + quoteIdentifier(sequenceName) + " RESTART WITH " + startValue;
        }

        @Override
        public String binaryType() {
            return "BLOB";
        }
    },

    GENERIC("\"", "\"");

    private final String quoteStart;
    private final String quoteEnd;

    SqlDialect(String quoteStart, String quoteEnd) {
        this.quoteStart = quoteStart;
        this.quoteEnd = quoteEnd;
    }

    public static SqlDialect resolve(String configuredDialect, String jdbcUrl,
                                     String driverClassName, String databaseProductName) {
        SqlDialect byDriver = resolveToken(driverClassName);
        if (byDriver != GENERIC) return byDriver;

        SqlDialect byConfig = resolveToken(configuredDialect);
        if (byConfig != GENERIC) return byConfig;

        SqlDialect byUrl = resolveToken(jdbcUrl);
        if (byUrl != GENERIC) return byUrl;

        SqlDialect byProduct = resolveToken(databaseProductName);
        if (byProduct != GENERIC) return byProduct;

        return GENERIC;
    }

    private static SqlDialect resolveToken(String value) {
        if (value == null || value.isBlank()) {
            return GENERIC;
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.contains("postgres")) return POSTGRESQL;
        if (normalized.contains("mariadb")) return MARIADB;
        if (normalized.contains("mysql")) return MYSQL;
        if (normalized.contains("h2")) return H2;
        if (normalized.contains("sqlite")) return SQLITE;
        if (normalized.contains("sqlserver") || normalized.contains("microsoft sql server")
                || normalized.contains("mssql")) return SQLSERVER;
        if (normalized.contains("oracle")) return ORACLE;
        if (normalized.contains("db2")) return DB2;
        return GENERIC;
    }

    public static Optional<String> inferDriverClassName(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            return Optional.empty();
        }

        String normalized = jdbcUrl.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("jdbc:postgresql:")) return Optional.of("org.postgresql.Driver");
        if (normalized.startsWith("jdbc:mysql:")) return Optional.of("com.mysql.cj.jdbc.Driver");
        if (normalized.startsWith("jdbc:mariadb:")) return Optional.of("org.mariadb.jdbc.Driver");
        if (normalized.startsWith("jdbc:h2:")) return Optional.of("org.h2.Driver");
        if (normalized.startsWith("jdbc:sqlite:")) return Optional.of("org.sqlite.JDBC");
        if (normalized.startsWith("jdbc:sqlserver:")) return Optional.of("com.microsoft.sqlserver.jdbc.SQLServerDriver");
        if (normalized.startsWith("jdbc:oracle:")) return Optional.of("oracle.jdbc.OracleDriver");
        if (normalized.startsWith("jdbc:db2:")) return Optional.of("com.ibm.db2.jcc.DB2Driver");
        return Optional.empty();
    }

    public String quoteIdentifier(String identifier) {
        validateIdentifier(identifier);
        if (identifier.contains(".")) {
            String[] parts = identifier.split("\\.");
            StringBuilder quoted = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) quoted.append(".");
                quoted.append(quoteSimpleIdentifier(parts[i]));
            }
            return quoted.toString();
        }
        return quoteSimpleIdentifier(identifier);
    }

    public String dropTableIfExists(String tableName) {
        return "DROP TABLE IF EXISTS " + quoteIdentifier(tableName);
    }

    public String createTableIfNotExists(String tableName, String definition) {
        return "CREATE TABLE IF NOT EXISTS " + quoteIdentifier(tableName) + " (" + definition + ")";
    }

    public String createIndexSql(boolean unique, String indexName, String tableName, String columnName) {
        return "CREATE " + (unique ? "UNIQUE " : "") + "INDEX " + quoteIdentifier(indexName)
                + " ON " + quoteIdentifier(tableName) + "(" + quoteIdentifier(columnName) + ")";
    }

    public String addForeignKeySql(String table, String constraintName, String column,
                                   String referencedTable, String referencedColumn, boolean cascade) {
        return "ALTER TABLE " + quoteIdentifier(table)
                + " ADD CONSTRAINT " + quoteIdentifier(constraintName)
                + " FOREIGN KEY (" + quoteIdentifier(column) + ")"
                + " REFERENCES " + quoteIdentifier(referencedTable)
                + "(" + quoteIdentifier(referencedColumn) + ")"
                + (cascade ? " ON DELETE CASCADE" : "");
    }

    public String defaultValuesInsert(String tableName) {
        return "INSERT INTO " + quoteIdentifier(tableName) + " DEFAULT VALUES";
    }

    public String idColumnDefinition(String columnName, Class<?> javaType, GeneratedValue generatedValue) {
        if (generatedValue != null && generatedValue.strategy() == Strategy.PATTERN) {
            return quoteIdentifier(columnName) + " " + stringType(100) + " PRIMARY KEY";
        }
        if (generatedValue != null && generatedValue.strategy() == Strategy.IDENTITY) {
            return quoteIdentifier(columnName) + " " + identityColumnType(javaType) + " PRIMARY KEY";
        }
        if (generatedValue != null) {
            return quoteIdentifier(columnName) + " " + longType() + " PRIMARY KEY";
        }
        return quoteIdentifier(columnName) + " " + sqlType(javaType) + " PRIMARY KEY";
    }

    public String identityColumnType(Class<?> javaType) {
        return isLong(javaType)
                ? longType() + " GENERATED BY DEFAULT AS IDENTITY"
                : integerType() + " GENERATED BY DEFAULT AS IDENTITY";
    }

    public String sqlType(Class<?> javaType) {
        if (isLong(javaType)) return longType();
        if (isInteger(javaType)) return integerType();
        if (isDouble(javaType)) return doubleType();
        if (isFloat(javaType)) return floatType();
        if (isBoolean(javaType)) return booleanType();
        if (isBinary(javaType)) return binaryType();

        String simpleName = javaType.getSimpleName();
        if ("LocalDateTime".equals(simpleName)) return timestampType();
        if ("LocalDate".equals(simpleName)) return dateType();
        if ("LocalTime".equals(simpleName)) return timeType();

        return stringType(255);
    }

    public String stringType(int length) {
        return "VARCHAR(" + length + ")";
    }

    public String longType() {
        return "BIGINT";
    }

    public String integerType() {
        return "INTEGER";
    }

    public String doubleType() {
        return "DOUBLE PRECISION";
    }

    public String floatType() {
        return "REAL";
    }

    public String booleanType() {
        return "BOOLEAN";
    }

    public String timestampType() {
        return "TIMESTAMP";
    }

    public String dateType() {
        return "DATE";
    }

    public String timeType() {
        return "TIME";
    }

    public String binaryType() {
        return "BLOB";
    }

    public String clobType() {
        return "CLOB";
    }

    public String applyLimitOffset(String sql, Integer limit, Integer offset) {
        StringBuilder result = new StringBuilder(sql);
        if (limit != null) {
            result.append(" LIMIT ").append(limit);
        }
        if (offset != null) {
            result.append(" OFFSET ").append(offset);
        }
        return result.toString();
    }

    public String forUpdateClause(boolean nowait) {
        return nowait ? " FOR UPDATE NOWAIT" : " FOR UPDATE";
    }

    public int inClauseLimit() {
        return switch (this) {
            case ORACLE -> 1000;
            case SQLITE -> 999;
            case SQLSERVER -> 2000;
            default -> 1000;
        };
    }

    public String booleanLiteral(boolean value) {
        return switch (this) {
            case ORACLE, SQLSERVER, DB2, SQLITE -> value ? "1" : "0";
            default -> value ? "TRUE" : "FALSE";
        };
    }

    public String regexCondition(String columnExpression) {
        if (this == POSTGRESQL) {
            return columnExpression + " ~ ?";
        }
        throw new UnsupportedOperationException("Regex query methods are supported only by PostgreSQL dialect");
    }

    public String sequenceExistsSql() {
        return "SELECT 1 FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_NAME = ?";
    }

    public String sequenceLookupValue(String sequenceName) {
        return sequenceName;
    }

    public String createSequenceSql(String sequenceName, String tableName, String idColumn, long startValue) {
        return "CREATE SEQUENCE " + quoteIdentifier(sequenceName)
                + " START WITH " + startValue + " INCREMENT BY 1";
    }

    public String nextSequenceValueSql(String sequenceName) {
        return "SELECT NEXT VALUE FOR " + quoteIdentifier(sequenceName);
    }

    public String restartSequenceSql(String sequenceName, long startValue) {
        return "ALTER SEQUENCE " + quoteIdentifier(sequenceName) + " RESTART WITH " + startValue;
    }

    private String quoteSimpleIdentifier(String identifier) {
        return quoteStart + identifier.replace(quoteEnd, quoteEnd + quoteEnd) + quoteEnd;
    }

    protected static void validateIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("SQL identifier cannot be null or empty");
        }
        if (identifier.contains(";") || identifier.contains("--")
                || identifier.contains("/*") || identifier.contains("*/")) {
            throw new SecurityException("Potentially dangerous SQL identifier: " + identifier);
        }
    }

    protected static boolean isLong(Class<?> type) {
        return type == Long.class || type == long.class;
    }

    protected static boolean isInteger(Class<?> type) {
        return type == Integer.class || type == int.class;
    }

    protected static boolean isDouble(Class<?> type) {
        return type == Double.class || type == double.class;
    }

    protected static boolean isFloat(Class<?> type) {
        return type == Float.class || type == float.class;
    }

    protected static boolean isBoolean(Class<?> type) {
        return type == Boolean.class || type == boolean.class;
    }

    protected static boolean isBinary(Class<?> type) {
        return type == byte[].class || type == Byte[].class;
    }

    protected static String escapeSqlLiteral(String value) {
        return value.replace("'", "''");
    }

    protected static boolean containsOrderBy(String sql) {
        return sql.toLowerCase(Locale.ROOT).contains(" order by ");
    }

    protected static String oracleIgnoreErrorBlock(String ddl, int ignoredCode) {
        return "BEGIN EXECUTE IMMEDIATE '" + escapeSqlLiteral(ddl) + "'; "
                + "EXCEPTION WHEN OTHERS THEN IF SQLCODE != " + ignoredCode
                + " THEN RAISE; END IF; END;";
    }

    protected static String unsupportedSequences() {
        throw new UnsupportedOperationException("This SQL dialect does not support standalone sequences");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
