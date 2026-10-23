import { useState, type FormEvent } from 'react';
import { Button } from 'primereact/button';
import { InputText } from 'primereact/inputtext';
import { Password } from 'primereact/password';
import { Logo } from '../components/Layout';

const DEMO_ACCOUNTS = [
  ['ha', 'Kế toán công nợ'],
  ['khoa', 'Kế toán thanh toán'],
  ['nam', 'Kế toán trưởng (duyệt ghi giảm)'],
  ['bao', 'Quản trị hệ thống'],
];

export default function Login({ onSubmit, notice }: { onSubmit: (u: string, p: string) => Promise<void>; notice: string | null }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSubmit(username.trim(), password);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <form className="login-card" onSubmit={submit}>
        <div className="login-brand">
          <Logo />
          <div>
            <div className="login-title">ReconHub</div>
            <div className="small muted">Đối soát thanh toán đa kênh</div>
          </div>
        </div>
        {(error || notice) && <div className="notice tone-bad small" role="alert">{error ?? notice}</div>}
        <label htmlFor="login-user" className="field-label">Tên đăng nhập</label>
        <InputText id="login-user" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus />
        <label htmlFor="login-pass" className="field-label">Mật khẩu</label>
        <Password inputId="login-pass" value={password} onChange={(e) => setPassword(e.target.value)} feedback={false} toggleMask
          autoComplete="current-password" inputStyle={{ width: '100%' }} style={{ width: '100%' }} />
        <Button type="submit" label="Đăng nhập" icon="pi pi-sign-in" loading={busy} disabled={!username || !password} />
        <details className="small muted login-demo">
          <summary>Tài khoản demo (mật khẩu demo123)</summary>
          <ul>
            {DEMO_ACCOUNTS.map(([u, role]) => (
              <li key={u}>
                <button type="button" className="link-btn mono" onClick={() => { setUsername(u); setPassword('demo123'); }}>{u}</button> · {role}
              </li>
            ))}
          </ul>
        </details>
      </form>
    </div>
  );
}
