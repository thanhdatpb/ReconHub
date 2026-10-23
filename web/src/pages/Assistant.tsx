import { useRef, useState, type FormEvent } from 'react';
import { Chart } from 'primereact/chart';
import { Button } from 'primereact/button';
import { InputText } from 'primereact/inputtext';
import { answer as answerNow, ask, type AssistantAnswer } from '../api';
import { useSession } from '../state/session';
import { Card, PageHeader } from '../components/ui';
import { baseOptions, barStyle, tipLabels, useChartTheme } from '../components/charts';
import { fmtVndShort } from '../lib/format';

interface Msg {
  role: 'user' | 'bot';
  text: string;
  answer?: AssistantAnswer;
}

const SAMPLES = [
  'GHN còn nợ mình bao nhiêu?',
  'Tỷ lệ khớp tự động 7 ngày qua theo từng nguồn?',
  'Tháng này tổng phí bị trừ là bao nhiêu?',
  'Có bao nhiêu ngoại lệ quá hạn?',
];

const TOOLS = [
  ['getMatchRate', 'Tỷ lệ khớp theo ngày, nguồn'],
  ['getExceptions', 'Danh sách ngoại lệ theo bộ lọc'],
  ['getReceivables', 'Tuổi nợ phải thu theo đối tác'],
  ['getFees', 'Phí bị trừ theo kỳ, theo nguồn'],
  ['getFileStatus', 'Tình trạng nhận file'],
  ['getJobStatus', 'Lần chạy batch gần nhất'],
];

function AnswerChart({ chart }: { chart: NonNullable<AssistantAnswer['chart']> }) {
  const t = useChartTheme();
  const data = { labels: chart.labels, datasets: [{ label: chart.label, data: chart.values, ...barStyle(t, t.series[0], { horizontal: true }) }] };
  return (
    <div className="chart-box short">
      <Chart type="bar" data={data} options={baseOptions(t, { horizontal: true, tooltipValue: (n) => fmtVndShort(n) })} plugins={[tipLabels(t, (n) => fmtVndShort(n))]} style={{ height: '100%' }} />
    </div>
  );
}

export default function Assistant() {
  const { user } = useSession();
  const [msgs, setMsgs] = useState<Msg[]>(() => {
    const greeting: Msg = { role: 'bot', text: `Chào ${user.name.split(' ').slice(-1)[0]}. Mình trả lời câu hỏi về số liệu đối soát bằng cách gọi các tool chỉ đọc bên phải, theo đúng quyền của bạn. Mình không sửa dữ liệu.` };
    const first = answerNow(SAMPLES[0]);
    return first ? [greeting, { role: 'user', text: SAMPLES[0] }, { role: 'bot', text: first.text, answer: first }] : [greeting];
  });
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState(false);
  const endRef = useRef<HTMLDivElement>(null);

  const send = async (text: string) => {
    if (!text.trim() || busy) return;
    setMsgs((m) => [...m, { role: 'user', text }]);
    setQ('');
    setBusy(true);
    const answer = await ask(text);
    setMsgs((m) => [...m, { role: 'bot', text: answer.text, answer }]);
    setBusy(false);
    requestAnimationFrame(() => endRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }));
  };

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    send(q);
  };

  return (
    <>
      <PageHeader title="Trợ lý số liệu" sub="Hỏi bằng tiếng Việt. Trợ lý chỉ đọc dữ liệu qua các tool được cấp, không tự viết SQL." />
      <div className="chat-wrap">
        <Card flush className="chat">
          <div className="chat-log" aria-live="polite">
            {msgs.map((m, i) => (
              <div key={i} className={`msg ${m.role}`}>
                <div>{m.text}</div>
                {m.answer?.table && (
                  <div className="table-wrap">
                    <table className="mini-table">
                      <thead>
                        <tr>{m.answer.table.columns.map((c, j) => <th key={c} className={j > 0 ? 'r' : ''}>{c}</th>)}</tr>
                      </thead>
                      <tbody>
                        {m.answer.table.rows.map((r, k) => (
                          <tr key={k}>{r.map((c, j) => <td key={j} className={j > 0 ? 'r num' : ''}>{typeof c === 'number' ? c.toLocaleString('vi-VN') : c}</td>)}</tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                {m.answer?.chart && <AnswerChart chart={m.answer.chart} />}
                {m.answer && m.answer.tools.length > 0 && (
                  <div className="row" style={{ gap: 6 }}>
                    <span className="small muted">Đã gọi:</span>
                    {m.answer.tools.map((t) => <span key={t} className="tool-chip">{t}</span>)}
                  </div>
                )}
              </div>
            ))}
            {busy && (
              <div className="msg bot">
                <span className="ink-2 small"><i className="pi pi-spin pi-spinner" aria-hidden /> Đang gọi tool…</span>
              </div>
            )}
            <div ref={endRef} />
          </div>
          <div className="suggest">
            {SAMPLES.map((s) => (
              <button key={s} type="button" onClick={() => send(s)} disabled={busy}>{s}</button>
            ))}
          </div>
          <form className="chat-input" onSubmit={onSubmit}>
            <InputText id="ask" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Ví dụ: GHTK còn nợ mình bao nhiêu?" aria-label="Câu hỏi" />
            <Button type="submit" icon="pi pi-send" aria-label="Gửi" loading={busy} />
          </form>
        </Card>
        <div className="stack">
          <Card title="Tool trợ lý được dùng" sub="Tất cả chỉ đọc, chạy theo quyền của người hỏi">
            <ul className="tool-list">
              {TOOLS.map(([name, desc]) => (
                <li key={name}>
                  <code>{name}</code>
                  <div className="small ink-2">{desc}</div>
                </li>
              ))}
            </ul>
          </Card>
          <Card title="Giới hạn">
            <ul className="evidence">
              <li>Không cho LLM viết SQL; mọi số liệu lấy từ API báo cáo có sẵn.</li>
              <li>Dữ liệu cá nhân của khách (tên, SĐT) được che trước khi gửi cho LLM.</li>
              <li>Mỗi người dùng tối đa 200 câu hỏi/ngày; chi phí ghi vào bảng ai_requests.</li>
            </ul>
          </Card>
        </div>
      </div>
    </>
  );
}
