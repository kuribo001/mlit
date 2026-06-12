# Giải Thích Cách Build CZML Realtime Cho Trip

## Mục tiêu

Mục tiêu của bước này là tạo ra một file CZML để Cesium có thể:

- hiển thị một `trip` như một thực thể động
- cho thực thể đó chạy theo thời gian thực mô phỏng
- sử dụng dữ liệu GTFS hiện có: `trips`, `stop_times`, `stops`
- bám theo hình học tuyến đã build trước đó trong `gtfs_train_shapes`

File output hiện tại:

- `java-postgres-cli/gtfs_train_trips_<routeId>.czml`

Ví dụ:

- `java-postgres-cli/gtfs_train_trips_4.czml`

## Các bảng dữ liệu được dùng

Để build CZML realtime cho trip, code hiện dùng các bảng sau:

- `gtfs_train_trips`
- `gtfs_train_stop_times`
- `gtfs_train_stops`
- `gtfs_train_shapes`

Ý nghĩa:

- `gtfs_train_trips`: lấy metadata của trip như `trip_id`, `route_id`, `service_id`, `trip_headsign`
- `gtfs_train_stop_times`: lấy thứ tự dừng và thời gian đi qua từng stop
- `gtfs_train_stops`: lấy tọa độ của từng stop
- `gtfs_train_shapes`: lấy chuỗi point hình học của route để làm đường chạy

## Flow tổng thể

Flow hiện tại trong `CzmlExporter.exportTripsForRoute(...)` là:

1. lấy `shape` của route từ `gtfs_train_shapes`
2. lấy một `trip` cụ thể từ `gtfs_train_trips`
3. join sang `gtfs_train_stop_times` và `gtfs_train_stops`
4. dựng danh sách stop theo `stop_sequence`
5. chuyển thời gian GTFS `HH:mm:ss` thành số giây từ đầu ngày
6. map từng stop lên một vị trí gần nhất trên `shape`
7. nội suy các điểm trung gian giữa hai stop liên tiếp theo hình học của shape
8. tạo packet CZML có `clock`, `availability`, `position`, `path`, `point`, `label`

## Hàm chính trong code

File code:

- `java-postgres-cli/src/main/java/com/fpt/train/dbcli/CzmlExporter.java`

Các hàm quan trọng:

- `exportTripsForRoute(...)`
  - entry point để xuất CZML realtime cho trip
- `loadRouteShapePacket(...)`
  - lấy shape của route từ `gtfs_train_shapes`
- `loadRealtimeTripPacket(...)`
  - đọc trip + stop_times + stops từ database
- `populateSampledPositions(...)`
  - build timeline vị trí theo thời gian
- `appendSegmentSamples(...)`
  - nội suy các point trên shape giữa 2 stop liên tiếp
- `buildRealtimeTripCzml(...)`
  - dựng JSON CZML hoàn chỉnh
- `appendRealtimeTripPacket(...)`
  - dựng packet CZML cho thực thể trip

## Cách lấy shape của route

`shape` được lấy từ `gtfs_train_shapes`.

Mỗi row trong bảng này là một point:

- `shape_id`
- `shape_pt_lon`
- `shape_pt_lat`
- `shape_pt_sequence`

Code gom toàn bộ các point của cùng `shape_id`, rồi tạo thành một danh sách:

- `lon`
- `lat`
- `height = 0`

Danh sách này là polyline nền để trip chạy theo.

## Cách lấy stop theo trip

Trip realtime hiện tại đang dùng một `trip_id` cố định:

- `431114A0`

Query join logic:

- từ `gtfs_train_trips`
- join `gtfs_train_stop_times`
- join `gtfs_train_stops`

Sắp xếp theo:

- `st.stop_sequence`

Kết quả mỗi stop có:

- `stop_sequence`
- `arrival_time`
- `departure_time`
- `stop_id`
- `stop_name`
- `stop_lat`
- `stop_lon`

## Cách chuyển thời gian GTFS sang thời gian CZML

GTFS lưu thời gian dạng:

- `HH:mm:ss`

Code parse thời gian này thành:

- số giây tính từ đầu ngày

Ví dụ:

- `11:18:00` -> `40680`

Sau đó code dùng ngày hiện tại theo múi giờ Tokyo:

- `Asia/Tokyo`

để tạo:

- `startTime`
- `endTime`
- `availability`

Ví dụ:

- `2026-06-12T02:18:00Z/2026-06-12T03:42:00Z`

Lý do dùng UTC trong CZML:

- CZML/ Cesium làm việc tốt với ISO timestamp dạng UTC

## Cách map stop lên shape

Đây là bước quan trọng nhất.

Trip không chạy bằng cách nối thẳng `stop -> stop`. Thay vào đó:

1. shape của route đã có sẵn dưới dạng nhiều point
2. mỗi stop sẽ được map tới point gần nhất trên shape
3. sau đó trip chạy dọc theo shape giữa hai stop liên tiếp

Code hiện làm như sau:

