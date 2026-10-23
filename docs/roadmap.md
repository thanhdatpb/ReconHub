# ReconHub roadmap

Kế hoạch bổ sung sau bản 0.1 (đã chạy đủ: nạp file, khớp 4 tầng, ngoại lệ có duyệt hai người, AI theo quy tắc hoặc LLM,
Spring Batch, OMS cũ, Docker Compose, CI). Mỗi mục có **Mục tiêu**, **Phạm vi**, **Xong khi** và **Lưu ý**; thứ tự trong
từng giai đoạn là thứ tự nên làm. Đánh dấu `[x]` khi xong.

Quy tắc chung cho mọi mục: xem [CLAUDE.md](../CLAUDE.md) (cách build bằng Docker, quy ước code, định nghĩa "xong").

## Thứ tự đề xuất

1. 1.1 và 1.2: sửa chỗ đang hiển thị sai, khép kín luồng biểu phí AI đã gợi ý.
2. 3.1: có số liệu đo được cho phần AI (điểm khác biệt khi phỏng vấn, bảo vệ khóa luận).
3. 2.1: cải thiện rõ nhất chỉ số nghiệp vụ (tỷ lệ khớp Vietcombank 89,5%), đo được trước và sau.
4. Các mục còn lại theo nhu cầu. Nên tự code 1.2, 4.1 và 4.4 để có câu chuyện kể khi phỏng vấn.

## Hiện trạng khi lập kế hoạch (23/10/2026)

- Tỷ lệ khớp tự động 7 ngày: 97,7%; độ chính xác khớp tự động 100% so với ground truth.
- Vietcombank thấp nhất (89,5%) vì khách chuyển khoản không ghi mã đơn; các nguồn khác từ 97,8% trở lên.
- Cảnh báo "Job fileIngestionJob lỗi" trên Tổng quan không bao giờ tắt (xem 1.1).
- Viettel Post vẫn ở trạng thái DRAFT.

---

## Giai đoạn 1: khép kín các luồng xử lý (khoảng 1–2 tuần)

### [x] 1.1 Cảnh báo job lỗi biết tự tắt

**Mục tiêu:** Tổng quan chỉ cảnh báo những lần chạy lỗi còn cần người xử lý.

**Phạm vi**
- Lần chạy `fileIngestionJob` lỗi được coi là đã xử lý khi có file cùng nguồn, cùng ngày nghiệp vụ, nhận sau, đã nạp
  thành công (logic tìm file thay thế đã có trong `BatchController.details`, đưa về một chỗ dùng chung).
- Người dùng bấm "Đánh dấu đã xử lý" kèm ghi chú cho một lần chạy lỗi bất kỳ. Lưu ai, lúc nào, ghi chú
  (bảng mới, migration V5).
- `GET /api/batch/executions` trả thêm trạng thái xử lý (`supersededBy`, `acknowledged`); Tổng quan và Theo dõi batch
  hiển thị theo đó. Có cả ở `mock.ts`.

**Xong khi:** với dữ liệu mẫu, Tổng quan không còn cảnh báo GHTK_COD_20261021.csv; màn Theo dõi batch vẫn hiện lần
chạy đó kèm "Đã có file thay thế GHTK_COD_20261021_v2.csv"; test cho cả hai trường hợp.

### [x] 1.2 Biểu phí lưu trong database, có phiên bản

**Mục tiêu:** khi đối tác đổi phí, kế toán trưởng cập nhật biểu phí trên giao diện thay vì sửa code; các ngoại lệ sai
phí do biểu phí cũ tự đóng.

**Phạm vi**
- Bảng biểu phí theo nguồn, mỗi phiên bản có `effective_from`, tham số và người duyệt (migration mới). Dữ liệu ban
  đầu bằng đúng công thức hiện có trong `FeeCalculator`:
  GHN 22.000 + max(5.000, 0,5% COD) + 8.000 nếu vùng xa; GHTK 20.000 (đơn ≤ 900.000) hoặc 30.000;
  Shopee 4% + 4,91%; TikTok 5%; MoMo 1%; làm tròn như hiện tại.
