// Mock API on generated sample data. http.ts has the same exports and talks to the Spring Boot API;
// vite.config.ts picks one of the two with VITE_API_MODE (see README).
import {
  ACCOUNTS,
  ACTIVE_SOURCES,
  AGING,
  AGING_BUCKETS,
  BATCH,
  BATCH_SCHEDULE,
  DEMO_NOW,
  EXCEPTIONS,
  FILES,
  ORDERS,
  RECON_DAYS,
  SOURCES,
  USERS,
  WRITE_OFF_APPROVAL_THRESHOLD,
  daysAgo,
  fileErrors,
  journalFor,
  matchGroupsFor,
  startOfDay,
  stripAccents,
} from '../data/seed';
import type {
  BatchExplanation,
  ExceptionType,
  ImportFile,
  JobExecution,
  MappingRow,
  ReconDay,
  ReconException,
  SourceCode,
  User,
} from '../data/types';
import { SOURCE_LABEL, fmtVnd, fmtPct, fmtVndShort } from '../lib/format';
import type { Meta } from '../state/meta';

export const MODE = 'mock' as const;

/** Reference data for the screens, here straight from the sample data. */
export async function loadMeta(): Promise<Meta> {
  return {
    users: USERS,
    sources: SOURCES,
    activeSources: ACTIVE_SOURCES,
    batchSchedule: BATCH_SCHEDULE,
    accounts: ACCOUNTS,
    agingBuckets: AGING_BUCKETS,
    writeOffApprovalThreshold: WRITE_OFF_APPROVAL_THRESHOLD,
    slaHours: 48,
    aiEnabled: false,
    aiModel: null,
    fixedNow: DEMO_NOW,
    serverOffsetMs: 0,
  };
}

/** The mock has no accounts: the top bar lets you switch between the sample users instead. */
export async function login(username: string, _password: string): Promise<User> {
  const u = USERS.find((x) => stripAccents(x.name).toLowerCase().includes(username.toLowerCase())) ?? USERS[0];
  return u;
}

export function logout() {}

export async function currentUser(): Promise<User | null> {
  return null;
}

export function onUnauthorized(_fn: () => void) {}

const wait = (ms = 140) => new Promise((r) => setTimeout(r, ms));

// ---- change notifications: pages reload when mock data changes in the background
let version = 0;
const listeners = new Set<() => void>();
export function subscribe(fn: () => void) {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}
export const getVersion = () => version;
function notify() {
  version++;
  listeners.forEach((l) => l());
}

export class ApiError extends Error {}

const isOpen = (e: ReconException) => e.status === 'OPEN' || e.status === 'IN_REVIEW';
// The SLA clock stops while a claim waits for the partner (IN_REVIEW).
const isOverdue = (e: ReconException) => e.status === 'OPEN' && e.slaDueAt < DEMO_NOW;
const sameDay = (a: Date, b: Date) => startOfDay(a).getTime() === startOfDay(b).getTime();
const norm = (s: string) => stripAccents(s).toLowerCase();

function rate(days: ReconDay[]) {
  const total = days.reduce((s, d) => s + d.totalLines, 0);
  const auto = days.reduce((s, d) => s + d.autoMatched, 0);
  return total ? auto / total : 0;
}

const PERIODIC_DAYS: Partial<Record<SourceCode, number[]>> = { SHOPEE: [1, 4], TIKTOK: [2] };

export type FileArrival =
  | { source: SourceCode; state: 'received'; at: Date; rows: number }
  | { source: SourceCode; state: 'late'; minutes: number; expected: string }
  | { source: SourceCode; state: 'waiting'; expected: string }
  | { source: SourceCode; state: 'due' }
  | { source: SourceCode; state: 'none' };

export function fileArrivals(): FileArrival[] {
  return ACTIVE_SOURCES.map((code): FileArrival => {
    const s = SOURCES.find((x) => x.code === code)!;
    const f = FILES.find((x) => x.source === code && sameDay(x.receivedAt, DEMO_NOW) && x.status !== 'DUPLICATE' && x.status !== 'FAILED');
    if (f) return { source: code, state: 'received', at: f.receivedAt, rows: f.totalRows };
    if (s.expectedHour == null) return PERIODIC_DAYS[code]?.includes(DEMO_NOW.getDay()) ? { source: code, state: 'due' } : { source: code, state: 'none' };
    const expected = `${String(s.expectedHour).padStart(2, '0')}:00`;
    const minutes = (DEMO_NOW.getHours() - s.expectedHour) * 60 + DEMO_NOW.getMinutes();
    return minutes > 15 ? { source: code, state: 'late', minutes, expected } : { source: code, state: 'waiting', expected };
  });
}

