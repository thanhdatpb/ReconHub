// Deterministic sample data. It mirrors what the Python data generator will produce later:
// the same seed always yields the same orders, files, exceptions and batch history.
import type {
  AgingRow,
  AiCandidate,
  AiSuggestion,
  ExceptionAction,
  ExceptionStatus,
  ExceptionType,
  FileErrorRow,
  ImportFile,
  JobExecution,
  JournalEntry,
  MatchGroup,
  MatchMethod,
  Order,
  PaymentLine,
  ReconDay,
  ReconException,
  Source,
  SourceCode,
  User,
} from './types';

export const DEMO_NOW = new Date(2026, 9, 22, 9, 30, 0);
export const WRITE_OFF_APPROVAL_THRESHOLD = 50_000;
const HOUR = 3_600_000;

export function mulberry32(seed: number) {
  let a = seed;
  return () => {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const rnd = mulberry32(20261022);
const int = (min: number, max: number, r = rnd) => Math.floor(r() * (max - min + 1)) + min;
const pick = <T,>(arr: readonly T[], r = rnd): T => arr[Math.floor(r() * arr.length)];
const chance = (p: number, r = rnd) => r() < p;
function weighted<T>(pairs: [T, number][], r = rnd): T {
  const total = pairs.reduce((s, [, w]) => s + w, 0);
  let x = r() * total;
  for (const [v, w] of pairs) {
    x -= w;
    if (x <= 0) return v;
  }
  return pairs[pairs.length - 1][0];
}
const digits = (n: number, r = rnd) => Array.from({ length: n }, () => int(0, 9, r)).join('');
const ALNUM = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
const alnum = (n: number, r = rnd) => Array.from({ length: n }, () => pick(ALNUM.split(''), r)).join('');

export function startOfDay(d: Date) {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x;
}
export function daysAgo(n: number, h = 0, m = 0) {
  const x = startOfDay(DEMO_NOW);
  x.setDate(x.getDate() - n);
  x.setHours(h, m, 0, 0);
  return x;
}
const addHours = (d: Date, h: number) => new Date(d.getTime() + h * HOUR);
const pad = (n: number, w = 2) => String(n).padStart(w, '0');
const ymd = (d: Date) => `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
const yymmdd = (d: Date) => ymd(d).slice(2);
const dm = (d: Date) => `${pad(d.getDate())}/${pad(d.getMonth() + 1)}`;
const vnd = (n: number) => `${Math.round(n).toLocaleString('vi-VN')} ₫`;
const roundK = (n: number) => Math.round(n / 1000) * 1000;

export function stripAccents(s: string) {
  return s
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D');
}
const upperNoAccent = (s: string) => stripAccents(s).toUpperCase();
const SHORT: Record<SourceCode, string> = { GHN: 'GHN', GHTK: 'GHTK', SHOPEE: 'Shopee', TIKTOK: 'TikTok Shop', VCB: 'Vietcombank', MOMO: 'MoMo', VTP: 'Viettel Post' };

export const USERS: User[] = [
  { id: 'u1', name: 'Nguyễn Thu Hà', role: 'ACCOUNTANT', title: 'Kế toán công nợ' },
  { id: 'u2', name: 'Trần Minh Khoa', role: 'ACCOUNTANT', title: 'Kế toán thanh toán' },
  { id: 'u3', name: 'Lê Hoàng Nam', role: 'APPROVER', title: 'Kế toán trưởng' },
  { id: 'u4', name: 'Phạm Quốc Bảo', role: 'ADMIN', title: 'Quản trị hệ thống' },
];

const NAMES = [
  'Nguyễn Thị Lan', 'Trần Văn Hùng', 'Lê Thị Mai', 'Phạm Minh Tuấn', 'Hoàng Thu Trang',
  'Vũ Đức Anh', 'Đặng Ngọc Hân', 'Bùi Quang Huy', 'Đỗ Thị Hương', 'Ngô Gia Bảo',
  'Dương Khánh Linh', 'Lý Thanh Tâm', 'Phan Thị Yến', 'Võ Hoàng Long', 'Trịnh Mỹ Duyên',
  'Mai Anh Thư', 'Hồ Văn Phúc', 'Cao Thị Ngọc', 'Tạ Minh Châu', 'Lương Bảo Ngân',
  'Đinh Công Danh', 'Chu Thị Hà', 'Kiều Quốc Việt', 'La Thu Uyên', 'Quách Tuấn Kiệt',
  'Nguyễn Hải Yến', 'Trần Thị Thảo', 'Lê Quang Vinh', 'Phạm Thùy Dung', 'Hoàng Gia Huy',
];

// ---------------------------------------------------------------- sources

export const SOURCES: Source[] = [
  {
    code: 'GHN', name: 'Giao Hàng Nhanh', kind: 'CARRIER', status: 'ACTIVE',
    channel: 'SFTP /inbox/ghn', filePattern: 'GHN_DOISOAT_{yyyyMMdd}.xlsx',
    schedule: 'Hằng ngày, khoảng 08:00', expectedHour: 8, encoding: 'UTF-8', dateFormat: 'dd/MM/yyyy',
    headers: ['Mã vận đơn', 'Mã đơn shop', 'Ngày đối soát', 'Tiền thu hộ', 'Tổng phí', 'Tiền trả shop', 'Ghi chú'],
    sampleRows: [
      ['GHNLBT9XK2V', 'DH2610190412', '21/10/2026', '485.000', '27.000', '458.000', 'Giao thành công'],
      ['GHNQ7M2PZ4D', 'DH2610190427', '21/10/2026', '1.290.000', '28.450', '1.261.550', 'Giao thành công'],
      ['GHN8KD3RW6N', 'DH2610200033', '21/10/2026', '359.000', '35.000', '324.000', 'Phụ phí vùng xa'],
    ],
    mapping: [
      { field: 'externalRef', column: 'Mã vận đơn' },
      { field: 'orderCode', column: 'Mã đơn shop' },
      { field: 'trackingCode', column: 'Mã vận đơn' },
      { field: 'amount', column: 'Tiền trả shop' },
      { field: 'fee', column: 'Tổng phí' },
      { field: 'txnDate', column: 'Ngày đối soát' },
      { field: 'description', column: 'Ghi chú' },
    ],
    feeRules: [
      { name: 'Cước giao nội thành', formula: '22.000 ₫/đơn, khối lượng đến 2 kg', effectiveFrom: '01/10/2026' },
      { name: 'Phí thu hộ COD', formula: '0,5% tiền thu hộ, tối thiểu 5.000 ₫', effectiveFrom: '01/10/2026' },
      { name: 'Phụ phí vùng xa', formula: '+8.000 ₫/đơn với xã trong danh sách vùng xa', effectiveFrom: '15/09/2026' },
    ],
  },
  {
    code: 'GHTK', name: 'Giao Hàng Tiết Kiệm', kind: 'CARRIER', status: 'ACTIVE',
    channel: 'SFTP /inbox/ghtk', filePattern: 'GHTK_COD_{yyyyMMdd}.csv',
    schedule: 'Hằng ngày, khoảng 09:00', expectedHour: 9, encoding: 'UTF-8', dateFormat: 'dd-MM-yyyy HH:mm',
    headers: ['Mã đơn hàng', 'Mã đơn KH', 'Ngày', 'COD', 'Phí ship', 'Phí bảo hiểm', 'Thực nhận', 'Trạng thái'],
    sampleRows: [
      ['S218840571', 'DH2610190388', '21-10-2026 16:40', '650000', '30000', '0', '620000', 'Đã đối soát'],
      ['S218840602', 'DH2610190391', '21-10-2026 16:40', '1150000', '30000', '5750', '1114250', 'Đã đối soát'],
      ['S218841117', 'DH2610200102', '21-10-2026 16:40', '279000', '20000', '0', '259000', 'Đã đối soát'],
    ],
    mapping: [
      { field: 'externalRef', column: 'Mã đơn hàng' },
      { field: 'orderCode', column: 'Mã đơn KH' },
      { field: 'trackingCode', column: 'Mã đơn hàng' },
      { field: 'amount', column: 'Thực nhận' },
      { field: 'fee', column: 'Phí ship' },
      { field: 'txnDate', column: 'Ngày' },
      { field: 'description', column: 'Trạng thái' },
    ],
    feeRules: [
      { name: 'Phí giao hàng', formula: '20.000 ₫ nội tỉnh, 30.000 ₫ liên tỉnh', effectiveFrom: '01/08/2026' },
      { name: 'Phí bảo hiểm', formula: '0,5% giá trị khai giá từ 1.000.000 ₫', effectiveFrom: '01/08/2026' },
    ],
  },
  {
    code: 'SHOPEE', name: 'Shopee', kind: 'MARKETPLACE', status: 'ACTIVE',
    channel: 'Tải thủ công từ trang người bán', filePattern: 'Shopee_Income_{yyyyMMdd}.xlsx',
    schedule: 'Thứ Hai và Thứ Năm (kỳ thanh toán)', expectedHour: null, encoding: 'UTF-8', dateFormat: 'yyyy-MM-dd',
    headers: ['Mã đơn hàng', 'Ngày hoàn thành thanh toán', 'Giá gốc', 'Phí cố định', 'Phí dịch vụ', 'Phí thanh toán', 'Tổng tiền đã thanh toán'],
    sampleRows: [
      ['261018KQ7H2MXA', '2026-10-19', '399000', '15960', '0', '19590', '363450'],
      ['261018KR2N8TPC', '2026-10-19', '829000', '33160', '0', '40700', '755140'],
      ['261018KT5W3ZBD', '2026-10-19', '249000', '9960', '0', '12230', '226810'],
    ],
    mapping: [
      { field: 'externalRef', column: 'Mã đơn hàng' },
      { field: 'orderCode', column: 'Mã đơn hàng' },
      { field: 'trackingCode', column: null },
      { field: 'amount', column: 'Tổng tiền đã thanh toán' },
      { field: 'fee', column: 'Phí cố định' },
      { field: 'txnDate', column: 'Ngày hoàn thành thanh toán' },
      { field: 'description', column: null },
    ],
    feeRules: [
      { name: 'Phí cố định', formula: '4% giá trị đơn (ngành hàng thời trang)', effectiveFrom: '01/07/2026' },
      { name: 'Phí thanh toán', formula: '4,91% giá trị thanh toán', effectiveFrom: '01/07/2026' },
    ],
  },
  {
    code: 'TIKTOK', name: 'TikTok Shop', kind: 'MARKETPLACE', status: 'ACTIVE',
    channel: 'Tải thủ công từ trang người bán', filePattern: 'TTS_Settlement_{yyyyMMdd}.csv',
    schedule: 'Thứ Ba hằng tuần', expectedHour: null, encoding: 'UTF-8', dateFormat: 'yyyy/MM/dd HH:mm:ss',
    headers: ['Order ID', 'Settlement time', 'Order amount', 'Total fees', 'Total settlement amount'],
    sampleRows: [
      ['578112093345671234', '2026/10/20 03:12:44', '459000', '22950', '436050'],
      ['578112093398012211', '2026/10/20 03:12:44', '1199000', '59950', '1139050'],
      ['578112094001127755', '2026/10/20 03:12:45', '319000', '15950', '303050'],
    ],
    mapping: [
      { field: 'externalRef', column: 'Order ID' },
      { field: 'orderCode', column: 'Order ID' },
      { field: 'trackingCode', column: null },
      { field: 'amount', column: 'Total settlement amount' },
      { field: 'fee', column: 'Total fees' },
      { field: 'txnDate', column: 'Settlement time' },
      { field: 'description', column: null },
    ],
    feeRules: [{ name: 'Phí giao dịch', formula: '5% giá trị đơn', effectiveFrom: '01/09/2026' }],
  },
  {
    code: 'VCB', name: 'Vietcombank · TK ••• 4567', kind: 'BANK', status: 'ACTIVE',
    channel: 'SFTP /inbox/vcb + webhook giao dịch', filePattern: 'VCB_SaoKe_{yyyyMMdd}.csv',
    schedule: 'Hằng ngày, khoảng 07:00', expectedHour: 7, encoding: 'Windows-1258', dateFormat: 'dd/MM/yyyy HH:mm:ss',
    headers: ['Ngày giao dịch', 'Số tham chiếu', 'Số tiền ghi nợ', 'Số tiền ghi có', 'Số dư', 'Mô tả'],
    sampleRows: [
      ['21/10/2026 09:14:02', 'FT26294518823', '', '485.000', '182.440.120', 'CK DH2610200156 NGUYEN THI LAN'],
      ['21/10/2026 10:02:51', 'FT26294533017', '', '1.120.000', '183.560.120', 'MBVCB.8812347.TRAN VAN HUNG.thanh toan don hang'],
      ['21/10/2026 11:40:19', 'FT26294560412', '', '659.000', '184.219.120', 'IBFT LE THI MAI ck tien hang'],
    ],
    mapping: [
      { field: 'externalRef', column: 'Số tham chiếu' },
      { field: 'orderCode', column: null },
      { field: 'trackingCode', column: null },
      { field: 'amount', column: 'Số tiền ghi có' },
      { field: 'fee', column: null },
      { field: 'txnDate', column: 'Ngày giao dịch' },
      { field: 'description', column: 'Mô tả' },
    ],
    feeRules: [{ name: 'Phí nhận chuyển khoản', formula: 'Miễn phí', effectiveFrom: '01/01/2026' }],
  },
  {
    code: 'MOMO', name: 'Ví MoMo doanh nghiệp', kind: 'EWALLET', status: 'ACTIVE',
    channel: 'SFTP /inbox/momo', filePattern: 'MOMO_Settlement_{yyyyMMdd}.csv',
    schedule: 'Hằng ngày, khoảng 10:00', expectedHour: 10, encoding: 'UTF-8', dateFormat: 'yyyy-MM-dd HH:mm:ss',
    headers: ['Mã giao dịch', 'Thời gian', 'Số tiền', 'Phí', 'Nội dung', 'Mã tham chiếu đối tác'],
    sampleRows: [
      ['48211873350', '2026-10-21 19:22:05', '329000', '3290', 'Thanh toan don DH2610210219', 'DH2610210219'],
      ['48211874412', '2026-10-21 20:05:41', '799000', '7990', 'Chuyen tien tu Hoang Thu Trang', ''],
      ['48211875120', '2026-10-21 21:47:13', '215000', '2150', 'Thanh toan don DH2610210244', 'DH2610210244'],
    ],
    mapping: [
      { field: 'externalRef', column: 'Mã giao dịch' },
      { field: 'orderCode', column: 'Mã tham chiếu đối tác' },
      { field: 'trackingCode', column: null },
      { field: 'amount', column: 'Số tiền' },
      { field: 'fee', column: 'Phí' },
      { field: 'txnDate', column: 'Thời gian' },
      { field: 'description', column: 'Nội dung' },
    ],
    feeRules: [{ name: 'Phí giao dịch ví', formula: '1% giá trị giao dịch', effectiveFrom: '01/06/2026' }],
  },
  {
    code: 'VTP', name: 'Viettel Post', kind: 'CARRIER', status: 'DRAFT',
    channel: 'Email đính kèm (chưa cấu hình SFTP)', filePattern: 'VTP_DoiSoat_{yyyyMMdd}.xlsx',
    schedule: 'Chưa có lịch', expectedHour: null, encoding: 'UTF-8', dateFormat: 'dd/MM/yyyy',
    headers: ['Mã vận đơn', 'Mã đơn hàng KH', 'Ngày đối soát', 'Tiền thu hộ (COD)', 'Tổng cước', 'Phí thu hộ', 'Thực trả', 'Ghi chú'],
    sampleRows: [
      ['VTP1029384756', 'DH2610200311', '21/10/2026', '540.000', '25.000', '5.400', '509.600', ''],
      ['VTP1029384802', 'DH2610200318', '21/10/2026', '1.080.000', '32.000', '10.800', '1.037.200', 'Giao lần 2'],
      ['VTP1029385117', 'DH2610200340', '21/10/2026', '299.000', '25.000', '5.000', '269.000', ''],
    ],
    mapping: [
      { field: 'externalRef', column: null },
      { field: 'orderCode', column: null },
      { field: 'trackingCode', column: null },
      { field: 'amount', column: null },
      { field: 'fee', column: null },
      { field: 'txnDate', column: null },
      { field: 'description', column: null },
    ],
    feeRules: [],
  },
];

export const ACTIVE_SOURCES = SOURCES.filter((s) => s.status === 'ACTIVE').map((s) => s.code);

export function fileNameFor(source: SourceCode, d: Date, suffix = '') {
  const s = SOURCES.find((x) => x.code === source)!;
  return s.filePattern.replace('{yyyyMMdd}', ymd(d) + suffix);
}

// ---------------------------------------------------------------- orders

function genOrders(): Order[] {
  const orders: Order[] = [];
  const perDay = new Map<string, number>();
  for (let i = 0; i < 900; i++) {
    const created = daysAgo(int(0, 40), int(7, 22), int(0, 59));
    const key = ymd(created);
    const seq = (perDay.get(key) ?? 0) + int(1, 9);
    perDay.set(key, seq);
    const channel = weighted<Order['channel']>([['WEBSITE', 40], ['SHOPEE', 38], ['TIKTOK', 22]]);
    const payVia: SourceCode =
      channel === 'SHOPEE' ? 'SHOPEE' : channel === 'TIKTOK' ? 'TIKTOK'
        : weighted<SourceCode>([['GHN', 32], ['GHTK', 24], ['VCB', 32], ['MOMO', 12]]);
    const amount = int(129, 1890) * 1000;
    const isCod = payVia === 'GHN' || payVia === 'GHTK';
    const phone = `09${digits(2)}***${digits(3)}`;
    orders.push({
      code: `DH${yymmdd(created)}${pad(seq, 4)}`,
      channel,
      customer: pick(NAMES),
      phone,
      amount,
      codAmount: isCod ? amount : 0,
      payVia,
      trackingCode: payVia === 'GHN' ? `GHN${alnum(8)}` : payVia === 'GHTK' ? `S2188${digits(5)}` : undefined,
      createdAt: created,
      deliveredAt: isCod || channel !== 'WEBSITE' ? addHours(created, int(20, 60)) : undefined,
    });
  }
  return orders.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());
}

export const ORDERS = genOrders();

// ---------------------------------------------------------------- payment lines

let plSeq = 1;
function refFor(source: SourceCode, d: Date, order?: Order) {
  const doy = Math.floor((startOfDay(d).getTime() - new Date(d.getFullYear(), 0, 0).getTime()) / 86_400_000);
  switch (source) {
    case 'VCB': return `FT26${pad(doy, 3)}${digits(6)}`;
    case 'MOMO': return `482${digits(8)}`;
    case 'GHN':
    case 'GHTK': return order?.trackingCode ?? `GHN${alnum(8)}`;
    case 'SHOPEE': return `${yymmdd(order?.createdAt ?? d)}${alnum(8)}`;
    case 'TIKTOK': return `5781${digits(14)}`;
    default: return digits(10);
  }
}
function payment(source: SourceCode, amount: number, fee: number, txnDate: Date, description: string, order?: Order, ref?: string, fileSuffix = ''): PaymentLine {
  return {
    id: `PL-${pad(plSeq++, 6)}`,
    source,
    externalRef: ref ?? refFor(source, txnDate, order),
    description,
    amount,
    fee,
    txnDate,
    fileName: fileNameFor(source, startOfDay(addHours(txnDate, 24)), fileSuffix),
  };
}

// ---------------------------------------------------------------- exceptions

const SOURCE_TYPES: Record<SourceCode, [ExceptionType, number][]> = {
  VCB: [['UNMATCHED_PAYMENT', 90], ['DUPLICATE', 10]],
  MOMO: [['UNMATCHED_PAYMENT', 100]],
  GHN: [['AMOUNT_DIFF', 35], ['FEE_DIFF', 35], ['UNPAID_ORDER', 30]],
  GHTK: [['AMOUNT_DIFF', 35], ['FEE_DIFF', 25], ['UNPAID_ORDER', 32], ['DUPLICATE', 8]],
  SHOPEE: [['AMOUNT_DIFF', 50], ['FEE_DIFF', 50]],
  TIKTOK: [['AMOUNT_DIFF', 55], ['FEE_DIFF', 45]],
  VTP: [],
};

function expectedFeeFor(source: SourceCode, o: Order) {
  switch (source) {
    case 'GHN': return 22_000 + Math.max(5_000, roundK(o.codAmount * 0.005));
    case 'GHTK': return o.amount > 900_000 ? 30_000 : 20_000;
    case 'SHOPEE': return roundK(o.amount * 0.0891);
    case 'TIKTOK': return roundK(o.amount * 0.05);
    case 'MOMO': return roundK(o.amount * 0.01);
    default: return 0;
  }
}

function typoCode(code: string) {
  const v = int(0, 2);
  if (v === 0) return code.slice(2);
  if (v === 1) return code.includes('1') ? code.replace(/1(?=[^1]*$)/, 'I') : code.replace(/0(?=[^0]*$)/, 'O');
  return `${code.slice(0, 8)} ${code.slice(8)}`;
}

function poolFor(source: SourceCode, from: Date, to: Date) {
  return ORDERS.filter((o) => o.payVia === source && o.createdAt >= from && o.createdAt <= to);
}

let exSeqByDay = new Map<string, number>();
function exId(d: Date) {
  const k = yymmdd(d);
  const n = (exSeqByDay.get(k) ?? 0) + 1;
  exSeqByDay.set(k, n);
  return `EX-${k}-${pad(n, 3)}`;
}

interface Built {
  orders: Order[];
  payments: PaymentLine[];
  diff: number;
  meta: ReconException['meta'];
  ai: AiSuggestion;
}

function ai(summary: string, confidence: number, recommended: AiSuggestion['recommended'], candidates: AiCandidate[], evidence: string[]): AiSuggestion {
  return { summary, confidence, recommended, candidates, evidence, latencyMs: int(900, 2600), tokens: int(1400, 3800) };
}

function buildUnmatched(source: SourceCode, runDay: Date): Built | null {
  const pool = poolFor(source, addHours(runDay, -24 * 5), addHours(runDay, -2));
  if (pool.length < 3) return null;
  const o = pick(pool);
  const scenario = weighted<'typo' | 'nocode' | 'othername' | 'multi'>([['typo', 34], ['nocode', 28], ['othername', 22], ['multi', 16]]);
  const txn = addHours(o.createdAt, int(1, 20));
  const name = upperNoAccent(o.customer);
  const decoys = pool.filter((x) => x !== o && x.customer !== o.customer).slice(0, 2);
  const decoyCands: AiCandidate[] = decoys.slice(0, scenario === 'multi' ? 1 : 2).map((d, i) => ({
    orderCodes: [d.code],
    amount: d.amount,
    score: i === 0 ? 0.21 : 0.09,
    reasons: [
      { ok: d.amount === o.amount, text: d.amount === o.amount ? 'Số tiền bằng nhau' : `Số tiền lệch ${vnd(Math.abs(d.amount - o.amount))}` },
      { ok: false, text: `Tên khách "${d.customer}" khác người chuyển` },
      { ok: false, text: 'Nội dung chuyển khoản không nhắc tới mã đơn này' },
    ],
  }));

  if (scenario === 'multi') {
    const o2 = pool.find((x) => x !== o && x.customer !== o.customer) ?? decoys[0];
    if (!o2) return null;
    o2.customer = o.customer;
    const total = o.amount + o2.amount;
    const desc = `tt don ${o.code.slice(-4)} va ${o2.code.slice(-4)} ${name}`;
    const line = payment(source, total, source === 'MOMO' ? roundK(total * 0.01) : 0, txn, desc);
    return {
      orders: [], payments: [line], diff: total, meta: {},
      ai: ai(
        `Một lần chuyển cho 2 đơn của cùng khách ${o.customer}: tổng ${o.code} và ${o2.code} bằng đúng số tiền nhận được.`,
        0.9, 'MATCH',
        [
          {
            orderCodes: [o.code, o2.code], amount: total, score: 0.9,
            reasons: [
              { ok: true, text: `Tổng 2 đơn = ${vnd(total)}, khớp chính xác` },
              { ok: true, text: `Nội dung có 4 số cuối của cả hai mã (${o.code.slice(-4)}, ${o2.code.slice(-4)})` },
              { ok: true, text: `Tên người chuyển trùng tên khách "${o.customer}"` },
            ],
          },
          ...decoyCands,
        ],
        [`Nội dung: "${desc}"`, 'Quy tắc khớp tầng 3 (một khoản cho nhiều đơn) chỉ xét đơn có mã đầy đủ nên bỏ sót ca này'],
      ),
    };
  }

  let desc = '';
  let cand: AiCandidate;
  let summary = '';
  let conf = 0.9;
  if (scenario === 'typo') {
    const t = typoCode(o.code);
    desc = source === 'MOMO' ? `Thanh toan don ${t}` : `CK ${t} ${name}`;
    conf = 0.94;
    summary = `Nội dung ghi "${t}", gần giống mã ${o.code}; số tiền và tên người chuyển đều khớp.`;
    cand = {
      orderCodes: [o.code], amount: o.amount, score: 0.94,
      reasons: [
        { ok: true, text: `Số tiền khớp chính xác ${vnd(o.amount)}` },
        { ok: true, text: `"${t}" lệch 1 ký tự so với mã ${o.code}` },
        { ok: true, text: `Tên người chuyển trùng tên khách "${o.customer}"` },
      ],
    };
  } else if (scenario === 'nocode') {
    desc = source === 'MOMO' ? `Chuyen tien tu ${stripAccents(o.customer)}` : `${name} chuyen tien`;
    conf = 0.81;
    summary = `Nội dung không có mã đơn, nhưng số tiền, tên người chuyển và thời điểm đều khớp đơn ${o.code}.`;
    cand = {
      orderCodes: [o.code], amount: o.amount, score: 0.81,
      reasons: [
        { ok: true, text: `Số tiền khớp chính xác ${vnd(o.amount)}` },
        { ok: true, text: `Tên người chuyển trùng tên khách "${o.customer}"` },
        { ok: true, text: 'Chuyển khoản trong vòng 24 giờ sau khi đặt hàng' },
        { ok: false, text: 'Nội dung không có mã đơn' },
      ],
    };
  } else {
    const payer = pick(NAMES.filter((n) => n !== o.customer));
    desc = source === 'MOMO' ? `Chuyen tien tu ${stripAccents(payer)}` : `IBFT ${upperNoAccent(payer)} ck tien hang`;
    conf = 0.58;
    summary = `Số tiền và thời điểm khớp đơn ${o.code}, nhưng người chuyển (${payer}) khác tên khách. Có thể là người nhà chuyển hộ, nên gọi xác nhận trước khi khớp.`;
    cand = {
      orderCodes: [o.code], amount: o.amount, score: 0.58,
      reasons: [
        { ok: true, text: `Số tiền khớp chính xác ${vnd(o.amount)}` },
        { ok: true, text: 'Chuyển khoản 3 giờ sau khi đặt hàng' },
        { ok: false, text: `Người chuyển "${payer}" khác tên khách "${o.customer}"` },
      ],
    };
  }
  const line = payment(source, o.amount, source === 'MOMO' ? roundK(o.amount * 0.01) : 0, txn, desc);
  return {
    orders: [], payments: [line], diff: o.amount, meta: {},
    ai: ai(summary, conf, 'MATCH', [cand, ...decoyCands], [`Nội dung: "${desc}"`, `Thời gian giao dịch: ${dm(txn)} ${pad(txn.getHours())}:${pad(txn.getMinutes())}`]),
  };
}

function buildAmountDiff(source: SourceCode, runDay: Date): Built | null {
  const pool = poolFor(source, addHours(runDay, -24 * 12), addHours(runDay, -24 * 2));
  if (!pool.length) return null;
  const o = pick(pool);
  const fee = expectedFeeFor(source, o);
  const base = (o.codAmount || o.amount) - fee;
  const scenario = source === 'GHN' && chance(0.45) ? 'feeChange' : weighted<'partial' | 'short' | 'round'>([['partial', 30], ['short', 45], ['round', 25]]);
  const delta =
    scenario === 'feeChange' ? -20_000
      : scenario === 'partial' ? -roundK(o.amount * pick([0.3, 0.4, 0.5]))
        : scenario === 'round' ? -pick([1_000, 2_000, 500])
          : -int(5, 90) * 1000;
  const paid = base + delta;
  const note = scenario === 'partial' ? 'Giao 1 phần - KH trả lại 1 sp' : scenario === 'feeChange' ? 'Giao thành công' : '';
  const line = payment(source, paid, fee, addHours(runDay, -int(6, 18)), note, o);
  let a: AiSuggestion;
  if (scenario === 'feeChange') {
    a = ai(`Chênh đúng 20.000 ₫, trùng mức tăng phí thu hộ của GHN từ 01/10/2026. Biểu phí trong ReconHub có thể chưa cập nhật; 9 đơn GHN khác trong tuần có cùng mức chênh.`,
      0.86, 'UPDATE_FEE_RULE', [], ['9 ngoại lệ GHN khác từ 01/10 lệch đúng −20.000 ₫', 'Ghi chú của đối tác: "Giao thành công"']);
  } else if (scenario === 'partial') {
    a = ai(`Đối tác ghi "Giao 1 phần". Khách chỉ nhận một phần hàng nên tiền thu hộ thấp hơn. Cần đối chiếu phiếu hoàn hàng trong OMS trước khi ghi giảm.`,
      0.74, 'CLAIM', [], [`Ghi chú: "${note}"`, 'OMS chưa có phiếu hoàn hàng cho đơn này']);
  } else if (scenario === 'round') {
    a = ai(`Chênh lệch rất nhỏ (${vnd(Math.abs(delta))}), nhiều khả năng do làm tròn phí. Có thể ghi giảm mà không cần duyệt.`,
      0.88, 'WRITE_OFF', [], ['Chênh lệch dưới ngưỡng 5.000 ₫']);
  } else {
    a = ai(`Không tìm thấy nguyên nhân trong dữ liệu: đối tác trả thiếu ${vnd(Math.abs(delta))} và không có ghi chú. Nên gửi yêu cầu đối soát cho đối tác.`,
      0.55, 'CLAIM', [], ['Không có ghi chú từ đối tác', 'Không có thay đổi biểu phí trong kỳ']);
  }
  return { orders: [o], payments: [line], diff: delta, meta: { expectedAmount: base, paidAmount: paid }, ai: a };
}

function buildFeeDiff(source: SourceCode, runDay: Date): Built | null {
  const pool = poolFor(source, addHours(runDay, -24 * 12), addHours(runDay, -24 * 2));
  if (!pool.length) return null;
  const o = pick(pool);
  const expected = expectedFeeFor(source, o);
  const remote = source === 'GHN' && chance(0.5);
  const extra = remote ? 8_000 : pick([5_000, 7_000, 12_000, 15_000]);
  const actual = expected + extra;
  const line = payment(source, (o.codAmount || o.amount) - actual, actual, addHours(runDay, -int(6, 18)), remote ? 'Phụ phí vùng xa' : '', o);
  const rule = SOURCES.find((s) => s.code === source)!.feeRules.map((r) => `${r.name}: ${r.formula}`).join('; ');
  const a = remote
    ? ai(`Phí thực trừ cao hơn 8.000 ₫, đúng bằng phụ phí vùng xa. Địa chỉ giao thuộc danh sách xã vùng xa của GHN, nên khoản phí này hợp lệ. Gợi ý thêm quy tắc áp phụ phí theo địa chỉ.`,
      0.82, 'UPDATE_FEE_RULE', [], ['Ghi chú đối tác: "Phụ phí vùng xa"', 'Địa chỉ giao: xã thuộc danh sách vùng xa cập nhật 15/09/2026'])
    : ai(`Phí thực trừ cao hơn biểu phí ${vnd(extra)} và không khớp phụ phí nào đã biết. 6 đơn khác trong kỳ có cùng mức chênh, nên gửi khiếu nại phí cho đối tác.`,
      0.7, 'CLAIM', [], [`6 đơn ${source} khác trong kỳ lệch đúng ${vnd(extra)}`]);
  return { orders: [o], payments: [line], diff: expected - actual, meta: { expectedFee: expected, actualFee: actual, feeRule: rule }, ai: a };
}

function buildUnpaid(source: SourceCode, runDay: Date): Built | null {
  const pool = ORDERS.filter((o) => o.payVia === source && o.deliveredAt && o.deliveredAt < addHours(runDay, -24 * 7) && o.deliveredAt > addHours(runDay, -24 * 22));
  if (!pool.length) return null;
  const o = pick(pool);
  const days = Math.round((runDay.getTime() - o.deliveredAt!.getTime()) / 86_400_000);
  return {
    orders: [o], payments: [], diff: -o.codAmount, meta: { daysSinceDelivery: days },
    ai: ai(`Đơn giao thành công ngày ${dm(o.deliveredAt!)} nhưng mã vận đơn ${o.trackingCode} không có trong ${days - 1} file đối soát ${source} kể từ đó. Gợi ý gửi yêu cầu đối soát kèm mã vận đơn.`,
      0.8, 'CLAIM', [], [`Trạng thái OMS: Giao thành công ${dm(o.deliveredAt!)}`, `Đã quét ${days - 1} file ${source}, không thấy mã ${o.trackingCode}`]),
  };
}

function buildDuplicate(source: SourceCode, runDay: Date): Built | null {
  const pool = poolFor(source === 'VCB' ? 'VCB' : 'GHTK', addHours(runDay, -24 * 4), addHours(runDay, -2));
  if (!pool.length) return null;
  const o = pick(pool);
  const txn = addHours(o.createdAt, int(1, 10));
  const desc = source === 'VCB' ? `CK ${o.code} ${upperNoAccent(o.customer)}` : 'Đã đối soát';
  const amount = source === 'VCB' ? o.amount : o.codAmount - expectedFeeFor(source, o);
  const l1 = payment(source, amount, 0, txn, desc, o);
  const l2 = payment(source, amount, 0, txn, desc, o, l1.externalRef, '_bosung');
  return {
    orders: [o], payments: [l1, l2], diff: amount, meta: {},
    ai: ai(`Hai dòng có cùng số tham chiếu ${l1.externalRef}, cùng số tiền và thời gian, nằm ở 2 file có khoảng ngày chồng nhau. Dòng thứ nhất đã khớp với đơn ${o.code}; gợi ý đánh dấu dòng thứ hai là trùng.`,
      0.97, 'MARK_DUPLICATE', [], [`File 1: ${l1.fileName}`, `File 2: ${l2.fileName}`]),
  };
}

const BUILDERS: Record<ExceptionType, (s: SourceCode, d: Date) => Built | null> = {
  UNMATCHED_PAYMENT: buildUnmatched,
  AMOUNT_DIFF: buildAmountDiff,
  FEE_DIFF: buildFeeDiff,
  UNPAID_ORDER: buildUnpaid,
  DUPLICATE: buildDuplicate,
};

const LINES_PER_DAY: Record<SourceCode, (d: Date) => number> = {
  GHN: () => int(120, 175),
  GHTK: () => int(80, 125),
  SHOPEE: (d) => ([1, 4].includes(d.getDay()) ? int(300, 460) : 0),
  TIKTOK: (d) => (d.getDay() === 2 ? int(180, 260) : 0),
  VCB: () => int(55, 95),
  MOMO: () => int(18, 42),
  VTP: () => 0,
};
const BASE_AUTO: Record<SourceCode, number> = { GHN: 0.95, GHTK: 0.94, SHOPEE: 0.975, TIKTOK: 0.965, VCB: 0.905, MOMO: 0.92, VTP: 0 };
const AVG_AMOUNT: Record<SourceCode, number> = { GHN: 455_000, GHTK: 410_000, SHOPEE: 372_000, TIKTOK: 398_000, VCB: 520_000, MOMO: 318_000, VTP: 0 };
const AVG_FEE: Record<SourceCode, number> = { GHN: 27_500, GHTK: 23_800, SHOPEE: 33_100, TIKTOK: 19_900, VCB: 0, MOMO: 3_180, VTP: 0 };
const TODAY_FILES: Partial<Record<SourceCode, [number, number]>> = { VCB: [7, 4], GHN: [8, 12] };

function statusFor(ageDays: number): ExceptionStatus {
  if (ageDays === 0) return weighted<ExceptionStatus>([['OPEN', 80], ['IN_REVIEW', 20]]);
  if (ageDays === 1) return weighted<ExceptionStatus>([['OPEN', 55], ['IN_REVIEW', 25], ['RESOLVED', 15], ['WRITE_OFF_PENDING', 5]]);
  if (ageDays <= 4) return weighted<ExceptionStatus>([['OPEN', 5], ['IN_REVIEW', 10], ['RESOLVED', 67], ['WRITE_OFF_PENDING', 4], ['WRITTEN_OFF', 14]]);
  if (ageDays <= 12) return weighted<ExceptionStatus>([['OPEN', 1], ['IN_REVIEW', 3], ['RESOLVED', 78], ['WRITTEN_OFF', 18]]);
  return weighted<ExceptionStatus>([['RESOLVED', 80], ['WRITTEN_OFF', 20]]);
}

function genReconData() {
  const days: ReconDay[] = [];
  const exceptions: ReconException[] = [];
  exSeqByDay = new Map();
  for (let d = 29; d >= 0; d--) {
    const day = daysAgo(d);
    for (const source of ACTIVE_SOURCES) {
      if (d === 0 && !TODAY_FILES[source]) continue;
      let lines = LINES_PER_DAY[source](day);
      if (d === 0) lines = Math.round(lines * 0.9);
      if (!lines) continue;
      const trend = d <= 7 ? 0.022 : 0;
      const auto = Math.min(0.99, BASE_AUTO[source] + trend + (rnd() - 0.5) * 0.024);
      const nonAuto = Math.max(1, Math.round(lines * (1 - auto)));
      const runAt = d === 0 ? (() => { const [h, m] = TODAY_FILES[source]!; return daysAgo(0, h, m + 6); })() : daysAgo(d, 1, int(2, 25));
      let made = 0;
      for (let i = 0; i < nonAuto; i++) {
        const type = weighted(SOURCE_TYPES[source]);
        const built = BUILDERS[type](source, day);
        if (!built) continue;
        made++;
        const status = statusFor(d);
        const createdAt = new Date(runAt.getTime() + i * 1000);
        const sla = addHours(createdAt, 48);
        const assigneeId = status === 'OPEN' && chance(0.45) ? null : pick(['u1', 'u1', 'u2']);
        const job = 18240 + (29 - d) * 9 + 2;
        const actions: ExceptionAction[] = [
          { at: createdAt, actor: `matchingJob #${job}`, kind: 'CREATED', note: 'Không khớp được ở 3 tầng quy tắc' },
          { at: new Date(createdAt.getTime() + built.ai.latencyMs + 60_000), actor: 'Trợ lý AI', kind: 'AI_SUGGESTED', note: `Độ tin cậy ${Math.round(built.ai.confidence * 100)}%` },
        ];
        const who = USERS.find((u) => u.id === assigneeId);
        if (who) actions.push({ at: addHours(createdAt, 7.2), actor: 'Lê Hoàng Nam', kind: 'ASSIGNED', note: `Giao cho ${who.name}` });
        const doneAt = addHours(createdAt, int(8, 40));
        let writeOff: ReconException['writeOff'] = null;
        let finalStatus = status;
        if (status === 'WRITE_OFF_PENDING' && Math.abs(built.diff) <= WRITE_OFF_APPROVAL_THRESHOLD) finalStatus = 'IN_REVIEW';
        if (finalStatus === 'IN_REVIEW' && who) {
          actions.push({ at: addHours(createdAt, 8), actor: who.name, kind: 'CLAIM_SENT', note: `Đã gửi yêu cầu đối soát cho ${SHORT[source]} qua email` });
        }
        if (finalStatus === 'RESOLVED') {
          const by = who?.name ?? 'Trần Minh Khoa';
          const kind = type === 'DUPLICATE' ? 'MARKED_DUPLICATE' : 'MATCHED';
          const note = built.ai.candidates[0] ? `Khớp với ${built.ai.candidates[0].orderCodes.join(', ')} theo gợi ý AI` : type === 'UNPAID_ORDER' ? 'Đối tác đã chuyển bổ sung trong file kỳ sau' : 'Đối tác đã điều chỉnh, khớp lại';
          actions.push({ at: doneAt, actor: by, kind, note });
        }
        if (finalStatus === 'WRITTEN_OFF' || finalStatus === 'WRITE_OFF_PENDING') {
          const req = who ?? USERS[0];
          writeOff = { requestedBy: req.name, requestedById: req.id, reason: type === 'FEE_DIFF' ? 'Phí đối tác thay đổi' : 'Đối tác trả thiếu', note: 'Đã đối chiếu email xác nhận của đối tác', amount: Math.abs(built.diff) };
          actions.push({ at: doneAt, actor: req.name, kind: 'WRITE_OFF_REQUESTED', note: `${writeOff.reason} · ${vnd(writeOff.amount)}` });
          if (finalStatus === 'WRITTEN_OFF') {
            const needs = Math.abs(built.diff) > WRITE_OFF_APPROVAL_THRESHOLD;
            actions.push({ at: addHours(doneAt, needs ? 3 : 0), actor: needs ? 'Lê Hoàng Nam' : 'Hệ thống', kind: 'APPROVED', note: needs ? 'Đồng ý ghi giảm' : 'Tự duyệt vì dưới ngưỡng 50.000 ₫' });
          }
        }
        exceptions.push({
          id: exId(createdAt),
          type,
          source,
          status: finalStatus,
          diffAmount: built.diff,
          createdAt,
          slaDueAt: sla,
          assigneeId,
          orders: built.orders,
          payments: built.payments,
          meta: built.meta,
          ai: built.ai,
          actions: actions.sort((a, b) => a.at.getTime() - b.at.getTime()),
          writeOff,
          version: actions.length,
        });
      }
      days.push({
        date: day,
        source,
        totalLines: lines,
        autoMatched: lines - made,
        manualMatched: 0,
        exceptions: made,
        amountIn: roundK(lines * AVG_AMOUNT[source] * (0.9 + rnd() * 0.2)),
        feeTotal: roundK(lines * AVG_FEE[source] * (0.92 + rnd() * 0.16)),
      });
    }
  }
  // Split each day's non-auto lines into "resolved by a person" vs "still open".
  for (const day of days) {
    const list = exceptions.filter((e) => e.source === day.source && startOfDay(e.createdAt).getTime() === day.date.getTime());
    const resolved = list.filter((e) => e.status === 'RESOLVED' || e.status === 'WRITTEN_OFF').length;
    day.manualMatched = resolved;
    day.exceptions = list.length - resolved;
  }
  // Guarantee a few write-offs waiting for approval so the maker-checker flow is visible.
  const pendingCandidates = exceptions.filter((e) => (e.status === 'OPEN' || e.status === 'IN_REVIEW') && e.type === 'AMOUNT_DIFF' && Math.abs(e.diffAmount) > WRITE_OFF_APPROVAL_THRESHOLD && e.assigneeId);
  for (const e of pendingCandidates.slice(0, 4)) {
    const req = USERS.find((u) => u.id === e.assigneeId)!;
    e.status = 'WRITE_OFF_PENDING';
    e.writeOff = { requestedBy: req.name, requestedById: req.id, reason: 'Đối tác trả thiếu', note: 'Đối tác xác nhận qua email sẽ không bù phần chênh lệch', amount: Math.abs(e.diffAmount) };
    e.actions.push({ at: addHours(e.createdAt, 9), actor: req.name, kind: 'WRITE_OFF_REQUESTED', note: `Đối tác trả thiếu · ${vnd(Math.abs(e.diffAmount))}` });
    e.version++;
  }
  exceptions.sort((a, b) => b.createdAt.getTime() - a.createdAt.getTime());
  return { days, exceptions };
}