- `FeeCalculator` lấy phiên bản có hiệu lực theo ngày giao dịch của dòng tiền.
- Màn Cấu hình nguồn: xem lịch sử, đề xuất phiên bản mới (kế toán), duyệt (APPROVER/ADMIN).
- Gợi ý AI "UPDATE_FEE_RULE" có nút "Đề xuất cập nhật biểu phí" điền sẵn tham số suy ra từ các ngoại lệ giống nhau.
- Job `feeRevalidationJob`: sau khi duyệt, kiểm tra lại các ngoại lệ FEE_DIFF đang mở từ ngày hiệu lực; khớp biểu
  phí mới thì đóng (RESOLVED) với ghi chú "Theo biểu phí phiên bản N".

**Xong khi:** trên dữ liệu mẫu, tạo phiên bản GHN "+2.000 ₫ cho COD trên 1,5 triệu từ ngày X" thì các ngoại lệ sai phí
GHN tương ứng tự đóng, các ngoại lệ sai phí khác vẫn mở; test cho chọn phiên bản theo ngày và cho job.

**Lưu ý:** chỉ đóng ngoại lệ khi phí thực trừ bằng đúng phí theo phiên bản mới; không đổi kết quả khớp tiền.

### [ ] 1.3 Khiếu nại đối tác có kết thúc

**Mục tiêu:** ngoại lệ đang chờ đối tác (IN_REVIEW) có đường đóng ngoài ghi giảm.

**Phạm vi**
- Ghi nhận phản hồi của đối tác (ngày, nội dung, kết luận: đối tác sẽ bù / đối tác từ chối / đã bù), đính kèm file
  bằng chứng (lưu dưới `/data/evidence`).
- Tiền bù về sau (dòng tiền cùng mã vận đơn/mã đơn, số tiền bằng phần thiếu) được nhận là "bù tiền" cho ngoại lệ
  AMOUNT_DIFF đang mở và đóng ngoại lệ đó, thay vì bị coi là DUPLICATE như bây giờ (xem `MatchingEngine.match`, nhánh
  "đã được thanh toán trước đó").
- Thời hạn chờ đối tác (ví dụ 7 ngày); quá hạn thì ngoại lệ quay lại "Cần xử lý".

**Xong khi:** generator có thêm vài khoản bù tiền sau SHORT_PAY; eval vẫn 100% chính xác; ngoại lệ tương ứng tự đóng.

### [ ] 1.4 Kích hoạt nguồn mới (Viettel Post)

**Mục tiêu:** thêm một đối tác mới không cần lập trình viên.

**Phạm vi**
- Luồng nhiều bước trên màn Cấu hình nguồn: tải file mẫu → hệ thống đọc tiêu đề, đoán bảng mã và định dạng ngày →
  AI đề xuất ánh xạ (đã có `MappingSuggester`) → chạy thử trên file mẫu (đọc và xử lý, không ghi dữ liệu), hiện dòng lỗi
  → kế toán trưởng duyệt → nguồn ACTIVE.
- Hỗ trợ một trường lấy từ tổng nhiều cột (phí VTP = "Tổng cước" + "Phí thu hộ").
- Generator sinh file VTP để thử.

**Xong khi:** từ dữ liệu mẫu, kích hoạt VTP qua giao diện, thả file VTP vào inbox và được nạp, khớp như các nguồn khác.

### [ ] 1.5 Quản trị người dùng và nhật ký thao tác

**Phạm vi:** ADMIN tạo, khóa, đặt lại mật khẩu, đổi vai trò; mọi người tự đổi mật khẩu; trang nhật ký gồm thao tác trên
ngoại lệ (đã có) và thay đổi cấu hình (ánh xạ, biểu phí, người dùng: bảng mới).