// ---------------------------------------------------------------- dashboard

export async function getDashboard() {
  await wait();
  const last7 = RECON_DAYS.filter((d) => d.date >= daysAgo(6));
  const prev7 = RECON_DAYS.filter((d) => d.date >= daysAgo(13) && d.date < daysAgo(6));
  const open = EXCEPTIONS.filter(isOpen);
  const trend = Array.from({ length: 30 }, (_, i) => {
    const day = daysAgo(29 - i);
    const ds = RECON_DAYS.filter((d) => d.date.getTime() === day.getTime());
    return { date: day, rate: rate(ds), lines: ds.reduce((s, d) => s + d.totalLines, 0) };
  });
  const bySource = ACTIVE_SOURCES.map((code) => ({
    source: code,
    amount: open.filter((e) => e.source === code).reduce((s, e) => s + Math.abs(e.diffAmount), 0),
    count: open.filter((e) => e.source === code).length,
  })).sort((a, b) => b.amount - a.amount);
  const feeOct = EXCEPTIONS.filter((e) => e.type === 'FEE_DIFF' && e.createdAt.getMonth() === DEMO_NOW.getMonth());
  return {
    autoRate7: rate(last7),
    autoRatePrev7: rate(prev7),
    open: open.length,
    overdue: open.filter(isOverdue).length,
    pendingApproval: EXCEPTIONS.filter((e) => e.status === 'WRITE_OFF_PENDING').length,
    unreconciled: bySource.reduce((s, x) => s + x.amount, 0),
    agingOver7: AGING.reduce((s, r) => s + r.buckets[1] + r.buckets[2] + r.buckets[3], 0),
    agingOver30: AGING.reduce((s, r) => s + r.buckets[2] + r.buckets[3], 0),
    feeDiffAmount: feeOct.reduce((s, e) => s + Math.abs(e.diffAmount), 0),
    feeDiffCount: feeOct.length,
    trend,
    bySource,
    failedJobs: BATCH.filter((b) => b.status === 'FAILED' && !b.restartedAs),
    arrivals: fileArrivals(),
    latest: open.filter((e) => e.status === 'OPEN').slice(0, 6),
  };
}

// ---------------------------------------------------------------- exceptions

export type ExceptionTab = 'todo' | 'pending' | 'done' | 'all';
export interface ExceptionFilter {
  tab: ExceptionTab;
  types: ExceptionType[];
  sources: SourceCode[];
  assignee: string;
  overdueOnly: boolean;
  q: string;
}

const TAB_TEST: Record<ExceptionTab, (e: ReconException) => boolean> = {
  todo: isOpen,
  pending: (e) => e.status === 'WRITE_OFF_PENDING',
  done: (e) => e.status === 'RESOLVED' || e.status === 'WRITTEN_OFF',
  all: () => true,
};

export async function listExceptions(f: ExceptionFilter) {
  await wait();
  const q = norm(f.q.trim());
  const rows = EXCEPTIONS.filter((e) => {
    if (!TAB_TEST[f.tab](e)) return false;
    if (f.types.length && !f.types.includes(e.type)) return false;
    if (f.sources.length && !f.sources.includes(e.source)) return false;
    if (f.assignee === 'none' && e.assigneeId) return false;
    if (f.assignee !== 'all' && f.assignee !== 'none' && e.assigneeId !== f.assignee) return false;
    if (f.overdueOnly && !isOverdue(e)) return false;
    if (q) {
      const hay = norm([e.id, ...e.orders.map((o) => `${o.code} ${o.customer} ${o.trackingCode ?? ''}`), ...e.payments.map((p) => `${p.externalRef} ${p.description}`)].join(' '));
      if (!hay.includes(q)) return false;
    }
    return true;
  });
  // Work first, then claims waiting for a partner (their SLA is paused).
  if (f.tab === 'todo') rows.sort((a, b) => Number(a.status !== 'OPEN') - Number(b.status !== 'OPEN') || a.slaDueAt.getTime() - b.slaDueAt.getTime());
  return rows;
}

