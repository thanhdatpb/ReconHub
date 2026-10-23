import { Link } from 'react-router-dom';
import { Chart } from 'primereact/chart';
import { getDashboard, type FileArrival } from '../api';
import { useLoad } from '../state/session';
import { Card, ErrorNote, Kpi, Loading, Money, PageHeader, Pill, SourceBadge } from '../components/ui';
import { baseOptions, barStyle, targetLine, tipLabels, useChartTheme, withAlpha } from '../components/charts';
import { now } from '../state/meta';
import { SOURCE_LABEL, TYPE_META, fmtDayMonth, fmtLongDate, fmtPct, fmtTime, fmtVndShort } from '../lib/format';

function Arrival({ a }: { a: FileArrival }) {
  const right =
    a.state === 'received' ? <Pill tone="good" icon="pi pi-check">Nhận {fmtTime(a.at)}</Pill>
      : a.state === 'late' ? <Pill tone="bad" icon="pi pi-exclamation-triangle">Trễ {a.minutes} phút</Pill>
        : a.state === 'waiting' ? <Pill tone="neutral" icon="pi pi-clock">Dự kiến {a.expected}</Pill>
          : a.state === 'due' ? <Pill tone="warn" icon="pi pi-upload">Cần tải thủ công</Pill>
            : <span className="muted small">Không có kỳ hôm nay</span>;
  return (
    <div className="arrival">
      <SourceBadge code={a.source} />
      {right}
    </div>
  );
}

