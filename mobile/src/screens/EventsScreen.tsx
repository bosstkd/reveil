import { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, StyleSheet, Text, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import { EventPeriod, ReveilEvent, eventDate, eventTime, mergeEvents, timeUntil } from '../events/events';
import { listEvents } from '../events/eventsApi';
import { EventForm } from './EventForm';

/** Un onglet paginé. `page` : dernière page chargée (-1 : rien chargé). */
interface Tab {
  items: ReveilEvent[];
  page: number;
  hasNext: boolean;
  total: number;
}

const EMPTY: Tab = { items: [], page: -1, hasNext: false, total: 0 };
/** Rafraîchissement du temps restant affiché. */
const CLOCK_TICK_MS = 60_000;

/** Liste des événements de l'utilisateur (onglets « À venir » / « Passés »), avec création en place. */
export function EventsScreen() {
  const { session, logout, authFetch } = useAuth();
  const [leaving, setLeaving] = useState(false);
  const [tabs, setTabs] = useState<Record<EventPeriod, Tab>>({ upcoming: EMPTY, past: EMPTY });
  const [period, setPeriod] = useState<EventPeriod>('upcoming');
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [now, setNow] = useState(() => new Date());
  const tabsRef = useRef(tabs);
  tabsRef.current = tabs;
  const inFlight = useRef(new Set<EventPeriod>());
  const mounted = useRef(true);

  const fetchTab = useCallback(
    async (which: EventPeriod, more = false) => {
      if (inFlight.current.has(which)) return;
      inFlight.current.add(which);
      setLoading(true);
      setLoadError(null);
      try {
        const res = await listEvents(authFetch, which, more ? tabsRef.current[which].page + 1 : 0);
        if (!mounted.current) return;
        setTabs((t) => ({
          ...t,
          [which]: {
            // Fusion : conserve un événement créé localement pendant le chargement.
            items: mergeEvents(which, more || t[which].page < 0 ? t[which].items : [], res.items),
            page: res.page,
            hasNext: res.hasNext,
            total: res.totalElements,
          },
        }));
        setNow(new Date());
      } catch (e) {
        if (mounted.current) setLoadError((e as Error).message);
      } finally {
        inFlight.current.delete(which);
        if (mounted.current) setLoading(inFlight.current.size > 0);
      }
    },
    [authFetch],
  );

  useEffect(() => {
    mounted.current = true;
    void fetchTab('upcoming');
    const timer = setInterval(() => setNow(new Date()), CLOCK_TICK_MS);
    return () => {
      mounted.current = false;
      clearInterval(timer);
    };
  }, [fetchTab]);

  function select(which: EventPeriod) {
    setPeriod(which);
    if (tabsRef.current[which].page < 0) void fetchTab(which);
  }

  function startCreating() {
    setNotice(null);
    setCreating(true);
  }

  function onCreated(created: ReveilEvent) {
    // Ajout immédiat dans l'onglet correspondant, sans rechargement de la liste.
    const which: EventPeriod = created.past ? 'past' : 'upcoming';
    setTabs((t) => {
      const isNew = !t[which].items.some((e) => e.id === created.id);
      return {
        ...t,
        [which]: { ...t[which], items: mergeEvents(which, t[which].items, [created]), total: t[which].total + (isNew ? 1 : 0) },
      };
    });
    setCreating(false);
    setNow(new Date());
    setNotice(created.warnings?.length ? created.warnings.join('\n') : null);
  }

  const current = tabs[period];
  const empty = current.page >= 0 && current.items.length === 0;

  const header = (
    <View>
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
      ) : period === 'upcoming' && empty ? null : (
        <Pressable testID="new-event" accessibilityRole="button" style={styles.primary} onPress={startCreating}>
          <Text style={styles.primaryText}>+ Nouvel événement</Text>
        </Pressable>
      )}

      {notice ? (
        <Text testID="created-notice" accessibilityRole="alert" style={styles.notice}>
          {notice}
        </Text>
      ) : null}

      <View style={styles.tabs} accessibilityRole="tablist">
        {(['upcoming', 'past'] as const).map((which) => (
          <Pressable
            key={which}
            testID={`tab-${which}`}
            accessibilityRole="tab"
            accessibilityState={{ selected: period === which }}
            onPress={() => select(which)}
            style={[styles.tab, period === which && styles.tabActive]}
          >
            <Text style={[styles.tabText, period === which && styles.tabTextActive]}>
              {which === 'upcoming' ? 'À venir' : 'Passés'}
              {tabs[which].page >= 0 ? ` (${tabs[which].total})` : ''}
            </Text>
          </Pressable>
        ))}
      </View>

      {loadError ? <Text testID="events-load-error" style={styles.error}>{loadError}</Text> : null}
    </View>
  );

  const emptyState = empty ? (
    <View testID="events-empty" style={styles.empty}>
      {period === 'upcoming' ? (
        <>
          <Text style={styles.emptyText}>Aucun événement à venir.</Text>
          {creating ? null : (
            <Pressable testID="create-first" accessibilityRole="button" style={styles.primary} onPress={startCreating}>
              <Text style={styles.primaryText}>Créer mon premier événement</Text>
            </Pressable>
          )}
        </>
      ) : (
        <Text style={styles.emptyText}>Aucun événement passé.</Text>
      )}
    </View>
  ) : undefined;

  const footer = loading ? (
    <ActivityIndicator testID="events-loading" style={styles.footer} />
  ) : current.hasNext ? (
    <Pressable testID="load-more" accessibilityRole="button" style={[styles.outline, styles.footer]} onPress={() => void fetchTab(period, true)}>
      <Text style={styles.outlineText}>Afficher plus</Text>
    </Pressable>
  ) : undefined;

  return (
    <FlatList
      testID="event-list"
      style={styles.scroll}
      contentContainerStyle={styles.container}
      keyboardShouldPersistTaps="handled"
      data={current.items}
      keyExtractor={(e) => e.id}
      extraData={now}
      initialNumToRender={15}
      ListHeaderComponent={header}
      ListEmptyComponent={emptyState}
      ListFooterComponent={footer}
      onEndReachedThreshold={0.5}
      onEndReached={() => {
        if (current.hasNext && !loading) void fetchTab(period, true);
      }}
      renderItem={({ item }) => (
        <View testID="event-item" style={[styles.item, item.past && styles.past]}>
          <Text testID="item-title" style={styles.itemTitle}>
            {item.title}
          </Text>
          <Text style={styles.meta}>
            <Text testID="item-date">{eventDate(item)}</Text> à <Text testID="item-time">{eventTime(item)}</Text>
          </Text>
          <Text testID="item-remaining" style={[styles.badge, item.past && styles.badgePast]}>
            {timeUntil(item.startsAt, now)}
          </Text>
          {item.location ? <Text style={styles.meta}>{item.location}</Text> : null}
        </View>
      )}
    />
  );
}

