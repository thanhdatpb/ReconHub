// HTTP client for the Spring Boot API. Same exports as mock.ts; vite.config.ts picks one with VITE_API_MODE.
import type {
  BatchExplanation,
  FileErrorRow,
  ImportFile,
  JobExecution,
  JournalEntry,
  AgingRow,
  MappingRow,
  MatchGroup,
  Order,
  ReconDay,
  ReconException,
  Source,
  SourceCode,
  User,
} from '../data/types';
import type { Meta } from '../state/meta';
import type { AssistantAnswer, ExceptionFilter, FileArrival } from './mock';

export type { AssistantAnswer, ExceptionFilter, ExceptionTab, FileArrival } from './mock';

export const MODE = 'http' as const;
const BASE = (import.meta.env.VITE_API_BASE as string | undefined) ?? '/api';
const TOKEN_KEY = 'reconhub.token';

export class ApiError extends Error {
  constructor(message: string, readonly status = 0) {
    super(message);
  }
}

// ---------------------------------------------------------------- change notifications and polling

let version = 0;
const listeners = new Set<() => void>();
export function subscribe(fn: () => void) {
  listeners.add(fn);
  if (listeners.size === 1) startPolling();
  return () => {
    listeners.delete(fn);
  };
}
export const getVersion = () => version;
function notify() {
  version++;
  listeners.forEach((l) => l());
}

let pollTimer: number | undefined;
let burstUntil = 0;
/** Screens refresh every 30 s, and every 2 s for a minute after starting background work (upload, restart). */
function startPolling() {
  if (pollTimer) return;
  let last = Date.now();
  pollTimer = window.setInterval(() => {
    const t = Date.now();
    const every = t < burstUntil ? 2_000 : 30_000;
    if (t - last >= every && document.visibilityState === 'visible' && token) {
      last = t;
      notify();
    }
  }, 1_000);
}
function followBackgroundWork() {
  burstUntil = Date.now() + 60_000;
}

// ---------------------------------------------------------------- auth

function readToken() {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}
let token: string | null = readToken();
let unauthorized: () => void = () => {};

export function onUnauthorized(fn: () => void) {
  unauthorized = fn;
}

export async function login(username: string, password: string): Promise<User> {
  const res = await request<{ token: string; user: User }>('POST', '/auth/login', { username, password }, { anonymous: true });
  token = res.token;
  try {
    sessionStorage.setItem(TOKEN_KEY, res.token);
  } catch {
    // private mode: the token lives until the tab is closed
  }
  return res.user;
}

export function logout() {
  token = null;
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // ignore
  }
}

