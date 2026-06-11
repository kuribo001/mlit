package com.fpt.train.dbcli;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DatabaseMetaData;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.StringJoiner;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GtfsImporter {

    private static final int BATCH_SIZE = 1000;
    private static final String GTFS_DIR_ENV = "GTFS_DIR";
    private static final String GTFS_SCHEMA_SQL_ENV = "GTFS_SCHEMA_SQL";
    private static final String MLIT_RAIL_GEOJSON_ENV = "MLIT_RAIL_GEOJSON";
    private static final Path DEFAULT_GTFS_DIR = Path.of("..", "Toei-Train-GTFS");
    private static final Path DEFAULT_SCHEMA_SQL = DEFAULT_GTFS_DIR.resolve("create_gtfs_train_tables.sql");
    private static final Path DEFAULT_MLIT_RAIL_GEOJSON = Path.of("..", "mlit_tokyo", "N02-22_tokyo_railroadsection.geojson");
    private static final Map<String, String> TOEI_LINE_ROUTE_MAP = createToeiLineRouteMap();
    private static final Pattern COORDINATE_PATTERN =
        Pattern.compile("\\[\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*\\]");

    private GtfsImporter() {
    }

    public static void importAll(Connection connection) throws SQLException {
        Path gtfsDir = resolveGtfsDir();
        Path schemaSql = resolveSchemaSql();

        if (!Files.isDirectory(gtfsDir)) {
            throw new IllegalStateException("Không tìm thấy thư mục GTFS: " + gtfsDir.toAbsolutePath());
        }

        if (!Files.exists(schemaSql)) {
            throw new IllegalStateException("Không tìm thấy file schema SQL: " + schemaSql.toAbsolutePath());
        }

        System.out.println("Preparing GTFS tables from: " + schemaSql.toAbsolutePath());
        executeSqlScript(connection, schemaSql);

        List<Path> gtfsFiles = listGtfsFiles(gtfsDir);
        if (gtfsFiles.isEmpty()) {
            throw new IllegalStateException("Không tìm thấy file GTFS .txt trong: " + gtfsDir.toAbsolutePath());
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try {
            for (Path file : gtfsFiles) {
                String tableName = toTableName(file);
                try {
                    long rowCount = insertIntoTable(connection, tableName, file);
                    connection.commit();
                    System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", file.getFileName(), tableName, rowCount);
                } catch (SQLException | RuntimeException e) {
                    connection.rollback();
                    throw e;
                }
            }

            try {
                long rowCount = importToeiMlitShapes(connection, resolveMlitRailGeoJson());
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "MLIT Toei rail", "gtfs_train_shapes", rowCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static Path resolveGtfsDir() {
        String configured = System.getenv(GTFS_DIR_ENV);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return DEFAULT_GTFS_DIR;
    }

    private static Path resolveSchemaSql() {
        String configured = System.getenv(GTFS_SCHEMA_SQL_ENV);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return DEFAULT_SCHEMA_SQL;
    }

    private static Path resolveMlitRailGeoJson() {
        String configured = System.getenv(MLIT_RAIL_GEOJSON_ENV);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        return DEFAULT_MLIT_RAIL_GEOJSON;
    }

    private static List<Path> listGtfsFiles(Path gtfsDir) {
        try (Stream<Path> stream = Files.list(gtfsDir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".txt"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được thư mục GTFS: " + e.getMessage(), e);
        }
    }

    private static void executeSqlScript(Connection connection, Path scriptFile) throws SQLException {
        String script;
        try {
            script = Files.readString(scriptFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file SQL: " + e.getMessage(), e);
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute(script);
        }
    }

    private static long insertIntoTable(Connection connection, String tableName, Path file) throws SQLException {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return 0L;
            }

            List<String> columns = parseCsvLine(headerLine);
            Map<String, Integer> columnTypes = loadColumnTypes(connection, tableName);
            String sql = buildInsertSql(tableName, columns);
            long rowCount = 0L;
            int batchCount = 0;

            try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }

                    List<String> values = parseCsvLine(line);
                    bindRow(preparedStatement, columns, values, columnTypes);
                    preparedStatement.addBatch();
                    rowCount++;
                    batchCount++;

                    if (batchCount >= BATCH_SIZE) {
                        preparedStatement.executeBatch();
                        batchCount = 0;
                    }
                }

                if (batchCount > 0) {
                    preparedStatement.executeBatch();
                }
            }

            return rowCount;
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file GTFS: " + file.toAbsolutePath(), e);
        }
    }

    private static long importToeiMlitShapes(Connection connection, Path geoJsonFile) throws SQLException {
        if (!Files.exists(geoJsonFile)) {
            throw new IllegalStateException("Không tìm thấy file MLIT rail GeoJSON: " + geoJsonFile.toAbsolutePath());
        }

        String sql = """
            INSERT INTO gtfs_train_shapes (
                shape_id,
                shape_pt_lat,
                shape_pt_lon,
                shape_pt_sequence,
                shape_dist_traveled
            ) VALUES (?, ?, ?, ?, ?)
            """;

        long rowCount = 0L;
        int batchCount = 0;
        int featureIndex = 0;

        try (BufferedReader reader = Files.newBufferedReader(geoJsonFile, StandardCharsets.UTF_8);
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lineName = extractJsonString(line, "\"N02_003\":\"");
                if (lineName == null || !TOEI_LINE_ROUTE_MAP.containsKey(lineName)) {
                    continue;
                }

                List<double[]> coordinates = parseCoordinates(line);
                if (coordinates.size() < 2) {
                    continue;
                }

                featureIndex++;
                String routeId = TOEI_LINE_ROUTE_MAP.get(lineName);
                String shapeId = "toei_mlit_r" + routeId + "_seg" + String.format("%03d", featureIndex);
                double dist = 0.0;

                for (int i = 0; i < coordinates.size(); i++) {
                    double lon = coordinates.get(i)[0];
                    double lat = coordinates.get(i)[1];

                    if (i > 0) {
                        double[] prev = coordinates.get(i - 1);
                        dist += distanceMeters(prev[1], prev[0], lat, lon);
                    }

                    preparedStatement.setString(1, shapeId);
                    preparedStatement.setDouble(2, lat);
                    preparedStatement.setDouble(3, lon);
                    preparedStatement.setInt(4, i);
                    preparedStatement.setBigDecimal(5, java.math.BigDecimal.valueOf(dist).setScale(3, java.math.RoundingMode.HALF_UP));
                    preparedStatement.addBatch();
                    rowCount++;
                    batchCount++;

                    if (batchCount >= BATCH_SIZE) {
                        preparedStatement.executeBatch();
                        batchCount = 0;
                    }
                }
            }

            if (batchCount > 0) {
                preparedStatement.executeBatch();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file MLIT rail GeoJSON: " + geoJsonFile.toAbsolutePath(), e);
        }

        return rowCount;
    }

    private static String buildInsertSql(String tableName, List<String> columns) {
        StringJoiner columnJoiner = new StringJoiner(", ");
        StringJoiner valueJoiner = new StringJoiner(", ");

        for (String column : columns) {
            columnJoiner.add(column);
            valueJoiner.add("?");
        }

        return "INSERT INTO " + tableName + " (" + columnJoiner + ") VALUES (" + valueJoiner + ")";
    }

    private static Map<String, Integer> loadColumnTypes(Connection connection, String tableName) throws SQLException {
        Map<String, Integer> columnTypes = new HashMap<>();
        DatabaseMetaData metaData = connection.getMetaData();

        try (ResultSet columns = metaData.getColumns(null, null, tableName, null)) {
            while (columns.next()) {
                columnTypes.put(columns.getString("COLUMN_NAME"), columns.getInt("DATA_TYPE"));
            }
        }

        return columnTypes;
    }

    private static void bindRow(
        PreparedStatement preparedStatement,
        List<String> columns,
        List<String> values,
        Map<String, Integer> columnTypes
    ) throws SQLException {
        for (int i = 0; i < columns.size(); i++) {
            String value = i < values.size() ? values.get(i) : null;
            int sqlType = columnTypes.getOrDefault(columns.get(i), Types.VARCHAR);

            if (value == null || value.isEmpty()) {
                preparedStatement.setNull(i + 1, sqlType);
            } else {
                setTypedValue(preparedStatement, i + 1, sqlType, value);
            }
        }
    }

    private static void setTypedValue(PreparedStatement preparedStatement, int parameterIndex, int sqlType, String value)
        throws SQLException {
        switch (sqlType) {
            case Types.SMALLINT -> preparedStatement.setShort(parameterIndex, Short.parseShort(value));
            case Types.INTEGER -> preparedStatement.setInt(parameterIndex, Integer.parseInt(value));
            case Types.BIGINT -> preparedStatement.setLong(parameterIndex, Long.parseLong(value));
            case Types.NUMERIC, Types.DECIMAL -> preparedStatement.setBigDecimal(parameterIndex, new java.math.BigDecimal(value));
            case Types.DOUBLE, Types.FLOAT -> preparedStatement.setDouble(parameterIndex, Double.parseDouble(value));
            case Types.REAL -> preparedStatement.setFloat(parameterIndex, Float.parseFloat(value));
            case Types.BOOLEAN, Types.BIT -> preparedStatement.setBoolean(parameterIndex, parseBoolean(value));
            default -> preparedStatement.setString(parameterIndex, value);
        }
    }

    private static boolean parseBoolean(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "t".equalsIgnoreCase(value);
    }

    private static String extractJsonString(String line, String keyPrefix) {
        int start = line.indexOf(keyPrefix);
        if (start < 0) {
            return null;
        }

        int valueStart = start + keyPrefix.length();
        int valueEnd = line.indexOf('"', valueStart);
        if (valueEnd < 0) {
            return null;
        }

        return line.substring(valueStart, valueEnd);
    }

    private static List<double[]> parseCoordinates(String line) {
        int start = line.indexOf("\"coordinates\":[");
        if (start < 0) {
            return List.of();
        }

        String coordsText = line.substring(start);
        Matcher matcher = COORDINATE_PATTERN.matcher(coordsText);
        List<double[]> coordinates = new ArrayList<>();

        while (matcher.find()) {
            double lon = Double.parseDouble(matcher.group(1));
            double lat = Double.parseDouble(matcher.group(2));
            coordinates.add(new double[]{lon, lat});
        }

        return coordinates;
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadius = 6_371_000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
            * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return earthRadius * c;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);

            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        values.add(current.toString());
        return values;
    }

    private static String toTableName(Path file) {
        String fileName = file.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String baseName = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
        return "gtfs_train_" + baseName;
    }

    private static Map<String, String> createToeiLineRouteMap() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("1号線浅草線", "1");
        map.put("6号線三田線", "2");
        map.put("10号線新宿線", "3");
        map.put("12号線大江戸線", "4");
        map.put("日暮里・舎人ライナー", "5");
        map.put("荒川線", "6");
        return map;
    }
}