export async function exceptionCounts() {
  await wait(60);
  return {
    todo: EXCEPTIONS.filter(TAB_TEST.todo).length,
    pending: EXCEPTIONS.filter(TAB_TEST.pending).length,
    done: EXCEPTIONS.filter(TAB_TEST.done).length,
    all: EXCEPTIONS.length,
    overdue: EXCEPTIONS.filter(isOverdue).length,
  };
}

export async function getException(id: string) {
  await wait();
  const e = EXCEPTIONS.find((x) => x.id === id);
  if (!e) throw new ApiError(`Không tìm thấy ngoại lệ ${id}`);
  return { ...e, actions: [...e.actions] };
}

function mutate(id: string, expectedVersion: number, fn: (e: ReconException) => void) {
  const e = EXCEPTIONS.find((x) => x.id === id);
  if (!e) throw new ApiError(`Không tìm thấy ngoại lệ ${id}`);
  // Optimistic locking, as the backend will do with a @Version column.
  if (e.version !== expectedVersion) throw new ApiError('Ngoại lệ vừa được người khác cập nhật. Tải lại trang để xem thay đổi mới nhất.');
  fn(e);
  e.version++;
  notify();
}

const now = () => new Date(DEMO_NOW.getTime() + (Date.now() % 600_000));

export async function assignExceptions(ids: string[], userId: string | null, actor: User) {
  await wait();
  const target = USERS.find((u) => u.id === userId);
  for (const id of ids) {
    const e = EXCEPTIONS.find((x) => x.id === id);
    if (!e) continue;
    e.assigneeId = userId;
    e.actions.push({ at: now(), actor: actor.name, kind: 'ASSIGNED', note: target ? `Giao cho ${target.name}` : 'Bỏ giao việc' });
    e.version++;
  }
  notify();
}

export async function matchException(id: string, v: number, orderCodes: string[], viaAi: boolean, actor: User) {
  await wait(260);
  mutate(id, v, (e) => {
    e.status = 'RESOLVED';
    e.actions.push({ at: now(), actor: actor.name, kind: 'MATCHED', note: `Khớp với ${orderCodes.join(', ')}${viaAi ? ' theo gợi ý AI' : ' (chọn thủ công)'}` });
  });
}

export async function requestWriteOff(id: string, v: number, reason: string, note: string, actor: User) {
  await wait(260);
  let autoApproved = false;
  mutate(id, v, (e) => {
    const amount = Math.abs(e.diffAmount);
    e.writeOff = { requestedBy: actor.name, requestedById: actor.id, reason, note, amount };
    e.actions.push({ at: now(), actor: actor.name, kind: 'WRITE_OFF_REQUESTED', note: `${reason} · ${fmtVnd(amount)}${note ? ` · ${note}` : ''}` });
    if (amount <= WRITE_OFF_APPROVAL_THRESHOLD) {
      autoApproved = true;
      e.status = 'WRITTEN_OFF';
      e.actions.push({ at: now(), actor: 'Hệ thống', kind: 'APPROVED', note: `Tự duyệt vì không vượt ${fmtVnd(WRITE_OFF_APPROVAL_THRESHOLD)}` });
    } else {
      e.status = 'WRITE_OFF_PENDING';
    }
  });
  return { autoApproved };
}

export async function decideWriteOff(id: string, v: number, approve: boolean, note: string, actor: User) {
  await wait(260);
  const e = EXCEPTIONS.find((x) => x.id === id);
  if (actor.role !== 'APPROVER') throw new ApiError('Chỉ Kế toán trưởng được duyệt ghi giảm.');
  if (e?.writeOff?.requestedById === actor.id) throw new ApiError('Người đề nghị không được tự duyệt đề nghị của mình.');
  mutate(id, v, (x) => {
    x.status = approve ? 'WRITTEN_OFF' : 'OPEN';
    x.actions.push({ at: now(), actor: actor.name, kind: approve ? 'APPROVED' : 'REJECTED', note: note || (approve ? 'Đồng ý ghi giảm' : 'Không đồng ý') });
    if (!approve) x.writeOff = null;
  });
}