const recon = genReconData();
export const RECON_DAYS = recon.days;
export const EXCEPTIONS = recon.exceptions;

// ---------------------------------------------------------------- files

function genFiles(): ImportFile[] {
  const files: ImportFile[] = [];
  let id = 1;
  let exec = 18100;
  for (let d = 13; d >= 0; d--) {
    for (const source of ACTIVE_SOURCES) {
      const rd = RECON_DAYS.find((x) => x.source === source && x.date.getTime() === daysAgo(d).getTime());
      if (!rd) continue;
      const s = SOURCES.find((x) => x.code === source)!;
      const [h, m] = d === 0 ? TODAY_FILES[source]! : [s.expectedHour ?? 11, int(0, 25)];
      const at = daysAgo(d, h, m);
      const failed = source === 'GHTK' && d === 2;
      const errorRows = failed ? 214 : chance(0.3) ? int(1, 6) : 0;
      files.push({
        id: `F${pad(id++, 5)}`,
        source,
        fileName: fileNameFor(source, daysAgo(d)),
        receivedAt: at,
        status: failed ? 'FAILED' : errorRows ? 'COMPLETED_WITH_ERRORS' : 'COMPLETED',
        totalRows: rd.totalLines + errorRows,
        errorRows,
        amountTotal: failed ? 0 : rd.amountIn,
        checksum: `${alnum(4).toLowerCase()}${digits(4)}${alnum(4).toLowerCase()}`,
        jobExecutionId: exec++,
        message: failed ? 'Vượt ngưỡng bỏ qua: 214 dòng lỗi > 100. Cột "Ngày" có định dạng lạ "20-10-2026 14h05".' : undefined,
      });
      if (failed) {
        files.push({
          id: `F${pad(id++, 5)}`, source, fileName: fileNameFor(source, daysAgo(d), '_v2'), receivedAt: daysAgo(d, 14, 37),
          status: 'COMPLETED', totalRows: rd.totalLines, errorRows: 0, amountTotal: rd.amountIn,
          checksum: `${alnum(4).toLowerCase()}${digits(4)}${alnum(4).toLowerCase()}`, jobExecutionId: exec++,
          message: 'Đối tác gửi lại file đã sửa định dạng ngày',
        });
      }
      if (source === 'VCB' && d === 6) {
        files.push({
          id: `F${pad(id++, 5)}`, source, fileName: fileNameFor(source, daysAgo(d)), receivedAt: daysAgo(d, 7, 41),
          status: 'DUPLICATE', totalRows: 0, errorRows: 0, amountTotal: 0, checksum: files[files.length - 1].checksum, jobExecutionId: null,
          message: `Trùng checksum với file nhận lúc 0${s.expectedHour}:${pad(m)} cùng ngày. Không nạp lại.`,
        });
      }
    }
  }
  return files.sort((a, b) => b.receivedAt.getTime() - a.receivedAt.getTime());
}
export const FILES = genFiles();