**Xong khi:** tài khoản bị khóa không đăng nhập được; mọi thay đổi cấu hình có trong nhật ký; test phân quyền.

---

## Giai đoạn 2: nghiệp vụ sát thực tế Việt Nam (khoảng 2–3 tuần)

### [ ] 2.1 Mã VietQR trên đơn hàng

**Mục tiêu:** khách chuyển khoản luôn kèm mã đơn, tăng tỷ lệ khớp Vietcombank.

**Phạm vi**
- Sinh chuỗi VietQR theo chuẩn EMVCo/NAPAS (BIN ngân hàng, số tài khoản cấu hình, số tiền, nội dung "DHxxxx", CRC16)
  ngay trong hệ thống, không gọi dịch vụ ngoài; ảnh QR tạo bằng ZXing.
- Hiện QR trên trang đơn của OMS cũ và trả qua API để in lên hóa đơn.
- Generator có tham số tỷ lệ khách dùng QR, để đo trước và sau bằng `eval_matching.py`.

**Xong khi:** quét QR bằng app ngân hàng thật ra đúng tài khoản, số tiền, nội dung (kiểm tra thủ công); unit test cho
chuỗi và CRC; số liệu trước/sau ghi vào tài liệu.

### [ ] 2.2 Nhận giao dịch ngân hàng ngay khi phát sinh (webhook)

**Phạm vi:** endpoint nhận giao dịch theo kiểu dịch vụ Casso/SePay, có chữ ký HMAC, chống trùng theo mã giao dịch; ghi
dòng tiền và gửi lệnh khớp qua JMS. Sao kê cuối ngày đến sau phải xác nhận các dòng đã nhận qua webhook, **không** tạo
ngoại lệ DUPLICATE cho chúng.

**Xong khi:** gửi webhook giả bằng curl → đơn được khớp trong vài giây; nạp sao kê cùng ngày không sinh ngoại lệ thừa.

### [ ] 2.3 Hàng hoàn và phí hoàn

**Phạm vi:** đơn RETURNED; dòng âm (hãng vận chuyển trừ phí hoàn); tầng khớp cho phí hoàn theo mã vận đơn; bút toán chi
phí; generator sinh dữ liệu tương ứng.

### [ ] 2.4 Khóa sổ tháng và xuất bút toán cho MISA

**Phạm vi:** kế toán trưởng khóa tháng; sau khi khóa không sửa được ngoại lệ, dòng tiền của tháng đó; xuất bút toán ra
Excel theo mẫu import của MISA SME (lấy file mẫu thật từ phần mềm để đối chiếu cột, không đoán).

### [ ] 2.5 Báo cáo Excel và thông báo

**Phạm vi:** xuất Excel (Apache POI) cho tuổi nợ và danh sách ngoại lệ; thông báo khi ngoại lệ quá hạn hoặc file đối tác
về trễ qua email (Mailpit khi chạy local) và Telegram bot (token trong `.env`).

---

## Giai đoạn 3: AI-native (khoảng 2–3 tuần)

### [ ] 3.1 Đo chất lượng gợi ý AI

**Mục tiêu:** có số liệu cho câu hỏi "AI giúp được bao nhiêu, tốn bao nhiêu".

**Phạm vi**
- Script `tools/datagen/eval_suggestions.py`: với mỗi ngoại lệ UNMATCHED_PAYMENT, lấy gợi ý (gọi API làm mới gợi ý),
  so với `truth.csv`: tỷ lệ ứng viên số 1 đúng, nằm trong top 3, hành động đề xuất đúng; độ trễ; token.
- Chạy hai chế độ: quy tắc và LLM; in bảng so sánh, ghi kết quả vào `docs/ai-evaluation.md`.
- Sửa chỗ thiếu: gợi ý từ LLM đang ghi `tokens = 0` (`SuggestionService.suggest`), cần lấy từ usage của phản hồi.
- Giá token đọc từ cấu hình, không viết cứng.

