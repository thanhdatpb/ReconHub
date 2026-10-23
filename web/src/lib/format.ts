import { now as clockNow } from '../state/meta';
import type {
  ActionKind,
  BatchStatus,
  CanonicalField,
  ExceptionStatus,
  ExceptionType,
  FileStatus,
  MatchMethod,
  SourceCode,
  SourceKind,
} from '../data/types';

const nf = new Intl.NumberFormat('vi-VN');

export const fmtNumber = (n: number) => nf.format(n);
export const fmtVnd = (n: number) => `${nf.format(Math.round(n))} ₫`;
export const fmtSignedVnd = (n: number) => `${n > 0 ? '+' : n < 0 ? '−' : ''}${nf.format(Math.abs(Math.round(n)))} ₫`;

/** 68.420.000 → "68,4 tr ₫"; 1.250.000.000 → "1,25 tỷ ₫" */
export function fmtVndShort(n: number) {
  const a = Math.abs(n);
  const sign = n < 0 ? '−' : '';
  if (a >= 1e9) return `${sign}${(a / 1e9).toLocaleString('vi-VN', { maximumFractionDigits: 2 })} tỷ ₫`;
  if (a >= 1e6) return `${sign}${(a / 1e6).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} tr ₫`;
  if (a >= 1e3) return `${sign}${(a / 1e3).toLocaleString('vi-VN', { maximumFractionDigits: 0 })} nghìn ₫`;
  return `${sign}${a} ₫`;
}

export const fmtPct = (x: number, digits = 1) =>
  `${(x * 100).toLocaleString('vi-VN', { minimumFractionDigits: digits, maximumFractionDigits: digits })}%`;

const p2 = (n: number) => String(n).padStart(2, '0');
export const fmtDate = (d: Date) => `${p2(d.getDate())}/${p2(d.getMonth() + 1)}/${d.getFullYear()}`;
export const fmtDayMonth = (d: Date) => `${p2(d.getDate())}/${p2(d.getMonth() + 1)}`;
export const fmtTime = (d: Date) => `${p2(d.getHours())}:${p2(d.getMinutes())}`;
export const fmtDateTime = (d: Date) => `${fmtTime(d)} · ${fmtDate(d)}`;

const WEEKDAYS = ['Chủ nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy'];
export const fmtLongDate = (d: Date) => `${WEEKDAYS[d.getDay()]}, ${fmtDate(d)}`;

export function fmtDuration(ms: number) {
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s} giây`;
  const m = Math.floor(s / 60);
  const r = s % 60;
  return r ? `${m} phút ${r} giây` : `${m} phút`;
}

/** Relative wording used for SLA: "còn 5 giờ", "quá 1 ngày". */
export function fmtSla(due: Date, now = clockNow()) {
  const diff = due.getTime() - now.getTime();
  const h = Math.round(Math.abs(diff) / 3_600_000);
  const text = h >= 24 ? `${Math.round(h / 24)} ngày` : h >= 1 ? `${h} giờ` : 'dưới 1 giờ';
  return { overdue: diff < 0, text: diff < 0 ? `Quá ${text}` : `Còn ${text}`, soon: diff >= 0 && diff < 6 * 3_600_000 };
}

export function fmtAgo(d: Date, now = clockNow()) {
  const m = Math.round((now.getTime() - d.getTime()) / 60_000);
  if (m < 1) return 'vừa xong';
  if (m < 60) return `${m} phút trước`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h} giờ trước`;
  return `${Math.round(h / 24)} ngày trước`;
}

export const initials = (name: string) => {
  const parts = name.split(' ');
  return (parts[parts.length - 2]?.[0] ?? '') + (parts[parts.length - 1]?.[0] ?? '');
};

// ---------------------------------------------------------------- labels

export const SOURCE_LABEL: Record<SourceCode, string> = {
  GHN: 'GHN',
  GHTK: 'GHTK',
  SHOPEE: 'Shopee',
  TIKTOK: 'TikTok Shop',
  VCB: 'Vietcombank',
  MOMO: 'MoMo',
  VTP: 'Viettel Post',
};

export const KIND_LABEL: Record<SourceKind, { label: string; icon: string }> = {
  CARRIER: { label: 'Vận chuyển (COD)', icon: 'pi pi-truck' },
  MARKETPLACE: { label: 'Sàn thương mại điện tử', icon: 'pi pi-shopping-bag' },
  BANK: { label: 'Ngân hàng', icon: 'pi pi-building' },
  EWALLET: { label: 'Ví điện tử', icon: 'pi pi-wallet' },
};

