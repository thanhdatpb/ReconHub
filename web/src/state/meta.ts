import type { Source, SourceCode, User } from '../data/types';

/** Reference data the screens need: from the sample data in mock mode, from GET /api/meta in HTTP mode. */
export interface Meta {
  users: User[];
  sources: Source[];
  activeSources: SourceCode[];
  batchSchedule: { job: string; cron: string; human: string }[];
  accounts: Record<string, string>;
  agingBuckets: string[];
  writeOffApprovalThreshold: number;
  slaHours: number;
  aiEnabled: boolean;
  aiModel: string | null;
  /** The sample data is frozen at one moment; the real API follows the server clock. */
  fixedNow: Date | null;
  serverOffsetMs: number;
}

export let META: Meta = {
  users: [],
  sources: [],
  activeSources: [],
  batchSchedule: [],
  accounts: {},
  agingBuckets: [],
  writeOffApprovalThreshold: 50_000,
  slaHours: 48,
  aiEnabled: false,
  aiModel: null,
  fixedNow: null,
  serverOffsetMs: 0,
};

export function setMeta(m: Meta) {
  META = m;
}

export function now() {
  return META.fixedNow ? new Date(META.fixedNow) : new Date(Date.now() + META.serverOffsetMs);
}

export function startOfDay(d: Date) {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x;
}

export function daysAgo(n: number, h = 0, m = 0) {
  const x = startOfDay(now());
  x.setDate(x.getDate() - n);
  x.setHours(h, m, 0, 0);
  return x;
}
