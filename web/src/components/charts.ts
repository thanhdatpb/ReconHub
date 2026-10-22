// Shared Chart.js styling: thin marks, hairline grid, text in ink tokens, colors read from CSS tokens
// so both themes stay correct. Series colors follow the validated reference palette (blue, orange, aqua).
import { useMemo } from 'react';
import { useThemeMode } from '../theme';

const v = (name: string) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

export function useChartTheme() {
  const mode = useThemeMode();
  return useMemo(
    () => ({
      mode,
      ink: v('--ink'),
      ink2: v('--ink-2'),
      muted: v('--muted'),
      surface: v('--surface'),
      grid: v('--chart-grid'),
      axis: v('--chart-axis'),
      tooltipBg: v('--tooltip-bg'),
      tooltipInk: v('--tooltip-ink'),
      series: [v('--series-1'), v('--series-2'), v('--series-3')],
      seq: [v('--seq-1'), v('--seq-2'), v('--seq-3'), v('--seq-4')],
      font: v('--font-ui') || 'system-ui',
    }),
    [mode],
  );
}
export type ChartTheme = ReturnType<typeof useChartTheme>;

export function withAlpha(hex: string, a: number) {
  const h = hex.replace('#', '');
  const n = parseInt(h.length === 3 ? h.split('').map((c) => c + c).join('') : h, 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}

export const compact = (n: number) => {
  const a = Math.abs(n);
  if (a >= 1e9) return `${(n / 1e9).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} tỷ`;
  if (a >= 1e6) return `${(n / 1e6).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} tr`;
  if (a >= 1e3) return `${(n / 1e3).toLocaleString('vi-VN', { maximumFractionDigits: 0 })}k`;
  return n.toLocaleString('vi-VN');
};

interface Opts {
  stacked?: boolean;
  horizontal?: boolean;
  legend?: boolean;
  yMin?: number;
  yMax?: number;
  tick?: (n: number) => string;
  tooltipValue?: (n: number) => string;
}

export function baseOptions(t: ChartTheme, o: Opts = {}) {
  const font = { family: t.font, size: 11.5 };
  const valueAxis = {
    stacked: o.stacked,
    min: o.yMin,
    max: o.yMax,
    grid: { color: t.grid, drawTicks: false },
    border: { display: false },
    ticks: { color: t.muted, font, padding: 8, maxTicksLimit: 6, callback: (val: string | number) => (o.tick ? o.tick(Number(val)) : compact(Number(val))) },
  };
  const catAxis = {
    stacked: o.stacked,
    grid: { display: false },
    border: { color: t.axis },
    ticks: { color: t.muted, font, maxRotation: 0, autoSkipPadding: 12 },
  };
  return {
    maintainAspectRatio: false,
    responsive: true,
    indexAxis: o.horizontal ? ('y' as const) : ('x' as const),
    animation: { duration: 250 },
    interaction: { mode: 'index' as const, intersect: false, axis: o.horizontal ? ('y' as const) : ('x' as const) },
    layout: { padding: { right: o.horizontal ? 64 : 8, top: 4 } },
    plugins: {
      legend: {
        display: !!o.legend,
        position: 'top' as const,
        align: 'start' as const,
        labels: { color: t.ink2, font: { family: t.font, size: 12 }, boxWidth: 10, boxHeight: 10, useBorderRadius: true, borderRadius: 3, padding: 14 },
      },
      tooltip: {
        backgroundColor: t.tooltipBg,
        titleColor: t.tooltipInk,
        bodyColor: t.tooltipInk,
        titleFont: { family: t.font, size: 12, weight: 600 as const },
        bodyFont: { family: t.font, size: 12 },
        padding: 10,
        cornerRadius: 8,
        boxPadding: 4,
        usePointStyle: true,
        callbacks: o.tooltipValue
          ? { label: (ctx: { dataset: { label?: string }; parsed: { x: number; y: number } }) => `${ctx.dataset.label ? `${ctx.dataset.label}: ` : ''}${o.tooltipValue!(o.horizontal ? ctx.parsed.x : ctx.parsed.y)}` }
          : undefined,
      },
    },
    scales: o.horizontal ? { x: valueAxis, y: catAxis } : { x: catAxis, y: valueAxis },
  };
}

/** Bar styling: ≤24px thick, 4px rounded data end, square at the baseline, 2px surface gap. */
export function barStyle(t: ChartTheme, color: string, opts: { end?: boolean; horizontal?: boolean } = {}) {
  const r = opts.end === false ? 0 : 4;
  return {
    backgroundColor: color,
    hoverBackgroundColor: color,
    borderColor: t.surface,
    borderWidth: opts.horizontal ? { right: 2, left: 0, top: 0, bottom: 0 } : { top: 2, left: 0, right: 0, bottom: 0 },
    borderRadius: opts.horizontal ? { topRight: r, bottomRight: r, topLeft: 0, bottomLeft: 0 } : { topLeft: r, topRight: r, bottomLeft: 0, bottomRight: 0 },
    borderSkipped: false as const,
    maxBarThickness: 24,
    categoryPercentage: 0.72,
    barPercentage: 0.9,
  };
}

interface ChartLike {
  ctx: CanvasRenderingContext2D;
  chartArea: { left: number; right: number; top: number; bottom: number };
  scales: Record<string, { getPixelForValue: (n: number) => number }>;
  data: { datasets: { data: unknown[] }[] };
  getDatasetMeta: (i: number) => { data: { x: number; y: number }[]; hidden?: boolean };
  isDatasetVisible: (i: number) => boolean;
}

/** A solid hairline reference line with a short label, e.g. a target rate. */
export function targetLine(t: ChartTheme, value: number, label: string) {
  return {
    id: `target-${value}`,
    afterDatasetsDraw(chart: ChartLike) {
      const { ctx, chartArea, scales } = chart;
      const y = scales.y.getPixelForValue(value);
      if (y < chartArea.top || y > chartArea.bottom) return;
      ctx.save();
      ctx.strokeStyle = t.muted;
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(chartArea.left, Math.round(y) + 0.5);
      ctx.lineTo(chartArea.right, Math.round(y) + 0.5);
      ctx.stroke();
      ctx.fillStyle = t.ink2;
      ctx.font = `500 11px ${t.font}`;
      ctx.textAlign = 'left';
      ctx.textBaseline = 'bottom';
      ctx.fillText(label, chartArea.left + 6, y - 4);
      ctx.restore();
    },
  };
}

/** Value at the tip of each bar in the last visible dataset (horizontal charts). */
export function tipLabels(t: ChartTheme, format: (n: number) => string, totals?: number[]) {
  return {
    id: 'tip-labels',
    afterDatasetsDraw(chart: ChartLike) {
      const { ctx } = chart;
      let last = -1;
      chart.data.datasets.forEach((_, i) => {
        if (chart.isDatasetVisible(i)) last = i;
      });
      if (last < 0) return;
      const meta = chart.getDatasetMeta(last);
      ctx.save();
      ctx.fillStyle = t.ink2;
      ctx.font = `600 11.5px ${t.font}`;
      ctx.textAlign = 'left';
      ctx.textBaseline = 'middle';
      meta.data.forEach((el, idx) => {
        const val = totals ? totals[idx] : Number(chart.data.datasets[last].data[idx]);
        ctx.fillText(format(val), el.x + 6, el.y);
      });
      ctx.restore();
    },
  };
}
