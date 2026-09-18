# Décisions architecturales Groupia

Les ADR ci-dessous enregistrent les décisions V1 validées le 17 septembre 2026. Les détails de conception proposés pour les appliquer sont explicités dans les conséquences ; les politiques commerciales encore ouvertes sont centralisées dans [les règles métier](../docs/business-rules.md#questions-ouvertes-et-blocage-par-slice).

| ADR | Décision | Statut |
| --- | --- | --- |
| [0001](0001-payment-model.md) | Obligations, tentatives et remboursements distincts | Accepté |
| [0002](0002-shipping-model.md) | Transport par commande, calculs révisés et indépendance financière | Accepté |
| [0003](0003-cross-module-references.md) | Références UUID, FK SQL scalaires autorisées, aucun lien JPA intermodule | Accepté |
| [0004](0004-public-checkout.md) | Checkout public sans compte, contexte serveur et suivi privé par jeton hashé | Accepté |
| [0005](0005-offer-price-changes.md) | Prix modifiables pendant OPEN avec revalidation et conflit explicite | Accepté |

Une proposition antérieure ne prévaut pas sur ces décisions. Toute modification future doit indiquer quel ADR elle remplace et pourquoi. Aucun ADR de cette série n'implique qu'un workflow métier soit déjà implémenté.
