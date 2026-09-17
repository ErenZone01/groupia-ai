# Arborescence des sources

Arborescence relevée après création du squelette, sans les fichiers de compilation.

```text
src/
+-- main/
|   +-- java/
|   |   \-- sn/
|   |       \-- groupia/
|   |           +-- audit/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- catalog/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- grouping/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- identity/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- media/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- notification/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- ordering/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- organization/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- payment/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           +-- shared/
|   |           |   \-- package-info.java
|   |           +-- shipping/
|   |           |   +-- api/
|   |           |   |   +-- dto/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- event/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- application/
|   |           |   |   +-- command/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- query/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- domain/
|   |           |   |   +-- exception/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- model/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- repository/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- service/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   +-- infrastructure/
|   |           |   |   +-- persistence/
|   |           |   |   |   \-- package-info.java
|   |           |   |   +-- web/
|   |           |   |   |   \-- package-info.java
|   |           |   |   \-- package-info.java
|   |           |   \-- package-info.java
|   |           \-- GroupiaApplication.java
|   \-- resources/
|       +-- db/
|       |   \-- changelog/
|       |       +-- 001-modulith-event-publication.sql
|       |       \-- db.changelog-master.yaml
|       +-- static/
|       +-- templates/
|       \-- application.properties
\-- test/
    \-- java/
        \-- sn/
            \-- groupia/
                +-- GroupiaApplicationTests.java
                +-- ModularityTests.java
                +-- TestcontainersConfiguration.java
                \-- TestGroupiaApplication.java
```
