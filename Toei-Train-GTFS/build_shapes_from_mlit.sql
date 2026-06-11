-- Build derived GTFS shapes for Toei from:
-- - GTFS tables imported from Toei-Train-GTFS
-- - mlit_rail_segments(id, operator_name, line_name, gem)
--
-- Assumptions:
-- - The GTFS table names are exactly: routes, trips, stop_times, stops, shapes
-- - mlit_rail_segments.gem is a LINESTRING geometry in EPSG:4326
--   If your actual geometry column is named geom, replace gem -> geom below.
-- - trips.shape_id is currently empty in the Toei feed
--
-- Important:
-- - Route 4 (大江戸線) is a lasso/loop-like service in this GTFS feed.
--   The same stop_id (429 = 都庁前) appears twice in many trips.
--   For that route, this script builds shapes from ordered GTFS stop points
--   instead of MLIT merged geometry, to avoid accidental closure like a->...->e->a.
-- - Routes 1,2,3,5,6 are built from MLIT geometry plus GTFS stop order.

BEGIN;

-- Optional but recommended if you regenerate these shapes repeatedly.
-- Comment this out if you want to keep existing derived Toei shapes.
DELETE FROM shapes
WHERE shape_id LIKE 'toei_r%';

DROP TABLE IF EXISTS tmp_toei_trip_patterns;
CREATE TEMP TABLE tmp_toei_trip_patterns ON COMMIT DROP AS
SELECT
  t.trip_id,
  t.route_id::text AS route_id,
  t.direction_id::text AS direction_id,
  md5(string_agg(st.stop_id::text, '>' ORDER BY st.stop_sequence)) AS pattern_hash,
  array_agg(st.stop_id::text ORDER BY st.stop_sequence) AS stop_ids
FROM trips t
JOIN stop_times st
  ON st.trip_id = t.trip_id
GROUP BY
  t.trip_id,
  t.route_id,
  t.direction_id;

DROP TABLE IF EXISTS tmp_toei_shape_patterns;
CREATE TEMP TABLE tmp_toei_shape_patterns ON COMMIT DROP AS
SELECT
  route_id,
  direction_id,
  pattern_hash,
  stop_ids,
  count(*) AS trip_count,
  format('toei_r%s_d%s_%s', route_id, direction_id, substr(pattern_hash, 1, 12)) AS shape_id
FROM tmp_toei_trip_patterns
GROUP BY
  route_id,
  direction_id,
  pattern_hash,
  stop_ids;

DROP TABLE IF EXISTS tmp_toei_route_geom;
CREATE TEMP TABLE tmp_toei_route_geom ON COMMIT DROP AS
WITH route_map(route_id, mlit_line_name) AS (
  VALUES
    ('1', '1号線浅草線'),
    ('2', '6号線三田線'),
    ('3', '10号線新宿線'),
    ('4', '12号線大江戸線'),
    ('5', '日暮里・舎人ライナー'),
    ('6', '荒川線')
)
SELECT
  rm.route_id,
  ST_LineMerge(ST_UnaryUnion(ST_Collect(s.gem))) AS route_geom
FROM route_map rm
JOIN mlit_rail_segments s
  ON s.operator_name = '東京都'
 AND s.line_name = rm.mlit_line_name
WHERE rm.route_id <> '4'
GROUP BY rm.route_id;

-- Expected result here:
-- route_id 1,2,3,5,6 should normally be ST_LineString.
-- If one of them is ST_MultiLineString, inspect that route before inserting shapes.
-- SELECT route_id, ST_GeometryType(route_geom) FROM tmp_toei_route_geom ORDER BY route_id;

DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM tmp_toei_route_geom
    WHERE ST_GeometryType(route_geom) <> 'ST_LineString'
  ) THEN
    RAISE EXCEPTION
      'One of route_id 1,2,3,5,6 did not merge to ST_LineString. Inspect tmp_toei_route_geom before building shapes.';
  END IF;
END
$$;

