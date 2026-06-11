# Thảo Luận Kỹ Thuật: Luồng Xây Dựng Shape GTFS

## Mục tiêu
Xây dựng `shape` cho từng tuyến Toei từ dữ liệu đường sắt raw MLIT và thứ tự stop trong GTFS, để shape cuối cùng đi theo đúng trình tự stop của tuyến.

## Dữ liệu hiện có

- `mlit_raw_rail_segments`
  - dữ liệu raw đường sắt MLIT
  - hình học lưu trong `geom` dạng `LINESTRING`
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

### Bước 1.1: Merge segment theo tuyến

- Sau khi lưu raw xong, dùng thứ tự stop của GTFS để nối các segment theo đúng hướng tuyến.
- Tạo một geometry cấp tuyến thay vì chỉ từng segment rời.
- Lưu geometry đã dựng lại vào một bảng khác, ví dụ:
  - `mlit_route_geometries`
  - hoặc tên tương đương theo convention của dự án
- Bảng này sẽ được dùng cho bước snap stop và build shape.
- Các tuyến có `MultiLineString` raw sẽ được chuẩn hóa về `LineString` theo stop order.

### Bước 2: Xây dựng thứ tự stop từ GTFS

- Với mỗi tuyến:
  - tìm các trip thuộc tuyến đó
  - chọn một trip đại diện hoặc pattern stop đã gộp
  - trích ra danh sách stop theo thứ tự từ `stop_times`

### Bước 3: Tạo geometry của tuyến

- Gộp các segment raw MLIT thuộc cùng một tuyến.
- Dùng stop order GTFS để sắp xếp lại điểm chạy của tuyến.
- Tạo ra một `LINESTRING` cấp tuyến đã được dựng lại theo thứ tự stop.

### Bước 4: Snap stop lên geometry tuyến

- Với mỗi stop trong thứ tự tuyến:
  - chuyển lat/lon stop thành point
  - snap point đó lên geometry tuyến
  - tính measure / khoảng cách dọc theo tuyến

### Bước 5: Tạo các dòng shape

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
- Luồng import ổn định, có thể chạy lại.
- Mapping rõ ràng từ route -> stop order -> shape points.
