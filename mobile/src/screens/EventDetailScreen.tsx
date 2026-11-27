import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Alert, Image, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { API_URL } from '../config';
import { ReveilEvent, eventDate, eventTime, reminderLabel, timeUntil } from '../events/events';
import { EVENT_NOT_FOUND, deleteEvent, getEvent } from '../events/eventsApi';
import { compressPhoto, ensureCameraPermission, pickFromGallery, takePhoto } from '../photos/camera';
import {
  type EventPhoto,
  type PreparedPhoto,
  SELECTION_TRUNCATED,
  photoName,
  rejectReason,
} from '../photos/photos';
import { listPhotos, uploadPhoto, uploadPhotos } from '../photos/photosApi';
import { EventForm } from './EventForm';
import { PhotoViewer } from './PhotoViewer';

export const CAMERA_DENIED = "L'accès à l'appareil photo est nécessaire pour prendre une photo.";
export const CAMERA_BLOCKED = "L'accès à l'appareil photo a été refusé. Autorisez-le dans les réglages de l'appareil.";
export const PREPARE_FAILED = 'Impossible de préparer la photo. Réessayez.';
export const GALLERY_FAILED = "Impossible d'ouvrir la galerie. Réessayez.";
export const DELETE_TITLE = "Supprimer l'événement ?";
export const DELETE_MESSAGE = 'Ses rappels programmés et ses photos seront aussi supprimés. Cette action est définitive.';

type Busy = 'idle' | 'camera' | 'gallery' | 'preparing' | 'uploading';

/** Envoi en échec, renvoyé tel quel par « Réessayer » : une photo (appareil photo) ou un lot (galerie). */
type Pending = { kind: 'single'; photo: PreparedPhoto } | { kind: 'batch'; photos: PreparedPhoto[] };

/** Rafraîchissement du temps restant affiché. */
export const CLOCK_TICK_MS = 60_000;

/**
 * Détail d'un événement (depuis la liste, une notification de rappel ou un lien reveil://events/<id>) :
 * titre, date, heure, lieu, temps restant (rafraîchi chaque minute), rappels configurés, galerie de miniatures, la prise de photo,
 * l'import depuis la galerie (10 photos au plus, envoyées en un seul envoi), la visionneuse plein écran (appui sur une miniature :
 * défilement, suppression d'une photo après confirmation), la modification et la suppression (après confirmation).
 * Après suppression, l'écran se ferme (`onBack`) : la liste, remontée, est rechargée.
 */
