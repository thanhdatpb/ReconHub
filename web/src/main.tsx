import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter, Route, Routes } from 'react-router-dom';
import { PrimeReactProvider, addLocale, locale } from 'primereact/api';
import 'primeicons/primeicons.css';
import './styles/app.css';
import { initTheme } from './theme';
import { SessionProvider } from './state/session';
import Layout from './components/Layout';
import Dashboard from './pages/Dashboard';
import Exceptions from './pages/Exceptions';
import ExceptionDetail from './pages/ExceptionDetail';
import Files from './pages/Files';
import Sources from './pages/Sources';
import Results from './pages/Results';
import Aging from './pages/Aging';
import Batch from './pages/Batch';
import Assistant from './pages/Assistant';

initTheme();

addLocale('vi', {
  firstDayOfWeek: 1,
  dayNames: ['Chủ nhật', 'Thứ hai', 'Thứ ba', 'Thứ tư', 'Thứ năm', 'Thứ sáu', 'Thứ bảy'],
  dayNamesShort: ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'],
  dayNamesMin: ['CN', 'T2', 'T3', 'T4', 'T5', 'T6', 'T7'],
  monthNames: ['Tháng 1', 'Tháng 2', 'Tháng 3', 'Tháng 4', 'Tháng 5', 'Tháng 6', 'Tháng 7', 'Tháng 8', 'Tháng 9', 'Tháng 10', 'Tháng 11', 'Tháng 12'],
  monthNamesShort: ['Th1', 'Th2', 'Th3', 'Th4', 'Th5', 'Th6', 'Th7', 'Th8', 'Th9', 'Th10', 'Th11', 'Th12'],
  today: 'Hôm nay',
  clear: 'Xoá',
  emptyMessage: 'Không có kết quả',
  emptyFilterMessage: 'Không có kết quả',
  weekHeader: 'Tuần',
});
locale('vi');

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <PrimeReactProvider value={{ ripple: false }}>
      <SessionProvider>
        <HashRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<Dashboard />} />
              <Route path="exceptions" element={<Exceptions />} />
              <Route path="exceptions/:id" element={<ExceptionDetail />} />
              <Route path="results" element={<Results />} />
              <Route path="aging" element={<Aging />} />
              <Route path="files" element={<Files />} />
              <Route path="sources" element={<Sources />} />
              <Route path="batch" element={<Batch />} />
              <Route path="assistant" element={<Assistant />} />
              <Route path="*" element={<Dashboard />} />
            </Route>
          </Routes>
        </HashRouter>
      </SessionProvider>
    </PrimeReactProvider>
  </StrictMode>,
);
