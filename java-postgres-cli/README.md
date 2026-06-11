# Java Postgres CLI

Project Java thuần dùng JDBC để kết nối PostgreSQL và chạy query từ command line.

Khi app khởi chạy, nó sẽ:

1. chạy file schema `../Toei-Train-GTFS/create_gtfs_train_tables.sql`
2. tạo lại toàn bộ bảng GTFS
3. đọc từng file `.txt`, parse CSV trong Java, rồi batch `INSERT` vào các bảng `gtfs_train_*`
4. đọc `../mlit_tokyo/N02-22_tokyo_railroadsection.geojson`, lọc 6 tuyến Toei và nạp vào `gtfs_train_shapes`
5. sau đó mới chạy query

## Yêu cầu

- Java 21+
- Internet ở lần build đầu tiên để Gradle tải dependency

## Cấu hình database

Có 2 cách:

1. Dùng `DB_URL` trực tiếp
2. Hoặc dùng `DB_HOST`, `DB_PORT`, `DB_NAME`

Biến môi trường hỗ trợ:

- `DB_URL`
- `DB_HOST`
- `DB_PORT` mặc định `5432`
- `DB_NAME`
- `DB_USER`
- `DB_PASSWORD`
- `DB_PARAMS` tùy chọn, ví dụ `sslmode=disable`

Ngoài ra project cũng đọc fallback từ biến chuẩn PostgreSQL:

- `PGHOST`
- `PGPORT`
- `PGDATABASE`
- `PGUSER`
- `PGPASSWORD`

Project cũng tự đọc file `.env` trong thư mục `java-postgres-cli`. Biến môi trường hệ thống sẽ override giá trị trong `.env`.

Biến môi trường tùy chọn cho import:

- `GTFS_DIR`: đường dẫn tới thư mục chứa các file GTFS `.txt`
- `GTFS_SCHEMA_SQL`: đường dẫn tới file schema SQL để tạo bảng trước khi import
- `MLIT_RAIL_GEOJSON`: đường dẫn tới file MLIT Tokyo railroad GeoJSON dùng để nạp `gtfs_train_shapes`

## Chạy bằng Gradle wrapper

Windows `cmd`:

```bat
cd java-postgres-cli
set DB_HOST=localhost
set DB_PORT=5432
set DB_NAME=postgres
set DB_USER=postgres
set DB_PASSWORD=postgres
gradlew.bat run
```

Lần chạy đầu tiên, `gradlew` và `gradlew.bat` sẽ tự tải Gradle distribution vào thư mục `.gradle-dist/`.

Chạy query tùy ý:

```bat
gradlew.bat run --args="SELECT NOW() AS server_time, current_database() AS db_name"
```

macOS/Linux:

```bash
cd java-postgres-cli
export DB_HOST=localhost
export DB_PORT=5432
export DB_NAME=postgres
export DB_USER=postgres
export DB_PASSWORD=postgres
./gradlew run
```

## Tạo file jar chạy trực tiếp

```bat
gradlew.bat fatJar
java -jar build\libs\java-postgres-cli-1.0.0-all.jar
```

macOS/Linux:

```bash
./gradlew fatJar
java -jar build/libs/java-postgres-cli-1.0.0-all.jar
```

## Query mặc định

Nếu không truyền SQL, app sẽ chạy query:

```sql
SELECT current_database() AS database_name,
       current_user AS user_name,
       NOW() AS server_time,
       version() AS server_version
```