export async function sendClaim(id: string, v: number, note: string, actor: User) {
  await wait(260);
  mutate(id, v, (e) => {
    e.status = 'IN_REVIEW';
    e.actions.push({ at: now(), actor: actor.name, kind: 'CLAIM_SENT', note: note || `Đã gửi yêu cầu đối soát cho ${SOURCE_LABEL[e.source]}` });
  });
}

export async function markDuplicate(id: string, v: number, actor: User) {
  await wait(260);
  mutate(id, v, (e) => {
    e.status = 'RESOLVED';
    e.actions.push({ at: now(), actor: actor.name, kind: 'MARKED_DUPLICATE', note: `Bỏ qua dòng ${e.payments[1]?.id ?? ''} trong ${e.payments[1]?.fileName ?? 'file bổ sung'}` });
  });
}

export async function addComment(id: string, v: number, text: string, actor: User) {
  await wait(120);
  mutate(id, v, (e) => {
    e.actions.push({ at: now(), actor: actor.name, kind: 'COMMENT', note: text });
  });
}

/** The mock keeps the generated suggestion; the real API asks the LLM again when one is configured. */
export async function refreshSuggestion(id: string) {
  await wait(900);
  return getException(id);
}

export async function searchOrders(q: string) {
  await wait(120);
  const s = norm(q.trim());
  if (!s) return ORDERS.slice(0, 12);
  const n = Number(s.replace(/[^\d]/g, ''));
  return ORDERS.filter((o) => norm(`${o.code} ${o.customer} ${o.trackingCode ?? ''}`).includes(s) || (n > 1000 && o.amount === n)).slice(0, 20);
}

// ---------------------------------------------------------------- sources

export async function listSources() {
  await wait();
  return SOURCES.map((s) => ({ ...s }));
}

const VTP_SUGGESTION: MappingRow[] = [
  { field: 'externalRef', column: 'Mã vận đơn', confidence: 0.97, aiNote: 'Giá trị dạng VTP + 10 chữ số, duy nhất trên mỗi dòng' },
  { field: 'orderCode', column: 'Mã đơn hàng KH', confidence: 0.95, aiNote: 'Khớp mẫu mã đơn DH + 10 chữ số của OMS' },
  { field: 'trackingCode', column: 'Mã vận đơn', confidence: 0.96 },
  { field: 'amount', column: 'Thực trả', confidence: 0.72, aiNote: 'Bằng "Tiền thu hộ (COD)" trừ "Tổng cước" và "Phí thu hộ" ở cả 3 dòng mẫu. Cần người xác nhận.' },
  { field: 'fee', column: 'Tổng cước', confidence: 0.61, aiNote: 'Phí nằm ở 2 cột: "Tổng cước" và "Phí thu hộ". Hiện chỉ ánh xạ được 1 cột; cần quy tắc cộng 2 cột.' },
  { field: 'txnDate', column: 'Ngày đối soát', confidence: 0.93, aiNote: 'Định dạng dd/MM/yyyy' },
  { field: 'description', column: 'Ghi chú', confidence: 0.88 },
];

export async function suggestMapping(code: SourceCode) {
  await wait(1400);
  const s = SOURCES.find((x) => x.code === code)!;
  if (code === 'VTP') return VTP_SUGGESTION.map((m) => ({ ...m }));
  return s.mapping.map((m) => ({ ...m, confidence: m.column ? 0.9 + ((m.column.length % 9) / 100) : undefined }));
}

export async function saveMapping(code: SourceCode, mapping: MappingRow[]) {
  await wait(300);
  const s = SOURCES.find((x) => x.code === code)!;
  s.mapping = mapping.map((m) => ({ ...m }));
  notify();
}

// ---------------------------------------------------------------- files

export async function listFiles() {
  await wait();
  return [...FILES];
}

export async function getFileErrors(id: string) {
  await wait();
  const f = FILES.find((x) => x.id === id);
  return f ? fileErrors(f) : [];
}

async function sha256(file: File) {
  try {
    const buf = await file.arrayBuffer();
    const hash = await crypto.subtle.digest('SHA-256', buf);
    return Array.from(new Uint8Array(hash)).map((b) => b.toString(16).padStart(2, '0')).join('');
  } catch {
    return `${file.name}-${file.size}-${file.lastModified}`;
  }
}