const ERROR_REASONS: Record<SourceCode, [string, string, () => string][]> = {
  GHN: [['Mã đơn shop', 'Mã đơn không tồn tại trong OMS', () => `DH26${digits(8)}`], ['Tiền trả shop', 'Số tiền không hợp lệ', () => '1.2OO.000'], ['Ngày đối soát', 'Ngày nằm ngoài kỳ đối soát', () => '31/09/2026']],
  GHTK: [['Ngày', 'Không đọc được ngày theo mẫu dd-MM-yyyy HH:mm', () => `20-10-2026 ${int(8, 17)}h${pad(int(0, 59))}`], ['Mã đơn KH', 'Thiếu mã đơn khách hàng', () => '']],
  SHOPEE: [['Tổng tiền đã thanh toán', 'Số tiền âm', () => `-${int(10, 90)}000`], ['Mã đơn hàng', 'Mã đơn trùng trong cùng file', () => `2610${alnum(10)}`]],
  TIKTOK: [['Settlement time', 'Không đọc được ngày', () => '2026/10/20 25:61:00'], ['Order ID', 'Thiếu mã đơn', () => '']],
  VCB: [['Số tiền ghi có', 'Dòng ghi nợ (tiền ra), bỏ qua', () => ''], ['Mô tả', 'Ký tự lỗi do sai bảng mã', () => 'CK DH26101? NGUY?N TH? LAN']],
  MOMO: [['Số tiền', 'Số tiền bằng 0', () => '0'], ['Mã giao dịch', 'Mã giao dịch trùng', () => `482${digits(8)}`]],
  VTP: [],
};

