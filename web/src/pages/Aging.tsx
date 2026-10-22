import { useState } from 'react';
import { Chart } from 'primereact/chart';
import { Dropdown } from 'primereact/dropdown';
import { DataTable } from 'primereact/datatable';
import { Column } from 'primereact/column';
import { ColumnGroup } from 'primereact/columngroup';
import { Row } from 'primereact/row';
import { Button } from 'primereact/button';
import { getAging, getJournal } from '../api';
import { useLoad, useSession } from '../state/session';
import { Card, ErrorNote, Kpi, Loading, Money, PageHeader, SourceBadge } from '../components/ui';
import { baseOptions, barStyle, tipLabels, useChartTheme } from '../components/charts';
import { ACCOUNTS, AGING_BUCKETS } from '../data/seed';
import type { JournalEntry } from '../data/types';
import { SOURCE_LABEL, fmtDate, fmtVnd, fmtVndShort } from '../lib/format';

const MONTHS = [
  { label: 'Tháng 10/2026', value: '2026-9' },
  { label: 'Tháng 09/2026', value: '2026-8' },
];

function toCsv(rows: JournalEntry[]) {
  const head = ['Ngày chứng từ', 'Số chứng từ', 'Diễn giải', 'TK Nợ', 'TK Có', 'Số tiền', 'Nguồn'];
  const esc = (s: string | number) => `"${String(s).replace(/"/g, '""')}"`;
  return [head.map(esc).join(','), ...rows.map((r) => [fmtDate(r.date), r.voucher, r.description, r.debit, r.credit, r.amount, SOURCE_LABEL[r.source]].map(esc).join(','))].join('\n');
}

export default function Aging() {
  const t = useChartTheme();
  const { notify } = useSession();
  const aging = useLoad(getAging, []);
  const [month, setMonth] = useState('2026-9');
  const [y, m] = month.split('-').map(Number);
  const journal = useLoad(() => getJournal(y, m), [month]);
  const [copied, setCopied] = useState(false);

  if (aging.error) return <ErrorNote message={aging.error} />;
  if (!aging.data) return <Loading rows={6} />;
  const rows = aging.data;
  const bucketTotals = [0, 1, 2, 3].map((i) => rows.reduce((s, r) => s + r.buckets[i], 0));
  const rowTotals = rows.map((r) => r.buckets.reduce((s, x) => s + x, 0));

  const chartData = {
    labels: rows.map((r) => SOURCE_LABEL[r.source]),
    datasets: AGING_BUCKETS.map((label, i) => ({ label, data: rows.map((r) => r.buckets[i]), ...barStyle(t, t.seq[i], { horizontal: true, end: i === 3 }) })),
  };
  const options = baseOptions(t, { horizontal: true, stacked: true, legend: true, tooltipValue: (n) => fmtVndShort(n) });

  const entries = journal.data ?? [];
  const debitTotal = entries.reduce((s, r) => s + r.amount, 0);

  const copy = async () => {
    const csv = toCsv(entries);
    try {
      await navigator.clipboard.writeText(csv);
      setCopied(true);
      notify({ severity: 'success', summary: 'Đã sao chép CSV', detail: `${entries.length} bút toán, dán vào Excel hoặc phần mềm kế toán` });
      setTimeout(() => setCopied(false), 2500);
    } catch {
      notify({ severity: 'warn', summary: 'Trình duyệt không cho sao chép', detail: 'Hãy chọn bảng và sao chép bằng Ctrl+C.' });
    }
  };

  return (
    <>
      <PageHeader title="Tuổi nợ & bút toán" sub="Số tiền đối tác còn phải trả cho cửa hàng, theo số ngày kể từ khi giao hàng thành công; và các bút toán sinh ra từ kết quả đối soát." />
      <div className="grid kpis" style={{ marginBottom: 16 }}>
        {AGING_BUCKETS.map((b, i) => (
          <Kpi key={b} label={b} value={fmtVndShort(bucketTotals[i])} foot={i === 0 ? 'Bình thường, chưa tới kỳ đối soát' : i === 1 ? 'Cần nhắc đối tác' : 'Cần gửi yêu cầu đối soát chính thức'} />
        ))}
      </div>
      <div className="stack">
        <Card title="Tuổi nợ theo đối tác" sub="Đậm hơn là nợ lâu hơn">
          <div className="chart-box">
            <Chart type="bar" data={chartData} options={options} plugins={[tipLabels(t, (n) => fmtVndShort(n), rowTotals)]} style={{ height: '100%' }} />
          </div>
          <div className="table-wrap" style={{ marginTop: 12 }}>
            <table className="mini-table" style={{ minWidth: 640 }}>
              <thead>
                <tr>
                  <th>Đối tác</th>
                  {AGING_BUCKETS.map((b) => <th key={b} className="r">{b}</th>)}
                  <th className="r">Tổng</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r, i) => (
                  <tr key={r.source}>
                    <td><SourceBadge code={r.source} /></td>
                    {r.buckets.map((v, j) => <td key={j} className={`r num ${j >= 2 && v > 0 ? 'neg' : ''}`}>{v ? fmtVnd(v) : '—'}</td>)}
                    <td className="r num"><strong>{fmtVnd(rowTotals[i])}</strong></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>

        <Card
          title="Bút toán từ kết quả đối soát"
          sub={Object.entries(ACCOUNTS).map(([k, v]) => `${k} ${v}`).join(' · ')}
          actions={
            <>
              <Dropdown inputId="journal-month" value={month} options={MONTHS} onChange={(e) => setMonth(e.value)} />
              <Button label={copied ? 'Đã sao chép' : 'Sao chép CSV'} icon={copied ? 'pi pi-check' : 'pi pi-copy'} outlined onClick={copy} disabled={!entries.length} />
            </>
          }
          flush
        >
          <DataTable value={entries} loading={journal.loading} size="small" paginator rows={12} tableStyle={{ minWidth: '58rem' }}
            paginatorTemplate="CurrentPageReport PrevPageLink NextPageLink" currentPageReportTemplate="{first}–{last} / {totalRecords}"
            footerColumnGroup={
              <ColumnGroup>
                <Row>
                  <Column footer="Tổng phát sinh" colSpan={5} footerStyle={{ textAlign: 'right' }} />
                  <Column footer={<span className="num"><strong>{fmtVnd(debitTotal)}</strong></span>} footerStyle={{ textAlign: 'right' }} />
                  <Column />
                </Row>
              </ColumnGroup>
            }
            emptyMessage="Không có bút toán trong tháng này."
          >
            <Column header="Ngày CT" body={(r: JournalEntry) => <span className="num">{fmtDate(r.date)}</span>} />
            <Column header="Số CT" body={(r: JournalEntry) => <span className="mono">{r.voucher}</span>} />
            <Column header="Diễn giải" field="description" />
            <Column header="TK Nợ" body={(r: JournalEntry) => <span className="mono" title={ACCOUNTS[r.debit]}>{r.debit}</span>} />
            <Column header="TK Có" body={(r: JournalEntry) => <span className="mono" title={ACCOUNTS[r.credit]}>{r.credit}</span>} />
            <Column header="Số tiền" align="right" body={(r: JournalEntry) => <Money value={r.amount} />} />
            <Column header="Nguồn" body={(r: JournalEntry) => <SourceBadge code={r.source} />} />
          </DataTable>
        </Card>
      </div>
    </>
  );
}
