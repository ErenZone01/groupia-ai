# ADR-0012 — Adaptateur Bictorys sandbox
Date : 22 septembre 2026. Décisions d'implémentation de la tranche Bictorys.
Complète ADR-0010 ; précise sa limite de réconciliation sans identifiant transaction.

## Frontières
Bictorys reste dans payment.infrastructure.provider.bictorys. PaymentProvider est
le port de traduction ; aucun type externe dans domain/api. Montants, unicités,
retard, double succès, allocation et événements restent dans le noyau existant.
Aucun lien payment → ordering, association JPA intermodule ou exclusion Modulith.

Ordering transmet PaymentCheckoutContext via payment::api après autorisation du
jeton et lecture du snapshot. Aucun nouvel input public de contact, montant ou
organizationId. Les coordonnées ne sont pas dupliquées dans Payment.

## Extensions minimales
ProviderInstruction transporte le contexte éphémère. ProviderObservation reçoit
ProviderMetadata : référence lisible, lien de checkout, charge distincte de la
transaction, signal de revue. Les anciens constructeurs restent compatibles.

Après résolution d'une merchantReference authentifiée appartenant au provider et
compte, le noyau complète uniquement les identifiants internes absents de la
demande/commande/purpose. Il ne remplace ni cible explicitement différente, ni
montant, ni devise observés. Le numéro lisible attendu est également contrôlé.

Migration additive 009 : payments.payment_reference (40), provider_charge_id (160),
checkout_url (4096). Unicité provider/compte/charge non nulle. Colonnes nulles pour
les anciennes tentatives ; aucun changement des migrations déployées.
Le lien porte potentiellement un op_token : reçu protégé par jeton, PENDING sans
anomalie uniquement. toString expurgés ; aucun payload brut, secret ou customerObject
dans les réceptions.

## Accusé et preuve
202 peut contenir chargeId sans transactionId : les conserver séparément.
Un ACK PENDING peut être persisté sans transaction avec validation dédiée, sans
régler de dette. S'il arrive après un webhook, il ne doit pas effacer sa transaction
ni son état. Aucun statut indiqué dans un ACK ne vaut confirmation financière.

GET transactions/{id}/status est complété, si nécessaire, par le GET documenté
transactions/{id}. Les deux réponses doivent concorder et fournir les preuves
financières. Aucune copie des valeurs attendues pour inventer une preuve.

La recherche API par merchantReference seule souhaitée dans ADR-0010 n'est pas
documentée pour Bictorys. Sans transactionId après timeout ou 202, le claim reste
durable et attend webhook/redélivrance ou investigation dans le dashboard.
Aucun GET de charge inventé, polling permanent ou second débit. Ce choix amende
explicitement cette exigence d'ADR-0010 pour Bictorys en attendant un contrat dédié.

## Statuts ambigus
success/succeeded sont candidats SUCCEEDED, contrôlés par verify et le noyau.
pending/processing, failed, cancelled respectent les transitions existantes.

Le guide décrit authorized comme pré-capture carte mais recommande succeeded.
Sans capture carte, une autorisation n'est pas une preuve d'encaissement mobile
money : choix conservateur AUTHORIZATION_NOT_CAPTURED, revue sans règlement.
Cette différence explicite avec le guide doit être confirmée avec Bictorys avant
support des cartes. Un succeeded explicite et vérifié peut résoudre la situation.

reversed devient PROVIDER_REVERSAL : état financier conservé, SUCCEEDED ne régresse
pas. Les événements ordinaires et erreurs réseau ne peuvent effacer cette alerte.
Aucun remboursement ou changement commercial automatique ; résolution future
explicite et auditée. Statut inconnu : PROVIDER_STATUS_UNKNOWN.

## Exploitation et tests
Un compte sandbox configuré par serveur, accountReference=sandbox. Pas de rotation
de compte avec tentatives en cours sans procédure de reprise. API HTTPS sandbox
et clé test_public exigées, provider désactivé par défaut.
RestClient existant, délais 3 s/10 s, redirections HTTP désactivées.
Aucune dépendance Maven ajoutée. Surefire désactive le vrai provider avant le
chargement des contextes, indépendamment du .env local.

Tests HTTP locaux avec fixtures sourcées, PostgreSQL et assertions d'absence de
transaction dans chaque appel du vrai adaptateur. Tests historiques et Modulith
conservés. Aucun appel sandbox dans les tests ; validation distante manuelle.
Voir le [contrat et parcours sandbox](../docs/bictorys-sandbox.md).