export function fileErrors(file: ImportFile): FileErrorRow[] {
  const r = mulberry32(parseInt(file.id.slice(1), 10) * 7919);
  const reasons = ERROR_REASONS[file.source];
  const n = Math.min(file.errorRows, 40);
  return Array.from({ length: n }, () => {
    const [column, reason, value] = pick(reasons, r);
    return { row: int(2, file.totalRows + 1, r), column, value: value(), reason };
  }).sort((a, b) => a.row - b.row);
}

// ---------------------------------------------------------------- match groups (drill-down)

export function matchGroupsFor(day: ReconDay): MatchGroup[] {
  const r = mulberry32(day.date.getDate() * 131 + day.source.length * 17 + day.totalLines);
  const pool = ORDERS.filter((o) => o.payVia === day.source).slice(0, 80);
  return Array.from({ length: 14 }, (_, i) => {
    const method = weighted<MatchMethod>([['EXACT', 68], ['AMOUNT_DATE', 14], ['MANY_TO_ONE', 6], ['MANUAL', 6], ['AI_CONFIRMED', 6]], r);
    const o = pool.length ? pick(pool, r) : ORDERS[i];
    const o2 = pool.length ? pick(pool, r) : ORDERS[i + 1];
    const codes = method === 'MANY_TO_ONE' ? [o.code, o2.code] : [o.code];
    const amount = method === 'MANY_TO_ONE' ? o.amount + o2.amount : o.amount;
    const fee = expectedFeeFor(day.source, o);
    return {
      id: `MG-${ymd(day.date)}-${day.source}-${pad(i + 1, 3)}`,
      method,
      orderCodes: codes,
      paymentRef: refFor(day.source, day.date, o),
      amount: amount - (day.source === 'VCB' ? 0 : fee),
      fee: day.source === 'VCB' ? 0 : fee,
      confidence: method === 'EXACT' ? 1 : method === 'AMOUNT_DATE' ? 0.97 : method === 'MANY_TO_ONE' ? 0.95 : method === 'AI_CONFIRMED' ? 0.9 : 1,
    };
  });
}

