import { useEffect, useRef } from 'react';
import { Link, Route, Routes, useLocation } from 'react-router-dom';
import { AccountSession } from './components/AccountSession/AccountSession';
import { ReportMessage, ReportPage } from './components/ReportPage/ReportPage';
import { CreateReportPage } from './pages/CreateReportPage';
import { EditReportPage } from './pages/EditReportPage';
import { ReportDetailsPage } from './pages/ReportDetailsPage';
import { ReportListPage } from './pages/ReportListPage';

export default function App() {
  const { pathname } = useLocation();
  const main = useRef<HTMLElement>(null);
  const previousPath = useRef(pathname);

  useEffect(() => {
    if (previousPath.current === pathname) return;
    previousPath.current = pathname;
    main.current?.focus({ preventScroll: true });
    window.scrollTo(0, 0);
  }, [pathname]);

  return (
    <main className="foundly-page" ref={main} tabIndex={-1}>
      <div className="foundly-topbar">
        <Link className="foundly-brand" to="/">foundly<span>.</span></Link>
        <AccountSession />
      </div>
      <Routes>
        <Route path="/" element={<ReportListPage />} />
        <Route path="/ogloszenia/nowe" element={<CreateReportPage />} />
        <Route path="/ogloszenia/:id" element={<ReportDetailsPage />} />
        <Route path="/ogloszenia/:id/edytuj" element={<EditReportPage />} />
        <Route path="*" element={
          <ReportPage>
            <ReportMessage title="Nie znaleziono strony">Sprawdź adres albo wróć do listy ogłoszeń.</ReportMessage>
          </ReportPage>
        } />
      </Routes>
    </main>
  );
}
