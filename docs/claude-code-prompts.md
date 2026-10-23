# Prompt cho Claude Code CLI

Dùng cùng [roadmap.md](roadmap.md). Claude Code tự đọc `CLAUDE.md` ở thư mục gốc (cách build bằng Docker, quy ước code),
nên prompt chỉ cần nói làm mục nào và các yêu cầu riêng của mục đó.

## Chuẩn bị mỗi lần

```powershell
cd "D:\Clone github\ReconHub"
git checkout main
git pull
claude
```

- Mỗi phiên làm **một mục** của roadmap. Xong mục thì thoát (`/exit`) và mở phiên mới cho mục sau.
- Với mục lớn, bật **plan mode** trước khi dán prompt (nhấn `Shift+Tab` cho tới khi thấy "plan mode"): Claude chỉ đọc
  code và đưa kế hoạch, chưa sửa gì. Đọc kỹ kế hoạch, sửa nếu cần, rồi mới cho làm.
- Khi Claude xin quyền chạy lệnh `docker`, `git`, `scripts/verify.sh`: cho phép. Lệnh xóa dữ liệu (`docker compose down -v`,
  `git reset --hard`, `rm -rf`) thì đọc kỹ trước khi đồng ý.
- Sau khi Claude push nhánh: vào GitHub tạo PR, chờ CI xanh, đọc diff, merge. Rồi trên máy:
  ```powershell
  git checkout main
  git pull
  docker compose up -d --build
  docker builder prune -a -f
  ```

## Prompt mẫu (dùng cho mọi mục)

Thay `<X.Y>` và `<ten-nhanh>`:

```text
Đọc CLAUDE.md và mục <X.Y> trong docs/roadmap.md, rồi thực hiện mục đó.

Cách làm:
1. Tạo nhánh feature/<ten-nhanh> từ main mới nhất.
2. Đọc code liên quan trước. Trình bày kế hoạch ngắn: module nào đổi, migration Flyway mới (số V tiếp theo),
   API mới hoặc đổi, màn hình nào, test nào. Dừng lại chờ tôi đồng ý.
3. Làm từng bước nhỏ, mỗi bước một commit. Logic mới phải có test.
4. Kiểm tra bằng scripts/verify.sh. Nếu đụng tới khớp tiền, dữ liệu mẫu hoặc schema: build lại stack, reset dữ liệu demo
   theo CLAUDE.md, chạy scripts/verify.sh eval (độ chính xác phải giữ 100%) và docker builder prune -a -f.
5. Cập nhật tài liệu liên quan, đánh dấu [x] cho mục <X.Y> trong docs/roadmap.md.
6. Push nhánh. Không merge, không push vào main.
7. Kết thúc bằng tóm tắt: đã làm gì; các bước để tôi tự kiểm tra trên giao diện; những gì chưa làm hoặc còn rủi ro.
```

## Prompt cho các mục nên làm trước

### 1.1 Cảnh báo job lỗi biết tự tắt

```text
Đọc CLAUDE.md và mục 1.1 trong docs/roadmap.md, rồi thực hiện mục đó theo các bước sau.
1. Tạo nhánh feature/batch-alert-ack từ main mới nhất.
2. Đọc code liên quan, trình bày kế hoạch, dừng lại chờ tôi đồng ý.
3. Làm từng bước nhỏ, mỗi bước một commit, có test.
4. Kiểm tra bằng scripts/verify.sh; build lại api, batch, web và xem trên giao diện.
5. Cập nhật tài liệu, đánh dấu [x] mục 1.1 trong roadmap.
6. Push nhánh, không merge.
7. Tóm tắt kết quả và các bước để tôi tự kiểm tra.

Yêu cầu riêng:
- Tiêu chí "đã có file thay thế" viết ở một chỗ dùng chung cho Tổng quan, Theo dõi batch và AI giải thích
  (hiện nằm trong BatchController.details).
- "Đánh dấu đã xử lý" bắt buộc nhập ghi chú; ai bấm, lúc nào phải hiện trong chi tiết lần chạy.
- Làm cả trong src/api/mock.ts để chế độ dữ liệu mẫu vẫn chạy.
- Kết quả mong đợi với dữ liệu demo: Tổng quan không còn cảnh báo GHTK_COD_20261021.csv.
```

### 1.2 Biểu phí có phiên bản (nên bật plan mode)

