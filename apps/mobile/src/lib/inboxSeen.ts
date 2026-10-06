import * as SecureStore from 'expo-secure-store';
import { create } from 'zustand';

/**
 * The notification inbox's "seen" marker.
 *
 * Unread = sent after the newest item the user has already seen in the inbox.
 * One store, because the tabs layout mounts a TopHeader per tab and every
 * bell's dot must clear together when any one of them is opened.
 *
 * Lives here rather than in the inbox component so the session store can
 * reset it on sign-out: the marker is per device, and the next person to sign
 * in on this phone must not have their alerts marked read by someone else.
 */

const SEEN_KEY = 'th_inbox_seen_at';

interface SeenState {
  /** Epoch ms of the newest item shown when the sheet was last opened. */
  seenAt: number | null;
  loaded: boolean;
  load: () => Promise<void>;
  markSeen: (at: number) => void;
}

let loadStarted = false;

export const useSeenStore = create<SeenState>((set, get) => ({
  seenAt: null,
  loaded: false,
  load: async () => {
    if (loadStarted) return;
    loadStarted = true;
    try {
      const raw = await SecureStore.getItemAsync(SEEN_KEY);
      const n = raw === null ? NaN : Number(raw);
      // A markSeen that raced ahead of this read wins.
      const current = get().seenAt;
      set({ seenAt: Number.isFinite(n) ? Math.max(n, current ?? 0) : current, loaded: true });
    } catch {
      set({ loaded: true });
    }
  },
  markSeen: (at) => {
    const prev = get().seenAt;
    if (prev !== null && prev >= at) return;
    set({ seenAt: at, loaded: true });
    SecureStore.setItemAsync(SEEN_KEY, String(at)).catch(() => {
      // Losing this only means the dot may reappear after a restart.
    });
  },
}));

/** Forget the marker — called whenever the signed-in identity changes. */
export async function resetInboxSeen(): Promise<void> {
  loadStarted = true; // nothing left on disk worth loading
  useSeenStore.setState({ seenAt: null, loaded: true });
  await SecureStore.deleteItemAsync(SEEN_KEY);
}
