import { useState } from 'react';
import { ReportCard } from './components/ReportCard/ReportCard';
import { demoReports } from './demo/reports';
import type { Report } from './types/report';

export default function App() {
  const [selectedReport, setSelectedReport] = useState<Report | null>(null);

  return (
    <main className="card-preview">
      <p className="card-preview__brand">foundly<span>.</span></p>
      <header className="card-preview__heading">
        <p className="card-preview__eyebrow">PRZYKŁADOWE OGŁOSZENIA</p>
        <h1>Każda rzecz ma swoją historię.</h1>
        <p>Pomóż przedmiotom wrócić tam, gdzie ich miejsce.</p>
      </header>

      <section className="card-preview__grid" aria-label="Karty przykładowych ogłoszeń">
        {demoReports.map((report) => (
          <ReportCard key={report.id} report={report} onSelect={setSelectedReport} />
        ))}
      </section>

      <p className="card-preview__selection" role="status">
        {selectedReport
          ? `Wybrano ogłoszenie: ${selectedReport.title}.`
          : 'Wybierz kartę, aby wskazać ogłoszenie.'}
      </p>
    </main>
  );
}
