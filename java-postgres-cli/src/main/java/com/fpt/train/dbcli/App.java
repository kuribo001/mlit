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

        ExecutionOptions options = resolveOptions(args);

        try {
            DbConfig config = DbConfig.fromEnv();
            runQuery(config, options);
        } catch (IllegalStateException e) {
            System.err.println("Lỗi cấu hình: " + e.getMessage());
            printUsage();
            System.exit(1);
        } catch (SQLException e) {
            System.err.println("Lỗi database: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void runQuery(DbConfig config, ExecutionOptions options) throws SQLException {
        try (Connection connection = DriverManager.getConnection(config.url(), config.user(), config.password());
             Statement statement = connection.createStatement()) {

            System.out.println("Connected to: " + connection.getMetaData().getURL());
            GtfsImporter.importAll(connection);
            String routeId = options.routeId();
            if (routeId == null) {
                System.out.println("Exported CZML: " + CzmlExporter.exportShapes(connection));
            } else {
                System.out.println("Exported route CZML: " + CzmlExporter.exportShapes(connection, routeId));
                System.out.println("Exported trip CZML: " + CzmlExporter.exportTripsForRoute(connection, routeId));
            }
            System.out.println();
            RouteStopPrinter.printLongestRouteStops(connection);
            System.out.println();
            boolean hasResultSet = statement.execute(options.sql());

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

    private static ExecutionOptions resolveOptions(String[] args) {
        if (args.length == 0) {
            return new ExecutionOptions(DEFAULT_SQL, null);
        }
        if (args.length == 1 && args[0].matches("\\d+")) {
            return new ExecutionOptions(DEFAULT_SQL, args[0]);
        }
        return new ExecutionOptions(String.join(" ", args).trim(), null);
    }

    private static void printUsage() {
        System.out.println("""
            Usage:
              gradlew run
              gradlew run --args="1"
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

    private record ExecutionOptions(String sql, String routeId) {
    }
}
