import { useCallback } from 'react';
import { Alert } from 'react-native';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { ApiRequestError } from '@/api/client';
import { qk, useJoinSession } from '@/api/hooks';
import { openExternal } from '@/lib/links';
import { serverNow } from '@/lib/time';

/** Matches the API's wait room (apps/api/src/time.ts WAIT_ROOM_MINUTES). */
export const WAIT_ROOM_MS = 60 * 60_000;
const CLASS_MS = 60 * 60_000;

/**
 * True while a batch can be joined: from the wait room opening (1h before)
 * until the class ends. Outside it the server refuses the join, so the app
 * never offers one.
 */
export function isJoinOpen(startsAtIso: string, now: number = serverNow()): boolean {
  const start = Date.parse(startsAtIso);
  return now >= start - WAIT_ROOM_MS && now < start + CLASS_MS;
}

/**
 * The one way the app joins a live class — Home hero, Yoga next-session card
 * and the live batch row all use it, so they behave identically:
 *   - the server records attendance and hands back the class link (§7.1)
 *   - a membership that lapsed while the app was open re-gates on the spot
 *     instead of failing silently
 *   - any other refusal is explained, never a dead tap
 */
export function useJoinClass() {
  const join = useJoinSession();
  const qc = useQueryClient();
  const router = useRouter();

  const joinClass = useCallback(
    (batchId: string) =>
      join.mutate(batchId, {
        onSuccess: (res) => void openExternal(res.joinUrl),
        onError: (e) => {
          const code = e instanceof ApiRequestError ? e.body.code : null;
          if (code === 'NOT_ENTITLED') {
            // Expired since the screen loaded: refresh everything that gates on it.
            void qc.invalidateQueries({ queryKey: qk.session });
            void qc.invalidateQueries({ queryKey: qk.home });
            void qc.invalidateQueries({ queryKey: ['yoga'] });
            Alert.alert('Your membership has ended', 'Renew to keep joining live classes.', [
              { text: 'Not now', style: 'cancel' },
              {
                text: 'Renew',
                onPress: () => router.push({ pathname: '/paywall', params: { productId: 'yoga_annual' } }),
              },
            ]);
            return;
          }
          Alert.alert(
            'Couldn’t join the class',
            e instanceof ApiRequestError && e.status !== 0 ? e.message : 'Check your connection and try again.',
          );
        },
      }),
    [join, qc, router],
  );

  return { joinClass, joining: join.isPending, joiningBatchId: join.isPending ? join.variables : null };
}
