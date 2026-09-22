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

Le [ADR-0006](0006-identity-organization-slice.md), daté du 18 septembre 2026, documente les exigences et décisions d'implémentation de la première tranche identity/organization, dont le contenu technique désormais justifié de shared.

[ADR-0007](0007-catalog-variant-identity-and-concurrency.md), daté du 20 septembre 2026 :
identité canonique versionnée des variantes et concurrence de l'agrégat catalogue.
Il complète les décisions précédentes sans les remplacer.

[ADR-0008](0008-grouping-publication-and-coordination.md), daté du 20 septembre 2026 :
publication publique, coordination des campagnes/offres et journal local atomique
des prix/échéances. Il complète ADR-0002/0003/0005 sans les remplacer.

[ADR-0009](0009-checkout-admission-idempotency-and-tracking.md), daté du 20 septembre 2026 :
admission du checkout sous verrou, idempotence transactionnelle, secret de suivi
remis une seule fois, frais configurables et snapshots. Complète ADR-0003/0004/0005.

[ADR-0010](0010-financial-core-and-settlement.md), daté du 20 septembre 2026 :
noyau financier, obligation initiale atomique au checkout, frontières réseau,
réceptions et reprise, règlement événementiel, expiration et réconciliation.
Amende explicitement ADR-0001/0004 pour INITIAL_ORDER et complète ADR-0009.

[ADR-0011](0011-ordering-migration-compatibility.md), daté du 21 septembre 2026 :
compatibilité ciblée avec la première version déployée de 006-ordering et
convergence des contraintes par une nouvelle migration 008, sans perte de données.

[ADR-0012](0012-bictorys-sandbox-adapter.md), daté du 22 septembre 2026 :
adaptateur Bictorys sandbox, charge distincte de transaction, checkout privé,
preuves financières, authorized/reversed et limite de reprise sans transactionId.
