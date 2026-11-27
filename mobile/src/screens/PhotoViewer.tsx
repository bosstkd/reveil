import { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  FlatList,
  Image,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  View,
  useWindowDimensions,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { API_URL } from '../config';
import type { EventPhoto } from '../photos/photos';
import { deletePhoto } from '../photos/photosApi';

export const DELETE_PHOTO_TITLE = 'Supprimer la photo ?';
export const DELETE_PHOTO_MESSAGE = 'Elle sera retirée de cet événement sur tous vos appareils. Cette action est définitive.';

type Props = {
  /** Galerie (la plus récente d'abord), tenue à jour par l'écran parent. */
  photos: EventPhoto[];
  /** Position de la photo ouverte. */
  start: number;
  onClose: () => void;
  /** Photo supprimée côté serveur : l'écran parent la retire de la galerie. */
  onDeleted: (photo: EventPhoto) => void;
};

/**
 * Visionneuse plein écran : photo d'origine (URL d'API protégée, jeton dans l'en-tête Authorization) affichée
 * par-dessus sa miniature pendant le chargement, défilement horizontal page par page vers les suivantes
 * (`FlatList` virtualisée : seules les voisines sont rendues), boutons précédente/suivante, suppression
 * après confirmation (`Alert`).
 */
export function PhotoViewer({ photos, start, onClose, onDeleted }: Props) {
  const { authFetch, session } = useAuth();
  const { width, height } = useWindowDimensions();
  const list = useRef<FlatList<EventPhoto>>(null);
  const [index, setIndex] = useState(() => Math.min(Math.max(start, 0), Math.max(photos.length - 1, 0)));
  const [deleting, setDeleting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // Galerie raccourcie (suppression) : position bornée ; plus aucune photo : fermeture.
  const position = Math.min(index, Math.max(photos.length - 1, 0));
  useEffect(() => {
    if (!photos.length) onClose();
    else if (position !== index) setIndex(position);
  }, [photos.length, position, index, onClose]);

  const current = photos[position];
  const headers = session ? { Authorization: `Bearer ${session.token}` } : undefined;

  function go(to: number) {
    if (to < 0 || to >= photos.length) return;
    setIndex(to);
    setError(null);
    try {
      list.current?.scrollToIndex({ index: to, animated: true });
    } catch {
      // Liste pas encore mesurée : la position est appliquée par l'état.
    }
  }

  function onScrollEnd(e: NativeSyntheticEvent<NativeScrollEvent>) {
    const to = Math.round(e.nativeEvent.contentOffset.x / width);
    if (to !== position && to >= 0 && to < photos.length) {
      setIndex(to);
      setError(null);
    }
  }

  function confirmDelete() {
    if (!current || deleting) return;
    setError(null);
    const photo = current;
    Alert.alert(DELETE_PHOTO_TITLE, DELETE_PHOTO_MESSAGE, [
      { text: 'Annuler', style: 'cancel' },
      { text: 'Supprimer', style: 'destructive', onPress: () => void remove(photo) },
    ]);
  }

  async function remove(photo: EventPhoto) {
    setDeleting(true);
    try {
      await deletePhoto(authFetch, photo);
      if (!mounted.current) return;
      // La suivante prend la place de la photo supprimée (même position).
      onDeleted(photo);
    } catch (e) {
      if (mounted.current) setError((e as Error).message);
    } finally {
      if (mounted.current) setDeleting(false);
    }
  }

  return (
    <Modal visible animationType="fade" onRequestClose={onClose} supportedOrientations={['portrait', 'landscape']}>
      <View testID="photo-viewer" style={styles.root} accessibilityViewIsModal>
        <FlatList
          ref={list}
          testID="viewer-list"
          data={photos}
          keyExtractor={(p) => p.id}
          horizontal
          pagingEnabled
          showsHorizontalScrollIndicator={false}
          initialScrollIndex={position}
          getItemLayout={(_, i) => ({ length: width, offset: width * i, index: i })}
          initialNumToRender={1}
          windowSize={3}
          maxToRenderPerBatch={2}
          onMomentumScrollEnd={onScrollEnd}
          renderItem={({ item }) => (
            <View style={[styles.page, { width, height }]}>
              <Image
                source={{ uri: `${API_URL}${item.thumbnailUrl}`, headers }}
                style={StyleSheet.absoluteFill}
                resizeMode="contain"
                blurRadius={2}
              />
              <Image
                testID="viewer-image"
                accessibilityLabel="Photo de l'événement en plein écran"
                source={{ uri: `${API_URL}${item.url}`, headers }}
                style={StyleSheet.absoluteFill}
                resizeMode="contain"
              />
            </View>
          )}
        />

        <View style={[styles.bar, styles.top]}>
          <Text testID="viewer-counter" style={styles.counter}>
            {photos.length ? `${position + 1} / ${photos.length}` : ''}
          </Text>
          <Pressable testID="viewer-close" accessibilityRole="button" accessibilityLabel="Fermer" onPress={onClose} style={styles.ghost}>
            <Text style={styles.ghostText}>✕</Text>
          </Pressable>
        </View>

        <View style={[styles.bar, styles.bottom]}>
          <Pressable
            testID="viewer-previous"
            accessibilityRole="button"
            accessibilityLabel="Photo précédente"
            accessibilityState={{ disabled: position === 0 }}
            disabled={position === 0}
            onPress={() => go(position - 1)}
            style={[styles.ghost, position === 0 && styles.disabled]}
          >
            <Text style={styles.ghostText}>‹</Text>
          </Pressable>
          <Pressable
            testID="viewer-delete"
            accessibilityRole="button"
            accessibilityState={{ disabled: deleting }}
            disabled={deleting}
            onPress={confirmDelete}
            style={[styles.danger, deleting && styles.disabled]}
          >
            {deleting ? <ActivityIndicator color="#fff" /> : <Text style={styles.dangerText}>🗑️ Supprimer</Text>}
          </Pressable>
          <Pressable
            testID="viewer-next"
            accessibilityRole="button"
            accessibilityLabel="Photo suivante"
            accessibilityState={{ disabled: position >= photos.length - 1 }}
            disabled={position >= photos.length - 1}
            onPress={() => go(position + 1)}
            style={[styles.ghost, position >= photos.length - 1 && styles.disabled]}
          >
            <Text style={styles.ghostText}>›</Text>
          </Pressable>
        </View>
        {error ? (
          <Text testID="viewer-error" accessibilityRole="alert" style={styles.error}>
            {error}
          </Text>
        ) : null}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#000' },
  page: { justifyContent: 'center', alignItems: 'center' },
  bar: { position: 'absolute', left: 0, right: 0, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16 },
  top: { top: 48 },
  bottom: { bottom: 40 },
  counter: { color: '#fff', fontSize: 16 },
  ghost: { borderWidth: 1, borderColor: 'rgba(255,255,255,0.6)', borderRadius: 8, paddingVertical: 6, paddingHorizontal: 14, backgroundColor: 'rgba(0,0,0,0.4)' },
  ghostText: { color: '#fff', fontSize: 20 },
  danger: { backgroundColor: '#c92a2a', borderRadius: 8, paddingVertical: 10, paddingHorizontal: 16, minWidth: 120, alignItems: 'center' },
  dangerText: { color: '#fff', fontWeight: '600' },
  disabled: { opacity: 0.4 },
  error: { position: 'absolute', bottom: 96, left: 16, right: 16, textAlign: 'center', color: '#ffa8a8' },
});