// ---------------------------------------------------------------- batch

function genBatch(): JobExecution[] {
  const list: JobExecution[] = [];
  let id = 18240;
  const ms = (s: number) => s * 1000;
  for (let d = 6; d >= 0; d--) {
    const day = daysAgo(d);
    const prev = RECON_DAYS.filter((x) => x.date.getTime() === daysAgo(d + 1).getTime());
    const lines = prev.reduce((s, x) => s + x.totalLines, 0) || 480;
    const exc = prev.reduce((s, x) => s + x.exceptions + x.manualMatched, 0);
    const start = daysAgo(d, 1, 0);
    const dur = int(95, 180);
    list.push({
      id: id++, job: 'matchingJob', params: { runDate: `${dm(daysAgo(d + 1))}/2026`, rules: 'v14' }, status: 'COMPLETED',
      startTime: start, endTime: new Date(start.getTime() + ms(dur)),
      steps: [
        { name: 'exactMatchStep', status: 'COMPLETED', read: lines, write: Math.round(lines * 0.86), skip: 0, commits: Math.ceil(lines / 500), durationMs: ms(dur * 0.35) },
        { name: 'amountDateMatchStep', status: 'COMPLETED', read: Math.round(lines * 0.14), write: Math.round(lines * 0.06), skip: 0, commits: 1, durationMs: ms(dur * 0.25) },
        { name: 'manyToOneMatchStep', status: 'COMPLETED', read: Math.round(lines * 0.08), write: Math.round(lines * 0.02), skip: 0, commits: 1, durationMs: ms(dur * 0.2) },
        { name: 'exceptionStep', status: 'COMPLETED', read: exc, write: exc, skip: 0, commits: 1, durationMs: ms(dur * 0.05) },
        { name: 'aiSuggestStep', status: 'COMPLETED', read: exc, write: exc, skip: 0, commits: Math.ceil(exc / 20), durationMs: ms(dur * 0.15) },
      ],
    });
    const f0 = daysAgo(d, 1, 20);
    list.push({
      id: id++, job: 'feeValidationJob', params: { runDate: `${dm(daysAgo(d + 1))}/2026` }, status: 'COMPLETED',
      startTime: f0, endTime: new Date(f0.getTime() + ms(int(20, 45))),
      steps: [
        { name: 'loadFeeRulesStep', status: 'COMPLETED', read: 9, write: 9, skip: 0, commits: 1, durationMs: 800 },
        { name: 'validateFeeStep', status: 'COMPLETED', read: lines, write: int(4, 14), skip: 0, commits: Math.ceil(lines / 500), durationMs: ms(int(18, 40)) },
      ],
    });
    const a0 = daysAgo(d, 6, 0);
    const failedToday = d === 0;
    list.push({
      id: id++, job: 'agingAndReportJob', params: { asOf: `${dm(day)}/2026`, recipients: 'ketoan@shop.vn' },
      status: failedToday ? 'FAILED' : 'COMPLETED', startTime: a0, endTime: new Date(a0.getTime() + ms(failedToday ? 96 : int(30, 50))),
      exitMessage: failedToday ? 'MailSendException: Mail server connection failed; nested exception is SocketTimeoutException: Read timed out (3/3 lần thử)' : undefined,
      steps: [
        { name: 'agingSnapshotStep', status: 'COMPLETED', read: 1820, write: 4, skip: 0, commits: 1, durationMs: ms(11) },
        { name: 'journalExportStep', status: 'COMPLETED', read: 46, write: 46, skip: 0, commits: 1, durationMs: ms(6) },
        { name: 'emailReportStep', status: failedToday ? 'FAILED' : 'COMPLETED', read: 1, write: failedToday ? 0 : 1, skip: 0, commits: failedToday ? 0 : 1, durationMs: ms(failedToday ? 79 : 4), exitMessage: failedToday ? 'SocketTimeoutException: Read timed out' : undefined },
      ],
    });
    if (day.getDay() === 0) {
      const p0 = daysAgo(d, 2, 0);
      list.push({
        id: id++, job: 'archiveAndPurgeJob', params: { olderThanDays: '90' }, status: 'COMPLETED', startTime: p0, endTime: new Date(p0.getTime() + ms(212)),
        steps: [
          { name: 'archiveFilesStep', status: 'COMPLETED', read: 61, write: 61, skip: 0, commits: 1, durationMs: ms(170) },
          { name: 'purgeStagingStep', status: 'COMPLETED', read: 28_410, write: 28_410, skip: 0, commits: 57, durationMs: ms(42) },
        ],
      });
    }
  }
  for (const f of FILES.filter((x) => x.jobExecutionId && x.receivedAt >= daysAgo(6))) {
    const failed = f.status === 'FAILED';
    const st = new Date(f.receivedAt.getTime() + 20_000);
    list.push({
      id: f.jobExecutionId!, job: 'fileIngestionJob', params: { source: f.source, file: f.fileName }, status: failed ? 'FAILED' : 'COMPLETED',
      startTime: st, endTime: new Date(st.getTime() + ms(int(8, 40))),
      exitMessage: failed ? 'SkipLimitExceededException: Skip limit of 100 exceeded' : undefined,
      steps: [
        { name: 'validateFileStep', status: 'COMPLETED', read: 1, write: 1, skip: 0, commits: 1, durationMs: 600 },
        { name: 'parseAndStageStep', status: failed ? 'FAILED' : 'COMPLETED', read: failed ? 600 : f.totalRows, write: failed ? 386 : f.totalRows - f.errorRows, skip: failed ? 101 : f.errorRows, commits: failed ? 1 : Math.ceil(f.totalRows / 500), durationMs: ms(int(5, 30)), exitMessage: failed ? 'Skip limit of 100 exceeded' : undefined },
        ...(failed ? [] : [{ name: 'publishEventsStep', status: 'COMPLETED' as const, read: f.totalRows - f.errorRows, write: f.totalRows - f.errorRows, skip: 0, commits: 1, durationMs: 1200 }]),
      ],
    });
  }
  const ghtkFailed = list.find((x) => x.job === 'fileIngestionJob' && x.status === 'FAILED');
  const ghtkFixed = list.find((x) => x.job === 'fileIngestionJob' && x.params.file?.includes('_v2'));
  if (ghtkFailed && ghtkFixed) ghtkFailed.restartedAs = ghtkFixed.id;
  // Renumber in start order so execution ids grow with time, as in BATCH_JOB_EXECUTION.
  list.sort((a, b) => a.startTime.getTime() - b.startTime.getTime());
  const remap = new Map<number, number>();
  list.forEach((x, i) => remap.set(x.id, 18200 + i));
  for (const x of list) {
    x.id = remap.get(x.id)!;
    if (x.restartedAs) x.restartedAs = remap.get(x.restartedAs);
  }
  for (const f of FILES) if (f.jobExecutionId && remap.has(f.jobExecutionId)) f.jobExecutionId = remap.get(f.jobExecutionId)!;
  for (const e of EXCEPTIONS) {
    const run = [...list].reverse().find((x) => x.job === 'matchingJob' && x.startTime <= e.createdAt);
    const created = e.actions.find((a) => a.kind === 'CREATED');
    if (run && created) created.actor = `matchingJob #${run.id}`;
  }
  return list.sort((a, b) => b.startTime.getTime() - a.startTime.getTime());
}
export const BATCH = genBatch();

