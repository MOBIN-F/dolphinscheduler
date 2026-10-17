/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dolphinscheduler.api.service.seatunnel;

import org.apache.dolphinscheduler.ai.connection.BrowseNode;
import org.apache.dolphinscheduler.ai.connection.ConnectionBrowser;
import org.apache.dolphinscheduler.ai.connection.ConnectionType;
import org.apache.dolphinscheduler.ai.connection.DescribedField;
import org.apache.dolphinscheduler.ai.connection.ObjectDescription;
import org.apache.dolphinscheduler.ai.connection.SeatunnelConnection;
import org.apache.dolphinscheduler.common.utils.JSONUtils;
import org.apache.dolphinscheduler.plugin.datasource.api.utils.DataSourceUtils;
import org.apache.dolphinscheduler.spi.datasource.ConnectionParam;
import org.apache.dolphinscheduler.spi.enums.DbType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Browses the databases, tables and columns of the MySQL, PostgreSQL and ClickHouse connections, with the
 * datasource plugins of DolphinScheduler: the same drivers, URLs and parameter checks as the datasource center.
 */
@Component
public class JdbcConnectionBrowser implements ConnectionBrowser {

    static final int MAX_TABLES = 2000;

    private static final Set<String> SYSTEM_DATABASES = new HashSet<>(java.util.Arrays.asList(
            "information_schema", "mysql", "performance_schema", "sys", "pg_catalog", "pg_toast", "system"));

    @Override
    public boolean supports(ConnectionType type) {
        return type == ConnectionType.MYSQL || type == ConnectionType.POSTGRESQL || type == ConnectionType.CLICKHOUSE;
    }

    @Override
    public void test(SeatunnelConnection connection) throws SQLException {
        try (Connection jdbc = connect(connection)) {
            jdbc.getMetaData();
        }
    }

    @Override
    public List<BrowseNode> list(SeatunnelConnection connection, String parent) throws SQLException {
        try (Connection jdbc = connect(connection)) {
            return parent == null || parent.isEmpty() ? databases(connection.type(), jdbc)
                    : tables(connection.type(), jdbc, parent);
        }
    }

    private List<BrowseNode> databases(ConnectionType type, Connection jdbc) throws SQLException {
        List<BrowseNode> nodes = new ArrayList<>();
        if (type == ConnectionType.POSTGRESQL) {
            // The database is fixed by the connection, browse its schemas
            try (ResultSet schemas = jdbc.getMetaData().getSchemas()) {
                while (schemas.next()) {
                    addDatabase(nodes, schemas.getString("TABLE_SCHEM"));
                }
            }
            return nodes;
        }
        try (
                Statement statement = jdbc.createStatement();
                ResultSet databases = statement.executeQuery("SHOW DATABASES")) {
            while (databases.next()) {
                addDatabase(nodes, databases.getString(1));
            }
        }
        return nodes;
    }

    private static void addDatabase(List<BrowseNode> nodes, String name) {
        if (name != null && !SYSTEM_DATABASES.contains(name.toLowerCase(Locale.ROOT))
                && !name.toLowerCase(Locale.ROOT).startsWith("pg_temp")) {
            nodes.add(new BrowseNode(name, name, false));
        }
    }

    private List<BrowseNode> tables(ConnectionType type, Connection jdbc, String database) throws SQLException {
        List<BrowseNode> nodes = new ArrayList<>();
        if (type == ConnectionType.CLICKHOUSE) {
            try (
                    PreparedStatement statement = jdbc.prepareStatement(
                            "SELECT name FROM system.tables WHERE database = ? ORDER BY name LIMIT " + MAX_TABLES)) {
                statement.setString(1, database);
                try (ResultSet tables = statement.executeQuery()) {
                    while (tables.next()) {
                        nodes.add(new BrowseNode(tables.getString(1), database + "." + tables.getString(1), true));
                    }
                }
            }
            return nodes;
        }
        DatabaseMetaData metaData = jdbc.getMetaData();
        boolean mysql = type == ConnectionType.MYSQL;
        try (
                ResultSet tables = metaData.getTables(mysql ? database : null, mysql ? null : database, "%",
                        new String[]{"TABLE", "VIEW", "PARTITIONED TABLE"})) {
            while (tables.next() && nodes.size() < MAX_TABLES) {
                String table = tables.getString("TABLE_NAME");
                nodes.add(new BrowseNode(table, database + "." + table, true));
            }
        }
        return nodes;
    }

