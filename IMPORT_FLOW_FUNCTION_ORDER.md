# Thứ Tự Hàm Import MLIT -> Shape

## Mục đích

Tài liệu này ghi lại thứ tự các hàm đang được gọi trong luồng import, tính từ lúc bắt đầu nạp dữ liệu MLIT cho tới khi hoàn tất `gtfs_train_shapes`.

File code chính:

- [GtfsImporter.java](/Users/account/Desktop/works/FPT/train/java-postgres-cli/src/main/java/com/fpt/train/dbcli/GtfsImporter.java:1)

## Điểm bắt đầu

Hàm entry của toàn bộ luồng là:

- `GtfsImporter.importAll(Connection connection)`

Hàm này được gọi từ:

- [App.java](/Users/account/Desktop/works/FPT/train/java-postgres-cli/src/main/java/com/fpt/train/dbcli/App.java:1)

## Thứ tự hàm và tác dụng

### 1. `importRawMlitRailSegments(connection, resolveMlitRailGeoJson())`

Tác dụng:

- Đọc file GeoJSON MLIT
- Parse từng feature đường sắt
- Insert vào bảng `mlit_raw_rail_segments`

Kết quả:

- Có dữ liệu raw segment của MLIT trong database
- Đây là nguồn dữ liệu geometry gốc

### 2. `importRouteStopOrders(connection)`

Tác dụng:

- Đọc GTFS `trips`, `stop_times`, `stops`
- Chọn `trip` đại diện cho từng `route`
- Lưu thứ tự stop của route vào `mlit_route_stop_orders`

Kết quả:

- Có danh sách stop theo thứ tự của từng tuyến
- Đây là trục logic để route đi đúng hướng

### 3. `importNetworkNodes(connection)`

Tác dụng:

- Lấy endpoint của các raw segment MLIT
- Biến các endpoint thành node graph
- Insert vào `mlit_network_nodes`

Kết quả:

- Có danh sách node của rail network

### 4. `importNetworkEdges(connection)`

Tác dụng:

- Lấy từng raw segment MLIT
- Xác định `source_node` và `target_node`
- Tính chiều dài segment
- Insert vào `mlit_network_edges`

Kết quả:

- Có graph edge của network đường sắt

### 5. `importStopNodeMap(connection)`

Tác dụng:

- Với mỗi stop trong `mlit_route_stop_orders`
- Tìm node gần nhất trong `mlit_network_nodes`
- Lưu mapping vào `mlit_stop_node_map`

Kết quả:

- Mỗi stop được gắn vào một node cụ thể trên network

### 6. `importRoutePathEdges(connection)`

Tác dụng:

- Lấy từng cặp stop liên tiếp của route
- Từ node của stop đầu và stop cuối
- Tìm path ngắn nhất trên `mlit_network_edges`
- Lưu các edge của path vào `mlit_route_path_edges`

Kết quả:

- Có đường đi thực tế trên network cho từng đoạn `stop_i -> stop_i+1`

### 7. `ensureRoutePathCoverage(connection)`

Tác dụng:

- Kiểm tra tất cả cặp stop liên tiếp đã có path hay chưa
- Nếu thiếu path ở bất kỳ đoạn nào thì ném lỗi

Kết quả:

- Đảm bảo route không bị đứt đoạn trước khi ghép geometry

Lưu ý:

- Hàm này được gọi bên trong `importRoutePathEdges(...)`

### 8. `importMlitRouteGeometries(connection)`

Tác dụng:

- Lấy toàn bộ `mlit_route_path_edges`
- Ghép các edge theo đúng thứ tự stop
- Tạo geometry cấp tuyến
- Insert vào `mlit_route_geometries`

Kết quả:

- Có `LINESTRING` của từng tuyến, bám theo network MLIT

### 9. `importToeiShapesFromRouteGeometries(connection)`

Tác dụng:

- Đọc `LINESTRING` từ `mlit_route_geometries`
- Tách thành các point shape bằng `ST_DumpPoints`
- Tính:
  - `shape_pt_sequence`
  - `shape_dist_traveled`
  - `cumulative_distance_m`
- Gán thêm:
  - `pref_code = '13'`
  - `agency_id = 'toei'`
- Insert vào `gtfs_train_shapes`

Kết quả:

- Hoàn tất bảng shape GTFS để hiển thị hoặc export

## Tóm tắt ngắn

Thứ tự gọi hiện tại:

1. `importRawMlitRailSegments`
2. `importRouteStopOrders`
3. `importNetworkNodes`
4. `importNetworkEdges`
5. `importStopNodeMap`
6. `importRoutePathEdges`
7. `ensureRoutePathCoverage`
8. `importMlitRouteGeometries`
9. `importToeiShapesFromRouteGeometries`

## Ý nghĩa tổng thể

- MLIT cung cấp geometry gốc
- GTFS cung cấp thứ tự stop
- Network pathing nối hai nguồn này lại với nhau
- Kết quả cuối cùng là `shape` vừa đúng tuyến, vừa bám theo polyline thật của MLIT
