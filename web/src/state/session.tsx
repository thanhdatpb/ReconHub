import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react';
import { Toast, type ToastMessage } from 'primereact/toast';
import type { User } from '../data/types';
import { MODE, currentUser, getVersion, loadMeta, login, logout, onUnauthorized, subscribe } from '../api';
import { META, setMeta } from './meta';
import Login from '../pages/Login';

interface Session {
  user: User;
  /** Mock mode only: switch between the sample users to try the approval flow. */
  setUserId: (id: string) => void;
  signOut: () => void;
  notify: (m: ToastMessage) => void;
}

const Ctx = createContext<Session | null>(null);

/**
 * Loads the reference data, then the signed-in user. In HTTP mode an expired or missing token shows the
 * login screen; in mock mode there is no login and the first sample user is signed in.
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [booting, setBooting] = useState(true);
  const [notice, setNotice] = useState<string | null>(null);
  const toast = useRef<Toast>(null);
  const notify = useCallback((m: ToastMessage) => toast.current?.show({ life: 4000, ...m }), []);

  const start = useCallback(async (u: User) => {
    setMeta(await loadMeta());
    setUser(u);
  }, []);

  useEffect(() => {
    onUnauthorized(() => {
      setUser(null);
      setNotice('Phiên đăng nhập đã hết hạn, hãy đăng nhập lại.');
    });
    (async () => {
      try {
        if (MODE === 'mock') {
          const m = await loadMeta();
          setMeta(m);
          setUser(m.users[0]);
        } else {
          const u = await currentUser();
          if (u) await start(u);
        }
      } catch (e) {
        setNotice((e as Error).message);
      } finally {
        setBooting(false);
      }
    })();
  }, [start]);

  const signIn = async (username: string, password: string) => {
    const u = await login(username, password);
    setNotice(null);
    await start(u);
  };

  const signOut = useCallback(() => {
    logout();
    setUser(null);
  }, []);

  const setUserId = useCallback((id: string) => {
    const u = META.users.find((x) => x.id === id);
    if (u) setUser(u);
  }, []);

  const value = useMemo(() => (user ? { user, setUserId, signOut, notify } : null), [user, setUserId, signOut, notify]);

  return (
    <>
      <Toast ref={toast} position="bottom-right" />
      {value ? <Ctx.Provider value={value}>{children}</Ctx.Provider> : booting ? null : <Login onSubmit={signIn} notice={notice} />}
    </>
  );
}

export function useSession() {
  const s = useContext(Ctx);
  if (!s) throw new Error('SessionProvider missing');
  return s;
}

/** Runs an API call and re-runs it when its deps change or the data changes (mutation, background job, polling). */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]) {
  const version = useSyncExternalStore(subscribe, getVersion);
  const [state, setState] = useState<{ data: T | null; loading: boolean; error: string | null }>({ data: null, loading: true, error: null });
  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true }));
    fn()
      .then((data) => alive && setState({ data, loading: false, error: null }))
      .catch((e: Error) => alive && setState((s) => ({ data: s.data, loading: false, error: e.message })));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, version]);
  return state;
}
