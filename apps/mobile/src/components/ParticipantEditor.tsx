import { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { ApiRequestError } from '@/api/client';
import { useUpdateParticipant } from '@/api/hooks';
import { FONTS, colors, radii } from '@/theme';

/**
 * Edit participant details — PRD §8.3, design RaceDetailScreenKt's AlertDialog
 * ("Edit Details": T-Shirt Size chips, Emergency Contact, Save Changes /
 * Cancel). An M3 dialog: 28dp corners, 24dp padding, serif title, actions
 * bottom-right.
 *
 * Name and DOB are deliberately NOT editable here: they are tied to the timing
 * chip registration and event insurance (design FAQ: "Bibs are legally tied to
 * mandatory timing chip registration and event insurance"). Changing them is a
 * support action, not a self-serve one.
 *
 * Two deliberate departures from the design, both for real runners: the size
 * list keeps XS and XXL (the design's S–XL would strand anyone outside it),
 * and the contact is two fields because the API stores name and number apart.
 */

const SIZES = ['XS', 'S', 'M', 'L', 'XL', 'XXL'];

export function ParticipantEditor({
  eventId,
  visible,
  initialSize,
  initialContactName = null,
  initialContactPhone = null,
  onClose,
}: {
  eventId: string;
  visible: boolean;
  initialSize: string | null;
  initialContactName?: string | null;
  /** Stored as +91XXXXXXXXXX; edited as the 10 digits. */
  initialContactPhone?: string | null;
  onClose: () => void;
}) {
  const update = useUpdateParticipant(eventId);
  const [size, setSize] = useState<string | null>(initialSize);
  const [contactName, setContactName] = useState('');
  const [contactPhone, setContactPhone] = useState('');

  useEffect(() => {
    if (visible) {
      setSize(initialSize);
      // Prefilled, so a save never blanks what the runner entered before.
      setContactName(initialContactName ?? '');
      setContactPhone((initialContactPhone ?? '').replace(/^\+91/, ''));
      update.reset();
    }
    // `update` is a fresh object each render; only a re-open should reset it.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [visible, initialSize, initialContactName, initialContactPhone]);

  const phoneOk = contactPhone === '' || contactPhone.replace(/\D/g, '').length >= 10;
  const sizes = size && !SIZES.includes(size) ? [...SIZES, size] : SIZES;

  const save = () =>
    update.mutate(
      {
        ...(size ? { tshirtSize: size } : {}),
        // An emptied field is sent empty so it clears; one never set is left alone.
        ...(contactName.trim() || initialContactName ? { emergencyContactName: contactName.trim() } : {}),
        ...(contactPhone.trim() || initialContactPhone ? { emergencyContactPhone: contactPhone.trim() } : {}),
      },
      { onSuccess: onClose },
    );

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      <KeyboardAvoidingView style={styles.backdrop} behavior="padding">
        {/* Tapping outside dismisses, as the AlertDialog's onDismissRequest does. */}
        <Pressable style={StyleSheet.absoluteFill} onPress={onClose} accessibilityLabel="Close" />
        <View style={styles.dialog} accessibilityViewIsModal>
          <ScrollView keyboardShouldPersistTaps="handled" bounces={false}>
            <Text style={styles.title} accessibilityRole="header">
              Edit Details
            </Text>

            <View style={styles.content}>
              <Text style={styles.label}>T-Shirt Size</Text>
              <View style={styles.sizes}>
                {sizes.map((s) => {
                  const on = size === s;
                  return (
                    <Pressable
                      key={s}
                      style={[styles.size, on && styles.sizeOn]}
                      onPress={() => setSize(s)}
                      accessibilityRole="radio"
                      accessibilityState={{ selected: on }}
                    >
                      <Text style={[styles.sizeText, on && { color: colors.paperWhite }]}>{s}</Text>
                    </Pressable>
                  );
                })}
              </View>

              <Text style={[styles.label, { marginTop: 6 }]}>Emergency Contact</Text>
              <TextInput
                value={contactName}
                onChangeText={setContactName}
                placeholder="Contact name"
                placeholderTextColor={colors.textMuted}
                maxLength={80}
                style={styles.input}
              />
              <TextInput
                value={contactPhone}
                onChangeText={setContactPhone}
                placeholder="Contact mobile number"
                placeholderTextColor={colors.textMuted}
                keyboardType="phone-pad"
                maxLength={10}
                style={[styles.input, !phoneOk && styles.inputError]}
              />
              {!phoneOk ? <Text style={styles.error}>Enter a 10-digit mobile number.</Text> : null}

              <Text style={styles.hint}>
                Name and date of birth are tied to your timing chip and event insurance — contact
                support to change them.
              </Text>

              {update.isError ? (
                <Text style={styles.error}>
                  {/* The server's own sentence for a bad number or a started race. */}
                  {update.error instanceof ApiRequestError && update.error.status !== 0 && update.error.body.message
                    ? update.error.body.message
                    : 'Couldn’t save. Check your connection and try again.'}
                </Text>
              ) : null}
            </View>

            <View style={styles.actions}>
              <Pressable style={styles.textBtn} onPress={onClose} accessibilityRole="button">
                <Text style={styles.textBtnLabel}>Cancel</Text>
              </Pressable>
              <Pressable
                style={[styles.confirmBtn, !phoneOk && { opacity: 0.6 }]}
                disabled={!phoneOk || update.isPending}
                onPress={save}
                accessibilityRole="button"
                accessibilityState={{ busy: update.isPending, disabled: !phoneOk }}
              >
                {update.isPending ? (
                  <ActivityIndicator color={colors.paperWhite} />
                ) : (
                  <Text style={styles.confirmLabel}>Save Changes</Text>
                )}
              </Pressable>
            </View>
          </ScrollView>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  // M3 dialog scrim: 32% black.
  backdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.32)',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  dialog: {
    width: '100%',
    maxWidth: 560,
    maxHeight: '90%',
    backgroundColor: colors.paperWhite,
    borderRadius: radii.sheet,
    padding: 24,
  },
  // AlertDialog title (headlineSmall slot, 17) set Bold Serif by the design.
  title: {
    fontFamily: FONTS.serif,
    fontSize: 17,
    lineHeight: 22,
    fontWeight: '700',
    color: colors.textPrimary,
  },
  // Dialog text slot: bodyMedium on onSurfaceVariant (TextSecondary), spacedBy 10.
  content: { marginTop: 16, gap: 10 },
  label: { fontFamily: FONTS.sans, fontSize: 12, fontWeight: '700', color: colors.textSecondary },
  sizes: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  size: {
    borderRadius: radii.sm,
    backgroundColor: colors.surfaceSand,
    paddingHorizontal: 14,
    paddingVertical: 6,
  },
  sizeOn: { backgroundColor: colors.coralBrand },
  sizeText: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.textPrimary },
  // M3 OutlinedTextField: 56dp, 4dp corners, Outline (BorderRule) stroke, bodyLarge 15.
  input: {
    minHeight: 56,
    borderWidth: 1,
    borderColor: colors.borderRule,
    borderRadius: 4,
    paddingHorizontal: 16,
    fontFamily: FONTS.sans,
    fontSize: 15,
    color: colors.textPrimary,
  },
  inputError: { borderColor: colors.crimsonAlert },
  error: { fontFamily: FONTS.sans, fontSize: 12, lineHeight: 16, color: colors.crimsonAlert },
  hint: { fontFamily: FONTS.sans, fontSize: 11.5, lineHeight: 16, color: colors.textMuted },
  // Actions bottom-right, 24 below the text, 8 apart.
  actions: {
    marginTop: 24,
    flexDirection: 'row',
    justifyContent: 'flex-end',
    alignItems: 'center',
    gap: 8,
  },
  textBtn: { height: 40, paddingHorizontal: 12, justifyContent: 'center' },
  textBtnLabel: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.coralBrand },
  // M3 Button: 40dp, fully rounded, 24dp side padding, CoralBrand.
  confirmBtn: {
    height: 40,
    minWidth: 120,
    borderRadius: 20,
    paddingHorizontal: 24,
    backgroundColor: colors.coralBrand,
    alignItems: 'center',
    justifyContent: 'center',
  },
  confirmLabel: { fontFamily: FONTS.sans, fontSize: 13, fontWeight: '700', color: colors.paperWhite },
});
