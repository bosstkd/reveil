import { Pressable, StyleSheet, Text, View } from 'react-native';
import { MAX_REMINDERS, REMINDER_OPTIONS, reminderDelay, toggleReminder } from '../events/events';

interface Props {
  /** Délais choisis (minutes), du plus éloigné au plus proche. */
  value: number[];
  onChange: (value: number[]) => void;
}

/** Choix des rappels d'un événement : jusqu'à 3 parmi 15 min, 1 h, 1 jour, 1 semaine ; aucun pour les désactiver. */
export function ReminderPicker({ value, onChange }: Props) {
  const full = value.length >= MAX_REMINDERS;
  return (
    <View testID="event-reminders">
      <Text style={styles.label}>Rappels (jusqu'à {MAX_REMINDERS})</Text>
      <View style={styles.choices}>
        {REMINDER_OPTIONS.map((minutes) => {
          const chosen = value.includes(minutes);
          const locked = !chosen && full;
          return (
            <Pressable
              key={minutes}
              testID={`reminder-${minutes}`}
              accessibilityRole="checkbox"
              accessibilityState={{ checked: chosen, disabled: locked }}
              disabled={locked}
              onPress={() => onChange(toggleReminder(value, minutes))}
              style={[styles.chip, chosen && styles.chipChosen, locked && styles.chipLocked]}
            >
              <Text style={chosen ? styles.chipTextChosen : styles.chipText}>
                {chosen ? '✓ ' : ''}
                {reminderDelay(minutes)}
              </Text>
            </Pressable>
          );
        })}
      </View>
      {value.length === 0 ? (
        <Text testID="reminders-off" style={styles.hint}>
          Aucun rappel : vous ne serez pas prévenu avant cet événement.
        </Text>
      ) : (
        <>
          {full ? (
            <Text testID="reminders-max" style={styles.hint}>
              {MAX_REMINDERS} rappels maximum.
            </Text>
          ) : null}
          <Pressable testID="reminders-disable" accessibilityRole="button" onPress={() => onChange([])}>
            <Text style={styles.link}>Désactiver les rappels</Text>
          </Pressable>
        </>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  label: { marginTop: 10, marginBottom: 4, fontWeight: '600' },
  choices: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { borderWidth: 1, borderColor: '#bbb', borderRadius: 999, paddingVertical: 8, paddingHorizontal: 12 },
  chipChosen: { borderColor: '#3b5bdb', backgroundColor: '#edf2ff' },
  chipLocked: { opacity: 0.4 },
  chipText: { color: '#333' },
  chipTextChosen: { color: '#3b5bdb', fontWeight: '600' },
  hint: { color: '#666', fontSize: 12, marginTop: 6 },
  link: { color: '#3b5bdb', marginTop: 6 },
});
