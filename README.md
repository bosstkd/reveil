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
- `REVEIL_FCM_PROJECT_ID` — projet Firebase pour l'envoi des notifications push. Absent : envois seulement journalisés.
- `REVEIL_FCM_CREDENTIALS_JSON` — contenu JSON du compte de service Firebase (rôle « Firebase Cloud Messaging API Admin »).
  Absent : `GOOGLE_APPLICATION_CREDENTIALS` (chemin d'un fichier) est utilisé. Jamais dans le dépôt.

## Tests

```bash
cd backend && mvn test        # unitaires + intégration (MongoDB 4.4 embarqué, téléchargé au 1er lancement)
cd web && npx ng test --watch=false
cd mobile && npm test && npm run typecheck
./devpilot-verify.sh          # tout : compilation + tests (backend, web, mobile)
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
{ "title": "Concert", "date": "2026-12-24", "time": "20:30", "timeZone": "Europe/Paris", "location": "Olympia",
  "reminders": [1440, 60] }
```

`reminders` (facultatif) : délais des rappels en minutes, **au plus 3** parmi `15`, `60`, `1440` (1 jour), `10080`
(1 semaine), sans doublon. `[]` : aucun rappel. Absent : rappel par défaut 1 h (`reveil.reminders.lead-time`).

La date/heure est **locale** à l'utilisateur ; le serveur la convertit en UTC selon `timeZone`
(heure inexistante au passage à l'heure d'été : décalée vers l'avant).

- `201` → `{ "id", "title", "startsAt": "2026-12-24T19:30:00Z", "localDateTime": "2026-12-24T20:30:00",
  "timeZone", "location", "past": false, "warnings": [] }`
- Date passée : **acceptée** (`201`), `"past": true`, `"warnings": ["La date de cet événement est déjà passée"]`.
- `400` → titre vide/absent, date ou heure absente, fuseau absent ou inconnu, rappels invalides (`errors[].field` =
  `reminders`), JSON/date illisible.

### `GET /api/events?when=upcoming|past&page=0&size=20` (protégé)

Liste paginée des événements **de l'utilisateur connecté** (index `ix_events_owner_starts_at` sur `(ownerId, startsAt)`,
utilisé pour le filtre et le tri, sans tri en mémoire).

- `when` : `upcoming` (défaut, `startsAt ≥ maintenant`, date **croissante**) ou `past` (`startsAt < maintenant`,
  du plus récent au plus ancien). « Maintenant » = horloge du serveur.
- `page` ≥ 0 (défaut 0), `size` entre 1 et 100 (défaut 20).
- `200` → `{ "when": "upcoming", "items": [ <événement> ], "page": 0, "size": 20, "totalElements": 42, "hasNext": true }`
- `400` → `{ "message": "Paramètres invalides", "errors": [{ "field": "size", "message": "..." }] }`

Écran « Mes événements » (web et mobile) : onglets **À venir** / **Passés** (chargé à la première ouverture), pages
de 100 avec « Afficher plus » (mobile : `FlatList` virtualisée, chargement en fin de liste). Chaque élément affiche
titre, date, heure (dans le fuseau de saisie) et temps restant (« dans 2 jours », « il y a 3 heures » ; arrondi à
l'unité inférieure, rafraîchi chaque minute). Liste à venir vide : bouton « Créer mon premier événement ».

Clients : sélecteur « Rappels » dans les formulaires de création et de modification (web : cases à cocher ;
mobile : pastilles) — 15 min / 1 h / 1 jour / 1 semaine, 1 h coché par défaut, au-delà de 3 les autres choix sont
grisés, bouton « Désactiver les rappels ». Formulaire de création sur l'écran « Mes événements » (web : `<input type="date|time">` ; mobile :
sélecteur natif `@react-native-community/datetimepicker`). Le fuseau est celui du navigateur/appareil
(`Intl`). Une date passée affiche un avertissement sans bloquer ; l'événement créé est ajouté à la liste sans rechargement.

### `GET /api/events/{id}` (protégé)

Détail d'un événement de l'utilisateur connecté (même format que la liste). `404 { "message": "Événement introuvable" }`
s'il n'existe pas, a été supprimé ou appartient à un autre utilisateur (pas de fuite d'existence).

Tout événement renvoyé par l'API (création, liste, détail, modification) porte ses **rappels configurés** :

```json
"reminders": [{ "minutesBefore": 60, "remindAt": "2026-12-24T18:30:00Z", "status": "scheduled" }]
```

(triés du plus éloigné au plus proche de l'événement)

`status` : `scheduled` (programmé), `sent` (envoyé) ou `skipped` (abandonné : l'événement avait déjà commencé).
Liste vide si aucun rappel (événement créé dans le passé). Le temps restant est calculé par les clients à partir de
`startsAt`. Les photos restent servies par `GET /api/events/{id}/photos` (chargées en parallèle par les clients).

Écran / page de détail (web : `/evenements/:id`, ouvert depuis le titre d'un événement de la liste ; mobile : appui
sur un événement) : titre, date, heure (fuseau de saisie), lieu, temps restant (rafraîchi chaque minute), rappels
(« 1 h avant · 24 déc. 2026, 19:30 · programmé »), galerie de miniatures (la plus récente d'abord), boutons
« Modifier » (formulaire pré-rempli) et ajout de photo (mobile : « Prendre une photo » ; web : « Ajouter une photo »,
sélecteur de fichier JPEG/PNG ≤ 10 Mo, appareil photo proposé sur navigateur mobile). Sur le web, les miniatures
(routes protégées) sont téléchargées avec le jeton puis affichées via des URL `blob:` locales, libérées en quittant
la page.

### `PUT /api/events/{id}` (protégé)

Modification complète : même corps et mêmes validations que `POST /api/events` (titre, date, heure, fuseau, lieu,
rappels ; `reminders` absent = délais actuels conservés). `200` + événement modifié (avertissement si la date est
passée). Si l'instant de début change, tous les rappels sont recalculés sur la nouvelle date (y compris ceux déjà
envoyés ; aucun si la nouvelle date est passée). Sinon, un délai conservé garde son état (un rappel envoyé ne repart
pas), un délai ajouté est programmé, un délai retiré est supprimé. Mise à jour atomique (`findAndModify`) n'écrivant
que les champs modifiables ; les rappels sont écrits sous contrôle optimiste (`remindersVersion`, 3 tentatives) pour
ne pas écraser un envoi concurrent du planificateur.

### `DELETE /api/events/{id}` (protégé)

`204`. Supprime l'événement, son rappel programmé (porté par le document) et ses photos (métadonnées + fichiers
GridFS, via l'événement applicatif `EventDeleted`).

PUT et DELETE : `404` si l'événement n'existe pas, `403 { "message": "Vous n'êtes pas autorisé à modifier cet
événement" }` s'il appartient à un autre utilisateur.

Clients : web — boutons « Modifier » (formulaire en place, pré-rempli) et « Supprimer » (confirmation en place)
sur chaque événement de la liste ; mobile — mêmes actions sur l'écran de détail (confirmation par `Alert`, retour à
la liste après suppression). La date et l'heure sont modifiées dans le fuseau de saisie de l'événement.

## Rappels (notifications push)

- **Jusqu'à 3 rappels par événement** parmi 15 min, 1 h, 1 jour, 1 semaine (`ReminderOptions`), stockés dans le
  document de l'événement : `reminders: [{ minutesBefore, dueAt, status: SCHEDULED|SENT|SKIPPED, processedAt }]`,
  `remindersVersion` (compteur d'écritures). Index multiclé `ix_events_reminders_due` (`reminders.status`,
  `reminders.dueAt`). Par défaut (champ absent à la création) : 1 h avant. Liste vide : rappels désactivés.
  Événement passé : aucun rappel ; échéance déjà dépassée d'un événement à venir : envoi au prochain passage.
- **Planificateur** (`ReminderScheduler`) : chaque minute à la seconde 0 (`reveil.reminders.cron`), avec 5 s
  d'anticipation (`reveil.reminders.lookahead`). D'abord, les rappels échus d'événements déjà commencés (API arrêtée)
  sont marqués `SKIPPED`. Puis chaque événement ayant des rappels échus est pris par un `findAndModify` atomique qui
  marque ces rappels `SENT` (filtre de tableau `$[r]`) avant l'envoi : **chaque rappel part une seule fois**, même avec
  plusieurs instances. Plusieurs rappels échus d'un même événement (créé ou déplacé peu avant) donnent une seule
  notification.
- **Démarrage** : les documents antérieurs (`reminderDueAt` / `reminderSentAt` de la story 5, ou aucun champ) sont
  convertis en tableau `reminders` ; l'ancien index `ix_events_reminder_due_at` est supprimé.
- **Événement supprimé** : les rappels sont portés par le document ; ils disparaissent avec lui.
- **Envoi** (`FcmPushSender`) : API FCM HTTP v1 ; Android en direct, iOS relayé par FCM vers APNs. Message
  « notification » (affiché par l'OS application fermée), priorité haute (Android `HIGH`, APNs `10`), expiration
  à l'heure de l'événement, données `{ type: "event-reminder", eventId, link: "reveil://events/<id>" }`.
  Texte : « Rappel : Concert » / « Commence dans 1 h, à 20:30 · Olympia » ; à partir d'un jour :
  « Commence dans 7 jours, le 24/12 à 20:30 » (heure dans le fuseau de saisie).
- **Jetons invalides** : `UNREGISTERED`, `SENDER_ID_MISMATCH` ou `INVALID_ARGUMENT` portant sur le jeton → jeton
  supprimé. Autres erreurs (401, 429, 5xx, réseau) : jeton conservé, rappel non renvoyé.

### `PUT /api/devices` (protégé)

```json
{ "token": "<jeton FCM>", "platform": "android" }
```

`204`, idempotent. Collection `device_tokens` : `token` (unique, `uk_device_tokens_token`), `userId`
(`ix_device_tokens_user`), `sessionId`, `platform`. Un jeton déjà connu est rattaché au dernier compte connecté
sur l'appareil. Seuls les appareils dont la session est active reçoivent les rappels (après déconnexion, le jeton
est supprimé au prochain envoi). `400` : jeton vide, plate-forme absente ou inconnue.

### Mobile

- `@react-native-firebase/messaging` (FCM, + APNs sur iOS) : nécessite un **build de développement**
  (`npx expo prebuild` / `npx expo run:android|ios`), pas Expo Go.
- Fichiers Firebase du projet à déposer (non versionnés) : `mobile/google-services.json`,
  `mobile/GoogleService-Info.plist`. Sur iOS, la clé APNs (.p8) est à déclarer dans la console Firebase.
- **Premier lancement** : demande d'autorisation (Android 13+ : `POST_NOTIFICATIONS` ; iOS : alerte système),
  une seule fois (indicateur `reveil.notifications.asked`).
- Une fois connecté, le jeton FCM est envoyé à `PUT /api/devices`, puis à chaque renouvellement.
- **Appui sur la notification** (application fermée ou en arrière-plan) ou lien `reveil://events/<id>` :
  ouvre l'écran de détail de l'événement (après connexion si nécessaire ; « Cet événement n'existe plus. » si
  supprimé). Application au premier plan : une alerte propose « Voir ».

## Photos des événements

Stockage : fichiers dans **GridFS** (bucket `fs` : `fs.files` / `fs.chunks`, métadonnées `ownerId`, `eventId`),
métadonnées dans la collection `photos` (`eventId`, `ownerId`, `fileId`, `thumbnailFileId`, `contentType`, `size`,
`width`, `height`, `createdAt` ; index `ix_photos_event_created_at`). Chaque photo a une **miniature JPEG**
générée côté serveur (plus grand côté 400 px, `reveil.photos.thumbnail-size`, jamais agrandie ; transparence → fond blanc).

### `POST /api/events/{id}/photos` (protégé, `multipart/form-data`, partie `file`)

- Type contrôlé **sur le contenu** (signature) : JPEG ou PNG uniquement, le type déclaré est ignoré.
- Taille ≤ 10 Mo (`reveil.photos.max-size`, `spring.servlet.multipart.*`, nginx `client_max_body_size 11m`) ;
  dimensions ≤ 50 Mpx (`reveil.photos.max-pixels`), vérifiées avant décodage.
- `201` → `{ "id", "eventId", "contentType", "size", "width", "height", "createdAt",
  "url": "/api/events/{id}/photos/{photoId}/content", "thumbnailUrl": ".../thumbnail" }`
- `400` fichier absent/vide/illisible · `404` événement inconnu ou d'un autre utilisateur · `413` trop volumineux ·
  `415` type non pris en charge. Corps : `{ "message", "errors": [{ "field": "file", ... }] }`.

### `GET /api/events/{id}/photos` (protégé)

Galerie de l'événement, la plus récente d'abord (`404` si l'événement n'est pas à l'utilisateur).

### `GET /api/events/{id}/photos/{photoId}/content` et `/thumbnail` (protégés)

Octets de l'image d'origine / de la miniature (`Cache-Control: private, max-age=2592000`). `404` sinon.

### Mobile

- Un appui sur un événement de la liste ouvre son détail : galerie (miniatures) et bouton « Prendre une photo ».
- **Autorisation caméra au premier usage** (appui sur le bouton, pas au lancement) via `expo-image-picker` ;
  refus : message ; refus définitif : lien « Ouvrir les réglages ». Message iOS `NSCameraUsageDescription`
  et permission Android `CAMERA` déclarés dans `app.json` (micro et photothèque non demandés).
- Avant envoi, `expo-image-manipulator` **redimensionne à 2048 px de large maximum** (proportions conservées,
  jamais agrandie) et réencode en **JPEG qualité 0,8** (≈ 0,5–1,5 Mo au lieu de 3–6 Mo) : une photo de 5 Mo
  part en quelques secondes en 4G. Envoi abandonné après 30 s.
- **Échec d'envoi** (réseau, 5xx, délai) : message d'erreur et bouton « Réessayer », qui renvoie la photo
  déjà compressée sans reprendre de vue. La photo envoyée apparaît en tête de la galerie sans rechargement.
