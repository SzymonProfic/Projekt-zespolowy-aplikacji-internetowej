import type { Report } from '../types/report';

export const demoReports: Report[] = [
  {
    id: '1',
    title: 'Słuchawki Apple AirPods Pro',
    type: 'lost',
    category: 'Elektronika',
    location: 'Warszawa, Śródmieście',
    date: '2026-06-18',
    color: 'Biały',
    description: 'Zgubiłem słuchawki w białym etui w okolicy stacji metra Centrum. Na etui mała rysa.',
  },
  {
    id: '2',
    title: 'Klucze z niebieskim brelokiem',
    type: 'found',
    category: 'Klucze',
    location: 'Kraków, Stare Miasto',
    date: '2026-06-18',
    color: 'Niebieski',
    description: 'Znaleziono pęk trzech kluczy z niebieskim brelokiem przy ławce w Plantach.',
  },
  {
    id: '3',
    title: 'Brązowy portfel skórzany',
    type: 'found',
    category: 'Portfele i dokumenty',
    location: 'Wrocław, Krzyki',
    date: '2026-06-17',
    color: 'Brązowy',
    description: 'Portfel znaleziony obok przystanku tramwajowego. Właściciel odebrał już przedmiot.',
    recovered: true,
  },
];