let uploadSeq = 900;
export async function uploadFile(source: SourceCode, file: File, actor: User): Promise<ImportFile> {
  const fileName = file.name;
  const checksum = await sha256(file);
  await wait(400);
  const dup = FILES.find((f) => f.checksum === checksum && f.status !== 'DUPLICATE');
  const base: ImportFile = {
    id: `F${String(++uploadSeq).padStart(5, '0')}`,
    source,
    fileName,
    receivedAt: now(),
    status: dup ? 'DUPLICATE' : 'PROCESSING',
    totalRows: 0,
    errorRows: 0,
    amountTotal: 0,
    checksum,
    jobExecutionId: dup ? null : nextExecId(),
    message: dup ? `Trùng checksum với ${dup.fileName} (nhận ${dup.receivedAt.toLocaleString('vi-VN')}). Không nạp lại.` : `Tải lên bởi ${actor.name}`,
  };
  FILES.unshift(base);
  notify();
  if (!dup) {
    const exec: JobExecution = {
      id: base.jobExecutionId!, job: 'fileIngestionJob', params: { source, file: fileName }, status: 'STARTED',
      startTime: now(), endTime: null, steps: [{ name: 'validateFileStep', status: 'COMPLETED', read: 1, write: 1, skip: 0, commits: 1, durationMs: 500 }],
    };
    BATCH.unshift(exec);
    setTimeout(() => {
      const rows = 300 + Math.floor(Math.random() * 900);
      const errors = Math.random() < 0.6 ? 1 + Math.floor(Math.random() * 5) : 0;
      Object.assign(base, { status: errors ? 'COMPLETED_WITH_ERRORS' : 'COMPLETED', totalRows: rows, errorRows: errors, amountTotal: rows * 412_000 });
      Object.assign(exec, {
        status: 'COMPLETED', endTime: now(),
        steps: [
          ...exec.steps,
          { name: 'parseAndStageStep', status: 'COMPLETED', read: rows, write: rows - errors, skip: errors, commits: Math.ceil(rows / 500), durationMs: 2100 },
          { name: 'publishEventsStep', status: 'COMPLETED', read: rows - errors, write: rows - errors, skip: 0, commits: 1, durationMs: 300 },
        ],
      });
      notify();
    }, 2600);
  }
  return base;
}

// ---------------------------------------------------------------- results

export async function listReconDays(from: Date, to: Date, sources: SourceCode[]) {
  await wait();
  return RECON_DAYS.filter((d) => d.date >= startOfDay(from) && d.date <= startOfDay(to) && (!sources.length || sources.includes(d.source)));
}

export async function listMatchGroups(day: ReconDay) {
  await wait();
  return matchGroupsFor(day);
}

// ---------------------------------------------------------------- aging & journal

export async function getAging() {
  await wait();
  return AGING.map((r) => ({ ...r }));
}

export async function getJournal(year: number, month: number) {
  await wait();
  return journalFor(year, month);
}

// ---------------------------------------------------------------- batch

function nextExecId() {
  return Math.max(...BATCH.map((b) => b.id)) + 1;
}

export async function listExecutions() {
  await wait();
  return BATCH.map((b) => ({ ...b }));
}

export async function restartExecution(id: number): Promise<{ message: string }> {
  await wait(300);
  const old = BATCH.find((b) => b.id === id);
  if (!old) throw new ApiError('Không tìm thấy lần chạy');
  const fresh: JobExecution = {
    id: nextExecId(), job: old.job, params: { ...old.params, restartOf: `#${old.id}` }, status: 'STARTED', startTime: now(), endTime: null,
    steps: old.steps.filter((s) => s.status === 'COMPLETED').map((s) => ({ ...s, read: 0, write: 0, commits: 0, durationMs: 0, exitMessage: 'Bỏ qua: đã hoàn tất ở lần chạy trước' })),
  };
  old.restartedAs = fresh.id;
  BATCH.unshift(fresh);
  notify();
  setTimeout(() => {
    fresh.status = 'COMPLETED';
    fresh.endTime = now();
    fresh.steps = [
      ...fresh.steps,
      ...old.steps.filter((s) => s.status !== 'COMPLETED').map((s) => ({ ...s, status: 'COMPLETED' as const, write: s.read, skip: 0, commits: 1, durationMs: 3800, exitMessage: undefined })),
    ];
    notify();
  }, 3200);
  return { message: `Lần chạy mới #${fresh.id}. Các bước đã xong được bỏ qua.` };
}

