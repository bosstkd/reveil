# Reveil — mémoire du projet

Application de rappels d'événements avec photos. Détail fonctionnel et API : `README.md` (à tenir à jour).

## Architecture
- `backend/` — API REST Spring Boot 3.5 / Java 17 / MongoDB. Paquets par domaine sous `com.reveil` :
  `auth` (JWT + sessions), `session`, `user`, `event` (CRUD, rappels configurés), `reminder` (planificateur, file,
  envoi), `push` (FCM HTTP v1, ou journalisation sans config), `device` (jetons FCM), `photo` (GridFS + miniatures),
  `account` (suppression du compte + purge), `config` (sécurité, HTTPS, en-têtes, erreurs JSON
  `{message, errors[{field,message}]}`).
- `web/` — Angular 22 (composants standalone, signals, formulaires réactifs). `core/` (services, modèles),
  `features/` (écrans). Appels API en chemins **relatifs** (`api/...`), base href au build. Tests Vitest (`ng test`).
- `mobile/` — React Native / Expo SDK 58. `src/screens`, `src/events` (modèles + API), `src/auth`. Tests Jest
  (`jest-expo`, `@testing-library/react-native`), via `<App />` avec `fetch` simulé.
- Docker : `Dockerfile` multi-étapes (cibles `api`, `web` = nginx 8080 relayant `/api`), `docker-compose.devpilot.yml`
  (mongo 4.4, api, web ; argument `BASE_HREF=/test/reveil/`).

## Conventions
- Code, commentaires, messages et tests en **français**. Textes d'erreur API en français.
- Événements : instant UTC `startsAt` + fuseau IANA de saisie ; le client envoie date/heure locales + `timeZone`.
- Rappels (story 9) : tableau `reminders` dans le document `events` (`minutesBefore`, `dueAt`, `status`
  SCHEDULED/SENT/SKIPPED, `processedAt`) + `remindersVersion` (verrou optimiste). Délais autorisés dans
  `ReminderOptions` (15, 60, 1440, 10080 ; max 3) — dupliqués côté clients (`REMINDER_OPTIONS` dans
  `web/.../event.models.ts` et `mobile/src/events/events.ts`) : garder synchronisés.
- Envoi « une seule fois » : le statut passe à SENT par `findAndModify` atomique **avant** l'envoi (`ReminderQueue`).
- Écritures concurrentes : `findAndModify` ciblé, jamais `save()` d'un document relu pour une modification.
- `data-testid` sur les éléments testés (web : attribut ; mobile : `testID`).
- Photos (stories 6, 10) : l'API n'accepte que JPEG/PNG (signature du contenu), ≤ 15 Mo/fichier (`PhotoSettings`).
  Envoi unitaire `POST .../photos` (partie `file`, caméra mobile) et multiple `POST .../photos/batch` (parties `files`,
  ≤ 10, traitement fichier par fichier → `{photos, rejected}`). Limite multipart Spring 60 Mo = nginx 61m (garder
  alignés). Mobile : tout est recompressé en JPEG (`expo-image-manipulator`). Web : HEIC converti en JPEG côté
  navigateur (`heic-to`, import dynamique dans `HeicConverter`, simulé dans les tests) ; règles `photo.models.ts`.
- Visionneuse / suppression (story 11) : `DELETE .../photos/{photoId}` (`deleteByIdAndEventIdAndOwnerId` = findAndRemove,
  puis GridFS) ; 404 pour une photo d'autrui ou déjà supprimée (clients : 404 = supprimée). Lecture : URL
  authentifiées (Bearer), pas d'URL signée. Web `features/events/photo-viewer` (originaux en `blob:`, voisines
  préchargées) ; mobile `src/screens/PhotoViewer.tsx` (`Modal` + `FlatList` paginée, confirmation `Alert`).

- Suppression de compte (story 12) : `DELETE /api/account` `{password}` → 204 / 403 (jamais 401 : déclencherait le
  rafraîchissement client). Marqueur `account_deletions` → suppression user → `AccountPurger.purge` (sessions, devices,
  events, photos, GridFS `metadata.ownerId`) ; `AccountPurgeScheduler` rejoue après `purge-grace` puis clôt. Toute
  nouvelle collection liée à un utilisateur **doit** être ajoutée à `AccountPurger` (+ `AccountIntegrationTest`).
- HTTPS : `reveil.security.require-https` (défaut true, `HttpsEnforcementFilter` → 403 en clair ; tests : false via
  `src/test/resources/application.properties`, vérifié par `SecurityHeadersTest`/`HttpsIntegrationTest` avec TLS réel
  autosigné). TLS natif facultatif (`REVEIL_TLS_*`, PEM). `forward-headers-strategy: native`. Compose : false.
  En-têtes de sécurité : `SecurityConfig` (API) et `web/nginx/default.conf` (UI, CSP compatible `heic-to/csp`).
  Mobile : `resolveApiUrl` refuse HTTP hors `__DEV__`.

## Commandes
- Tout vérifier : `./devpilot-verify.sh` (backend `mvn test`, web `ng test` + build prod, mobile `jest` + `tsc`).
- Backend seul : `cd backend && mvn test`. Lancement local en HTTP : `REVEIL_REQUIRE_HTTPS=false`. MongoDB embarqué 4.4 → besoin de `libcrypto.so.1.1` : le script le
  télécharge dans `~/.cache/reveil/libssl1.1` et règle `LD_LIBRARY_PATH` (ou `EMBEDDED_MONGO_VERSION=7.0.14` si AVX).
- Web : `cd web && npx ng test --watch=false`. Mobile : `cd mobile && npm test && npm run typecheck`.
- Les tests backend désactivent les planificateurs (`reveil.reminders.scheduler-enabled=false`,
  `reveil.accounts.purge-scheduler-enabled=false`) et appellent `ReminderService.sendDueReminders()` /
  `AccountService.purgePending()` ; horloge injectable via les constructeurs package-private (`Clock`).

## Historique des stories
1–3 compte / connexion / sessions · 4 liste des événements · 5 rappel push 1 h avant · 6 photos · 7 modifier/supprimer
· 8 détail · 9 choix des rappels (jusqu'à 3 délais, désactivation, envoi unique) · 10 import galerie (mobile 10 max,
web glisser-déposer JPEG/PNG/HEIC ≤ 15 Mo, `photos/batch`) · 11 visionneuse plein écran + suppression de photo
· 12 suppression du compte (RGPD, paramètres web/mobile), HTTPS obligatoire, en-têtes de sécurité.
