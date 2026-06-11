# Thảo Luận Kỹ Thuật: Luồng Xây Dựng Shape GTFS

## Mục tiêu
Xây dựng `shape` cho từng tuyến Toei từ dữ liệu đường sắt raw MLIT và thứ tự stop trong GTFS, để shape cuối cùng đi theo đúng trình tự stop của tuyến.

## Dữ liệu hiện có

- `mlit_raw_rail_segments`
  - dữ liệu raw đường sắt MLIT
  - hình học lưu trong `geom` dạng `LINESTRING`
- `mlit_route_stop_orders`
  - stop order đại diện cho từng route
- `mlit_network_nodes`
  - node graph dựng từ endpoint của raw segment
- `mlit_network_edges`
  - edge graph dựng từ raw segment
- `mlit_stop_node_map`
  - mapping stop GTFS -> node gần nhất trên graph
- `mlit_route_path_edges`
  - path edge cho từng cặp stop liên tiếp
- `mlit_route_geometries`
  - geometry cấp tuyến sau khi ghép path
- `gtfs_train_routes`
  - dữ liệu master của tuyến
- `gtfs_train_trips`
  - các trip theo tuyến
- `gtfs_train_stop_times`
  - thứ tự stop của từng trip
- `gtfs_train_stops`
  - tọa độ và tên stop
- `gtfs_train_shapes`
  - bảng lưu point của shape theo chuẩn GTFS

## Ý tưởng hiện tại

1. Nạp raw MLIT vào database trước.
2. Trích xuất `LINESTRING` theo từng tuyến từ raw MLIT.
3. Dùng thứ tự stop trong GTFS để xác định hướng đi của tuyến.
4. Chuyển đường tuyến thành các point của GTFS shape.
5. Insert các point đã sắp xếp vào `gtfs_train_shapes`.

## Quy tắc shape mong muốn

- Shape phải đi qua các stop của tuyến theo đúng thứ tự.
- `shape_pt_sequence` phải đi theo hướng của tuyến.
- Mỗi tuyến nên có shape đại diện cho toàn tuyến, không phải chỉ một đoạn.
- `shape_id` nên gắn với mã tuyến.

## Flow đề xuất

### Bước 1: Import raw MLIT

- Đọc GeoJSON MLIT.
- Insert từng segment raw vào `mlit_raw_rail_segments`.
- Giữ nguyên `geom` dạng `geometry(LineString, 4326)`.

### Bước 1.1: Dựng graph segment theo tuyến

- Sau khi lưu raw xong, dựng graph từ các segment:
  - endpoint của segment -> `mlit_network_nodes`
  - segment -> `mlit_network_edges`
- Đây là network gốc để tìm path giữa các stop liên tiếp.

### Bước 2: Xây dựng thứ tự stop từ GTFS

- Với mỗi tuyến:
  - tìm các trip thuộc tuyến đó
  - chọn một trip đại diện hoặc pattern stop đã gộp
  - trích ra danh sách stop theo thứ tự từ `stop_times`
  - lưu vào `mlit_route_stop_orders`

### Bước 3: Snap stop vào graph

- Với mỗi stop trong route:
  - tìm node gần nhất trong graph cùng tuyến
  - lưu vào `mlit_stop_node_map`

### Bước 4: Tìm path giữa hai stop liên tiếp

- Với mỗi cặp stop liên tiếp:
  - lấy `from_node`
  - lấy `to_node`
  - tìm path ngắn nhất trên `mlit_network_edges`
  - lưu kết quả vào `mlit_route_path_edges`

### Bước 5: Tạo geometry cấp tuyến

- Ghép toàn bộ `mlit_route_path_edges` theo đúng thứ tự stop
- Dựng lại `mlit_route_geometries`
- Kết quả là geometry tuyến bám theo network MLIT thật, thay vì nối thẳng stop

### Bước 6: Tạo các dòng shape

- Đọc `LINESTRING` từ `mlit_route_geometries`.
- Tách `LINESTRING` thành các point theo thứ tự hình học.
- Sinh:
  - `shape_id`
  - `shape_pt_lat`
  - `shape_pt_lon`
  - `shape_pt_sequence`
  - `shape_dist_traveled`
- Insert vào `gtfs_train_shapes`.

## Câu hỏi mở

- Route 4 nên xử lý thế nào, vì nó có nhiều pattern?
- Shape nên dựa trên:
  - một trip đại diện
  - pattern stop đã gộp
  - union đầy đủ của các pattern tuyến
- Nên lưu một shape cho mỗi route hay nhiều shape theo từng pattern của route?

## Kết quả mong đợi

- Một shape theo cấp tuyến, đi đúng thứ tự stop.
- Shape bám sát polyline MLIT hơn, hiển thị mượt hơn trên map.
- Luồng import ổn định, có thể chạy lại.
- Mapping rõ ràng từ route -> stop order -> shape points.