export async function runJob(job: string): Promise<{ message: string }> {
  await wait(300);
  return { message: `Bản mẫu không chạy ${job} thật; khi nối với backend, lệnh được gửi cho dịch vụ batch qua JMS.` };
}

export async function explainExecution(id: number): Promise<BatchExplanation> {
  await wait(1300);
  const ex = BATCH.find((b) => b.id === id);
  if (ex?.job === 'agingAndReportJob') {
    return {
      cause: 'Bước emailReportStep không kết nối được máy chủ mail sau 3 lần thử. Hai bước trước đã hoàn tất, nên số liệu tuổi nợ và file bút toán đã có; chỉ có email báo cáo chưa được gửi.',
      evidence: [
        '06:00:00.412 INFO  SimpleJobLauncher - Job: [agingAndReportJob] launched with parameters: [{asOf=22/10/2026}]',
        '06:00:11.807 INFO  AbstractStep - Step: [agingSnapshotStep] executed in 11s395ms',
        '06:00:17.902 INFO  AbstractStep - Step: [journalExportStep] executed in 6s95ms',
        '06:00:48.115 WARN  ReportMailer - Lần thử 1/3 thất bại: Read timed out (smtp.shop.vn:587)',
        '06:01:19.330 WARN  ReportMailer - Lần thử 2/3 thất bại: Read timed out (smtp.shop.vn:587)',
        '06:01:36.004 ERROR AbstractStep - Encountered an error executing step emailReportStep: MailSendException',
      ],
      steps: [
        'Kiểm tra máy chủ mail từ máy chạy batch: nc -vz smtp.shop.vn 587',
        'Khi máy chủ mail hoạt động lại, bấm "Chạy lại". Spring Batch bỏ qua 2 bước đã xong và chỉ chạy lại emailReportStep.',
        'Nếu lỗi kéo dài, mở mục Tuổi nợ & bút toán và gửi số liệu cho Kế toán trưởng theo cách thủ công.',
      ],
    };
  }
  if (ex?.job === 'fileIngestionJob') {
    return {
      cause: 'File có 214 dòng không đọc được cột "Ngày": đối tác đổi định dạng từ "dd-MM-yyyy HH:mm" sang dạng "20-10-2026 14h05". Số dòng lỗi vượt ngưỡng 100, nên job dừng để tránh nạp thiếu dữ liệu.',
      evidence: [
        'FlatFileParseException: Parsing error at line: 2 in resource=[GHTK_COD_20261020.csv], input=[...,"20-10-2026 09h12",...]',
        'FlatFileParseException: Parsing error at line: 3 ... input=[...,"20-10-2026 09h12",...]',
        'SkipLimitExceededException: Skip limit of 100 exceeded',
      ],
      steps: [
        'Không cần chạy lại: GHTK đã gửi file _v2 đúng định dạng lúc 14:37 và đã nạp thành công.',
        'Nếu đối tác giữ định dạng mới, thêm mẫu ngày dd-MM-yyyy HH\'h\'mm vào cấu hình nguồn GHTK.',
      ],
    };
  }
  return { cause: 'Không có lỗi nào cần giải thích cho lần chạy này.', evidence: [], steps: [] };
}

// ---------------------------------------------------------------- assistant

export interface AssistantAnswer {
  text: string;
  tools: string[];
  engine?: 'llm' | 'rules';
  table?: { columns: string[]; rows: (string | number)[][] };
  chart?: { labels: string[]; values: number[]; label: string };
}

export async function ask(question: string): Promise<AssistantAnswer> {
  await wait(1100);
  return answerFor(question);
}

/** Answers instantly from the sample data; the HTTP client has no synchronous answer and returns null. */
export function answer(question: string): AssistantAnswer | null {
  return answerFor(question);
}

