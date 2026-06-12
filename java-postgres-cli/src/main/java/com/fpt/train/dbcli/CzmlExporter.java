package com.fpt.train.dbcli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CzmlExporter {

    private static final String CZML_OUTPUT_ENV = "CZML_OUTPUT";
    private static final Path DEFAULT_OUTPUT = Path.of("gtfs_train_shapes.czml");
    private static final String FIXED_TRIP_ID = "431114A0";
    private static final ZoneId TOKYO_ZONE = ZoneId.of("Asia/Tokyo");
    private static final DateTimeFormatter CZML_TIME_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private CzmlExporter() {
    }

    public static Path exportShapes(Connection connection) throws SQLException {
        return exportShapes(connection, null);
    }

    public static Path exportShapes(Connection connection, String routeId) throws SQLException {
        Map<String, ShapePacket> packets = loadShapePackets(connection, routeId);
        Path output = resolveOutputPath(routeId);

        try {
            Files.writeString(output, buildCzml(packets), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không ghi được file CZML: " + output.toAbsolutePath(), e);
        }

        return output.toAbsolutePath();
    }

    public static Path exportTripsForRoute(Connection connection, String routeId) throws SQLException {
        ShapePacket routePacket = loadRouteShapePacket(connection, routeId);
        RealtimeTripPacket tripPacket = loadRealtimeTripPacket(connection, routeId, routePacket);
        Path output = resolveTripOutputPath(routeId);

        try {
            Files.writeString(output, buildRealtimeTripCzml(routePacket.shapeId, tripPacket), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Không ghi được file CZML trip: " + output.toAbsolutePath(), e);
        }

        return output.toAbsolutePath();
    }

    private static Path resolveOutputPath(String routeId) {
        String configured = System.getenv(CZML_OUTPUT_ENV);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim());
        }
        if (routeId != null && !routeId.isBlank()) {
            return Path.of("gtfs_train_shapes_" + routeId + ".czml");
        }
        return DEFAULT_OUTPUT;
    }

    private static Path resolveTripOutputPath(String routeId) {
        return Path.of("gtfs_train_trips_" + routeId + ".czml");
    }

    private static Map<String, ShapePacket> loadShapePackets(Connection connection, String routeId) throws SQLException {
        String sql = """
            SELECT
                shape_id,
                shape_pt_lon,
                shape_pt_lat,
                agency_id,
                pref_code
            FROM gtfs_train_shapes
            """ + (routeId != null && !routeId.isBlank() ? "WHERE shape_id = ?\n" : "") + """
            ORDER BY shape_id, shape_pt_sequence
            """;

        Map<String, ShapePacket> packets = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (routeId != null && !routeId.isBlank()) {
                statement.setString(1, routeId);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String shapeId = resultSet.getString("shape_id");
                ShapePacket packet = packets.computeIfAbsent(shapeId, CzmlExporter::newShapePacket);
                packet.positions.add(resultSet.getDouble("shape_pt_lon"));
                packet.positions.add(resultSet.getDouble("shape_pt_lat"));
                packet.positions.add(0.0);
                if (packet.agencyId == null) {
                    packet.agencyId = resultSet.getString("agency_id");
                }
                if (packet.prefCode == null) {
                    packet.prefCode = resultSet.getString("pref_code");
                }
            }
            }
        }

        return packets;
    }

    private static ShapePacket newShapePacket(String shapeId) {
        ShapePacket packet = new ShapePacket();
        packet.shapeId = shapeId;
        packet.name = "Toei Route " + shapeId;
        packet.color = colorForShape(shapeId);
        return packet;
    }

    private static ShapePacket loadRouteShapePacket(Connection connection, String routeId) throws SQLException {
        Map<String, ShapePacket> packets = loadShapePackets(connection, routeId);
        ShapePacket packet = packets.get(routeId);
        if (packet == null) {
            throw new SQLException("Không tìm thấy shape cho route_id = " + routeId);
        }
        return packet;
    }

    private static RealtimeTripPacket loadRealtimeTripPacket(Connection connection, String routeId, ShapePacket routePacket) throws SQLException {
        String sql = """
            SELECT
                t.trip_id,
                t.route_id,
                t.service_id,
                t.trip_headsign,
                t.direction_id,
                t.shape_id,
                st.stop_sequence,
                st.arrival_time,
                st.departure_time,
                s.stop_id,
                s.stop_name,
                s.stop_lat,
                s.stop_lon
            FROM gtfs_train_trips t
            JOIN gtfs_train_stop_times st
                ON st.trip_id = t.trip_id
            JOIN gtfs_train_stops s
                ON s.stop_id = st.stop_id
            WHERE t.route_id = ?
              AND t.trip_id = ?
            ORDER BY st.stop_sequence
            """;

        RealtimeTripPacket trip = null;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, routeId);
            statement.setString(2, FIXED_TRIP_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (trip == null) {
                        trip = new RealtimeTripPacket();
                        trip.tripId = resultSet.getString("trip_id");
                        trip.routeId = resultSet.getString("route_id");
                        trip.serviceId = resultSet.getString("service_id");
                        trip.tripHeadsign = resultSet.getString("trip_headsign");
                        trip.directionId = resultSet.getObject("direction_id") == null ? null : resultSet.getInt("direction_id");
                        trip.shapeId = resultSet.getString("shape_id");
                        if (trip.shapeId == null || trip.shapeId.isBlank()) {
                            trip.shapeId = routePacket.shapeId;
                        }
                        trip.color = routePacket.color;
                    }
                    StopTimePoint stop = new StopTimePoint();
                    stop.sequence = resultSet.getInt("stop_sequence");
                    stop.arrivalTime = resultSet.getString("arrival_time");
                    stop.departureTime = resultSet.getString("departure_time");
                    stop.stopId = resultSet.getString("stop_id");
                    stop.stopName = resultSet.getString("stop_name");
                    stop.lat = resultSet.getDouble("stop_lat");
                    stop.lon = resultSet.getDouble("stop_lon");
                    trip.stops.add(stop);
                }
            }
        }

        if (trip == null) {
            throw new SQLException("Không tìm thấy stop_times cho trip_id = " + FIXED_TRIP_ID);
        }

        populateSampledPositions(routePacket, trip);
        return trip;
    }

    private static int[] colorForShape(String shapeId) {
        return switch (shapeId) {
            case "1" -> new int[]{220, 46, 57, 255};
            case "2" -> new int[]{0, 121, 193, 255};
            case "3" -> new int[]{142, 178, 0, 255};
            case "4" -> new int[]{204, 0, 102, 255};
            case "5" -> new int[]{0, 174, 239, 255};
            case "6" -> new int[]{255, 102, 0, 255};
            default -> new int[]{255, 255, 255, 255};
        };
    }

    private static String buildCzml(Map<String, ShapePacket> packets) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"id\": \"document\",\n");
        sb.append("    \"name\": \"Toei Train Shapes\",\n");
        sb.append("    \"version\": \"1.0\"\n");
        sb.append("  }");

        for (ShapePacket packet : packets.values()) {
            sb.append(",\n");
            appendPacket(sb, packet);
        }

        sb.append("\n]\n");
        return sb.toString();
    }

    private static void appendPacket(StringBuilder sb, ShapePacket packet) {
        sb.append("  {\n");
        sb.append("    \"id\": ").append(json(packet.shapeId)).append(",\n");
        sb.append("    \"name\": ").append(json(packet.name)).append(",\n");
        sb.append("    \"properties\": {\n");
        sb.append("      \"shape_id\": ").append(json(packet.shapeId)).append(",\n");
        sb.append("      \"agency_id\": ").append(json(packet.agencyId)).append(",\n");
        sb.append("      \"pref_code\": ").append(json(packet.prefCode)).append("\n");
        sb.append("    },\n");
        sb.append("    \"polyline\": {\n");
        sb.append("      \"positions\": {\n");
        sb.append("        \"cartographicDegrees\": [");
        for (int i = 0; i < packet.positions.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatNumber(packet.positions.get(i)));
        }
        sb.append("]\n");
        sb.append("      },\n");
        sb.append("      \"width\": 4,\n");
        sb.append("      \"material\": {\n");
        sb.append("        \"solidColor\": {\n");
        sb.append("          \"color\": {\n");
        sb.append("            \"rgba\": [")
            .append(packet.color[0]).append(", ")
            .append(packet.color[1]).append(", ")
            .append(packet.color[2]).append(", ")
            .append(packet.color[3]).append("]\n");
        sb.append("          }\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    }\n");
        sb.append("  }");
    }

    private static String buildRealtimeTripCzml(String routeId, RealtimeTripPacket tripPacket) {
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"id\": \"document\",\n");
        sb.append("    \"name\": ").append(json("Toei Route " + routeId + " Trips")).append(",\n");
        sb.append("    \"version\": \"1.0\",\n");
        sb.append("    \"clock\": {\n");
        sb.append("      \"interval\": ").append(json(tripPacket.availability)).append(",\n");
        sb.append("      \"currentTime\": ").append(json(tripPacket.startTime)).append(",\n");
        sb.append("      \"multiplier\": 60,\n");
        sb.append("      \"range\": \"LOOP_STOP\",\n");
        sb.append("      \"step\": \"SYSTEM_CLOCK_MULTIPLIER\"\n");
        sb.append("    }\n");
        sb.append("  }");
        sb.append(",\n");
        appendRealtimeTripPacket(sb, tripPacket);

        sb.append("\n]\n");
        return sb.toString();
    }

    private static void appendRealtimeTripPacket(StringBuilder sb, RealtimeTripPacket trip) {
        sb.append("  {\n");
        sb.append("    \"id\": ").append(json(trip.tripId)).append(",\n");
        sb.append("    \"name\": ").append(json("Trip " + trip.tripId)).append(",\n");
        sb.append("    \"availability\": ").append(json(trip.availability)).append(",\n");
        sb.append("    \"properties\": {\n");
        appendProperty(sb, "trip_id", json(trip.tripId), true);
        appendProperty(sb, "route_id", json(trip.routeId), true);
        appendProperty(sb, "service_id", json(trip.serviceId), true);
        appendProperty(sb, "trip_headsign", json(trip.tripHeadsign), true);
        appendProperty(sb, "direction_id", trip.directionId == null ? null : Integer.toString(trip.directionId), true);
        appendProperty(sb, "shape_id", json(trip.shapeId), false);
        sb.append("    },\n");
        sb.append("    \"position\": {\n");
        sb.append("      \"epoch\": ").append(json(trip.startTime)).append(",\n");
        sb.append("      \"interpolationAlgorithm\": \"LINEAR\",\n");
        sb.append("      \"interpolationDegree\": 1,\n");
        sb.append("      \"cartographicDegrees\": [");
        for (int i = 0; i < trip.sampledPositions.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(formatNumber(trip.sampledPositions.get(i)));
        }
        sb.append("]\n");
        sb.append("    },\n");
        sb.append("    \"point\": {\n");
        sb.append("      \"pixelSize\": 10,\n");
        sb.append("      \"color\": {\n");
        sb.append("        \"rgba\": [")
            .append(trip.color[0]).append(", ")
            .append(trip.color[1]).append(", ")
            .append(trip.color[2]).append(", 255]\n");
        sb.append("      },\n");
        sb.append("      \"outlineColor\": {\n");
        sb.append("        \"rgba\": [255, 255, 255, 255]\n");
        sb.append("      },\n");
        sb.append("      \"outlineWidth\": 2\n");
        sb.append("    },\n");
        sb.append("    \"path\": {\n");
        sb.append("      \"resolution\": 5,\n");
        sb.append("      \"leadTime\": 0,\n");
        sb.append("      \"trailTime\": ").append(formatNumber(trip.durationSeconds)).append(",\n");
        sb.append("      \"width\": 4,\n");
        sb.append("      \"material\": {\n");
        sb.append("        \"solidColor\": {\n");
        sb.append("          \"color\": {\n");
        sb.append("            \"rgba\": [")
            .append(trip.color[0]).append(", ")
            .append(trip.color[1]).append(", ")
            .append(trip.color[2]).append(", 180]\n");
        sb.append("          }\n");
        sb.append("        }\n");
        sb.append("      }\n");
        sb.append("    },\n");
        sb.append("    \"label\": {\n");
        sb.append("      \"text\": ").append(json(trip.tripId + " " + safeText(trip.tripHeadsign))).append(",\n");
        sb.append("      \"fillColor\": {\n");
        sb.append("        \"rgba\": [255, 255, 255, 255]\n");
        sb.append("      },\n");
        sb.append("      \"showBackground\": true,\n");
        sb.append("      \"backgroundColor\": {\n");
        sb.append("        \"rgba\": [0, 0, 0, 180]\n");
        sb.append("      },\n");
        sb.append("      \"horizontalOrigin\": \"LEFT\",\n");
        sb.append("      \"pixelOffset\": {\n");
        sb.append("        \"cartesian2\": [12, 0]\n");
        sb.append("      }\n");
        sb.append("    },\n");
        sb.append("    \"orientation\": {\n");
        sb.append("      \"velocityReference\": \"#position\"\n");
        sb.append("    },\n");
        sb.append("    \"viewFrom\": {\n");
        sb.append("      \"cartesian\": [0, -1200, 700]\n");
        sb.append("    },\n");
        sb.append("    \"description\": ").append(json(buildTripDescription(trip))).append("\n");
        sb.append("  }");
    }

    private static void populateSampledPositions(ShapePacket routePacket, RealtimeTripPacket trip) {
        List<ShapePoint> shapePoints = toShapePoints(routePacket.positions);
        if (shapePoints.isEmpty()) {
            throw new IllegalStateException("Shape không có point để build realtime trip.");
        }

        LocalDate serviceDate = LocalDate.now(TOKYO_ZONE);
        int lastOffset = -1;
        for (StopTimePoint stop : trip.stops) {
            stop.secondsFromStartOfDay = parseGtfsTime(stop.departureTime != null ? stop.departureTime : stop.arrivalTime);
            if (stop.secondsFromStartOfDay <= lastOffset) {
                stop.secondsFromStartOfDay = lastOffset + 30;
            }
            lastOffset = stop.secondsFromStartOfDay;
        }

        StopTimePoint firstStop = trip.stops.get(0);
        firstStop.shapeIndex = findNearestShapeIndex(shapePoints, firstStop.lon, firstStop.lat, 0, true, false);
        boolean ascending = true;
        if (trip.stops.size() > 1) {
            StopTimePoint secondStop = trip.stops.get(1);
            secondStop.shapeIndex = findNearestShapeIndex(shapePoints, secondStop.lon, secondStop.lat, 0, true, false);
            ascending = secondStop.shapeIndex >= firstStop.shapeIndex;
        }
        int previousIndex = firstStop.shapeIndex;
        for (int i = 1; i < trip.stops.size(); i++) {
            StopTimePoint stop = trip.stops.get(i);
            if (i == 1 && stop.shapeIndex >= 0) {
                previousIndex = stop.shapeIndex;
                continue;
            }
            stop.shapeIndex = findNearestShapeIndex(shapePoints, stop.lon, stop.lat, previousIndex, ascending, true);
            previousIndex = stop.shapeIndex;
        }

        Instant start = toInstant(serviceDate, trip.stops.get(0).secondsFromStartOfDay);
        Instant end = toInstant(serviceDate, trip.stops.get(trip.stops.size() - 1).secondsFromStartOfDay);
        trip.startTime = CZML_TIME_FORMATTER.format(start);
        trip.endTime = CZML_TIME_FORMATTER.format(end);
        trip.availability = trip.startTime + "/" + trip.endTime;
        trip.durationSeconds = Math.max(1, trip.stops.get(trip.stops.size() - 1).secondsFromStartOfDay - trip.stops.get(0).secondsFromStartOfDay);
        int tripStartSeconds = trip.stops.get(0).secondsFromStartOfDay;

        for (int i = 0; i < trip.stops.size() - 1; i++) {
            StopTimePoint from = trip.stops.get(i);
            StopTimePoint to = trip.stops.get(i + 1);
            appendSegmentSamples(trip.sampledPositions, shapePoints, from, to, tripStartSeconds);
        }

        StopTimePoint lastStop = trip.stops.get(trip.stops.size() - 1);
        ShapePoint lastPoint = shapePoints.get(lastStop.shapeIndex);
        appendSample(trip.sampledPositions, lastStop.secondsFromStartOfDay - tripStartSeconds, lastPoint.lon, lastPoint.lat);
    }

    private static void appendSegmentSamples(List<Double> samples, List<ShapePoint> shapePoints, StopTimePoint from, StopTimePoint to, int tripStartSeconds) {
        List<ShapePoint> segment = new ArrayList<>();
        if (from.shapeIndex <= to.shapeIndex) {
            for (int i = from.shapeIndex; i <= to.shapeIndex; i++) {
                segment.add(shapePoints.get(i));
            }
        } else {
            for (int i = from.shapeIndex; i >= to.shapeIndex; i--) {
                segment.add(shapePoints.get(i));
            }
        }
        double[] cumulative = new double[segment.size()];
        for (int i = 1; i < segment.size(); i++) {
            cumulative[i] = cumulative[i - 1] + distance(segment.get(i - 1), segment.get(i));
        }
        double totalDistance = cumulative[cumulative.length - 1];
        int segmentDuration = Math.max(1, to.secondsFromStartOfDay - from.secondsFromStartOfDay);
        int baseOffset = from.secondsFromStartOfDay - tripStartSeconds;

        for (int i = 0; i < segment.size(); i++) {
            double ratio = totalDistance == 0.0 ? 0.0 : cumulative[i] / totalDistance;
            int offset = baseOffset + (int) Math.round(segmentDuration * ratio);
            ShapePoint point = segment.get(i);
            appendSample(samples, offset, point.lon, point.lat);
        }
    }

    private static void appendSample(List<Double> samples, int offsetSeconds, double lon, double lat) {
        int size = samples.size();
        if (size >= 4) {
            double previousOffset = samples.get(size - 4);
            double previousLon = samples.get(size - 3);
            double previousLat = samples.get(size - 2);
            if ((int) Math.round(previousOffset) == offsetSeconds
                && Double.compare(previousLon, lon) == 0
                && Double.compare(previousLat, lat) == 0) {
                return;
            }
        }
        samples.add((double) offsetSeconds);
        samples.add(lon);
        samples.add(lat);
        samples.add(0.0);
    }

    private static List<ShapePoint> toShapePoints(List<Double> positions) {
        List<ShapePoint> points = new ArrayList<>();
        for (int i = 0; i + 2 < positions.size(); i += 3) {
            ShapePoint point = new ShapePoint();
            point.lon = positions.get(i);
            point.lat = positions.get(i + 1);
            points.add(point);
        }
        return points;
    }

    private static int findNearestShapeIndex(List<ShapePoint> shapePoints, double lon, double lat, int startIndex, boolean ascending, boolean constrained) {
        int bestIndex = Math.max(0, Math.min(startIndex, shapePoints.size() - 1));
        double bestDistance = Double.MAX_VALUE;
        int begin = constrained ? bestIndex : 0;
        int end = constrained ? bestIndex : shapePoints.size() - 1;
        if (constrained) {
            if (ascending) {
                begin = bestIndex;
                end = shapePoints.size() - 1;
            } else {
                begin = 0;
                end = bestIndex;
            }
        }
        for (int i = begin; i <= end; i++) {
            ShapePoint point = shapePoints.get(i);
            double deltaLon = point.lon - lon;
            double deltaLat = point.lat - lat;
            double squared = deltaLon * deltaLon + deltaLat * deltaLat;
            if (squared < bestDistance) {
                bestDistance = squared;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int parseGtfsTime(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String[] parts = value.split(":");
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        int seconds = Integer.parseInt(parts[2]);
        return hours * 3600 + minutes * 60 + seconds;
    }

    private static Instant toInstant(LocalDate date, int secondsFromStartOfDay) {
        LocalDateTime localDateTime = date.atStartOfDay().plusSeconds(secondsFromStartOfDay);
        return localDateTime.atZone(TOKYO_ZONE).toInstant();
    }

    private static double distance(ShapePoint first, ShapePoint second) {
        double dx = first.lon - second.lon;
        double dy = first.lat - second.lat;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static String buildTripDescription(RealtimeTripPacket trip) {
        String headsign = safeText(trip.tripHeadsign);
        return "Trip " + trip.tripId + "\n"
            + "Route: " + safeText(trip.routeId) + "\n"
            + "Headsign: " + headsign + "\n"
            + "Service: " + safeText(trip.serviceId) + "\n"
            + "Stops: " + trip.stops.size();
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    private static void appendProperty(StringBuilder sb, String key, String value, boolean trailingComma) {
        if (value == null) {
            return;
        }
        sb.append("      ").append(json(key)).append(": ").append(value);
        if (trailingComma) {
            sb.append(",");
        }
        sb.append("\n");
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String formatNumber(double value) {
        if (Math.rint(value) == value) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private static final class ShapePacket {
        private String shapeId;
        private String name;
        private String agencyId;
        private String prefCode;
        private int[] color;
        private final List<Double> positions = new ArrayList<>();
    }

    private static final class RealtimeTripPacket {
        private String tripId;
        private String routeId;
        private String serviceId;
        private String tripHeadsign;
        private Integer directionId;
        private String shapeId;
        private int[] color;
        private String availability;
        private String startTime;
        private String endTime;
        private int durationSeconds;
        private final List<StopTimePoint> stops = new ArrayList<>();
        private final List<Double> sampledPositions = new ArrayList<>();
    }

    private static final class StopTimePoint {
        private int sequence;
        private String arrivalTime;
        private String departureTime;
        private String stopId;
        private String stopName;
        private double lat;
        private double lon;
        private int shapeIndex;
        private int secondsFromStartOfDay;
    }

    private static final class ShapePoint {
        private double lon;
        private double lat;
    }
}
