import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { ReveilEvent, eventDate, eventTime, timeUntil } from '../events/events';
import { getEvent } from '../events/eventsApi';

/** Détail d'un événement, ouvert depuis une notification de rappel ou un lien reveil://events/<id>. */
export function EventDetailScreen({ eventId, onBack }: { eventId: string; onBack: () => void }) {
  const { authFetch } = useAuth();
  const [event, setEvent] = useState<ReveilEvent | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setEvent(null);
    setError(null);
    getEvent(authFetch, eventId).then(
      (e) => active && setEvent(e),
      (e: Error) => active && setError(e.message),
    );
    return () => {
      active = false;
    };
  }, [authFetch, eventId]);

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
});
