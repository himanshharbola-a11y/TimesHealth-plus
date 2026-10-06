import { Pressable, StyleSheet, Text, View } from 'react-native';
import { MaterialIcons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { SafeAreaView } from 'react-native-safe-area-context';
import { RunTrackerPanel } from '@/components/RunTrackerPanel';
import { FONTS, colors } from '@/theme';

/**
 * Run tracker as a full screen — reached from the Home entry tile (§6.3 rail 3).
 * Home is a second entry point alongside the Marathon tab, so both render the
 * same panel rather than duplicating the logic.
 *
 * The live run itself (RUNNING / PAUSED / SUMMARY) is the panel's own
 * full-screen dark tracker (RunTrackerScreenKt), so this bar only frames the
 * READY view: the design's secondary-screen top bar — canvasBg, ArrowBack,
 * serif 20 Bold title — as on Race Details and Official Results.
 */
export default function RunTrackerScreen() {
  const router = useRouter();
  return (
    <SafeAreaView style={styles.root} edges={['top']}>
      <View style={styles.header}>
        <Pressable
          onPress={() => router.back()}
          style={styles.back}
          accessibilityRole="button"
          accessibilityLabel="Go back"
        >
          <MaterialIcons name="arrow-back" size={24} color={colors.textPrimary} />
        </Pressable>
        <Text style={styles.title} accessibilityRole="header">
          Run Tracker
        </Text>
      </View>
      <RunTrackerPanel />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, backgroundColor: colors.canvasBg },
  // M3 TopAppBar: 64dp row, 48dp navigation icon button, title after it.
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 64,
    paddingHorizontal: 4,
  },
  back: { width: 48, height: 48, alignItems: 'center', justifyContent: 'center' },
  title: {
    fontFamily: FONTS.serif,
    fontSize: 20,
    fontWeight: '700',
    color: colors.textPrimary,
    marginLeft: 4,
  },
});