```text
Đọc CLAUDE.md và mục 1.2 trong docs/roadmap.md, rồi thực hiện mục đó.
Tạo nhánh feature/fee-schedules từ main mới nhất.

Trước khi viết code, cho tôi xem và chờ tôi duyệt:
- thiết kế bảng (migration mới) và cách biểu diễn tham số biểu phí cho cả 5 nguồn GHN, GHTK, Shopee, TikTok, MoMo;
- cách FeeCalculator chọn phiên bản theo ngày giao dịch;
- luồng đề xuất, duyệt và job feeRevalidationJob.

Yêu cầu riêng:
- Dữ liệu ban đầu phải cho kết quả y hệt FeeCalculator hiện tại: viết test so sánh trên nhiều đơn mẫu TRƯỚC khi thay code.
- Kế toán đề xuất; chỉ APPROVER hoặc ADMIN duyệt; người đề xuất không tự duyệt (giống ghi giảm).
- feeRevalidationJob chỉ đóng ngoại lệ khi phí thực trừ bằng đúng phí theo phiên bản mới, ghi lý do vào lịch sử ngoại lệ.
- Gợi ý AI "UPDATE_FEE_RULE" có nút tạo đề xuất điền sẵn tham số.
- Reset dữ liệu demo và chạy scripts/verify.sh eval sau khi xong (phải giữ 100%).

Làm từng bước nhỏ có commit và test, cập nhật tài liệu, đánh dấu [x] mục 1.2, push nhánh (không merge),
rồi tóm tắt và hướng dẫn tôi tự thử: tạo phiên bản GHN "+2.000 ₫ cho COD trên 1,5 triệu" và xem ngoại lệ tự đóng.
```

### 3.1 Đo chất lượng gợi ý AI

```text
Đọc CLAUDE.md và mục 3.1 trong docs/roadmap.md, rồi thực hiện mục đó.
Tạo nhánh feature/ai-eval từ main mới nhất. Trình bày kế hoạch và chờ tôi đồng ý trước khi code.

Yêu cầu riêng:
- Script chạy được khi không có ANTHROPIC_API_KEY (chỉ đo chế độ quy tắc); không thay đổi dữ liệu ngoài việc làm mới gợi ý.
- Lấy đúng số token từ phản hồi của Spring AI thay cho giá trị 0 hiện nay; không đổi model hay cách gọi khác.
- Giá token đặt trong cấu hình, không viết cứng; chưa có giá thì chỉ báo số token.
- Chạy script được bằng Docker (thêm vào image datagen hoặc scripts/verify.sh), không cần Python trên máy.
- Kết quả ghi vào docs/ai-evaluation.md kèm ngày chạy, model, số mẫu, cách chạy lại.

Commit từng bước có test, đánh dấu [x] mục 3.1, push nhánh (không merge), tóm tắt và hướng dẫn tôi chạy với API key của tôi.
```

### 2.1 Mã VietQR trên đơn hàng

```text
Đọc CLAUDE.md và mục 2.1 trong docs/roadmap.md, rồi thực hiện mục đó.
Tạo nhánh feature/vietqr từ main mới nhất. Trình bày kế hoạch và chờ tôi đồng ý trước khi code.

Yêu cầu riêng:
- Tự sinh chuỗi VietQR theo chuẩn EMVCo của NAPAS, không gọi dịch vụ ngoài. Ghi nguồn tài liệu chuẩn bạn dựa vào.
- BIN ngân hàng, số tài khoản, tên chủ tài khoản đặt trong .env (thêm vào .env.example).
- Hiện QR trên trang đơn của legacy OMS (Java 17, Struts 6) và trả qua API của ReconHub.
- Thêm tham số "tỷ lệ khách dùng QR" cho generator; mặc định giữ nguyên dữ liệu hiện tại để CI không đổi.
  Đo eval_matching trước và sau (reset dữ liệu demo với tham số mới), ghi số liệu vào tài liệu.
- Unit test cho chuỗi VietQR và CRC.

Commit từng bước, đánh dấu [x] mục 2.1, push nhánh (không merge). Trong tóm tắt, ghi các bước để tôi quét thử QR bằng app ngân hàng.
```

## Khi muốn tự code để học (gợi ý cho 1.2, 4.1, 4.4)

```text
Tôi muốn tự viết mục <X.Y> trong docs/roadmap.md để học. Đọc CLAUDE.md và mục đó, rồi:
1. Giải thích phần code hiện có liên quan: file nào, luồng chạy ra sao, vì sao thiết kế như vậy.
2. Chia việc thành các bước nhỏ; mỗi bước nói rõ tôi cần viết gì, ở file nào, và cách kiểm tra. Chưa viết code thay tôi.
3. Mỗi khi tôi báo xong một bước: review diff của tôi, chỉ ra lỗi và chỗ chưa theo quy ước, chạy scripts/verify.sh.
Chỉ viết code khi tôi nói "viết giúp phần này".
```

## Review trước khi tạo PR

```text
Review toàn bộ thay đổi của nhánh hiện tại so với main: lỗi logic, thiếu test, vi phạm quy ước trong CLAUDE.md,
rủi ro bảo mật và phân quyền, chỗ có thể làm khớp tiền sai. Liệt kê theo mức độ nghiêm trọng, kèm file và dòng. Chưa sửa gì.
```