**Xong khi:** chạy được cả khi không có API key (chỉ đo quy tắc); có báo cáo số liệu thật khi có key.

### [ ] 3.2 Theo dõi chi phí AI và phiên bản prompt

**Phạm vi:** API và màn hình (APPROVER/ADMIN) tổng hợp `ai_requests` theo tính năng, ngày, người dùng (lượt gọi, token,
độ trễ, tỷ lệ phải quay về quy tắc); prompt tách ra file trong `resources/prompts` có mã phiên bản, `ai_requests` ghi
thêm phiên bản prompt.

### [ ] 3.3 AI soạn thư khiếu nại và bản tin sáng

**Phạm vi:** nút "Soạn thư" trên ngoại lệ: AI soạn nháp từ dữ kiện (có mẫu theo quy tắc khi không có LLM), người sửa và
gửi; job `morningBriefJob` 07:30 tóm tắt tình hình gửi kế toán trưởng. AI không tự gửi gì.

### [ ] 3.4 Đọc sao kê PDF hoặc ảnh chụp

**Phạm vi:** đối tác nhỏ gửi PDF: Claude đọc thành các dòng giao dịch (structured output) → màn duyệt cho người sửa →
nạp như một file bình thường. Kiểm tra Spring AI 1.1.8 có gửi được PDF cho Anthropic không trước khi chọn cách làm.

### [ ] 3.5 MCP server cho ReconHub

**Phạm vi:** đưa 6 tool chỉ đọc của trợ lý thành MCP server (Spring AI MCP) để hỏi số liệu từ Claude Desktop; xác thực
bằng token của từng người dùng, giữ nguyên phân quyền (ADMIN không thấy số tiền); hướng dẫn cấu hình trong tài liệu.

---

## Giai đoạn 4: vận hành và hồ sơ theo JD (song song hoặc sau)

### [ ] 4.1 Chạy được trên Oracle

**Phạm vi:** profile `oracle`; migration Flyway theo `db/migration/{vendor}`; câu SQL riêng cho Oracle trong MyBatis
(`databaseId`) cho các chỗ dùng cú pháp PostgreSQL (`lateral`, `filter`, `string_agg`, `::`, toán tử jsonb); bảng Spring
Batch cho Oracle; Testcontainers với `gvenzl/oracle-free`; compose profile `oracle`.

### [ ] 4.2 Giám sát bằng Prometheus và Grafana

**Phạm vi:** Micrometer metrics (thời gian job, số dòng, tỷ lệ khớp, số ngoại lệ, lỗi AI); compose profile `monitoring`
có dashboard dựng sẵn; cảnh báo khi job lỗi hoặc tỷ lệ khớp dưới 95%.

### [ ] 4.3 Triển khai lên VPS Linux

**Phạm vi:** `compose.prod.yml`, image đẩy lên GHCR, HTTPS (Caddy hoặc Nginx + Let's Encrypt), sao lưu `pg_dump` hằng
ngày và thử khôi phục, GitHub Actions deploy qua SSH, runbook vận hành.

### [ ] 4.4 Hiệu năng với dữ liệu lớn

**Phạm vi:** generator sinh file 100.000 dòng; đo thời gian nạp và khớp; chia xử lý song song (Spring Batch partitioning
theo nguồn hoặc chunk nhiều luồng); k6 cho API; kết quả trước/sau vào `docs/performance.md`.

### [ ] 4.5 Tài liệu theo kiểu dự án Nhật (song ngữ Việt–Nhật)

**Phạm vi:** 基本設計書 (danh sách màn hình, chức năng, ER, danh sách batch, giao tiếp hệ thống), 詳細設計書 (từng job và
API), テスト仕様書 (ca kiểm thử, kết quả mong đợi, liên kết tới test tự động). Viết Markdown; nếu cần, script xuất Excel
theo mẫu quen thuộc của dự án Nhật.