- stop đầu tiên:
  - tìm point gần nhất trên toàn bộ shape
- stop thứ hai:
  - tìm point gần nhất trên toàn bộ shape
  - từ đó suy ra chiều chạy:
    - tăng dần theo sequence point
    - hoặc giảm dần theo sequence point
- các stop tiếp theo:
  - chỉ tìm trong phần shape phù hợp với chiều đang chạy

Mục tiêu của bước này:

- tránh việc trip nhảy lung tung trên shape
- giữ cho thứ tự chạy bám theo hình học tuyến

## Cách nội suy giữa hai stop

Sau khi đã biết:

- stop A ứng với `shapeIndexA`
- stop B ứng với `shapeIndexB`

code lấy toàn bộ point shape nằm giữa hai index đó.

Nếu trip chạy xuôi:

- lấy từ `shapeIndexA -> shapeIndexB`

Nếu trip chạy ngược:

- lấy từ `shapeIndexA -> shapeIndexB` theo thứ tự giảm dần

Sau đó:

1. tính tổng chiều dài tương đối của segment
2. tính khoảng cách tích lũy tại từng point
3. phân bổ thời gian từ `stop A` đến `stop B` theo tỷ lệ chiều dài

Kết quả:

- mỗi point trên shape sẽ có một `offset second`
- offset này được tính từ `startTime` của trip

## Cấu trúc CZML được sinh ra

File CZML realtime hiện có 2 packet chính:

1. `document`
2. packet của trip

### 1. Packet `document`

Packet này chứa:

- `id = "document"`
- `name`
- `version`
- `clock`

`clock` quy định:

- `interval`
- `currentTime`
- `multiplier`
- `range`
- `step`

Ví dụ:

- `multiplier: 60`

nghĩa là thời gian chạy nhanh gấp 60 lần thời gian thực.

### 2. Packet trip

Packet trip hiện chứa:

- `id`
- `name`
- `availability`
- `properties`
- `position`
- `point`
- `path`
- `label`
- `orientation`
- `viewFrom`
- `description`

#### `position`

Đây là phần quan trọng nhất.

Nó dùng:

- `epoch`
- `cartographicDegrees`

Trong `cartographicDegrees`, dữ liệu đi theo bộ 4 giá trị:

- `offsetSeconds`
- `lon`
- `lat`
- `height`

Cesium sẽ nội suy theo thời gian giữa các mốc này.

#### `path`

`path` cho phép nhìn thấy vệt đường mà trip đã đi qua.

#### `point`

`point` là marker động đại diện cho tàu.

#### `label`

`label` hiển thị text như:

- `431114A0 都庁前`

## Tại sao file này là “realtime mô phỏng”

File CZML hiện tại chưa dùng GTFS-RT thật.

Nó là mô phỏng thời gian chạy dựa trên:

- lịch stop trong `stop_times`
- tọa độ stop trong `stops`
- hình học route trong `gtfs_train_shapes`

Nên cách hiểu đúng là:

- `scheduled playback`
- không phải realtime thực tế từ feed live

## Giới hạn hiện tại

Hiện trạng này chạy tốt cho bài toán demo/visualization cơ bản, nhưng vẫn có các giới hạn:

- route phức tạp như tuyến `4` có thể có loop hoặc nhiều pattern
- shape của route có thể không trùng hoàn toàn với pattern của trip
- việc map `stop -> nearest shape point` là heuristic, chưa phải rail matching hoàn hảo
- một số đoạn cuối có thể bị giữ tại một điểm quá lâu nếu pattern trip và shape không khớp hoàn toàn

## Hướng cải thiện tiếp theo

Nếu muốn nâng chất lượng trip realtime hơn, các bước nên làm tiếp là:

1. build shape riêng cho từng trip pattern thay vì dùng chung shape của route
2. map `stop_times` lên `shape_dist_traveled`
3. dùng `stop_times` + `shape_dist_traveled` để nội suy chính xác hơn
4. xử lý riêng tuyến `4` theo nhiều pattern thay vì shape route tổng hợp
5. nếu có GTFS-RT thật:
   - lấy vị trí live
   - cập nhật CZML động từ feed realtime

## Cách chạy hiện tại

Lệnh:

```bash
cd java-postgres-cli
./gradlew run --args="4"
```

Kết quả:

- xuất route tĩnh:
  - `gtfs_train_shapes_4.czml`
- xuất trip realtime mô phỏng:
  - `gtfs_train_trips_4.czml`

## Kết luận

Phần build CZML vừa rồi đang làm đúng theo hướng:

- dùng `stop_times` để tạo timeline
- dùng `stops` để xác định các mốc dừng
- dùng `shape` để giữ hình học tuyến
- xuất ra một thực thể CZML có thể chạy theo thời gian trong Cesium

Đây là một bước trung gian tốt để:

- kiểm tra hình học tuyến
- kiểm tra thứ tự stop
- mô phỏng chuyển động của tàu trên bản đồ

Trước khi đi tiếp sang:

- trip pattern chính xác hơn
- hoặc realtime live thật
