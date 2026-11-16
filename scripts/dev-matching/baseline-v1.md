# Ocena danych testowych

Wygenerowano: 2026-11-13T19:15:40.168127+00:00

SHA-256 fixtures.json: `28a76f2bc6f8ea4dfc575ea3b150b1a6201003234380d7967cd33ea28b786da6`

Metadane zapisanych wyników:

```json
{
  "embeddings": [
    {
      "model_id": "sdadas/mmlw-roberta-base",
      "model_revision": "bdeb29a43c3d58af1e6ed9d1836703a1ddfbe1d7",
      "preprocessing_version": "v1"
    }
  ],
  "ranking_versions": [
    "v1"
  ]
}
```

Wyniki dotyczą syntetycznych scenariuszy, nie jakości na rzeczywistych ogłoszeniach.
UNCERTAIN i EXCLUDED nie są liczone jako negatywne przykłady.
Próg poniżej jest tylko symulacją na zapisanych parach, bez zmiany konfiguracji aplikacji.
Brak zapisanej pary nie daje jej wyniku końcowego; surowy cosine jest w pairs.csv.

| Próg finalScore | Poprawne sugestie | Błędne sugestie | Precision | Recall poprawnych par |
|---|---:|---:|---:|---:|
| 0.00 | 13 | 206 | 5.9% | 100.0% |
| 0.70 | 13 | 197 | 6.2% | 100.0% |
| 0.75 | 12 | 162 | 6.9% | 92.3% |
| 0.80 | 12 | 93 | 11.4% | 92.3% |
| 0.85 | 12 | 49 | 19.7% | 92.3% |
| 0.90 | 11 | 3 | 78.6% | 84.6% |

## Ranking dla LOST

| Ogłoszenie | Top 1 | Pierwszy poprawny wynik | Poprawne w top 3 |
|---|---|---:|---:|
| lost-keys | found-keys (MATCH) | 1 | 1 |
| lost-wallet | found-wallet (MATCH) | 1 | 1 |
| lost-headphones | found-headphones (MATCH) | 1 | 1 |
| lost-speaker | found-speaker (MATCH) | 1 | 1 |
| lost-bag | found-bag (MATCH) | 1 | 2 |
| lost-ring | found-ring (MATCH) | 1 | 1 |
| lost-phone | found-phone (MATCH) | 1 | 1 |
| lost-glasses | found-glasses (MATCH) | 1 | 1 |
| lost-umbrella | found-umbrella (MATCH) | 1 | 1 |
| lost-watch | found-watch (MATCH) | 1 | 1 |

Pełne wyniki, w tym trudne negatywy, odległości i surowe cosine: `/tmp/foundly-matching-evaluation/pairs.csv`.
Remisy w tym raporcie rozstrzyga klucz scenariusza; API używa UUID pary.
Zestaw v1 służy dostrojeniu. Walidacja wymaga osobnego zestawu nowych przedmiotów.
