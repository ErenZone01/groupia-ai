# Groupia — Commandes de développement

Ce fichier sert de mémo pour les commandes utilisées régulièrement pendant le développement de Groupia.

> Environnement principal : Windows / PowerShell.

## 1. Démarrage quotidien

### Backend Spring Boot

Depuis `Backend/` :

```powershell
.\mvnw.cmd spring-boot:run
```

Spring Boot utilise le `compose.yaml` du backend pour les services de développement qu'il gère.

Si PostgreSQL n'est pas démarré automatiquement, voir la section **PostgreSQL manuel** plus bas.

### Frontend Angular

Depuis `Frontend/` :

```powershell
npm start -- --host 0.0.0.0
```

Le `--host 0.0.0.0` est nécessaire lorsque le frontend doit être accessible depuis le conteneur Cloudflare.

En local, l'application reste accessible sur :

```text
http://localhost:4200
```

Le proxy Angular transmet les appels `/api/**` vers le backend Spring Boot sur le port 8080.

---

## 2. Tunnel HTTPS Cloudflare

Le service `cloudflared` se trouve dans `Backend/compose.yaml`.

### Démarrer le tunnel

Depuis `Backend/` :

```powershell
docker compose --profile tunnel up -d cloudflared
```

### Voir l'URL publique générée

```powershell
docker logs -f groupia-cloudflared
```

Chercher une URL ressemblant à :

```text
https://xxxxx.trycloudflare.com
```

Cette URL est temporaire et peut changer lorsque le tunnel est recréé.

Pour les tests customer access / WhatsApp, utiliser cette origine comme :

```text
GROUPIA_PUBLIC_BASE_URL=https://xxxxx.trycloudflare.com
```

Ne pas ajouter `/api`, `/s` ou un autre chemin à `GROUPIA_PUBLIC_BASE_URL`.

### Arrêter le tunnel

```powershell
docker compose --profile tunnel stop cloudflared
```

### Vérifier les conteneurs

```powershell
docker compose ps
```

---

## 3. PostgreSQL manuel

Normalement Spring Boot peut gérer PostgreSQL via Docker Compose.

Si nécessaire, depuis `Backend/` :

### Démarrer PostgreSQL

```powershell
docker compose up -d postgres
```

### Voir les logs PostgreSQL

```powershell
docker compose logs -f postgres
```

### Arrêter PostgreSQL sans supprimer le conteneur

```powershell
docker compose stop postgres
```

> Éviter `docker compose down` tant que le stockage PostgreSQL n'est pas explicitement persistant dans un volume Docker. Supprimer le conteneur peut supprimer les données locales de développement.

---

## 4. Customer access local sans WhatsApp réel

À utiliser uniquement pour tester les OTP et magic links avec l'inbox locale.

WhatsApp Meta doit être désactivé.

Depuis `Backend/` :

```powershell
.\mvnw.cmd '-Dspring-boot.run.profiles=customer-access-local' spring-boot:run
```

Ce profil utilise notamment :

```text
secure-cookie=false
```

afin de permettre les tests sur `http://localhost:4200`.

Ne pas utiliser ce profil pour les tests WhatsApp Meta réels.

---

## 5. Customer access + WhatsApp réel

Pour le vrai transport WhatsApp :

1. démarrer Angular avec `--host 0.0.0.0` ;
2. démarrer le tunnel Cloudflare ;
3. récupérer l'URL HTTPS dans les logs ;
4. définir `GROUPIA_PUBLIC_BASE_URL` avec cette origine HTTPS ;
5. démarrer Spring Boot normalement, sans le profil `customer-access-local`.

Backend :

```powershell
.\mvnw.cmd spring-boot:run
```

Frontend :

```powershell
npm start -- --host 0.0.0.0
```

Tunnel :

```powershell
docker compose --profile tunnel up -d cloudflared
docker logs -f groupia-cloudflared
```

---

## 6. Tests backend

Depuis `Backend/` :

### Tests

```powershell
.\mvnw.cmd test
```

### Vérification complète Maven

```powershell
.\mvnw.cmd verify
```

---

## 7. Tests et build frontend

Depuis `Frontend/` :

### Tests unitaires

```powershell
npm test
```

### Tests navigateur / E2E

```powershell
npm run test:e2e
```

### Build production

```powershell
npm run build
```

### Régénérer le client OpenAPI

À utiliser lorsque le contrat backend exposé au frontend a changé :

```powershell
npm run api:generate
```

---

## 8. Routine conseillée

Pour une session de développement normale avec tunnel public :

**Terminal 1 — Frontend**

```powershell
cd Frontend
npm start -- --host 0.0.0.0
```

**Terminal 2 — Tunnel**

```powershell
cd Backend
docker compose --profile tunnel up -d cloudflared
docker logs -f groupia-cloudflared
```

**Terminal 3 — Backend**

Après avoir vérifié que `GROUPIA_PUBLIC_BASE_URL` correspond à l'URL Cloudflare courante :

```powershell
cd Backend
.\mvnw.cmd spring-boot:run
```

À la fin :

```powershell
docker compose --profile tunnel stop cloudflared
```

---

## 9. Ports utiles

| Service | Port local |
|---|---:|
| Angular | 4200 |
| Spring Boot | 8080 |
| PostgreSQL | fourni par Docker Compose |
| Cloudflare | URL HTTPS publique temporaire |

## 10. Rappel important

- Ne jamais commiter les access tokens Meta, clés Bictorys, clés JWT, magic keys ou OTP peppers.
- Les secrets restent dans l'environnement local / `.env` ignoré par Git.
- `customer-access-local` = simulation locale.
- démarrage normal + HTTPS Cloudflare = tests réels du parcours client et de WhatsApp.