export const BATCH_SCHEDULE: { job: string; cron: string; human: string }[] = [
  { job: 'fileIngestionJob', cron: '0 */15 * * * *', human: '15 phút một lần, quét /inbox' },
  { job: 'matchingJob', cron: '0 0 1 * * *', human: '01:00 hằng ngày và ngay sau khi nạp file' },
  { job: 'feeValidationJob', cron: '0 20 1 * * *', human: '01:20 hằng ngày' },
  { job: 'agingAndReportJob', cron: '0 0 6 * * *', human: '06:00 hằng ngày' },
  { job: 'archiveAndPurgeJob', cron: '0 0 2 * * SUN', human: '02:00 Chủ nhật' },
];

// ---------------------------------------------------------------- aging & journal

export const AGING: AgingRow[] = [
  { source: 'SHOPEE', buckets: [96_480_000, 3_120_000, 0, 0] },
  { source: 'GHN', buckets: [62_410_000, 8_230_000, 1_940_000, 450_000] },
  { source: 'GHTK', buckets: [41_730_000, 6_310_000, 2_380_000, 815_000] },
  { source: 'TIKTOK', buckets: [28_190_000, 4_640_000, 905_000, 0] },
];
export const AGING_BUCKETS = ['0–7 ngày', '8–30 ngày', '31–60 ngày', 'Trên 60 ngày'];

