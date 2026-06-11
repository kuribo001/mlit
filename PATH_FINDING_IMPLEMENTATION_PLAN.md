# Kế Hoạch Implement: Tìm Path Giữa Hai Stop Liên Tiếp

## Mục tiêu

Xây dựng `shape` mượt hơn bằng cách đi theo network segment của MLIT, thay vì nối thẳng các stop đã snap với nhau.

## Vấn đề của cách hiện tại

- `mlit_route_geometries` hiện được dựng từ chuỗi stop đã snap.
- Điều này tạo ra đường đi đúng tuyến nhưng không bám theo polyline chi tiết của MLIT.
- Khi hiển thị lên bản đồ, shape sẽ bị gãy khúc ở các đoạn cong.

## Hướng triển khai

### 1. Tạo stop order đại diện cho từng route

- Dùng GTFS để lấy một stop pattern đại diện cho từng route.
- Trước mắt:
  - chọn `trip` dài nhất của route
  - dùng stop order của `trip` đó

### 2. Dựng network từ raw MLIT

- Mỗi raw segment MLIT trở thành một `edge`.
- Hai đầu mút của segment trở thành `node`.
- Tạo các bảng:
  - `mlit_network_nodes`
  - `mlit_network_edges`

### 3. Snap stop GTFS vào network

- Với mỗi stop trong route:
  - tìm `node` gần nhất trong cùng route
  - lưu mapping vào bảng `mlit_stop_node_map`

### 4. Tìm path giữa từng cặp stop liên tiếp

- Với mỗi cặp stop liên tiếp `A -> B`:
  - lấy `from_node`
  - lấy `to_node`
  - duyệt graph để tìm path ngắn nhất theo tổng chiều dài edge
- Dùng recursive SQL trên graph của từng route.
- Chỉ tìm giữa **hai stop liên tiếp**, không tìm path toàn tuyến một lần.

## Vì sao chia theo từng cặp stop

- Không gian tìm kiếm nhỏ hơn nhiều.
- Dễ kiểm soát hơn ở các tuyến có branch hoặc loop.
- Có thể debug từng đoạn `stop_i -> stop_i+1`.

### 5. Ghép các path con thành route geometry

- Sau khi có path cho từng cặp stop:
  - ghép các edge theo đúng thứ tự stop
  - dựng lại `mlit_route_geometries`
- Kết quả mong muốn:
  - `LINESTRING` đi theo network MLIT thật
  - đúng hướng và đúng thứ tự stop

### 6. Tạo `gtfs_train_shapes`

- Tách point từ `mlit_route_geometries`
- Tính:
  - `shape_pt_sequence`
  - `shape_dist_traveled`
  - `cumulative_distance_m`
- Insert vào `gtfs_train_shapes`

## Bảng phụ trợ đề xuất

- `mlit_route_stop_orders`
  - lưu stop order đại diện của từng route
- `mlit_network_nodes`
  - node của graph theo route
- `mlit_network_edges`
  - edge của graph theo route
- `mlit_stop_node_map`
  - stop -> node gần nhất
- `mlit_route_path_edges`
  - các edge thuộc path của từng cặp stop

## Giới hạn hiện tại

- Route 4 vẫn đang dùng stop pattern đại diện từ `trip` dài nhất.
- Path finding hiện là heuristic theo graph endpoint của raw segment.
- Nếu raw segment không chia topology đủ sạch ở một số đoạn, có thể cần bước split network chi tiết hơn.

## Kết quả kỳ vọng

- Shape bám theo polyline MLIT thật.
- Đường hiển thị lên map mượt hơn.
- Vẫn giữ đúng thứ tự stop của GTFS.
