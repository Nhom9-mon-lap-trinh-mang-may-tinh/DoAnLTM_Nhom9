# MassMailer

Ứng dụng Java Swing gửi email theo hàng đợi trong RAM, dành cho một người dùng trên một máy.

## Chạy bằng NetBeans

Mở thư mục dự án `MassMailer` trong NetBeans 22 trở lên (JDK 22), sau đó Run Project. Dự án không cần thư viện ngoài.

Có thể biên dịch thủ công từ thư mục dự án:

```powershell
New-Item -ItemType Directory -Force build\classes
javac -encoding UTF-8 -d build\classes src\massmailer\*.java
java -cp build\classes massmailer.MassMailer
```

## Sử dụng

1. Nhập SMTP host, cổng, địa chỉ gửi và thông tin đăng nhập nếu máy chủ yêu cầu.
2. Chọn STARTTLS (thường cổng 587) hoặc TLS ngầm (thường cổng 465) theo cấu hình nhà cung cấp.
3. Nhập danh sách địa chỉ, mỗi dòng một địa chỉ, hoặc tải CSV có email ở cột đầu tiên.
4. Nhập tiêu đề và nội dung văn bản thuần; chọn giới hạn email/phút và số lần thử lại.
5. Chọn **Đưa vào Queue và gửi**. Bảng hiển thị trạng thái, số lần thử và lý do lỗi.

## Thiết kế và giới hạn hiện tại

- Mỗi người nhận là một công việc riêng; Queue FIFO dùng `LinkedBlockingQueue` trong RAM.
- Một Worker gửi tuần tự, cách nhau tối thiểu `ceil(60 / giới hạn)` giây để tôn trọng giới hạn gửi.
- Chỉ lỗi SMTP 4xx được thử lại, tối đa theo cấu hình, với thời gian chờ tăng dần. Lỗi truyền tải không rõ kết quả không tự thử lại để tránh gửi trùng khi SMTP có thể đã nhận nội dung.
- Log sự kiện và nguyên nhân lỗi được ghi qua Java Util Logging (mặc định ra console); bảng trong ứng dụng giữ chi tiết trạng thái.
- Queue mất khi ứng dụng đóng. Chưa có lưu bền vững, máy chủ ứng dụng TCP riêng, nhiều người dùng, hủy chiến dịch hay hỗ trợ HTML.
- Chỉ gửi tới người nhận đã đồng ý nhận thư; thử trước với SMTP sandbox/test account. Không dùng danh sách mua hoặc gửi thư rác.

## Giao thức SMTP

Ứng dụng hỗ trợ EHLO, STARTTLS hoặc TLS ngầm, AUTH LOGIN tùy chọn, MAIL FROM, RCPT TO và DATA. SMTP host, tài khoản và mật khẩu chỉ tồn tại trong phiên chạy, không được lưu vào tệp cấu hình. Dùng tài khoản thử nghiệm; nhà cung cấp có thể yêu cầu mật khẩu ứng dụng hoặc cơ chế xác thực khác.

## 📊 Ma trận đóng góp của các thành viên (Contribution Matrix)

| MSSV | Họ và Tên | Công việc đã thực hiện |
| :---: | :--- | :--- |
| 2380600726 | Nguyễn Huy Hoàng | - Nghiên cứu giải pháp phù hợp
- Áp dụng công nghệ cho 1 số chức năng |
| 2380600201 | Nguyễn Huỳnh Bình | - Nhóm trưởng chịu trách nhiệm đề tài nhóm
- Thực hiện lên ý tưởng, phân công,thực hiện 1 số thành phần module của sản phẩm |
| 2380600658 | Nguyễn Huy Hoàng Hiếu | - Tiến hành thử nghiệm, nghiên cứu giải pháp khác có thể thay thế giải pháp nhóm đang dùng.
 - Đề xuất cải tiến và hoàn thiện dự án |