export async function currentUser(): Promise<User | null> {
  if (!token) return null;
  try {
    return await request<User>('GET', '/me');
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- transport

/** Instants arrive as ISO strings and dates as yyyy-MM-dd; both become Date (dates at local midnight). */
const DATE_KEYS = new Set(['createdAt', 'slaDueAt', 'receivedAt', 'at', 'date', 'txnDate', 'deliveredAt', 'startTime', 'endTime', 'now', 'updatedAt', 'expiresAt']);
function revive(key: string, value: unknown) {
  if (typeof value !== 'string' || !DATE_KEYS.has(key)) return value;
  const d = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (d) return new Date(Number(d[1]), Number(d[2]) - 1, Number(d[3]));
  const t = Date.parse(value);
  return Number.isNaN(t) ? value : new Date(t);
}

async function request<T>(method: string, path: string, body?: unknown, opts: { anonymous?: boolean; form?: FormData } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (token && !opts.anonymous) headers.Authorization = `Bearer ${token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch(BASE + path, { method, headers, body: opts.form ?? (body === undefined ? undefined : JSON.stringify(body)) });
  } catch {
    throw new ApiError('Không kết nối được máy chủ ReconHub. Kiểm tra dịch vụ api đã chạy chưa.');
  }
  const text = await res.text();
  const data = text ? JSON.parse(text, revive) : null;
  if (res.status === 401 && !opts.anonymous) {
    logout();
    unauthorized();
  }
  if (!res.ok) throw new ApiError((data && data.message) || `Lỗi ${res.status}`, res.status);
  return data as T;
}

const ymd = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

async function mutate<T>(method: string, path: string, body?: unknown): Promise<T> {
  const r = await request<T>(method, path, body);
  notify();
  return r;
}

// ---------------------------------------------------------------- meta

interface MetaDto extends Omit<Meta, 'fixedNow' | 'serverOffsetMs'> {
  now: Date;
}

export async function loadMeta(): Promise<Meta> {
  const m = await request<MetaDto>('GET', '/meta');
  return { ...m, fixedNow: null, serverOffsetMs: m.now.getTime() - Date.now() };
}

// ---------------------------------------------------------------- dashboard

export interface Dashboard {
  autoRate7: number;
  autoRatePrev7: number;
  open: number;
  overdue: number;
  pendingApproval: number;
  unreconciled: number;
  agingOver7: number;
  agingOver30: number;
  feeDiffAmount: number;
  feeDiffCount: number;
  trend: { date: Date; rate: number; lines: number }[];
  bySource: { source: SourceCode; amount: number; count: number }[];
  failedJobs: JobExecution[];
  arrivals: FileArrival[];
  latest: ReconException[];
}

export const getDashboard = () => request<Dashboard>('GET', '/dashboard');

// ---------------------------------------------------------------- exceptions

export function listExceptions(f: ExceptionFilter) {
  const p = new URLSearchParams({ tab: f.tab, assignee: f.assignee, overdue: String(f.overdueOnly) });
  if (f.types.length) p.set('type', f.types.join(','));
  if (f.sources.length) p.set('source', f.sources.join(','));
  if (f.q.trim()) p.set('q', f.q.trim());
  return request<ReconException[]>('GET', `/exceptions?${p}`);
}

export const exceptionCounts = () => request<{ todo: number; pending: number; done: number; all: number; overdue: number }>('GET', '/exceptions/counts');

export const getException = (id: string) => request<ReconException>('GET', `/exceptions/${encodeURIComponent(id)}`);

export async function assignExceptions(ids: string[], userId: string | null, _actor: User) {
  await mutate('POST', '/exceptions/assign', { ids, assigneeId: userId });
}

export async function matchException(id: string, version: number, orderCodes: string[], viaAi: boolean, _actor: User) {
  await mutate('POST', `/exceptions/${id}/match`, { version, orderCodes, viaAi });
}

export async function requestWriteOff(id: string, version: number, reason: string, note: string, _actor: User) {
  const r = await mutate<{ autoApproved: boolean }>('POST', `/exceptions/${id}/write-off`, { version, reason, note });
  return { autoApproved: r.autoApproved };
}

export async function decideWriteOff(id: string, version: number, approve: boolean, note: string, _actor: User) {
  await mutate('POST', `/exceptions/${id}/write-off/decision`, { version, approve, note });
}

export async function sendClaim(id: string, version: number, note: string, _actor: User) {
  await mutate('POST', `/exceptions/${id}/claim`, { version, note });
}

export async function markDuplicate(id: string, version: number, _actor: User) {
  await mutate('POST', `/exceptions/${id}/duplicate`, { version });
}

export async function addComment(id: string, version: number, text: string, _actor: User) {
  await mutate('POST', `/exceptions/${id}/comments`, { version, text });
}

export const refreshSuggestion = (id: string) => mutate<ReconException>('POST', `/exceptions/${id}/suggestion`);

export const searchOrders = (q: string) => request<Order[]>('GET', `/orders?q=${encodeURIComponent(q.trim())}`);

// ---------------------------------------------------------------- sources

export const listSources = () => request<Source[]>('GET', '/sources');

export const suggestMapping = (code: SourceCode) => request<MappingRow[]>('POST', `/sources/${code}/mapping/suggest`);

export async function saveMapping(code: SourceCode, mapping: MappingRow[]) {
  await mutate('PUT', `/sources/${code}/mapping`, mapping);
}

// ---------------------------------------------------------------- files

export const listFiles = () => request<ImportFile[]>('GET', '/files');

export const getFileErrors = (id: string) => request<FileErrorRow[]>('GET', `/files/${id}/errors`);

export async function uploadFile(source: SourceCode, file: File, _actor: User): Promise<ImportFile> {
  const form = new FormData();
  form.set('source', source);
  form.set('file', file);
  const f = await request<ImportFile>('POST', '/files', undefined, { form });
  followBackgroundWork();
  notify();
  return f;
}

// ---------------------------------------------------------------- results, aging, journal

export function listReconDays(from: Date, to: Date, sources: SourceCode[]) {
  const p = new URLSearchParams({ from: ymd(from), to: ymd(to) });
  if (sources.length) p.set('source', sources.join(','));
  return request<ReconDay[]>('GET', `/recon/days?${p}`);
}

export const listMatchGroups = (day: ReconDay) => request<MatchGroup[]>('GET', `/recon/days/${ymd(day.date)}/${day.source}/groups`);

export const getAging = () => request<AgingRow[]>('GET', '/aging');

/** {@code month} is 0-based like Date.getMonth(). */
export const getJournal = (year: number, month: number) =>
  request<JournalEntry[]>('GET', `/journal?month=${year}-${String(month + 1).padStart(2, '0')}`);

// ---------------------------------------------------------------- batch

export const listExecutions = () => request<JobExecution[]>('GET', '/batch/executions');

export async function restartExecution(id: number): Promise<{ message: string }> {
  const r = await request<{ message: string }>('POST', `/batch/executions/${id}/restart`);
  followBackgroundWork();
  notify();
  return { message: `${r.message}. Spring Batch bỏ qua các bước đã xong và chạy tiếp từ bước lỗi.` };
}

export async function runJob(job: string): Promise<{ message: string }> {
  const r = await request<{ message: string }>('POST', `/batch/jobs/${job}/run`);
  followBackgroundWork();
  notify();
  return r;
}

export const explainExecution = (id: number) => request<BatchExplanation>('POST', `/ai/batch/${id}/explain`);

// ---------------------------------------------------------------- assistant

export const ask = (question: string) => request<AssistantAnswer>('POST', '/ai/ask', { question });

/** There is no instant answer over HTTP. */
export function answer(_question: string): AssistantAnswer | null {
  return null;
}
