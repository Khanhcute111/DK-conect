# DK Connect

Ứng dụng Android native (Kotlin + Jetpack Compose) và backend Node.js + PostgreSQL cho mạng xã hội, nhắn tin và các tính năng realtime.

## Tiến độ hiện tại

### Đã được nối trong mã nguồn
- Android có 5 khu vực: Khoảnh khắc, Tin nhắn, Bạn bè, Cuộc gọi, Cá nhân; giữ giao diện tối, theme và chế độ hiệu năng.
- Đăng ký/đăng nhập gọi API thật; token được lưu cục bộ và kiểm tra lại khi mở ứng dụng. Có thể cấu hình API URL ngay ở màn hình đăng nhập hoặc phần tuỳ biến.
- Mục Bạn bè tải danh sách từ PostgreSQL, tìm người dùng theo tên/DK ID, gửi lời mời và chấp nhận lời mời.
- Chat Android tải lịch sử từ máy chủ, gửi qua Socket.IO khi đang kết nối và tự chuyển sang REST khi offline/timeout. Tin nhắn được lưu trong PostgreSQL; `client_id` UUID giúp tránh gửi trùng khi fallback.
- Socket.IO giữ kết nối khi chuyển tab, xác thực JWT, phát tin nhắn tới các thiết bị cùng tài khoản, presence và typing cho bạn bè đã chấp nhận.
- Stories: chọn ảnh/video từ thiết bị, tải lên máy chủ và hết hạn sau 24 giờ. Video story phát khi người dùng chạm để tránh tự phát tốn tài nguyên.
- Sticker: `/stk` hoặc nút đính kèm cho phép chọn ảnh/video; backend dùng FFmpeg tạo WebP 512×512, video tối đa 5 giây. Sticker được gửi dưới dạng tin nhắn và hiển thị trong khung chat.
- Thu hồi tin nhắn: nhấn giữ tin nhắn của mình; API kiểm tra người gửi. Admin có thể bật/tắt chính sách chống thu hồi.
- Giới hạn tải media 25 MB/tệp, chỉ nhận MIME ảnh/video được hỗ trợ. Media được lưu trong thư mục upload; Docker Compose gắn volume riêng để giữ dữ liệu.
- CI workflow đã thêm cho kiểm tra backend và build APK debug trên GitHub Actions.

### Chưa được xác nhận / còn thiếu trước khi phát hành
- Chưa build APK trong môi trường này: không có Gradle, Android SDK và không có mạng để tải dependency. Workflow GitHub Actions sẽ build APK sau khi source được đẩy lên repo.
- Chưa chạy backend tích hợp với PostgreSQL thật trong môi trường này vì Docker/PostgreSQL và npm dependencies chưa có sẵn. 17 bài test hiện tại là kiểm tra cấu trúc/điều kiện mã nguồn, không phải test tích hợp.
- Chưa kiểm thử hai điện thoại thật, đo FPS/RAM/nhiệt độ hoặc kiểm thử mạng yếu.
- Gọi thoại/video chưa hoàn chỉnh: server mới có signaling relay, chưa tích hợp WebRTC client, TURN và SFU.
- Push notification FCM, chat nhóm, mã hoá đầu cuối, phân quyền media riêng tư và công cụ quản trị mở rộng vẫn cần triển khai. Media hiện được phục vụ qua URL khó đoán nhưng chưa có signed URL/kiểm tra quyền ở lớp tải tệp; không coi đây là bảo vệ nội dung riêng tư ở mức production.
- Rate limit hiện theo từng tiến trình API; nếu chạy nhiều instance cần dùng Redis/shared limiter.
- Ảnh/video upload được lưu trên ổ đĩa; khi triển khai Render cần gắn Persistent Disk tại đúng `UPLOAD_DIR` và đặt `PUBLIC_BASE_URL` là URL HTTPS của API.

## Chạy backend local

Yêu cầu Node.js 20+ và Docker Compose.

1. Tạo file môi trường:

```bash
cp server/.env.example server/.env
```

2. Đổi `JWT_SECRET` thành chuỗi ngẫu nhiên dài ít nhất 32 ký tự. Không dùng secret mẫu khi triển khai thật.
3. Chạy ở thư mục gốc:

```bash
docker compose up --build
```

API mặc định ở `http://localhost:3000`; kiểm tra `GET /health`. Đặt `PUBLIC_BASE_URL` thành URL mà điện thoại truy cập được để tạo link media chính xác.

Nếu database đã tồn tại từ bản trước, chạy lại migration an toàn bằng `docker compose exec -T db psql -U dkconnect -d dkconnect < server/schema.sql`. Nếu không dùng Docker, tự chạy PostgreSQL và đặt `DATABASE_URL`, `JWT_SECRET`, `PORT` trong `server/.env`, sau đó:

```bash
cd server
npm install
npm test
npm start
```

## Mở Android

Mở thư mục `android/` bằng Android Studio (JDK 17, Android SDK 35). Cấu hình API tại:

`android/app/src/main/java/com/dkconnect/app/data/ApiConfig.kt`

- Android Emulator: `http://10.0.2.2:3000/`
- Điện thoại thật: nhập URL API có thể truy cập ngay tại màn hình đăng nhập (ví dụ IP LAN hoặc URL HTTPS đã triển khai). `10.0.2.2` chỉ dành cho Android Emulator.
- Khi triển khai công khai, bắt buộc dùng HTTPS và cấu hình CORS theo domain thực tế.

Không đưa `.env`, JWT secret hoặc thông tin database vào GitHub.

## Cấp quyền admin

Tạo tài khoản bình thường trước, sau đó người vận hành database cấp quyền cho đúng tài khoản:

```sql
UPDATE users SET role = 'admin' WHERE email = 'email-da-xac-minh@example.com';
```

Ứng dụng không có chức năng tự nâng quyền. API quản trị kiểm tra role trực tiếp từ database.

## Kiểm thử

Tại `server/` chạy `npm test`. Bộ test hiện tại kiểm tra cấu trúc schema, các route và một số ràng buộc quan trọng ở mức mã nguồn. Đây **không phải** kiểm thử tích hợp database hoặc kiểm thử thiết bị. Android build được cấu hình trong `.github/workflows/android.yml`; tải artifact `DKConnect-debug-apk` sau khi workflow chạy thành công.