export const ACCOUNTS: Record<string, string> = {
  '112': 'Tiền gửi ngân hàng',
  '131': 'Phải thu của khách hàng',
  '641': 'Chi phí bán hàng',
  '811': 'Chi phí khác',
};

export function journalFor(year: number, month: number): JournalEntry[] {
  const rows: JournalEntry[] = [];
  let seq = 1;
  const inMonth = (d: Date) => d.getFullYear() === year && d.getMonth() === month;
  for (const day of RECON_DAYS.filter((x) => inMonth(x.date)).sort((a, b) => a.date.getTime() - b.date.getTime())) {
    const tag = `${dm(day.date)}/${year}`;
    const label = day.source === 'VCB' ? 'Khách chuyển khoản' : day.source === 'MOMO' ? 'Thu qua ví MoMo' : `Thu tiền ${SHORT[day.source]}`;
    rows.push({ date: day.date, voucher: `BC${pad(month + 1)}-${pad(seq++, 4)}`, description: `${label} đã khớp đơn, kỳ ${tag}`, debit: '112', credit: '131', amount: day.amountIn, source: day.source });
    if (day.feeTotal > 0) rows.push({ date: day.date, voucher: `PK${pad(month + 1)}-${pad(seq++, 4)}`, description: `Phí ${SHORT[day.source]} bị trừ, kỳ ${tag}`, debit: '641', credit: '131', amount: day.feeTotal, source: day.source });
  }
  for (const e of EXCEPTIONS.filter((x) => x.status === 'WRITTEN_OFF' && inMonth(x.createdAt))) {
    rows.push({ date: startOfDay(e.createdAt), voucher: `PK${pad(month + 1)}-${pad(seq++, 4)}`, description: `Ghi giảm chênh lệch ${e.id}`, debit: '811', credit: '131', amount: Math.abs(e.diffAmount), source: e.source });
  }
  return rows.sort((a, b) => a.date.getTime() - b.date.getTime() || a.voucher.localeCompare(b.voucher));
}