function answerFor(question: string): AssistantAnswer {
  const q = norm(question);
  if (/ghn|ghtk|shopee|tiktok/.test(q) && /(no|con|phai thu|chua tra)/.test(q)) {
    const code = (['GHN', 'GHTK', 'SHOPEE', 'TIKTOK'] as SourceCode[]).find((c) => q.includes(c.toLowerCase())) ?? 'GHN';
    const row = AGING.find((r) => r.source === code)!;
    const total = row.buckets.reduce((s, x) => s + x, 0);
    return {
      text: `${SOURCE_LABEL[code]} đang còn phải trả ${fmtVnd(total)}. Phần lớn (${fmtVndShort(row.buckets[0])}) là tiền thu hộ dưới 7 ngày, chưa tới kỳ đối soát. Phần đáng chú ý là ${fmtVndShort(row.buckets[2] + row.buckets[3])} đã quá 30 ngày.`,
      tools: [`getReceivables(source=${code})`],
      table: { columns: ['Tuổi nợ', 'Số tiền'], rows: [['0–7 ngày', fmtVnd(row.buckets[0])], ['8–30 ngày', fmtVnd(row.buckets[1])], ['31–60 ngày', fmtVnd(row.buckets[2])], ['Trên 60 ngày', fmtVnd(row.buckets[3])]] },
    };
  }
  if (/ty le/.test(q) && /khop/.test(q)) {
    const rows = ACTIVE_SOURCES.map((c) => {
      const ds = RECON_DAYS.filter((d) => d.source === c && d.date >= daysAgo(6));
      return [SOURCE_LABEL[c], ds.reduce((s, d) => s + d.totalLines, 0), fmtPct(rate(ds))] as (string | number)[];
    });
    const worst = [...rows].sort((a, b) => parseFloat(String(a[2]).replace(',', '.')) - parseFloat(String(b[2]).replace(',', '.')))[0];
    return {
      text: `Tỷ lệ khớp tự động 7 ngày qua là ${fmtPct(rate(RECON_DAYS.filter((d) => d.date >= daysAgo(6))))}. Thấp nhất là ${worst[0]} (${worst[2]}), chủ yếu do nội dung chuyển khoản không ghi mã đơn.`,
      tools: ['getMatchRate(from=16/10, to=22/10, groupBy=source)'],
      table: { columns: ['Nguồn', 'Số giao dịch', 'Khớp tự động'], rows },
    };
  }
  if (/phi/.test(q)) {
    const month = RECON_DAYS.filter((d) => d.date.getMonth() === DEMO_NOW.getMonth());
    const items = ACTIVE_SOURCES.map((c) => ({ c, v: month.filter((d) => d.source === c).reduce((s, d) => s + d.feeTotal, 0) })).filter((x) => x.v > 0).sort((a, b) => b.v - a.v);
    const total = items.reduce((s, x) => s + x.v, 0);
    return {
      text: `Từ 01/10 đến nay, tổng phí bị trừ là ${fmtVnd(total)}. ${SOURCE_LABEL[items[0].c]} chiếm nhiều nhất với ${fmtVndShort(items[0].v)}.`,
      tools: ['getFees(from=01/10, to=22/10, groupBy=source)'],
      chart: { labels: items.map((x) => SOURCE_LABEL[x.c]), values: items.map((x) => x.v), label: 'Phí bị trừ' },
    };
  }
  if (/qua han|ngoai le|ton dong/.test(q)) {
    const od = EXCEPTIONS.filter(isOverdue).sort((a, b) => a.slaDueAt.getTime() - b.slaDueAt.getTime());
    return {
      text: `Đang có ${od.length} ngoại lệ quá hạn xử lý (quá 48 giờ). Dưới đây là 5 ngoại lệ quá hạn lâu nhất.`,
      tools: ['getExceptions(status=OPEN,IN_REVIEW, overdue=true, limit=5)'],
      table: { columns: ['Mã', 'Nguồn', 'Chênh lệch'], rows: od.slice(0, 5).map((e) => [e.id, SOURCE_LABEL[e.source], fmtVnd(e.diffAmount)]) },
    };
  }
  return {
    text: 'Bản demo này chỉ trả lời các câu hỏi mẫu bên dưới. Khi nối với backend, câu hỏi sẽ được gửi qua Spring AI tới LLM, và LLM chỉ được gọi 6 tool chỉ đọc theo quyền của bạn.',
    tools: [],
  };
}
