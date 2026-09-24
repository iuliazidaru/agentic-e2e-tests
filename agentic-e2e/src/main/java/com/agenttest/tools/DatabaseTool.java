package com.agenttest.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC-backed database tool.
 *
 * Supported actions:
 *
 *   query          — run a SELECT and return rows as JSON
 *   assertRowExists — fail if no row matches a query
 *   assertCount    — fail if row count != expected
 *   execute        — run a non-SELECT statement (INSERT/UPDATE/DELETE)
 *
 * Connection is created once and reused.  Call close() in @AfterAll.
 *
 * Add your JDBC driver to pom.xml (see commented sections).
 * Example URLs:
 *   PostgreSQL: jdbc:postgresql://localhost:5432/mydb
 *   MSSQL:      jdbc:sqlserver://localhost:1433;databaseName=mydb;encrypt=false
 *   Oracle:     jdbc:oracle:thin:@localhost:1521:orcl
 */
public class DatabaseTool implements AgentTool, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTool.class);

    private final Connection   connection;
    private final ObjectMapper mapper = new ObjectMapper();

    public DatabaseTool(String jdbcUrl, String username, String password) throws SQLException {
        this.connection = DriverManager.getConnection(jdbcUrl, username, password);
        log.info("DB connected: {}", jdbcUrl);
    }

    /** Convenience constructor from environment variables (or system properties loaded from .env). */
    public static DatabaseTool fromEnv() throws SQLException {
        System.out.println( envOrProp("DB_JDBC_URL"));
        System.out.println( envOrProp("DB_USERNAME"));
        System.out.println( envOrProp("DB_PASSWORD"));
        return new DatabaseTool(
                envOrProp("DB_JDBC_URL"),
                envOrProp("DB_USERNAME"),
                envOrProp("DB_PASSWORD")
        );
    }

    private static String envOrProp(String key) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : System.getProperty(key);
    }

    @Override public String name()        { return "database"; }
    @Override public String description() {
        return "Runs SQL queries and assertions against the database. " +
               "Actions: query, assertRowExists, assertCount, execute.";
    }

    @Override
    public ObjectNode parametersSchema() {
        ObjectNode schema = mapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        props.putObject("action").put("type", "string")
             .put("description", "query | assertRowExists | assertCount | execute");
        props.putObject("sql").put("type", "string")
             .put("description", "SQL statement to run");
        props.putObject("expectedCount").put("type", "integer")
             .put("description", "Expected row count for assertCount");
        props.putObject("maxRows").put("type", "integer")
             .put("description", "Max rows to return for query (default 50)");

        schema.putArray("required").add("action").add("sql");
        return schema;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String action = args.path("action").asText();
        String sql    = args.path("sql").asText();

        return switch (action) {
            case "query"           -> query(sql, args.path("maxRows").asInt(50));
            case "assertRowExists" -> assertRowExists(sql);
            case "assertCount"     -> assertCount(sql, args.path("expectedCount").asInt(1));
            case "execute"         -> executeSql(sql);
            default -> "ERROR: Unknown database action '" + action + "'";
        };
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private String query(String sql, int maxRows) throws SQLException, Exception {
        log.info("DB query (maxRows={}): {}", maxRows, sql);

        try (Statement stmt = connection.createStatement()) {
            stmt.setMaxRows(maxRows);
            ResultSet rs = stmt.executeQuery(sql);
            ResultSetMetaData meta = rs.getMetaData();
            int cols = meta.getColumnCount();

            ArrayNode rows = mapper.createArrayNode();
            while (rs.next()) {
                ObjectNode row = mapper.createObjectNode();
                for (int i = 1; i <= cols; i++) {
                    String colName = meta.getColumnLabel(i);
                    String value   = rs.getString(i);
                    row.put(colName, value != null ? value : "NULL");
                }
                rows.add(row);
            }

            if (rows.isEmpty()) {
                return "Query returned 0 rows.";
            }
            return "Rows returned (" + rows.size() + "):\n" + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(rows);
        }
    }

    private String assertRowExists(String sql) throws SQLException {
        log.info("DB assertRowExists: {}", sql);

        try (Statement stmt = connection.createStatement()) {
            stmt.setMaxRows(1);
            ResultSet rs = stmt.executeQuery(sql);
            if (rs.next()) {
                return "PASS: Row exists for query: " + sql;
            } else {
                return "FAIL: No row found for query: " + sql;
            }
        }
    }

    private String assertCount(String sql, int expected) throws SQLException {
        log.info("DB assertCount (expected={}): {}", expected, sql);

        try (Statement stmt = connection.createStatement()) {
            ResultSet rs = stmt.executeQuery(sql);
            int count = 0;
            if (rs.next()) {
                // If the query is a COUNT(*) query, read the first column
                try {
                    count = rs.getInt(1);
                } catch (SQLException e) {
                    // Otherwise count rows manually
                    count = 1;
                    while (rs.next()) count++;
                }
            }

            if (count == expected) {
                return "PASS: Row count is " + count + " as expected.";
            } else {
                return "FAIL: Expected " + expected + " rows but found " + count + ". SQL: " + sql;
            }
        }
    }

    private String executeSql(String sql) throws SQLException {
        log.info("DB execute: {}", sql);
        try (Statement stmt = connection.createStatement()) {
            int affected = stmt.executeUpdate(sql);
            return "Executed successfully. Rows affected: " + affected;
        }
    }

    @Override
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
                log.info("DB connection closed");
            }
        } catch (SQLException ignored) {}
    }
}
