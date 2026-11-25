import { useEffect, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { ReveilEvent, formatWhen, sortByStart } from '../events/events';
import { listEvents } from '../events/eventsApi';
import { EventForm } from './EventForm';

/** Liste des événements de l'utilisateur, avec création en place. */
export function EventsScreen() {
  const { session, logout, authFetch } = useAuth();
  const [leaving, setLeaving] = useState(false);
  const [events, setEvents] = useState<ReveilEvent[]>([]);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    listEvents(authFetch)
      .then((list) => active && setEvents((current) => sortByStart(mergeById(list, current))))
      .catch((e: Error) => active && setLoadError(e.message));
    return () => {
      active = false;
    };
  }, [authFetch]);

  function onCreated(created: ReveilEvent) {
    // Ajout immédiat, sans rechargement de la liste.
    setEvents((current) => sortByStart(mergeById(current, [created])));
    setCreating(false);
    setNotice(created.warnings?.length ? created.warnings.join('\n') : null);
  }

  return (
    <ScrollView style={styles.scroll} contentContainerStyle={styles.container} keyboardShouldPersistTaps="handled">
      <View style={styles.header}>
        <Text style={styles.title} accessibilityRole="header">
          Mes événements
        </Text>
        <Pressable
          testID="logout"
          accessibilityRole="button"
          disabled={leaving}
          onPress={() => {
            setLeaving(true);
            void logout();
          }}
          style={styles.outline}
        >
          <Text style={styles.outlineText}>Se déconnecter</Text>
        </Pressable>
      </View>
      <Text style={styles.who}>Connecté en tant que {session?.user.email}</Text>

      {creating ? (
        <EventForm onCreated={onCreated} onCancel={() => setCreating(false)} />
      ) : (
        <Pressable
          testID="new-event"
          accessibilityRole="button"
          style={styles.primary}
          onPress={() => {
            setNotice(null);
            setCreating(true);
          }}
        >
          <Text style={styles.primaryText}>+ Nouvel événement</Text>
        </Pressable>
      )}

      {notice ? (
        <Text testID="created-notice" accessibilityRole="alert" style={styles.notice}>
          {notice}
        </Text>
      ) : null}
      {loadError ? <Text testID="events-load-error" style={styles.error}>{loadError}</Text> : null}

      {events.length === 0 ? (
        <Text testID="events-empty">Aucun événement pour le moment.</Text>
      ) : (
        events.map((e) => (
          <View key={e.id} testID="event-item" style={[styles.item, e.past && styles.past]}>
            <Text style={styles.itemTitle}>{e.title}</Text>
            <Text style={styles.meta}>{formatWhen(e)}</Text>
            {e.location ? <Text style={styles.meta}>{e.location}</Text> : null}
            {e.past ? <Text style={styles.badge}>Passé</Text> : null}
          </View>
        ))
      )}
    </ScrollView>
  );
}

function mergeById(base: ReveilEvent[], extra: ReveilEvent[]): ReveilEvent[] {
  const ids = new Set(base.map((e) => e.id));
  return [...base, ...extra.filter((e) => !ids.has(e.id))];
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: '#fff' },
  container: { padding: 24, paddingTop: 64 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontSize: 26, fontWeight: '700' },
  outline: { borderWidth: 1, borderColor: '#3b5bdb', borderRadius: 8, paddingVertical: 6, paddingHorizontal: 10 },
  outlineText: { color: '#3b5bdb' },
  who: { color: '#666', marginVertical: 8 },
  primary: { backgroundColor: '#3b5bdb', borderRadius: 8, padding: 12, alignItems: 'center', marginVertical: 12 },
  primaryText: { color: '#fff', fontWeight: '600' },
  notice: { padding: 8, borderRadius: 6, backgroundColor: '#fff3bf', color: '#8a6100', marginBottom: 8 },
  error: { color: '#c92a2a', marginBottom: 8 },
  item: { paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: '#eee' },
  past: { opacity: 0.65 },
  itemTitle: { fontSize: 16, fontWeight: '600' },
  meta: { color: '#555' },
  badge: { alignSelf: 'flex-start', marginTop: 4, paddingHorizontal: 6, backgroundColor: '#eee', borderRadius: 4, fontSize: 12 },
});
