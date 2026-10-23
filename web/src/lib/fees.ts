import type { FeeBase, FeeComponent, FeeCondition, FeeSchedule } from '../data/types';
import { fmtVnd } from './format';

/** What a fee is computed on; COD is the order's cash on delivery. */
export const BASE_LABEL: Record<FeeBase, string> = { COD: 'COD', ORDER_AMOUNT: 'giá trị đơn', LINE_GROSS: 'giá trị giao dịch' };

export interface FeeInput {
  cod: number;
  amount: number;
  remote: boolean;
  /** The payment line before fee. */
  gross: number;
}

const baseValue = (base: FeeBase | null | undefined, x: FeeInput) =>
  base === 'COD' ? x.cod : base === 'LINE_GROSS' ? x.gross : x.amount;

/** Half-up to the đồng, as FeeFormula.java: exact for rates with up to two decimals (0,5%, 4,91%). */
const percentOf = (value: number, rate: number) => Math.floor((value * Math.round(rate * 100) + 5_000) / 10_000);

export function applies(when: FeeCondition | null | undefined, x: FeeInput) {
  if (!when) return true;
  if (when.remoteArea && !x.remote) return false;
  if (when.above == null && when.atMost == null) return true;
  const v = baseValue(when.base, x);
  if (when.above != null && v <= when.above) return false;
  return when.atMost == null || v <= when.atMost;
}

/** The fee of a schedule: each component that applies, rounded half-up to the đồng, added up (same as FeeFormula.java). */
export function feeOf(components: FeeComponent[], x: FeeInput) {
  let total = 0;
  for (const c of components) {
    if (!applies(c.when, x)) continue;
    if (c.kind === 'FIXED') total += c.amount ?? 0;
    else {
      const v = percentOf(baseValue(c.base, x), c.rate ?? 0);
      total += c.min != null ? Math.max(c.min, v) : v;
    }
  }
  return total;
}

const rateText = (r: number) => String(r).replace('.', ',');

function conditionText(when: FeeCondition | null | undefined) {
  if (!when) return '';
  const parts: string[] = [];
  if (when.remoteArea) parts.push(' nếu vùng xa');
  const base = BASE_LABEL[when.base ?? 'ORDER_AMOUNT'];
  if (when.above != null) parts.push(` nếu ${base} trên ${fmtVnd(when.above)}`);
  if (when.atMost != null) parts.push(`${when.above == null ? ' nếu' : ' và'} ${base} đến ${fmtVnd(when.atMost)}`);
  return parts.join('');
}

/** "Cước giao 22.000 ₫; Phí thu hộ 0,5% COD, tối thiểu 5.000 ₫; Phụ phí vùng xa 8.000 ₫ nếu vùng xa" (as the server writes it). */
export function describeComponent(c: FeeComponent) {
  const value = c.kind === 'FIXED'
    ? fmtVnd(c.amount ?? 0)
    : `${rateText(c.rate ?? 0)}% ${BASE_LABEL[c.base ?? 'ORDER_AMOUNT']}${c.min != null ? `, tối thiểu ${fmtVnd(c.min)}` : ''}`;
  return `${c.label} ${value}${conditionText(c.when)}`;
}

export const describe = (components: FeeComponent[]) => components.map(describeComponent).join('; ');

/** "Áp dụng từ đầu" for version 1, otherwise "Áp dụng từ 13/10/2026". */
export function effectiveText(s: Pick<FeeSchedule, 'fromTheStart' | 'effectiveFrom'>) {
  if (s.fromTheStart) return 'Áp dụng từ đầu';
  const [y, m, d] = s.effectiveFrom.split('-');
  return `Áp dụng từ ${d}/${m}/${y}`;
}

/** The approved version in force on a day (yyyy-MM-dd): latest effective date on or before it, then the higher version. */
export function inForce(schedules: FeeSchedule[], day: string) {
  return schedules
    .filter((s) => s.status === 'APPROVED' && s.effectiveFrom <= day)
    .sort((a, b) => b.effectiveFrom.localeCompare(a.effectiveFrom) || (b.versionNo ?? 0) - (a.versionNo ?? 0))[0];
}
