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
    private static final Pattern WKT_COORDINATE_PATTERN =
        Pattern.compile("([-0-9.]+)\\s+([-0-9.]+)");

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
                long rawCount = importRawMlitRailSegments(connection, resolveMlitRailGeoJson());
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "MLIT raw rail", "mlit_raw_rail_segments", rawCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            try {
                long rowCount = importRouteStopOrders(connection);
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "GTFS route stops", "mlit_route_stop_orders", rowCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            try {
                long rowCount = importNetworkNodes(connection);
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "MLIT network nodes", "mlit_network_nodes", rowCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            try {
                long rowCount = importNetworkEdges(connection);
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "MLIT network edges", "mlit_network_edges", rowCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            try {
                long rowCount = importStopNodeMap(connection);
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "GTFS stop-node map", "mlit_stop_node_map", rowCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            try {
                long rowCount = importRoutePathEdges(connection);
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "MLIT route paths", "mlit_route_path_edges", rowCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            try {
                long rowCount = importMlitRouteGeometries(connection);
                connection.commit();
                System.out.printf("Imported %-30s -> %-28s %,d row(s)%n", "MLIT merged rail", "mlit_route_geometries", rowCount);
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }

            try {
                long rowCount = importToeiShapesFromRouteGeometries(connection);
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

    private static long importRawMlitRailSegments(Connection connection, Path geoJsonFile) throws SQLException {
        if (!Files.exists(geoJsonFile)) {
            throw new IllegalStateException("Không tìm thấy file MLIT rail GeoJSON: " + geoJsonFile.toAbsolutePath());
        }

        String sql = """
            INSERT INTO mlit_raw_rail_segments (
                operator_name,
                line_name,
                geom
            ) VALUES (?, ?, ST_GeomFromText(?, 4326))
            """;

        long rowCount = 0L;
        int batchCount = 0;

        try (BufferedReader reader = Files.newBufferedReader(geoJsonFile, StandardCharsets.UTF_8);
             PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String operatorName = extractJsonString(line, "\"N02_004\":\"");
                String lineName = extractJsonString(line, "\"N02_003\":\"");
                if (operatorName == null || lineName == null) {
                    continue;
                }

                List<double[]> coordinates = parseCoordinates(line);
                if (coordinates.size() < 2) {
                    continue;
                }

                preparedStatement.setString(1, operatorName);
                preparedStatement.setString(2, lineName);
                preparedStatement.setString(3, toLineStringWkt(coordinates));
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
        } catch (IOException e) {
            throw new IllegalStateException("Không đọc được file MLIT rail GeoJSON: " + geoJsonFile.toAbsolutePath(), e);
        }

        return rowCount;
    }

    private static long importRouteStopOrders(Connection connection) throws SQLException {
        String sql = """
            WITH route_map(route_id, line_name) AS (
                VALUES
                    ('1', '1号線浅草線'),
                    ('2', '6号線三田線'),
                    ('3', '10号線新宿線'),
                    ('4', '12号線大江戸線'),
                    ('5', '日暮里・舎人ライナー'),
                    ('6', '荒川線')
            ),
            trip_lengths AS (
                SELECT
                    t.route_id,
                    t.trip_id,
                    COUNT(*) AS stop_count,
                    MAX(st.stop_sequence) AS max_stop_sequence
                FROM gtfs_train_trips t
                JOIN gtfs_train_stop_times st
                    ON st.trip_id = t.trip_id
                GROUP BY t.route_id, t.trip_id
            ),
            ranked_trips AS (
                SELECT
                    route_id,
                    trip_id,
                    ROW_NUMBER() OVER (
                        PARTITION BY route_id
                        ORDER BY stop_count DESC, max_stop_sequence DESC, trip_id
                    ) AS rn
                FROM trip_lengths
            )
            INSERT INTO mlit_route_stop_orders (
                route_id,
                line_name,
                trip_id,
                stop_sequence,
                stop_id,
                stop_name,
                geom,
                pref_code,
                agency_id
            )
            SELECT
                r.route_id,
                rm.line_name,
                rt.trip_id,
                st.stop_sequence,
                st.stop_id,
                s.stop_name,
                ST_SetSRID(ST_MakePoint(s.stop_lon::double precision, s.stop_lat::double precision), 4326) AS geom,
                '13' AS pref_code,
                'toei' AS agency_id
            FROM ranked_trips rt
            JOIN gtfs_train_routes r
                ON r.route_id = rt.route_id
            JOIN route_map rm
                ON rm.route_id = rt.route_id
            JOIN gtfs_train_stop_times st
                ON st.trip_id = rt.trip_id
            JOIN gtfs_train_stops s
                ON s.stop_id = st.stop_id
            WHERE rt.rn = 1
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static long importNetworkNodes(Connection connection) throws SQLException {
        String sql = """
            WITH route_segments AS (
                SELECT
                    m.route_id,
                    r.geom
                FROM mlit_raw_rail_segments r
                JOIN (
                    VALUES
                        ('1', '1号線浅草線'),
                        ('2', '6号線三田線'),
                        ('3', '10号線新宿線'),
                        ('4', '12号線大江戸線'),
                        ('5', '日暮里・舎人ライナー'),
                        ('6', '荒川線')
                ) AS m(route_id, line_name)
                    ON r.operator_name = '東京都'
                   AND r.line_name = m.line_name
            ),
            route_points AS (
                SELECT
                    route_id,
                    ST_StartPoint(geom) AS pt_geom
                FROM route_segments
                UNION ALL
                SELECT
                    route_id,
                    ST_EndPoint(geom) AS pt_geom
                FROM route_segments
            )
            INSERT INTO mlit_network_nodes (
                route_id,
                node_key,
                geom,
                pref_code,
                agency_id
            )
            SELECT DISTINCT
                route_id,
                round(ST_X(pt_geom)::numeric, 7)::text || ',' || round(ST_Y(pt_geom)::numeric, 7)::text AS node_key,
                ST_SetSRID(ST_MakePoint(ST_X(pt_geom), ST_Y(pt_geom)), 4326) AS geom,
                '13' AS pref_code,
                'toei' AS agency_id
            FROM route_points
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static long importNetworkEdges(Connection connection) throws SQLException {
        String sql = """
            WITH route_segments AS (
                SELECT
                    m.route_id,
                    m.line_name,
                    r.geom
                FROM mlit_raw_rail_segments r
                JOIN (
                    VALUES
                        ('1', '1号線浅草線'),
                        ('2', '6号線三田線'),
                        ('3', '10号線新宿線'),
                        ('4', '12号線大江戸線'),
                        ('5', '日暮里・舎人ライナー'),
                        ('6', '荒川線')
                ) AS m(route_id, line_name)
                    ON r.operator_name = '東京都'
                   AND r.line_name = m.line_name
            ),
            prepared_edges AS (
                SELECT
                    route_id,
                    line_name,
                    round(ST_X(ST_StartPoint(geom))::numeric, 7)::text || ','
                        || round(ST_Y(ST_StartPoint(geom))::numeric, 7)::text AS source_node_key,
                    round(ST_X(ST_EndPoint(geom))::numeric, 7)::text || ','
                        || round(ST_Y(ST_EndPoint(geom))::numeric, 7)::text AS target_node_key,
                    geom,
                    ST_Length(geom::geography)::numeric(12,3) AS length_m
                FROM route_segments
            )
            INSERT INTO mlit_network_edges (
                route_id,
                line_name,
                source_node_key,
                target_node_key,
                source_node_id,
                target_node_id,
                geom,
                length_m,
                pref_code,
                agency_id
            )
            SELECT
                e.route_id,
                e.line_name,
                e.source_node_key,
                e.target_node_key,
                sn.node_id AS source_node_id,
                tn.node_id AS target_node_id,
                e.geom,
                e.length_m,
                '13' AS pref_code,
                'toei' AS agency_id
            FROM prepared_edges e
            JOIN mlit_network_nodes sn
                ON sn.route_id = e.route_id
               AND sn.node_key = e.source_node_key
            JOIN mlit_network_nodes tn
                ON tn.route_id = e.route_id
               AND tn.node_key = e.target_node_key
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static long importStopNodeMap(Connection connection) throws SQLException {
        String sql = """
            WITH ranked_nodes AS (
                SELECT
                    rso.route_id,
                    rso.stop_sequence,
                    rso.stop_id,
                    nn.node_id,
                    nn.node_key,
                    ST_Distance(rso.geom::geography, nn.geom::geography)::numeric(12,3) AS distance_m,
                    ROW_NUMBER() OVER (
                        PARTITION BY rso.route_id, rso.stop_sequence
                        ORDER BY rso.geom <-> nn.geom,
                                 ST_Distance(rso.geom::geography, nn.geom::geography)
                    ) AS rn
                FROM mlit_route_stop_orders rso
                JOIN mlit_network_nodes nn
                    ON nn.route_id = rso.route_id
            )
            INSERT INTO mlit_stop_node_map (
                route_id,
                stop_sequence,
                stop_id,
                node_id,
                node_key,
                distance_m,
                pref_code,
                agency_id
            )
            SELECT
                route_id,
                stop_sequence,
                stop_id,
                node_id,
                node_key,
                distance_m,
                '13' AS pref_code,
                'toei' AS agency_id
            FROM ranked_nodes
            WHERE rn = 1
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static long importRoutePathEdges(Connection connection) throws SQLException {
        String sql = """
            WITH RECURSIVE stop_pairs AS (
                SELECT
                    s.route_id,
                    s.stop_sequence AS from_stop_sequence,
                    LEAD(s.stop_sequence) OVER (
                        PARTITION BY s.route_id
                        ORDER BY s.stop_sequence
                    ) AS to_stop_sequence,
                    n.node_id AS from_node_id,
                    LEAD(n.node_id) OVER (
                        PARTITION BY s.route_id
                        ORDER BY s.stop_sequence
                    ) AS to_node_id
                FROM mlit_route_stop_orders s
                JOIN mlit_stop_node_map n
                    ON n.route_id = s.route_id
                   AND n.stop_sequence = s.stop_sequence
            ),
            filtered_pairs AS (
                SELECT *
                FROM stop_pairs
                WHERE to_stop_sequence IS NOT NULL
                  AND from_node_id IS NOT NULL
                  AND to_node_id IS NOT NULL
                  AND from_node_id <> to_node_id
            ),
            directed_edges AS (
                SELECT
                    edge_id,
                    route_id,
                    source_node_id AS from_node_id,
                    target_node_id AS to_node_id,
                    geom,
                    length_m,
                    0::smallint AS is_reversed
                FROM mlit_network_edges
                UNION ALL
                SELECT
                    edge_id,
                    route_id,
                    target_node_id AS from_node_id,
                    source_node_id AS to_node_id,
                    ST_Reverse(geom) AS geom,
                    length_m,
                    1::smallint AS is_reversed
                FROM mlit_network_edges
            ),
            search_paths AS (
                SELECT
                    p.route_id,
                    p.from_stop_sequence,
                    p.to_stop_sequence,
                    p.from_node_id,
                    p.to_node_id,
                    de.to_node_id AS current_node_id,
                    ARRAY[p.from_node_id, de.to_node_id]::bigint[] AS visited_nodes,
                    ARRAY[de.edge_id]::bigint[] AS edge_ids,
                    ARRAY[de.is_reversed]::smallint[] AS reverse_flags,
                    de.length_m AS total_length,
                    1 AS depth
                FROM filtered_pairs p
                JOIN directed_edges de
                    ON de.route_id = p.route_id
                   AND de.from_node_id = p.from_node_id
                UNION ALL
                SELECT
                    sp.route_id,
                    sp.from_stop_sequence,
                    sp.to_stop_sequence,
                    sp.from_node_id,
                    sp.to_node_id,
                    de.to_node_id AS current_node_id,
                    sp.visited_nodes || de.to_node_id,
                    sp.edge_ids || de.edge_id,
                    sp.reverse_flags || de.is_reversed,
                    (sp.total_length + de.length_m)::numeric(12,3) AS total_length,
                    sp.depth + 1
                FROM search_paths sp
                JOIN directed_edges de
                    ON de.route_id = sp.route_id
                   AND de.from_node_id = sp.current_node_id
                WHERE sp.current_node_id <> sp.to_node_id
                  AND sp.depth < 12
                  AND NOT (de.to_node_id = ANY(sp.visited_nodes))
            ),
            best_paths AS (
                SELECT DISTINCT ON (route_id, from_stop_sequence, to_stop_sequence)
                    route_id,
                    from_stop_sequence,
                    to_stop_sequence,
                    edge_ids,
                    reverse_flags,
                    total_length
                FROM search_paths
                WHERE current_node_id = to_node_id
                ORDER BY
                    route_id,
                    from_stop_sequence,
                    to_stop_sequence,
                    total_length,
                    array_length(edge_ids, 1)
            ),
            expanded_paths AS (
                SELECT
                    bp.route_id,
                    bp.from_stop_sequence,
                    bp.to_stop_sequence,
                    u.ordinality::integer AS path_order,
                    u.edge_id,
                    bp.reverse_flags[u.ordinality] AS is_reversed
                FROM best_paths bp
                CROSS JOIN LATERAL unnest(bp.edge_ids) WITH ORDINALITY AS u(edge_id, ordinality)
            )
            INSERT INTO mlit_route_path_edges (
                route_id,
                from_stop_sequence,
                to_stop_sequence,
                path_order,
                edge_id,
                is_reversed,
                geom,
                length_m,
                pref_code,
                agency_id
            )
            SELECT
                ep.route_id,
                ep.from_stop_sequence,
                ep.to_stop_sequence,
                ep.path_order,
                ep.edge_id,
                ep.is_reversed,
                CASE
                    WHEN ep.is_reversed = 1 THEN ST_Reverse(e.geom)
                    ELSE e.geom
                END AS geom,
                e.length_m,
                '13' AS pref_code,
                'toei' AS agency_id
            FROM expanded_paths ep
            JOIN mlit_network_edges e
                ON e.edge_id = ep.edge_id
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            long rowCount = statement.executeUpdate();
            ensureRoutePathCoverage(connection);
            return rowCount;
        }
    }

    private static void ensureRoutePathCoverage(Connection connection) throws SQLException {
        String sql = """
            WITH stop_pairs AS (
                SELECT
                    s.route_id,
                    s.stop_sequence AS from_stop_sequence,
                    LEAD(s.stop_sequence) OVER (
                        PARTITION BY s.route_id
                        ORDER BY s.stop_sequence
                    ) AS to_stop_sequence
                    ,
                    n.node_id AS from_node_id,
                    LEAD(n.node_id) OVER (
                        PARTITION BY s.route_id
                        ORDER BY s.stop_sequence
                    ) AS to_node_id
                FROM mlit_route_stop_orders s
                JOIN mlit_stop_node_map n
                    ON n.route_id = s.route_id
                   AND n.stop_sequence = s.stop_sequence
            ),
            expected_pairs AS (
                SELECT
                    route_id,
                    from_stop_sequence,
                    to_stop_sequence
                FROM stop_pairs
                WHERE to_stop_sequence IS NOT NULL
                  AND from_node_id <> to_node_id
            ),
            covered_pairs AS (
                SELECT DISTINCT
                    route_id,
                    from_stop_sequence,
                    to_stop_sequence
                FROM mlit_route_path_edges
            )
            SELECT
                e.route_id,
                e.from_stop_sequence,
                e.to_stop_sequence
            FROM expected_pairs e
            LEFT JOIN covered_pairs c
                ON c.route_id = e.route_id
               AND c.from_stop_sequence = e.from_stop_sequence
               AND c.to_stop_sequence = e.to_stop_sequence
            WHERE c.route_id IS NULL
            ORDER BY e.route_id, e.from_stop_sequence
            LIMIT 1
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (resultSet.next()) {
                throw new SQLException(
                    "Không tìm thấy path giữa stop_sequence "
                        + resultSet.getInt("from_stop_sequence")
                        + " -> "
                        + resultSet.getInt("to_stop_sequence")
                        + " của route "
                        + resultSet.getString("route_id")
                );
            }
        }
    }

    private static long importMlitRouteGeometries(Connection connection) throws SQLException {
        String sql = """
            WITH ordered_segments AS (
                SELECT
                    p.route_id,
                    s.line_name,
                    p.from_stop_sequence,
                    p.path_order,
                    p.geom
                FROM mlit_route_path_edges p
                JOIN (
                    SELECT DISTINCT route_id, line_name
                    FROM mlit_route_stop_orders
                ) s
                    ON s.route_id = p.route_id
            ),
            segment_points AS (
                SELECT
                    route_id,
                    line_name,
                    from_stop_sequence,
                    path_order,
                    (dp).path[1] AS point_order,
                    (dp).geom AS pt_geom
                FROM ordered_segments
                CROSS JOIN LATERAL ST_DumpPoints(ST_RemoveRepeatedPoints(geom)) dp
            ),
            ordered_points AS (
                SELECT
                    route_id,
                    line_name,
                    pt_geom,
                    ROW_NUMBER() OVER (
                        PARTITION BY route_id
                        ORDER BY from_stop_sequence, path_order, point_order
                    ) AS global_order
                FROM segment_points
            )
            INSERT INTO mlit_route_geometries (
                route_id,
                operator_name,
                line_name,
                geom,
                pref_code,
                agency_id
            )
            SELECT
                route_id,
                '東京都' AS operator_name,
                line_name,
                ST_RemoveRepeatedPoints(ST_MakeLine(pt_geom ORDER BY global_order)) AS geom,
                '13' AS pref_code,
                'toei' AS agency_id
            FROM ordered_points
            GROUP BY route_id, line_name
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
    }

    private static long importToeiShapesFromRouteGeometries(Connection connection) throws SQLException {
        String sql = """
            WITH dumped_points AS (
                SELECT
                    route_id,
                    (dp).path[1] - 1 AS shape_pt_sequence,
                    (dp).geom AS pt_geom
                FROM mlit_route_geometries
                CROSS JOIN LATERAL ST_DumpPoints(ST_RemoveRepeatedPoints(geom)) dp
            ),
            lagged_points AS (
                SELECT
                    route_id,
                    shape_pt_sequence,
                    pt_geom,
                    LAG(pt_geom) OVER (
                        PARTITION BY route_id
                        ORDER BY shape_pt_sequence
                    ) AS prev_pt_geom
                FROM dumped_points
            ),
            shape_rows AS (
                SELECT
                    route_id AS shape_id,
                    ST_Y(pt_geom) AS shape_pt_lat,
                    ST_X(pt_geom) AS shape_pt_lon,
                    shape_pt_sequence,
                    SUM(
                        COALESCE(
                            ST_Distance(prev_pt_geom::geography, pt_geom::geography),
                            0.0
                        )
                    ) OVER (
                        PARTITION BY route_id
                        ORDER BY shape_pt_sequence
                        ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                    )::numeric(12,3) AS shape_dist_traveled
                FROM lagged_points
            )
            INSERT INTO gtfs_train_shapes (
                shape_id,
                shape_pt_lat,
                shape_pt_lon,
                shape_pt_sequence,
                shape_dist_traveled,
                cumulative_distance_m,
                pref_code,
                agency_id
            )
            SELECT
                shape_id,
                shape_pt_lat,
                shape_pt_lon,
                shape_pt_sequence,
                shape_dist_traveled,
                shape_dist_traveled AS cumulative_distance_m,
                '13' AS pref_code,
                'toei' AS agency_id
            FROM shape_rows
            ORDER BY shape_id, shape_pt_sequence
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        }
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

    private static boolean tableHasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        try (ResultSet columns = metaData.getColumns(null, null, tableName, columnName)) {
            return columns.next();
        }
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

    private static String toLineStringWkt(List<double[]> coordinates) {
        StringJoiner joiner = new StringJoiner(", ", "LINESTRING(", ")");
        for (double[] coordinate : coordinates) {
            joiner.add(coordinate[0] + " " + coordinate[1]);
        }
        return joiner.toString();
    }

    private static List<double[]> parseWktLineString(String wkt) {
        if (wkt == null || !wkt.startsWith("LINESTRING")) {
            return List.of();
        }

        Matcher matcher = WKT_COORDINATE_PATTERN.matcher(wkt);
        List<double[]> coordinates = new ArrayList<>();
        while (matcher.find()) {
            coordinates.add(new double[]{
                Double.parseDouble(matcher.group(1)),
                Double.parseDouble(matcher.group(2))
            });
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
