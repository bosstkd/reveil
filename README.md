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
