# Reveil

C’est une application web après mobile qui permet de prendre des photos des evenements et permet de me notifier si l’evenement est proche

## Structure

| Dossier    | Rôle                                               | Pile                                   |
|------------|----------------------------------------------------|----------------------------------------|
| `backend/` | API REST (`/api/...`)                              | Java 17, Spring Boot 3.5, MongoDB      |
| `web/`     | Application web                                    | Angular 22                             |
| `mobile/`  | Application mobile                                 | React Native (Expo SDK 58)             |

## Développement

```bash
# API (MongoDB attendu sur localhost:27017, sinon SPRING_DATA_MONGODB_URI)
cd backend && mvn spring-boot:run          # http://localhost:8081
# Web (relaie /api vers :8081 via proxy.conf.json)
cd web && npm install && npm start         # http://localhost:4200
# Mobile
cd mobile && npm install && EXPO_PUBLIC_API_URL=http://<ip-du-poste>:8081 npx expo start
```

Variables d'environnement de l'API :

- `SPRING_DATA_MONGODB_URI` — URI MongoDB (défaut `mongodb://localhost:27017/reveil`)
- `REVEIL_JWT_SECRET` — clé de signature des jetons (≥ 32 octets). Absente : clé éphémère.

## Tests

```bash
cd backend && mvn test        # unitaires + intégration (MongoDB 4.4 embarqué, téléchargé au 1er lancement)
cd web && npx ng test --watch=false
cd mobile && npm test && npm run typecheck
```

Le MongoDB embarqué 4.4 (binaire Debian 10) requiert `libssl1.1` sur l'hôte ;
`EMBEDDED_MONGO_VERSION=7.0.14 mvn test` utilise une version récente (CPU AVX requis).
Sans libssl1.1 ni AVX : extraire `libssl1.1` (paquet Debian 11) dans un dossier hors dépôt et lancer
`LD_LIBRARY_PATH=<dossier>/usr/lib/x86_64-linux-gnu mvn test`.

## Environnement de test (Docker)

```bash
docker compose -f docker-compose.devpilot.yml up --build
```

Services : `mongo`, `api` (Spring Boot, 8081), `web` (nginx, 8080 : interface + relais `/api`).
L'interface est construite pour être servie sous `/test/reveil/` (argument de build `BASE_HREF`, défaut `/`).

## API

### `POST /api/auth/register`

```json
{ "email": "alice@example.com", "password": "motdepasse1" }
```

- `201` → `{ "token": "<JWT>", "user": { "id": "...", "email": "alice@example.com" } }`
- `400` → `{ "message": "Données invalides", "errors": [{ "field": "password", "message": "..." }] }`
- `409` → `{ "message": "Un compte existe déjà avec cet e-mail", ... }`

E-mail normalisé (trim + minuscules), unique (index MongoDB `uk_users_email`) ; mot de passe ≥ 8 caractères
dont 1 chiffre, haché en bcrypt.

## Authentification et sessions

- **Jeton d'accès** : JWT HS256, 15 min (`reveil.jwt.validity`), claims `sub` (id utilisateur), `email`, `sid` (session).
  À envoyer en `Authorization: Bearer <token>` ; toute route hors ouverture de session est protégée (Spring Security, 401 JSON).
- **Session** (collection `sessions`) : une par appareil connecté, repérée par un jeton de rafraîchissement opaque
  `<idSession>.<secret>` dont seul le hash SHA-256 est stocké. Expiration **glissante de 30 jours**
  (`reveil.session.validity`, index TTL MongoDB) : sur mobile, la session reste ouverte sans ressaisie tant que
  l'application est ouverte au moins une fois tous les 30 jours.
- **Rotation** à chaque rafraîchissement ; l'ancien jeton reste accepté 1 min (`reveil.session.rotation-grace`,
  requêtes concurrentes). Au-delà, sa réutilisation révoque la session (rejeu présumé).
- **Déconnexion** : supprime la session de l'appareil courant ; son jeton d'accès est refusé immédiatement
  (la session est vérifiée à chaque requête). Les autres appareils restent connectés.
- Stockage client : trousseau sécurisé `expo-secure-store` (mobile), `localStorage` (web).

### `POST /api/auth/login`

```json
{ "email": "alice@example.com", "password": "motdepasse1" }
```

- `200` → `{ "token": "<JWT>", "expiresIn": 900, "refreshToken": "<opaque>", "user": { "id": "...", "email": "..." } }`
- `401` → `{ "message": "E-mail ou mot de passe incorrect", "errors": [] }` (identique quel que soit le champ en cause)

`POST /api/auth/register` renvoie désormais la même structure (`201`).

### `POST /api/auth/refresh`

`{ "refreshToken": "..." }` → `200` nouvelle paire de jetons (même format que login) ; `401` session expirée/révoquée.

### `POST /api/auth/logout`

`{ "refreshToken": "..." }` (+ `Authorization` facultatif) → `204`, idempotent. Public : fonctionne même si le jeton
d'accès a expiré.

### `GET /api/auth/me`

Protégé → `200 { "id": "...", "email": "..." }` ; `401` sans jeton valide ou session révoquée.


## Événements

Collection `events` : `title`, `startsAt` (instant **UTC**, BSON Date), `timeZone` (fuseau IANA de l'utilisateur
à la saisie), `location` (facultatif, `null` si vide), `ownerId`, `createdAt`. Index `ix_events_owner_starts_at`.

### `POST /api/events` (protégé)

```json
{ "title": "Concert", "date": "2026-12-24", "time": "20:30", "timeZone": "Europe/Paris", "location": "Olympia" }
```

La date/heure est **locale** à l'utilisateur ; le serveur la convertit en UTC selon `timeZone`
(heure inexistante au passage à l'heure d'été : décalée vers l'avant).

- `201` → `{ "id", "title", "startsAt": "2026-12-24T19:30:00Z", "localDateTime": "2026-12-24T20:30:00",
  "timeZone", "location", "past": false, "warnings": [] }`
- Date passée : **acceptée** (`201`), `"past": true`, `"warnings": ["La date de cet événement est déjà passée"]`.
- `400` → titre vide/absent, date ou heure absente, fuseau absent ou inconnu (`errors[].field`), JSON/date illisible.

### `GET /api/events` (protégé)

Événements de l'utilisateur connecté, triés par date croissante (même format que ci-dessus).

Clients : formulaire de création sur l'écran « Mes événements » (web : `<input type="date|time">` ; mobile :
sélecteur natif `@react-native-community/datetimepicker`). Le fuseau est celui du navigateur/appareil
(`Intl`). Une date passée affiche un avertissement sans bloquer ; l'événement créé est ajouté à la liste sans rechargement.