WITH simple_pattern_stops AS (
  SELECT
    sp.shape_id,
    sp.route_id,
    u.ordinality::integer AS stop_sequence,
    u.stop_id::text AS stop_id,
    rg.route_geom,
    ST_SetSRID(
      ST_MakePoint(s.stop_lon::double precision, s.stop_lat::double precision),
      4326
    ) AS stop_geom
  FROM tmp_toei_shape_patterns sp
  JOIN tmp_toei_route_geom rg
    ON rg.route_id = sp.route_id
  CROSS JOIN LATERAL unnest(sp.stop_ids) WITH ORDINALITY AS u(stop_id, ordinality)
  JOIN stops s
    ON s.stop_id::text = u.stop_id::text
  WHERE sp.route_id <> '4'
),
simple_measures AS (
  SELECT
    shape_id,
    route_id,
    stop_sequence,
    stop_id,
    route_geom,
    stop_geom,
    ST_ClosestPoint(route_geom, stop_geom) AS snapped_stop_geom,
    ST_LineLocatePoint(route_geom, ST_ClosestPoint(route_geom, stop_geom)) AS measure
  FROM simple_pattern_stops
),
simple_endpoints AS (
  SELECT
    shape_id,
    route_id,
    min(stop_sequence) AS first_seq,
    max(stop_sequence) AS last_seq
  FROM simple_measures
  GROUP BY
    shape_id,
    route_id
),
simple_shape_geom AS (
  SELECT
    e.shape_id,
    e.route_id,
    CASE
      WHEN a.measure <= b.measure THEN
        ST_LineSubstring(a.route_geom, a.measure, b.measure)
      ELSE
        ST_Reverse(ST_LineSubstring(a.route_geom, b.measure, a.measure))
    END AS shape_geom
  FROM simple_endpoints e
  JOIN simple_measures a
    ON a.shape_id = e.shape_id
   AND a.stop_sequence = e.first_seq
  JOIN simple_measures b
    ON b.shape_id = e.shape_id
   AND b.stop_sequence = e.last_seq
),
simple_dumped_points AS (
  SELECT
    g.shape_id,
    (dp).path[1] - 1 AS shape_pt_sequence,
    (dp).geom AS pt_geom
  FROM simple_shape_geom g
  CROSS JOIN LATERAL ST_DumpPoints(ST_RemoveRepeatedPoints(g.shape_geom)) dp
),
simple_lagged_points AS (
  SELECT
    shape_id,
    shape_pt_sequence,
    pt_geom,
    lag(pt_geom) OVER (
      PARTITION BY shape_id
      ORDER BY shape_pt_sequence
    ) AS prev_pt_geom
  FROM simple_dumped_points
),
simple_shape_rows AS (
  SELECT
    shape_id,
    ST_Y(pt_geom) AS shape_pt_lat,
    ST_X(pt_geom) AS shape_pt_lon,
    shape_pt_sequence,
    sum(
      COALESCE(ST_Distance(prev_pt_geom::geography, pt_geom::geography), 0.0)
    ) OVER (
      PARTITION BY shape_id
      ORDER BY shape_pt_sequence
      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS shape_dist_traveled
  FROM simple_lagged_points
)
INSERT INTO shapes (
  shape_id,
  shape_pt_lat,
  shape_pt_lon,
  shape_pt_sequence,
  shape_dist_traveled
)
SELECT
  shape_id,
  shape_pt_lat,
  shape_pt_lon,
  shape_pt_sequence,
  shape_dist_traveled
FROM simple_shape_rows
ORDER BY
  shape_id,
  shape_pt_sequence;

WITH route4_pattern_stops AS (
  SELECT
    sp.shape_id,
    (u.ordinality - 1)::integer AS shape_pt_sequence,
    ST_SetSRID(
      ST_MakePoint(s.stop_lon::double precision, s.stop_lat::double precision),
      4326
    ) AS pt_geom
  FROM tmp_toei_shape_patterns sp
  CROSS JOIN LATERAL unnest(sp.stop_ids) WITH ORDINALITY AS u(stop_id, ordinality)
  JOIN stops s
    ON s.stop_id::text = u.stop_id::text
  WHERE sp.route_id = '4'
),
route4_lagged_points AS (
  SELECT
    shape_id,
    shape_pt_sequence,
    pt_geom,
    lag(pt_geom) OVER (
      PARTITION BY shape_id
      ORDER BY shape_pt_sequence
    ) AS prev_pt_geom
  FROM route4_pattern_stops
),
route4_shape_rows AS (
  SELECT
    shape_id,
    ST_Y(pt_geom) AS shape_pt_lat,
    ST_X(pt_geom) AS shape_pt_lon,
    shape_pt_sequence,
    sum(
      COALESCE(ST_Distance(prev_pt_geom::geography, pt_geom::geography), 0.0)
    ) OVER (
      PARTITION BY shape_id
      ORDER BY shape_pt_sequence
      ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
    ) AS shape_dist_traveled
  FROM route4_lagged_points
)
INSERT INTO shapes (
  shape_id,
  shape_pt_lat,
  shape_pt_lon,
  shape_pt_sequence,
  shape_dist_traveled
)
SELECT
  shape_id,
  shape_pt_lat,
  shape_pt_lon,
  shape_pt_sequence,
  shape_dist_traveled
FROM route4_shape_rows
ORDER BY
  shape_id,
  shape_pt_sequence;

-- Back-fill trips.shape_id from the derived pattern hash.
UPDATE trips t
SET shape_id = sp.shape_id
FROM tmp_toei_trip_patterns tp
JOIN tmp_toei_shape_patterns sp
  ON sp.route_id = tp.route_id
 AND sp.direction_id = tp.direction_id
 AND sp.pattern_hash = tp.pattern_hash
WHERE t.trip_id = tp.trip_id;

COMMIT;

-- Validation queries:
--
-- 1) Number of derived shapes:
-- SELECT count(DISTINCT shape_id) FROM shapes WHERE shape_id LIKE 'toei_r%';
--
-- 2) Every Toei trip should now have shape_id:
-- SELECT count(*) FROM trips WHERE coalesce(shape_id, '') = '';
--
-- 3) Preview derived shapes:
-- SELECT shape_id, count(*) AS point_count
-- FROM shapes
-- WHERE shape_id LIKE 'toei_r%'
-- GROUP BY shape_id
-- ORDER BY shape_id;
--
-- 4) Inspect route 4 patterns:
-- SELECT shape_id, min(shape_pt_sequence), max(shape_pt_sequence), count(*)
-- FROM shapes
-- WHERE shape_id LIKE 'toei_r4_%'
-- GROUP BY shape_id
-- ORDER BY shape_id;