    @Override
    public ObjectDescription describe(SeatunnelConnection connection, String object) throws SQLException {
        int dot = object.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("The table must be database.table or schema.table: " + object);
        }
        String database = object.substring(0, dot);
        String table = object.substring(dot + 1);
        try (Connection jdbc = connect(connection)) {
            List<DescribedField> fields = connection.type() == ConnectionType.CLICKHOUSE
                    ? clickhouseColumns(jdbc, database, table)
                    : columns(connection.type(), jdbc, database, table);
            if (fields.isEmpty()) {
                throw new IllegalArgumentException("Table " + object + " not found");
            }
            return new ObjectDescription(object, "table", fields, null, "", List.of());
        }
    }

    private List<DescribedField> columns(ConnectionType type, Connection jdbc, String database,
                                         String table) throws SQLException {
        DatabaseMetaData metaData = jdbc.getMetaData();
        boolean mysql = type == ConnectionType.MYSQL;
        String catalog = mysql ? database : null;
        String schema = mysql ? null : database;
        Set<String> primaryKeys = new HashSet<>();
        try (ResultSet keys = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (keys.next()) {
                primaryKeys.add(keys.getString("COLUMN_NAME"));
            }
        }
        List<DescribedField> fields = new ArrayList<>();
        try (ResultSet columns = metaData.getColumns(catalog, schema, table, "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                String typeName = columns.getString("TYPE_NAME");
                int size = columns.getInt("COLUMN_SIZE");
                int digits = columns.getInt("DECIMAL_DIGITS");
                String lower = typeName.toLowerCase(Locale.ROOT);
                if (lower.contains("char")) {
                    typeName += "(" + size + ")";
                } else if (lower.contains("decimal") || lower.contains("numeric")) {
                    typeName += "(" + size + "," + digits + ")";
                }
                fields.add(new DescribedField(name, typeName,
                        columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, primaryKeys.contains(name),
                        "", columns.getString("REMARKS")));
            }
        }
        return fields;
    }

    private List<DescribedField> clickhouseColumns(Connection jdbc, String database,
                                                   String table) throws SQLException {
        List<DescribedField> fields = new ArrayList<>();
        try (
                PreparedStatement statement = jdbc.prepareStatement("SELECT name, type, is_in_primary_key, comment "
                        + "FROM system.columns WHERE database = ? AND table = ? ORDER BY position")) {
            statement.setString(1, database);
            statement.setString(2, table);
            try (ResultSet columns = statement.executeQuery()) {
                while (columns.next()) {
                    String type = columns.getString(2);
                    fields.add(new DescribedField(columns.getString(1), type, type.startsWith("Nullable("),
                            columns.getInt(3) == 1, "", columns.getString(4)));
                }
            }
        }
        return fields;
    }

    /**
     * Connect through the datasource plugin of the type.
     */
    Connection connect(SeatunnelConnection connection) {
        DbType dbType = dbType(connection.type());
        Map<String, Object> param = new LinkedHashMap<>();
        param.put("type", dbType.name());
        param.put("host", connection.param("host"));
        param.put("port", Integer.parseInt(connection.param("port")));
        String database = connection.param("database");
        if (database.isEmpty() && connection.type() == ConnectionType.CLICKHOUSE) {
            database = "default";
        }
        param.put("database", database);
        param.put("userName", connection.param("username"));
        param.put("password", connection.secret("password"));
        param.put("other", other(connection));
        ConnectionParam connectionParam =
                DataSourceUtils.buildConnectionParams(DataSourceUtils.buildDatasourceParam(JSONUtils.toJsonString(
                        param)));
        return DataSourceUtils.getConnection(dbType, connectionParam);
    }

    private static Map<String, String> other(SeatunnelConnection connection) {
        Map<String, String> other = new LinkedHashMap<>();
        if (connection.type() != ConnectionType.CLICKHOUSE) {
            for (String pair : connection.param("jdbc_params").split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    other.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
                }
            }
        }
        if (connection.type() == ConnectionType.MYSQL) {
            other.putIfAbsent("connectTimeout", "10000");
        } else if (connection.type() == ConnectionType.POSTGRESQL) {
            other.putIfAbsent("connectTimeout", "10");
        }
        return other;
    }

    private static DbType dbType(ConnectionType type) {
        switch (type) {
            case MYSQL:
                return DbType.MYSQL;
            case POSTGRESQL:
                return DbType.POSTGRESQL;
            case CLICKHOUSE:
                return DbType.CLICKHOUSE;
            default:
                throw new IllegalArgumentException("Not a JDBC connection: " + type);
        }
    }
}