export const TYPE_META: Record<ExceptionType, { label: string; short: string; icon: string; tone: Tone }> = {
  UNMATCHED_PAYMENT: { label: 'Tiền về chưa xác định đơn', short: 'Tiền chưa rõ đơn', icon: 'pi pi-question-circle', tone: 'info' },
  UNPAID_ORDER: { label: 'Đơn đã giao chưa có tiền', short: 'Đơn chưa có tiền', icon: 'pi pi-hourglass', tone: 'warn' },
  AMOUNT_DIFF: { label: 'Lệch số tiền', short: 'Lệch tiền', icon: 'pi pi-arrows-h', tone: 'bad' },
  FEE_DIFF: { label: 'Phí bị tính sai', short: 'Sai phí', icon: 'pi pi-receipt', tone: 'bad' },
  DUPLICATE: { label: 'Giao dịch trùng', short: 'Trùng', icon: 'pi pi-copy', tone: 'neutral' },
};

export type Tone = 'good' | 'warn' | 'bad' | 'info' | 'pending' | 'neutral' | 'accent';

export const STATUS_META: Record<ExceptionStatus, { label: string; icon: string; tone: Tone }> = {
  OPEN: { label: 'Cần xử lý', icon: 'pi pi-circle', tone: 'warn' },
  IN_REVIEW: { label: 'Chờ đối tác', icon: 'pi pi-envelope', tone: 'info' },
  WRITE_OFF_PENDING: { label: 'Chờ duyệt', icon: 'pi pi-shield', tone: 'pending' },
  RESOLVED: { label: 'Đã khớp', icon: 'pi pi-check', tone: 'good' },
  WRITTEN_OFF: { label: 'Đã ghi giảm', icon: 'pi pi-check-circle', tone: 'neutral' },
};

export const FILE_STATUS_META: Record<FileStatus, { label: string; icon: string; tone: Tone }> = {
  PROCESSING: { label: 'Đang nạp', icon: 'pi pi-spin pi-spinner', tone: 'info' },
  COMPLETED: { label: 'Đã nạp', icon: 'pi pi-check', tone: 'good' },
  COMPLETED_WITH_ERRORS: { label: 'Nạp, có dòng lỗi', icon: 'pi pi-exclamation-triangle', tone: 'warn' },
  FAILED: { label: 'Thất bại', icon: 'pi pi-times', tone: 'bad' },
  DUPLICATE: { label: 'Trùng, bỏ qua', icon: 'pi pi-copy', tone: 'neutral' },
};

export const BATCH_STATUS_META: Record<BatchStatus, { label: string; icon: string; tone: Tone }> = {
  STARTED: { label: 'Đang chạy', icon: 'pi pi-spin pi-spinner', tone: 'info' },
  COMPLETED: { label: 'Hoàn tất', icon: 'pi pi-check', tone: 'good' },
  FAILED: { label: 'Lỗi', icon: 'pi pi-times', tone: 'bad' },
  STOPPED: { label: 'Đã dừng', icon: 'pi pi-ban', tone: 'neutral' },
};

export const METHOD_META: Record<MatchMethod, { label: string; tone: Tone }> = {
  EXACT: { label: 'Khớp chính xác', tone: 'good' },
  AMOUNT_DATE: { label: 'Số tiền + ngày', tone: 'info' },
  MANY_TO_ONE: { label: 'Một khoản, nhiều đơn', tone: 'info' },
  MANUAL: { label: 'Thủ công', tone: 'neutral' },
  AI_CONFIRMED: { label: 'AI gợi ý, người xác nhận', tone: 'accent' },
};

export const ACTION_LABEL: Record<ActionKind, string> = {
  CREATED: 'Tạo ngoại lệ',
  AI_SUGGESTED: 'AI đưa gợi ý',
  ASSIGNED: 'Giao việc',
  COMMENT: 'Bình luận',
  MATCHED: 'Đã khớp',
  CLAIM_SENT: 'Gửi yêu cầu đối soát',
  WRITE_OFF_REQUESTED: 'Đề nghị ghi giảm',
  APPROVED: 'Duyệt',
  REJECTED: 'Từ chối',
  MARKED_DUPLICATE: 'Đánh dấu trùng',
};

export const FIELD_META: Record<CanonicalField, { label: string; required: boolean }> = {
  externalRef: { label: 'Mã tham chiếu giao dịch', required: true },
  orderCode: { label: 'Mã đơn hàng', required: false },
  trackingCode: { label: 'Mã vận đơn', required: false },
  amount: { label: 'Số tiền thực nhận', required: true },
  fee: { label: 'Phí bị trừ', required: false },
  txnDate: { label: 'Ngày giao dịch', required: true },
  description: { label: 'Nội dung / ghi chú', required: false },
};

export const ROLE_LABEL = { ACCOUNTANT: 'Kế toán', APPROVER: 'Người duyệt', ADMIN: 'Quản trị' } as const;
