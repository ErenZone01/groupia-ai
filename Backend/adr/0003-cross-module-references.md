# ADR-0003 — UUID et clés étrangères scalaires entre modules

- Statut : accepté
- Date : 2026-09-17
- Portée : propriété des données et intégrité dans le monolithe
- Référence : [graphe autorisé](../docs/module-dependencies.md)

## Contexte

Interdire les associations JPA intermodules protège les frontières du code. Cela n'interdit pas de garantir certaines références dans le PostgreSQL unique du monolithe.

## Décision validée

- Références intermodules par UUID.
- Associations JPA intermodules interdites.
- FK SQL scalaires intermodules autorisées dans le PostgreSQL unique.
- Aucune cascade intermodule, en suppression ou mise à jour.
- Archivage plutôt que suppression des objets commercialement référencés.

## Application

Chaque module possède ses données et migrations. Les contraintes intermodules concernent des identifiants stables et demandent un ordre de migration coordonné. Leur présence n'autorise pas une jointure métier cachée vers une table voisine, un accès à son repository ou l'import d'une entité.

Lorsque deux données appartiennent à une organisation, prévoir des références qui permettent aussi de vérifier la cohérence du propriétaire, par exemple une clé de référence composée de organizationId et id. Une FK par id seul ne valide pas toutes les règles d'appartenance métier.

Les relations internes aux modules utilisent les contraintes appropriées. Les références d'audit polymorphes ou webhooks non encore rapprochés nécessitent un traitement spécifique ; on n'invente pas une organisation ou un parent pour satisfaire une contrainte.

Le graphe SQL et le graphe Java sont différents : payment peut porter un orderId contraint sans importer ordering. Spring Modulith vérifie les frontières et cycles du code, pas les politiques d'accès aux données ni la correction des migrations.

## Conséquences

L'intégrité référentielle est renforcée sans exposer les modèles internes. Le coût assumé est une coordination des migrations et de l'archivage. Une extraction future en services exigerait de revisiter ces contraintes ; cette extraction n'est pas un objectif du MVP.

Les relations exactes, index et noms de contraintes seront définis dans le slice concerné, jamais par un CRUD générique.

## Options écartées

Associations JPA entre modules ; suppressions en cascade transversales ; absence de toute contrainte au seul motif de modularité ; lecture directe des tables voisines comme substitut d'une API.
