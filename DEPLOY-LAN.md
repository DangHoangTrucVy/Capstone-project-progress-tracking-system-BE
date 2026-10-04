# Chạy backend trên PC này, truy cập qua IP LAN

Các máy khác cùng mạng LAN/Wi-Fi gọi API tại `http://<IP-PC>:8080`.
Stack gồm Postgres + Spring Boot (profile `dev`, có dữ liệu mẫu, bật login bằng mật khẩu).

## Yêu cầu

- Docker Desktop đang chạy.
- Port 8080 trống. Nếu đang chạy `docker compose up` bản dev thì tắt trước: `docker compose down`, hoặc dùng `-Port 8082`.

## Deploy (1 lệnh)

Mở **PowerShell bằng Run as Administrator** (lần đầu, để mở firewall), `cd` vào thư mục project:

```powershell
powershell -ExecutionPolicy Bypass -File .\deploy-lan.ps1
```

Script sẽ:

1. Tự lấy IP LAN của PC.
2. Tạo `.env.lan` với mật khẩu DB + JWT secret ngẫu nhiên (chỉ tạo 1 lần), cập nhật CORS theo IP hiện tại.
3. Mở Windows Firewall cho TCP 8080.
4. Build và chạy container, chờ đến khi API sẵn sàng rồi in ra URL.

Kết quả:

- Swagger: `http://<IP-PC>:8080/swagger-ui.html`
- FE đặt API base URL = `http://<IP-PC>:8080`

## Tài khoản mẫu (profile dev)

`admin@fpt.edu.vn` / `Admin@123` và các tài khoản seed khác (hoidong, council01..05) — xem README.

## Cấu hình thêm

- FE chạy ở port/máy khác (vd `http://192.168.1.20:5173`): thêm vào `EXTRA_CORS_ORIGINS` trong `.env.lan` (cách nhau dấu phẩy, không có `/` cuối), rồi chạy lại script với `-NoBuild`.
- IP PC đổi (DHCP): chạy lại script, CORS tự cập nhật. Nên đặt IP tĩnh / DHCP reservation trên router.
- Chỉ định IP thủ công: `.\deploy-lan.ps1 -Ip 192.168.1.10`

## Lệnh thường dùng

```powershell
docker compose -p capstone-lan logs -f app                                          # xem log
docker compose -p capstone-lan -f docker-compose.lan.yml --env-file .env.lan down    # dừng
docker compose -p capstone-lan -f docker-compose.lan.yml --env-file .env.lan down -v # dừng + xoá DB
.\deploy-lan.ps1 -NoBuild                                                            # chạy lại không build
.\deploy-lan.ps1                                                                     # build lại sau khi sửa code
```

Container có `restart: unless-stopped` nên tự chạy lại khi Docker Desktop khởi động (bật "Start Docker Desktop when you sign in").

## Máy khác không vào được?

1. Trên PC: `http://localhost:8080/swagger-ui.html` có mở được không? Không → xem log.
2. Máy khác: `Test-NetConnection <IP-PC> -Port 8080` (Windows) hoặc `curl http://<IP-PC>:8080/v3/api-docs`.
3. Firewall: kiểm tra rule "Capstone Backend 8080" đã có (`Get-NetFirewallRule -DisplayName "Capstone Backend 8080"`). Phần mềm diệt virus có firewall riêng cũng cần mở.
4. Wi-Fi công cộng / trường thường bật **client isolation** — các máy không thấy nhau dù cùng mạng. Dùng hotspot/router riêng, hoặc dùng Cloudflare Tunnel (`docker-compose.prod.yml`).
5. Lỗi CORS trên trình duyệt → origin của FE chưa có trong `EXTRA_CORS_ORIGINS`.

## Tên miền miễn phí (HTTPS, truy cập từ Internet)

Dùng Cloudflare Quick Tunnel: không cần tài khoản, không cần mở port trên router.

```powershell
powershell -ExecutionPolicy Bypass -File .\tunnel-up.ps1
```

Script in ra URL dạng `https://abc-xyz.trycloudflare.com` (lưu vào `tunnel-url.txt`). FE đặt API base URL = URL đó.

Lưu ý:

- URL **đổi mỗi lần** container `cloudflared` khởi động lại (restart PC, restart Docker). Chạy lại `tunnel-up.ps1` để lấy URL mới.
- Quick Tunnel **không hỗ trợ Server-Sent Events**, nên thông báo realtime (notification stream) có thể không chạy qua URL này; các API REST bình thường vẫn chạy.
- FE deploy ở domain khác (vd Vercel) → thêm origin đó vào `EXTRA_CORS_ORIGINS` trong `.env.lan`, chạy lại `deploy-lan.ps1 -NoBuild` rồi `tunnel-up.ps1`.
- Cần URL cố định: mua tên miền, chuyển DNS về Cloudflare, rồi dùng named tunnel (`docker-compose.prod.yml`).
