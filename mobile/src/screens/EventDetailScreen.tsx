import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, Image, Linking, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { API_URL } from '../config';
import { ReveilEvent, eventDate, eventTime, timeUntil } from '../events/events';
import { getEvent } from '../events/eventsApi';
import { compressPhoto, ensureCameraPermission, takePhoto } from '../photos/camera';
import type { EventPhoto, PreparedPhoto } from '../photos/photos';
import { listPhotos, uploadPhoto } from '../photos/photosApi';

export const CAMERA_DENIED = "L'accès à l'appareil photo est nécessaire pour prendre une photo.";
export const CAMERA_BLOCKED = "L'accès à l'appareil photo a été refusé. Autorisez-le dans les réglages de l'appareil.";
export const PREPARE_FAILED = 'Impossible de préparer la photo. Réessayez.';

type Busy = 'idle' | 'camera' | 'preparing' | 'uploading';

/**
 * Détail d'un événement (depuis la liste, une notification de rappel ou un lien reveil://events/<id>),
 * avec sa galerie et la prise de photo.
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
  /** Photo compressée dont l'envoi a échoué : renvoyée telle quelle par « Réessayer ». */
  const [pending, setPending] = useState<PreparedPhoto | null>(null);
  const mounted = useRef(true);

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
        setPending(photo);
        setUploadError((e as Error).message);
      } finally {
        if (mounted.current) setBusy('idle');
      }
    },
    [authFetch, eventId],
  );

  async function capture() {
    setCameraNotice(null);
    setUploadError(null);
    setPending(null);
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
      ) : (
        <View testID="event-detail">
          <Text testID="detail-title" style={styles.title} accessibilityRole="header">
            {event.title}
          </Text>
          <Text style={styles.meta}>
            <Text testID="detail-date">{eventDate(event)}</Text> à <Text testID="detail-time">{eventTime(event)}</Text>
          </Text>
          <Text testID="detail-remaining" style={styles.badge}>
            {timeUntil(event.startsAt)}
          </Text>
          {event.location ? (
            <Text testID="detail-location" style={styles.meta}>
              {event.location}
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

            {uploadError ? (
              <View testID="upload-error" accessibilityRole="alert" style={styles.errorBox}>
                <Text testID="upload-error-message" style={styles.error}>
                  {uploadError}
                </Text>
                {pending && busy === 'idle' ? (
                  <Pressable testID="retry-upload" accessibilityRole="button" style={styles.outline} onPress={() => void send(pending)}>
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
                {photos.map((p) => (
                  <Image
                    key={p.id}
                    testID="gallery-photo"
                    accessibilityLabel="Photo de l'événement"
                    source={{ uri: `${API_URL}${p.thumbnailUrl}`, headers: imageHeaders }}
                    style={styles.thumb}
                  />
                ))}
              </View>
            )}
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
  sectionTitle: { fontSize: 20, fontWeight: '600' },
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
  gallery: { flexDirection: 'row', flexWrap: 'wrap', gap: 4, marginTop: 8 },
  thumb: { width: 100, height: 100, borderRadius: 4, backgroundColor: '#eee' },
});
