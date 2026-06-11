package com.fpt.train.dbcli;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class App {

    private static final String DEFAULT_SQL = """
        SELECT current_database() AS database_name,
               current_user AS user_name,
               NOW() AS server_time,
               version() AS server_version
        """;

    private App() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && isHelp(args[0])) {
            printUsage();
            return;
        }

        String sql = resolveSql(args);

        try {
            DbConfig config = DbConfig.fromEnv();
            runQuery(config, sql);
        } catch (IllegalStateException e) {
            System.err.println("Lỗi cấu hình: " + e.getMessage());
            printUsage();
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Lỗi database: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runQuery(DbConfig config, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password());
             Statement statement = connection.createStatement()) {

            System.out.println("Connected to: " + connection.getMetaData().getURL());
            GtfsImporter.importAll(connection);
            System.out.println();
            RouteStopPrinter.printLongestRouteStops(connection);
            System.out.println();
            boolean hasResultSet = statement.execute(sql);

            if (hasResultSet) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    ResultSetPrinter.print(resultSet);
                }
            } else {
                System.out.printf("Statement executed. %d row(s) affected.%n", statement.getUpdateCount());
            }
        }
    }

    private static boolean isHelp(String arg) {
        return "--help".equals(arg) || "-h".equals(arg);
    }

    private static String resolveSql(String[] args) {
        if (args.length == 0) {
            return DEFAULT_SQL;
        }
        return String.join(" ", args).trim();
    }

    private static void printUsage() {
        System.out.println("""
            Usage:
              gradlew run
              gradlew run --args="SELECT NOW()"

            Required env:
              DB_USER
              and one of:
                DB_URL
                or DB_HOST + DB_NAME

            Optional env:
              DB_PORT (default 5432)
              DB_PASSWORD
              DB_PARAMS
            """);
    }
}
