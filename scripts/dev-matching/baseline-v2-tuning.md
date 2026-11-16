# Ocena danych testowych

Wygenerowano: 2026-11-13T19:37:50.425393+00:00

Podzbiór: **tuning**. Obie strony ocenianej pary należą do tego podzbioru.

SHA-256 fixtures.json: `ac16527af9409066b6f2f174c9dd53eca5351b600a494c040c3e860b28f9971d`

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
| 0.00 | 35 | 623 | 5.3% | 100.0% |
| 0.70 | 35 | 380 | 8.4% | 100.0% |
| 0.75 | 34 | 235 | 12.6% | 97.1% |
| 0.80 | 34 | 122 | 21.8% | 97.1% |
| 0.85 | 34 | 72 | 32.1% | 97.1% |
| 0.90 | 32 | 22 | 59.3% | 91.4% |

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
| lost-v2-bike-key | found-v2-bike-key (MATCH) | 1 | 1 |
| lost-v2-car-key | found-v2-car-key-vague (UNCERTAIN) | 2 | 1 |
| lost-v2-purse | found-v2-purse (MATCH) | 1 | 1 |
| lost-v2-laptop | found-v2-laptop (MATCH) | 1 | 2 |
| lost-v2-reader | found-v2-reader (MATCH) | 1 | 1 |
| lost-v2-powerbank | found-v2-powerbank (MATCH) | 1 | 1 |
| lost-v2-charger | found-v2-charger-different (NO_MATCH) | 2 | 1 |
| lost-v2-bracelet | found-v2-bracelet (MATCH) | 1 | 1 |
| lost-v2-necklace | found-v2-necklace (MATCH) | 1 | 1 |
| lost-v2-sportsbag | found-v2-sportsbag (MATCH) | 1 | 2 |
| lost-v2-sunglasses | found-v2-sunglasses (MATCH) | 1 | 1 |
| lost-v2-jacket | found-v2-jacket (MATCH) | 1 | 1 |
| lost-v2-gloves | found-v2-gloves (MATCH) | 1 | 1 |
| lost-v2-bear | found-v2-bear (MATCH) | 1 | 1 |
| lost-v2-scooter | found-v2-scooter (MATCH) | 1 | 1 |
| lost-v2-racket | found-v2-racket (MATCH) | 1 | 1 |
| lost-v2-book | found-v2-book (MATCH) | 1 | 1 |
| lost-v2-binder | found-v2-binder (MATCH) | 1 | 1 |
| lost-v2-lunchbox | found-v2-lunchbox (MATCH) | 1 | 1 |
| lost-v2-lanyard | found-v2-lanyard-different (NO_MATCH) | 2 | 1 |
| lost-v2-unmatched-calculator | found-v2-unmatched-calculator (NO_MATCH) | — | 0 |
| lost-v2-unmatched-shoes | found-v2-unmatched-shoes (NO_MATCH) | — | 0 |
| lost-v2-unmatched-tote | found-v2-unmatched-tote (NO_MATCH) | — | 0 |

Pełne wyniki, w tym trudne negatywy, odległości i surowe cosine: `/tmp/foundly-matching-evaluation-v2/tuning/pairs.csv`.
Remisy w tym raporcie rozstrzyga klucz scenariusza; API używa UUID pary.
Ranking dotyczy zapisanych par z tego podzbioru. API korzysta z całej bazy.
Zestaw tuning służy dostrojeniu. Validation otwórz po ustaleniu konfiguracji.
