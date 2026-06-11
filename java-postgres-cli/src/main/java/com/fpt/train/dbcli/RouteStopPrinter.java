package com.fpt.train.dbcli;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class RouteStopPrinter {

    private static final String LONGEST_ROUTE_STOPS_SQL = """
        WITH trip_lengths AS (
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
                stop_count,
                max_stop_sequence,
                ROW_NUMBER() OVER (
                    PARTITION BY route_id
                    ORDER BY stop_count DESC, max_stop_sequence DESC, trip_id
                ) AS rn
            FROM trip_lengths
        )
        SELECT
            r.route_id,
            r.route_long_name,
            rt.trip_id,
            st.stop_sequence,
            s.stop_id,
            s.stop_name
        FROM ranked_trips rt
        JOIN gtfs_train_routes r
            ON r.route_id = rt.route_id
        JOIN gtfs_train_stop_times st
            ON st.trip_id = rt.trip_id
        JOIN gtfs_train_stops s
            ON s.stop_id = st.stop_id
        WHERE rt.rn = 1
        ORDER BY r.route_id, st.stop_sequence, s.stop_id
        """;

    private RouteStopPrinter() {
    }

    public static void printLongestRouteStops(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(LONGEST_ROUTE_STOPS_SQL);
             ResultSet resultSet = statement.executeQuery()) {

            String currentRouteId = null;
            String currentRouteName = null;
            String currentTripId = null;

            while (resultSet.next()) {
                String routeId = resultSet.getString("route_id");
                String routeName = resultSet.getString("route_long_name");
                String tripId = resultSet.getString("trip_id");

                if (!routeId.equals(currentRouteId)) {
                    if (currentRouteId != null) {
                        System.out.println();
                    }

                    currentRouteId = routeId;
                    currentRouteName = routeName;
                    currentTripId = tripId;
                    System.out.printf("Route %s - %s (trip %s)%n", currentRouteId, currentRouteName, currentTripId);
                }

                System.out.printf(
                    "  %3d. %s (%s)%n",
                    resultSet.getInt("stop_sequence"),
                    resultSet.getString("stop_name"),
                    resultSet.getString("stop_id")
                );
            }
        }
    }
}