export function EventDetailScreen({ eventId, onBack }: { eventId: string; onBack: () => void }) {
  const { authFetch, session } = useAuth();
  const [event, setEvent] = useState<ReveilEvent | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [photos, setPhotos] = useState<EventPhoto[] | null>(null);
  const [photosError, setPhotosError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Busy>('idle');
  const [cameraNotice, setCameraNotice] = useState<{ message: string; blocked: boolean } | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  /** Photo(s) compressée(s) dont l'envoi a échoué : renvoyée(s) telle(s) quelle(s) par « Réessayer ». */
  const [pending, setPending] = useState<Pending | null>(null);
  /** Photos de la galerie refusées (format, préparation, refus de l'API) ; une ligne par photo. */
  const [importErrors, setImportErrors] = useState<string[]>([]);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [now, setNow] = useState(() => new Date());
  /** Position de la photo ouverte en plein écran (`null` : visionneuse fermée). */
  const [viewing, setViewing] = useState<number | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), CLOCK_TICK_MS);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    let active = true;
    setEvent(null);
    setError(null);
    setPhotos(null);
    setPhotosError(null);
    getEvent(authFetch, eventId).then(
      (e) => {
        if (!active) return;
        setEvent(e);
        listPhotos(authFetch, eventId).then(
          (p) => active && setPhotos(p),
          (err: Error) => active && setPhotosError(err.message),
        );
      },
      (e: Error) => active && setError(e.message),
    );
    return () => {
      active = false;
    };
  }, [authFetch, eventId]);

  const send = useCallback(
    async (photo: PreparedPhoto) => {
      setBusy('uploading');
      setUploadError(null);
      try {
        const saved = await uploadPhoto(authFetch, eventId, photo);
        if (!mounted.current) return;
        setPending(null);
        setPhotos((list) => [saved, ...(list ?? []).filter((p) => p.id !== saved.id)]);
      } catch (e) {
        if (!mounted.current) return;
        setPending({ kind: 'single', photo });
        setUploadError((e as Error).message);
      } finally {
        if (mounted.current) setBusy('idle');
      }
    },
    [authFetch, eventId],
  );

  /** Envoi multiple ; les photos enregistrées apparaissent en tête de la galerie (dernière choisie en premier). */
  const sendBatch = useCallback(
    async (batch: PreparedPhoto[]) => {
      setBusy('uploading');
      setUploadError(null);
      try {
        const result = await uploadPhotos(authFetch, eventId, batch);
        if (!mounted.current) return;
        setPending(null);
        const saved = [...result.photos].reverse();
        setPhotos((list) => [...saved, ...(list ?? []).filter((p) => !saved.some((s) => s.id === p.id))]);
        if (result.rejected.length) {
          setImportErrors((errors) => [
            ...errors,
            ...result.rejected.map((r) => `« ${batch[r.index] ? photoName(batch[r.index], r.index) : r.filename} » : ${r.message}`),
          ]);
        }
      } catch (e) {
        if (!mounted.current) return;
        setPending({ kind: 'batch', photos: batch });
        setUploadError((e as Error).message);
      } finally {
        if (mounted.current) setBusy('idle');
      }
    },
    [authFetch, eventId],
  );

  function resetNotices() {
    setCameraNotice(null);
    setUploadError(null);
    setImportErrors([]);
    setPending(null);
  }

  /** Import depuis la galerie : contrôle du format, compression (comme une prise de vue), envoi en une fois. */
  async function importFromGallery() {
    resetNotices();
    setBusy('gallery');
    try {
      let picked: Awaited<ReturnType<typeof pickFromGallery>>;
      try {
        picked = await pickFromGallery();
      } catch {
        if (mounted.current) setImportErrors([GALLERY_FAILED]);
        return;
      }
      if (!picked || !mounted.current) return;
      const errors: string[] = picked.truncated ? [SELECTION_TRUNCATED] : [];
      const accepted = picked.photos.filter((p, i) => {
        const reason = rejectReason(p, i);
        if (reason) errors.push(reason);
        return !reason;
      });
      setImportErrors([...errors]);
      if (!accepted.length) return;
      setBusy('preparing');
      const prepared: PreparedPhoto[] = [];
      for (const photo of accepted) {
        try {
          prepared.push(await compressPhoto(photo));
        } catch {
          errors.push(`« ${photoName(photo, picked.photos.indexOf(photo))} » : ${PREPARE_FAILED}`);
        }
      }
      if (!mounted.current) return;
      setImportErrors([...errors]);
      if (prepared.length) await sendBatch(prepared);
    } finally {
      if (mounted.current) setBusy((b) => (b === 'uploading' ? b : 'idle'));
    }
  }

  function retry(p: Pending) {
    if (p.kind === 'single') void send(p.photo);
    else void sendBatch(p.photos);
  }

  async function capture() {
    resetNotices();
    setBusy('camera');
    try {
      const access = await ensureCameraPermission();
      if (access !== 'granted') {
        setCameraNotice({ message: access === 'blocked' ? CAMERA_BLOCKED : CAMERA_DENIED, blocked: access === 'blocked' });
        return;
      }
      const shot = await takePhoto();
      if (!shot) return;
      setBusy('preparing');
      let prepared: PreparedPhoto;
      try {
        prepared = await compressPhoto(shot);
      } catch {
        setUploadError(PREPARE_FAILED);
        return;
      }
      await send(prepared);
    } finally {
      if (mounted.current) setBusy((b) => (b === 'uploading' ? b : 'idle'));
    }
  }

  function confirmDelete() {
    setActionError(null);
    setNotice(null);
    Alert.alert(DELETE_TITLE, DELETE_MESSAGE, [
      { text: 'Annuler', style: 'cancel' },
      { text: 'Supprimer', style: 'destructive', onPress: () => void remove() },
    ]);
  }

  async function remove() {
    setDeleting(true);
    try {
      await deleteEvent(authFetch, eventId);
      if (mounted.current) onBack();
    } catch (e) {
      if (!mounted.current) return;
      setDeleting(false);
      const err = e as Error;
      // Déjà supprimé ailleurs : l'écran n'a plus d'objet.
      if (err.message === EVENT_NOT_FOUND) setError(EVENT_NOT_FOUND);
      else setActionError(err.message);
    }
  }

  const closeViewer = useCallback(() => setViewing(null), []);
  const onPhotoDeleted = useCallback((deleted: EventPhoto) => {
    setPhotos((list) => (list ?? []).filter((p) => p.id !== deleted.id));
    setNotice('Photo supprimée.');
  }, []);

  const imageHeaders = session ? { Authorization: `Bearer ${session.token}` } : undefined;

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container}>
      <Pressable testID="detail-back" accessibilityRole="button" onPress={onBack} style={styles.back}>
        <Text style={styles.backText}>‹ Mes événements</Text>
      </Pressable>
      {error ? (
        <Text testID="detail-error" accessibilityRole="alert" style={styles.error}>
          {error}
        </Text>
      ) : !event ? (
        <ActivityIndicator testID="detail-loading" />
      ) : editing ? (
        <EventForm
          event={event}
          onCancel={() => setEditing(false)}
          onUpdated={(updated) => {
            setEvent(updated);
            setNow(new Date());
            setEditing(false);
            setNotice(updated.warnings?.length ? updated.warnings.join('\n') : 'Événement modifié.');
          }}
        />
      ) : (
        <View testID="event-detail">
          <Text testID="detail-title" style={styles.title} accessibilityRole="header">
            {event.title}
          </Text>
          <Text style={styles.meta}>
            <Text testID="detail-date">{eventDate(event)}</Text> à <Text testID="detail-time">{eventTime(event)}</Text>
          </Text>
          <Text testID="detail-remaining" style={styles.badge}>
            {timeUntil(event.startsAt, now)}
          </Text>
          {event.location ? (
            <Text testID="detail-location" style={styles.meta}>
              {event.location}
            </Text>
          ) : null}

          <View testID="detail-reminders" style={styles.reminders}>
            <Text style={styles.subTitle}>Rappels</Text>
            {(event.reminders ?? []).length === 0 ? (
              <Text testID="reminders-empty" style={styles.meta}>
                Aucun rappel configuré.
              </Text>
            ) : (
              (event.reminders ?? []).map((r) => (
                <Text key={`${r.minutesBefore}-${r.remindAt}`} testID="detail-reminder" style={styles.meta}>
                  🔔 {reminderLabel(event, r)}
                </Text>
              ))
            )}
          </View>

          <View style={styles.actions}>
            <Pressable
              testID="detail-edit"
              accessibilityRole="button"
              disabled={deleting}
              onPress={() => {
                setNotice(null);
                setActionError(null);
                setEditing(true);
              }}
              style={styles.outline}
            >
              <Text style={styles.outlineText}>Modifier</Text>
            </Pressable>
            <Pressable
              testID="detail-delete"
              accessibilityRole="button"
              accessibilityState={{ disabled: deleting }}
              disabled={deleting}
              onPress={confirmDelete}
              style={[styles.danger, deleting && styles.disabled]}
            >
              {deleting ? <ActivityIndicator color="#c92a2a" /> : <Text style={styles.dangerText}>Supprimer</Text>}
            </Pressable>
          </View>
          {notice ? (
            <Text testID="detail-notice" accessibilityRole="alert" style={styles.noticeBox}>
              {notice}
            </Text>
          ) : null}
          {actionError ? (
            <Text testID="detail-action-error" accessibilityRole="alert" style={styles.error}>
              {actionError}
            </Text>
          ) : null}

          <View style={styles.section}>
            <Text style={styles.sectionTitle} accessibilityRole="header">
              Photos
            </Text>
            <Pressable
              testID="take-photo"
              accessibilityRole="button"
              accessibilityState={{ disabled: busy !== 'idle' }}
              disabled={busy !== 'idle'}
              onPress={() => void capture()}
              style={[styles.primary, busy !== 'idle' && styles.disabled]}
            >
              <Text style={styles.primaryText}>📷 Prendre une photo</Text>
            </Pressable>
            <Pressable
              testID="pick-photos"
              accessibilityRole="button"
              accessibilityHint="Jusqu'à 10 photos à la fois"
              accessibilityState={{ disabled: busy !== 'idle' }}
              disabled={busy !== 'idle'}
              onPress={() => void importFromGallery()}
              style={[styles.outline, styles.wide, busy !== 'idle' && styles.disabled]}
            >
              <Text style={styles.outlineText}>🖼️ Choisir dans la galerie (10 max.)</Text>
            </Pressable>

            {busy === 'preparing' || busy === 'uploading' ? (
              <View testID="photo-progress" style={styles.row}>
                <ActivityIndicator />
                <Text style={styles.meta}>{busy === 'preparing' ? ' Préparation…' : ' Envoi en cours…'}</Text>
              </View>
            ) : null}

            {cameraNotice ? (
              <View testID="camera-permission" accessibilityRole="alert" style={styles.notice}>
                <Text style={styles.noticeText}>{cameraNotice.message}</Text>
                {cameraNotice.blocked ? (
                  <Pressable testID="open-settings" accessibilityRole="button" onPress={() => void Linking.openSettings()}>
                    <Text style={styles.link}>Ouvrir les réglages</Text>
                  </Pressable>
                ) : null}
              </View>
            ) : null}

            {importErrors.length ? (
              <View testID="import-errors" accessibilityRole="alert" style={styles.errorBox}>
                {importErrors.map((message, i) => (
                  <Text key={i} testID="import-error" style={styles.error}>
                    {message}
                  </Text>
                ))}
              </View>
            ) : null}

            {uploadError ? (
              <View testID="upload-error" accessibilityRole="alert" style={styles.errorBox}>
                <Text testID="upload-error-message" style={styles.error}>
                  {uploadError}
                </Text>
                {pending && busy === 'idle' ? (
                  <Pressable testID="retry-upload" accessibilityRole="button" style={styles.outline} onPress={() => retry(pending)}>
                    <Text style={styles.outlineText}>Réessayer</Text>
                  </Pressable>
                ) : null}
              </View>
            ) : null}

            {photosError ? (
              <Text testID="photos-error" style={styles.error}>
                {photosError}
              </Text>
            ) : photos === null ? (
              <ActivityIndicator testID="photos-loading" />
            ) : photos.length === 0 ? (
              <Text testID="photos-empty" style={styles.meta}>
                Aucune photo pour cet événement.
              </Text>
            ) : (
              <View testID="gallery" style={styles.gallery}>
                {photos.map((p, i) => (
                  <Pressable
                    key={p.id}
                    testID="gallery-open"
                    accessibilityRole="imagebutton"
                    accessibilityLabel={`Voir la photo ${i + 1} en plein écran`}
                    onPress={() => {
                      setNotice(null);
                      setViewing(i);
                    }}
                  >
                    <Image
                      testID="gallery-photo"
                      accessibilityLabel="Photo de l'événement"
                      source={{ uri: `${API_URL}${p.thumbnailUrl}`, headers: imageHeaders }}
                      style={styles.thumb}
                    />
                  </Pressable>
                ))}
              </View>
            )}
            {viewing !== null && photos?.length ? (
              <PhotoViewer photos={photos} start={viewing} onClose={closeViewer} onDeleted={onPhotoDeleted} />
            ) : null}
          </View>
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: '#fff' },
  container: { padding: 24, paddingTop: 64 },
  back: { marginBottom: 16 },
  backText: { color: '#3b5bdb', fontSize: 16 },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 8 },
  meta: { color: '#555', fontSize: 16, marginVertical: 2 },
  badge: { alignSelf: 'flex-start', marginVertical: 6, paddingHorizontal: 6, backgroundColor: '#edf2ff', color: '#3b5bdb', borderRadius: 4 },
  error: { color: '#c92a2a', marginVertical: 8 },
  section: { marginTop: 24 },
  actions: { flexDirection: 'row', gap: 8, marginTop: 12 },
  danger: { borderWidth: 1, borderColor: '#c92a2a', borderRadius: 8, paddingVertical: 6, paddingHorizontal: 10, alignItems: 'center' },
  dangerText: { color: '#c92a2a' },
  noticeBox: { marginTop: 8, padding: 8, borderRadius: 6, backgroundColor: '#fff3bf', color: '#8a6100' },
  sectionTitle: { fontSize: 20, fontWeight: '600' },
  reminders: { marginTop: 12 },
  subTitle: { fontSize: 16, fontWeight: '600', marginBottom: 2 },
  primary: { backgroundColor: '#3b5bdb', borderRadius: 8, padding: 12, alignItems: 'center', marginVertical: 12 },
  primaryText: { color: '#fff', fontWeight: '600' },
  disabled: { opacity: 0.5 },
  row: { flexDirection: 'row', alignItems: 'center', marginVertical: 4 },
  notice: { padding: 8, borderRadius: 6, backgroundColor: '#fff3bf', marginBottom: 8 },
  noticeText: { color: '#8a6100' },
  link: { color: '#3b5bdb', marginTop: 4 },
  errorBox: { padding: 8, borderRadius: 6, backgroundColor: '#fff5f5', marginBottom: 8 },
  outline: { borderWidth: 1, borderColor: '#3b5bdb', borderRadius: 8, paddingVertical: 6, paddingHorizontal: 10, alignItems: 'center', alignSelf: 'flex-start' },
  outlineText: { color: '#3b5bdb' },
  wide: { alignSelf: 'stretch', paddingVertical: 10, marginBottom: 12 },
  gallery: { flexDirection: 'row', flexWrap: 'wrap', gap: 4, marginTop: 8 },
  thumb: { width: 100, height: 100, borderRadius: 4, backgroundColor: '#eee' },
});