export default function Dashboard() {
  const { data, loading, error } = useLoad(getDashboard, []);
  const t = useChartTheme();
  if (error) return <ErrorNote message={error} />;
  if (loading && !data) return <Loading rows={6} />;
  if (!data) return null;

  const delta = (data.autoRate7 - data.autoRatePrev7) * 100;
  const late = data.arrivals.filter((a) => a.state === 'late');
  const due = data.arrivals.filter((a) => a.state === 'due');

  const trend = data.trend.filter((d) => d.lines > 0);
  const lineData = {
    labels: trend.map((d) => fmtDayMonth(d.date)),
    datasets: [
      {
        label: 'Khớp tự động',
        data: trend.map((d) => +(d.rate * 100).toFixed(2)),
        borderColor: t.series[0],
        backgroundColor: withAlpha(t.series[0], 0.1),
        fill: true,
        borderWidth: 2,
        cubicInterpolationMode: 'monotone' as const,
        pointRadius: trend.map((_, i) => (i === trend.length - 1 ? 4 : 0)),
        pointBackgroundColor: t.series[0],
        pointBorderColor: t.surface,
        pointBorderWidth: 2,
        pointHoverRadius: 5,
        pointHitRadius: 12,
      },
    ],
  };
  const lineOptions = baseOptions(t, { yMin: 90, yMax: 100, tick: (n) => `${n}%`, tooltipValue: (n) => `${n.toLocaleString('vi-VN')}%` });

  const src = data.bySource.filter((s) => s.amount > 0);
  const barData = {
    labels: src.map((s) => SOURCE_LABEL[s.source]),
    datasets: [{ label: 'Tiền chưa đối soát', data: src.map((s) => s.amount), ...barStyle(t, t.series[0], { horizontal: true }) }],
  };
  const barOptions = baseOptions(t, { horizontal: true, tooltipValue: (n) => fmtVndShort(n) });

  return (
    <>
      <PageHeader title="Tổng quan đối soát" sub={`${fmtLongDate(now())} · cập nhật lúc ${fmtTime(now())}`} />

      <div className="attention" aria-label="Cần chú ý">
        {data.overdue > 0 && (
          <Link to="/exceptions?overdue=1" className="attn tone-bad">
            <i className="pi pi-exclamation-circle" aria-hidden />
            <div>
              <strong>{data.overdue} ngoại lệ quá hạn xử lý</strong>
              <span>Đã quá 48 giờ kể từ khi phát hiện</span>
            </div>
          </Link>
        )}
        {data.pendingApproval > 0 && (
          <Link to="/exceptions?tab=pending" className="attn tone-pending">
            <i className="pi pi-shield" aria-hidden />
            <div>
              <strong>{data.pendingApproval} khoản ghi giảm chờ duyệt</strong>
              <span>Vượt 50.000 ₫, cần Kế toán trưởng duyệt</span>
            </div>
          </Link>
        )}
        {data.failedJobs.map((j) => (
          <Link key={j.id} to={`/batch?focus=${j.id}`} className="attn tone-bad">
            <i className="pi pi-server" aria-hidden />
            <div>
              <strong>Job {j.job} lỗi lúc {fmtTime(j.startTime)}</strong>
              <span>Email báo cáo sáng nay chưa được gửi</span>
            </div>
          </Link>
        ))}
        {late.length + due.length > 0 && (
          <Link to="/files" className="attn tone-warn">
            <i className="pi pi-clock" aria-hidden />
            <div>
              <strong>{late.length + due.length} nguồn chưa có file hôm nay</strong>
              <span>
                {[
                  ...late.map((a) => `${SOURCE_LABEL[a.source]} trễ ${a.state === 'late' ? a.minutes : 0} phút`),
                  ...due.map((a) => `${SOURCE_LABEL[a.source]} cần tải thủ công`),
                ].join(' · ')}
              </span>
            </div>
          </Link>
        )}
      </div>

      <div className="grid kpis" style={{ marginBottom: 16 }}>
        <Kpi
          label="Khớp tự động · 7 ngày"
          value={fmtPct(data.autoRate7)}
          foot={
            <>
              <span className={delta >= 0 ? 'delta-good' : 'delta-bad'}>
                {delta >= 0 ? '▲' : '▼'} {Math.abs(delta).toLocaleString('vi-VN', { maximumFractionDigits: 1 })} điểm
              </span>{' '}
              so với 7 ngày trước
            </>
          }
          to="/results"
        />
        <Kpi label="Ngoại lệ cần xử lý" value={data.open.toLocaleString('vi-VN')} foot={<><span className="delta-bad">{data.overdue} quá hạn</span> · tổng chênh {fmtVndShort(data.unreconciled)}</>} to="/exceptions" />
        <Kpi label="Đối tác nợ quá 7 ngày" value={fmtVndShort(data.agingOver7)} foot={`trong đó ${fmtVndShort(data.agingOver30)} quá 30 ngày`} to="/aging" />
        <Kpi label="Phí bị tính sai · tháng 10" value={fmtVndShort(data.feeDiffAmount)} foot={`${data.feeDiffCount} giao dịch lệch biểu phí`} to="/exceptions?type=FEE_DIFF&tab=all" />
      </div>

      <div className="grid cols-2" style={{ marginBottom: 16 }}>
        <Card title="Tỷ lệ khớp tự động theo ngày" sub="30 ngày gần nhất · từ 15/10 có thêm quy tắc khớp một khoản cho nhiều đơn">
          <div className="chart-box">
            <Chart type="line" data={lineData} options={lineOptions} plugins={[targetLine(t, 95, 'Mục tiêu 95%')]} style={{ height: '100%' }} />
          </div>
        </Card>
        <Card title="Tiền chưa đối soát theo nguồn" sub="Tổng chênh lệch của các ngoại lệ đang mở">
          <div className="chart-box">
            <Chart type="bar" data={barData} options={barOptions} plugins={[tipLabels(t, (n) => fmtVndShort(n))]} style={{ height: '100%' }} />
          </div>
        </Card>
      </div>

      <div className="grid cols-2">
        <Card title="Ngoại lệ mới nhất" actions={<Link to="/exceptions" className="small">Xem hàng chờ</Link>} flush>
          <div className="table-wrap">
            <table className="mini-table" style={{ minWidth: 520 }}>
              <thead>
                <tr>
                  <th style={{ paddingLeft: 16 }}>Mã</th>
                  <th>Loại</th>
                  <th>Nguồn</th>
                  <th className="r" style={{ paddingRight: 16 }}>Chênh lệch</th>
                </tr>
              </thead>
              <tbody>
                {data.latest.map((e) => (
                  <tr key={e.id}>
                    <td style={{ paddingLeft: 16 }}>
                      <Link to={`/exceptions/${e.id}`} className="mono">{e.id}</Link>
                    </td>
                    <td>
                      <Pill tone={TYPE_META[e.type].tone} icon={TYPE_META[e.type].icon}>{TYPE_META[e.type].short}</Pill>
                    </td>
                    <td><SourceBadge code={e.source} /></td>
                    <td className="r" style={{ paddingRight: 16 }}><Money value={e.diffAmount} signed tone /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
        <Card title="Lịch nhận file hôm nay" actions={<Link to="/files" className="small">Nhận file</Link>} flush>
          <div className="arrivals">
            {data.arrivals.map((a) => (
              <Arrival key={a.source} a={a} />
            ))}
          </div>
        </Card>
      </div>
    </>
  );
}
