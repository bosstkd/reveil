import DateTimePicker, { DateTimePickerEvent } from '@react-native-community/datetimepicker';
import { useState } from 'react';
import { ActivityIndicator, Platform, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { useAuth } from '../auth/AuthContext';
import {
  LOCATION_MAX_LENGTH,
  PAST_DATE_WARNING,
  PAST_DATE_WARNING_EDIT,
  ReveilEvent,
  TITLE_MAX_LENGTH,
  deviceTimeZone,
  formatDate,
  formatTime,
  isPast,
  parseLocalDateTime,
} from '../events/events';
import { EventError, createEvent, updateEvent } from '../events/eventsApi';

type Picker = 'date' | 'time' | null;

interface Errors {
  title?: string;
  date?: string;
  time?: string;
}

interface Props {
  /** Événement à modifier (absent : création). */
  event?: ReveilEvent;
  onCreated?: (e: ReveilEvent) => void;
  onUpdated?: (e: ReveilEvent) => void;
  onCancel: () => void;
}

/**
 * Création — ou modification si `event` est fourni — d'un événement : titre, date et heure obligatoires
 * (sélecteurs natifs), lieu facultatif.
 */
export function EventForm({ event: editing, onCreated, onUpdated, onCancel }: Props) {
  const { authFetch } = useAuth();
  const initial = editing ? parseLocalDateTime(editing.localDateTime) : null;
  const [title, setTitle] = useState(editing?.title ?? '');
  const [date, setDate] = useState<Date | null>(initial?.date ?? null);
  const [time, setTime] = useState<Date | null>(initial?.time ?? null);
  const [location, setLocation] = useState(editing?.location ?? '');
  const [picker, setPicker] = useState<Picker>(null);
  const [errors, setErrors] = useState<Errors>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  // Modification : date/heure affichées et renvoyées dans le fuseau de saisie de l'événement.
  const timeZone = editing?.timeZone ?? deviceTimeZone();

  function onPick(event: DateTimePickerEvent, value?: Date) {
    const which = picker;
    // Android : boîte de dialogue fermée à chaque choix ; iOS : sélecteur intégré fermé par « Valider ».
    if (Platform.OS !== 'ios') setPicker(null);
    if (event.type === 'dismissed' || !value) return;
    if (which === 'date') {
      setDate(value);
      setErrors((e) => ({ ...e, date: undefined }));
    } else if (which === 'time') {
      setTime(value);
      setErrors((e) => ({ ...e, time: undefined }));
    }
  }

  async function submit() {
    if (submitting) return;
    const found: Errors = {};
    if (!title.trim()) found.title = 'Le titre est obligatoire.';
    if (!date) found.date = 'La date est obligatoire.';
    if (!time) found.time = "L'heure est obligatoire.";
    setErrors(found);
    if (Object.keys(found).length > 0 || !date || !time) return;

    setSubmitting(true);
    setServerError(null);
    const payload = {
      title: title.trim(),
      date: formatDate(date),
      time: formatTime(time),
      timeZone,
      location: location.trim() || null,
    };
    try {
      if (editing) {
        onUpdated?.(await updateEvent(authFetch, editing.id, payload));
      } else {
        onCreated?.(await createEvent(authFetch, payload));
      }
    } catch (e) {
      const fallback = editing ? "La modification de l'événement a échoué." : "La création de l'événement a échoué.";
      setServerError(e instanceof EventError ? e.message : fallback);
      setSubmitting(false);
    }
  }

  return (
    <View testID="event-form" style={styles.form}>
      <Text style={styles.title} accessibilityRole="header">
        {editing ? "Modifier l'événement" : 'Nouvel événement'}
      </Text>

      <Text style={styles.label}>Titre *</Text>
      <TextInput
        testID="event-title"
        style={styles.input}
        value={title}
        onChangeText={(v) => {
          setTitle(v);
          if (v.trim()) setErrors((e) => ({ ...e, title: undefined }));
        }}
        maxLength={TITLE_MAX_LENGTH}
        placeholder="Anniversaire de Léa"
      />
      {errors.title ? <Text testID="title-error" style={styles.error}>{errors.title}</Text> : null}

      <View style={styles.row}>
        <View style={styles.col}>
          <Text style={styles.label}>Date *</Text>
          <Pressable testID="event-date" accessibilityRole="button" style={styles.input} onPress={() => setPicker('date')}>
            <Text style={date ? undefined : styles.placeholder}>
              {date ? date.toLocaleDateString('fr-FR') : 'Choisir une date'}
            </Text>
          </Pressable>
          {errors.date ? <Text testID="date-error" style={styles.error}>{errors.date}</Text> : null}
        </View>
        <View style={styles.col}>
          <Text style={styles.label}>Heure *</Text>
          <Pressable testID="event-time" accessibilityRole="button" style={styles.input} onPress={() => setPicker('time')}>
            <Text style={time ? undefined : styles.placeholder}>{time ? formatTime(time) : 'Choisir une heure'}</Text>
          </Pressable>
          {errors.time ? <Text testID="time-error" style={styles.error}>{errors.time}</Text> : null}
        </View>
      </View>
      <Text testID="event-timezone" style={styles.hint}>
        Fuseau horaire : {timeZone}
      </Text>

      {picker ? (
        <View>
          <DateTimePicker
            testID="event-picker"
            mode={picker}
            value={(picker === 'date' ? date : time) ?? new Date()}
            is24Hour
            display={Platform.OS === 'ios' ? (picker === 'date' ? 'inline' : 'spinner') : 'default'}
            onChange={onPick}
          />
          {Platform.OS === 'ios' ? (
            <Pressable testID="event-picker-done" accessibilityRole="button" onPress={() => setPicker(null)}>
              <Text style={styles.link}>Valider</Text>
            </Pressable>
          ) : null}
        </View>
      ) : null}

      {isPast(date, time) ? (
        <Text testID="past-warning" accessibilityRole="alert" style={styles.warning}>
          {editing ? PAST_DATE_WARNING_EDIT : PAST_DATE_WARNING}
        </Text>
      ) : null}

      <Text style={styles.label}>Lieu (facultatif)</Text>
      <TextInput
        testID="event-location"
        style={styles.input}
        value={location}
        onChangeText={setLocation}
        maxLength={LOCATION_MAX_LENGTH}
      />

      {serverError ? (
        <Text testID="event-error" accessibilityRole="alert" style={styles.error}>
          {serverError}
        </Text>
      ) : null}

      <View style={styles.actions}>
        <Pressable testID="event-cancel" accessibilityRole="button" onPress={onCancel} style={styles.secondary}>
          <Text style={styles.link}>Annuler</Text>
        </Pressable>
        <Pressable
          testID="event-submit"
          accessibilityRole="button"
          disabled={submitting}
          onPress={submit}
          style={[styles.button, submitting && styles.buttonDisabled]}
        >
          {submitting ? <ActivityIndicator color="#fff" /> : <Text style={styles.buttonText}>{editing ? 'Enregistrer' : "Créer l'événement"}</Text>}
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  form: { marginVertical: 12, padding: 16, borderRadius: 10, borderWidth: 1, borderColor: '#ddd' },
  title: { fontSize: 20, fontWeight: '700', marginBottom: 8 },
  label: { marginTop: 10, marginBottom: 4, fontWeight: '600' },
  input: { borderWidth: 1, borderColor: '#bbb', borderRadius: 8, padding: 12, fontSize: 16 },
  placeholder: { color: '#999' },
  row: { flexDirection: 'row', gap: 12 },
  col: { flex: 1 },
  hint: { color: '#666', fontSize: 12, marginTop: 4 },
  warning: { marginTop: 8, padding: 8, borderRadius: 6, backgroundColor: '#fff3bf', color: '#8a6100' },
  error: { color: '#c92a2a', marginTop: 4 },
  actions: { flexDirection: 'row', justifyContent: 'flex-end', gap: 8, marginTop: 16 },
  button: { backgroundColor: '#3b5bdb', borderRadius: 8, paddingVertical: 12, paddingHorizontal: 16 },
  buttonDisabled: { opacity: 0.6 },
  buttonText: { color: '#fff', fontWeight: '600' },
  secondary: { borderWidth: 1, borderColor: '#3b5bdb', borderRadius: 8, paddingVertical: 12, paddingHorizontal: 16 },
  link: { color: '#3b5bdb', textAlign: 'center', marginTop: 4 },
});
