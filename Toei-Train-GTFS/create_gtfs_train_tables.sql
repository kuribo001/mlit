BEGIN;

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS mlit_raw_rail_segments CASCADE;
DROP TABLE IF EXISTS mlit_route_geometries CASCADE;
DROP TABLE IF EXISTS gtfs_train_stop_times CASCADE;
DROP TABLE IF EXISTS gtfs_train_shapes CASCADE;
DROP TABLE IF EXISTS gtfs_train_trips CASCADE;
DROP TABLE IF EXISTS gtfs_train_stops CASCADE;
DROP TABLE IF EXISTS gtfs_train_routes CASCADE;
DROP TABLE IF EXISTS gtfs_train_calendar_dates CASCADE;
DROP TABLE IF EXISTS gtfs_train_calendar CASCADE;
DROP TABLE IF EXISTS gtfs_train_fare_rules CASCADE;
DROP TABLE IF EXISTS gtfs_train_fare_attributes CASCADE;
DROP TABLE IF EXISTS gtfs_train_translations CASCADE;
DROP TABLE IF EXISTS gtfs_train_feed_info CASCADE;
DROP TABLE IF EXISTS gtfs_train_agency CASCADE;

CREATE TABLE gtfs_train_agency (
    agency_id TEXT PRIMARY KEY,
    agency_name TEXT,
    agency_url TEXT,
    agency_timezone TEXT,
    agency_lang TEXT,
    agency_phone TEXT,
    agency_fare_url TEXT,
    agency_email TEXT
);

CREATE TABLE mlit_raw_rail_segments (
    id BIGSERIAL PRIMARY KEY,
    operator_name TEXT,
    line_name TEXT,
    geom geometry(LineString, 4326)
);

CREATE TABLE mlit_route_geometries (
    route_id TEXT PRIMARY KEY,
    operator_name TEXT,
    line_name TEXT,
    geom geometry(Geometry, 4326)
);

CREATE TABLE gtfs_train_calendar (
    service_id TEXT PRIMARY KEY,
    monday SMALLINT,
    tuesday SMALLINT,
    wednesday SMALLINT,
    thursday SMALLINT,
    friday SMALLINT,
    saturday SMALLINT,
    sunday SMALLINT,
    start_date TEXT,
    end_date TEXT
);

CREATE TABLE gtfs_train_calendar_dates (
    service_id TEXT,
    date TEXT,
    exception_type SMALLINT,
    PRIMARY KEY (service_id, date)
);

CREATE TABLE gtfs_train_fare_attributes (
    fare_id TEXT PRIMARY KEY,
    price NUMERIC(10,2),
    currency_type TEXT,
    payment_method SMALLINT,
    transfers SMALLINT,
    agency_id TEXT,
    transfer_duration INTEGER
);

CREATE TABLE gtfs_train_fare_rules (
    fare_id TEXT,
    route_id TEXT,
    origin_id TEXT,
    destination_id TEXT,
    contains_id TEXT
);

CREATE TABLE gtfs_train_feed_info (
    feed_publisher_name TEXT,
    feed_publisher_url TEXT,
    feed_lang TEXT,
    feed_start_date TEXT,
    feed_end_date TEXT,
    feed_version TEXT,
    feed_contact_email TEXT,
    feed_contact_url TEXT
);

CREATE TABLE gtfs_train_routes (
    route_id TEXT PRIMARY KEY,
    agency_id TEXT,
    route_short_name TEXT,
    route_long_name TEXT,
    route_desc TEXT,
    route_type INTEGER,
    route_url TEXT,
    route_color TEXT,
    route_text_color TEXT
);

CREATE TABLE gtfs_train_stop_times (
    trip_id TEXT,
    arrival_time TEXT,
    departure_time TEXT,
    stop_id TEXT,
    stop_sequence INTEGER,
    stop_headsign TEXT,
    pickup_type SMALLINT,
    drop_off_type SMALLINT,
    shape_dist_traveled NUMERIC(12,3),
    timepoint SMALLINT,
    PRIMARY KEY (trip_id, stop_sequence)
);

CREATE TABLE gtfs_train_shapes (
    shape_id TEXT,
    shape_pt_lat DOUBLE PRECISION,
    shape_pt_lon DOUBLE PRECISION,
    shape_pt_sequence INTEGER,
    shape_dist_traveled NUMERIC(12,3),
    cumulative_distance_m NUMERIC(12,3),
    pref_code TEXT,
    agency_id TEXT,
    PRIMARY KEY (shape_id, shape_pt_sequence)
);

CREATE TABLE gtfs_train_stops (
    stop_id TEXT PRIMARY KEY,
    stop_code TEXT,
    stop_name TEXT,
    stop_desc TEXT,
    stop_lat DOUBLE PRECISION,
    stop_lon DOUBLE PRECISION,
    zone_id TEXT,
    stop_url TEXT,
    location_type SMALLINT,
    parent_station TEXT,
    stop_timezone TEXT,
    wheelchair_boarding SMALLINT
);

CREATE TABLE gtfs_train_translations (
    table_name TEXT,
    field_name TEXT,
    field_value TEXT,
    language TEXT,
    translation TEXT
);

CREATE TABLE gtfs_train_trips (
    route_id TEXT,
    service_id TEXT,
    trip_id TEXT PRIMARY KEY,
    trip_headsign TEXT,
    trip_short_name TEXT,
    direction_id SMALLINT,
    block_id TEXT,
    shape_id TEXT,
    wheelchair_accessible SMALLINT,
    bikes_allowed SMALLINT
);

COMMIT;