const styles = StyleSheet.create({
  scroll: { flex: 1, backgroundColor: '#fff' },
  container: { padding: 24, paddingTop: 64 },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  title: { fontSize: 26, fontWeight: '700' },
  outline: { borderWidth: 1, borderColor: '#3b5bdb', borderRadius: 8, paddingVertical: 6, paddingHorizontal: 10, alignItems: 'center' },
  outlineText: { color: '#3b5bdb' },
  who: { color: '#666', marginVertical: 8 },
  primary: { backgroundColor: '#3b5bdb', borderRadius: 8, padding: 12, alignItems: 'center', marginVertical: 12 },
  primaryText: { color: '#fff', fontWeight: '600' },
  notice: { padding: 8, borderRadius: 6, backgroundColor: '#fff3bf', color: '#8a6100', marginBottom: 8 },
  error: { color: '#c92a2a', marginVertical: 8 },
  tabs: { flexDirection: 'row', borderBottomWidth: 1, borderBottomColor: '#ddd', marginTop: 8 },
  tab: { paddingVertical: 10, paddingHorizontal: 12, borderBottomWidth: 2, borderBottomColor: 'transparent' },
  tabActive: { borderBottomColor: '#3b5bdb' },
  tabText: { color: '#555' },
  tabTextActive: { color: '#3b5bdb', fontWeight: '600' },
  empty: { alignItems: 'center', paddingVertical: 32 },
  emptyText: { color: '#555' },
  footer: { marginVertical: 16 },
  item: { paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: '#eee' },
  past: { opacity: 0.65 },
  itemTitle: { fontSize: 16, fontWeight: '600' },
  meta: { color: '#555' },
  badge: { alignSelf: 'flex-start', marginTop: 4, paddingHorizontal: 6, backgroundColor: '#edf2ff', color: '#3b5bdb', borderRadius: 4, fontSize: 12 },
  badgePast: { backgroundColor: '#eee', color: '#555' },
});
