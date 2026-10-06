import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  AppConfigResponse,
  ContentResponse,
  CreateOrderRequest,
  CreateOrderResponse,
  DietLeadRequest,
  DietLeadResponse,
  HomeFeedResponse,
  JoinSessionResponse,
  MarathonListResponse,
  NotificationListResponse,
  OnboardingStepRequest,
  OrderStatusResponse,
  RaceDetailResponse,
  ReferralState,
  RunHistoryResponse,
  SessionResponse,
  UpdateParticipantRequest,
  UpdateProfileRequest,
  UploadRunRequest,
  UserProfile,
  WorkshopListResponse,
  YogaAttendance,
  YogaCatalogResponse,
  YogaTodayResponse,
} from '@th/types';
import { api, request } from './client';
import { offlinePassGeneration, removeOfflinePass, saveOfflinePass } from '@/lib/offlinePass';
import { clearPendingOrder, getPendingOrder, purchaseKey, setPendingOrder } from '@/lib/pendingOrders';

export const qk = {
  session: ['session'] as const,
  home: ['home'] as const,
  yogaToday: ['yoga', 'today'] as const,
  yogaCatalog: ['yoga', 'catalog'] as const,
  yogaAttendance: ['yoga', 'attendance'] as const,
  yogaMine: ['yoga', 'mine'] as const,
  marathonEvents: (lat?: number, lng?: number) => ['marathon', 'events', lat, lng] as const,
  raceDetail: (id: string) => ['marathon', 'event', id] as const,
  referral: ['marathon', 'referral'] as const,
  runs: ['runs'] as const,
  workshops: ['workshops'] as const,
  content: ['content'] as const,
};

/** Public launch config: force-update gate and maintenance switch. No auth. */
export function useAppConfig() {
  return useQuery({
    queryKey: ['app-config'],
    queryFn: () => request<AppConfigResponse>('/config', { anonymous: true, timeoutMs: 8000 }),
    staleTime: 5 * 60_000,
  });
}

/**
 * `enabled` defaults to true for screens that are only reachable signed in.
 * The entry gate passes `false` until auth has settled, so it never asks for a
 * session before Firebase has restored the saved login.
 */
export function useSession(enabled = true) {
  return useQuery({
    queryKey: qk.session,
    queryFn: () => api.get<SessionResponse>('/session'),
    staleTime: 60_000,
    enabled,
  });
}

export function useHomeFeed() {
  return useQuery({
    queryKey: qk.home,
    queryFn: () => api.get<HomeFeedResponse>('/home'),
    // Home changes as batches start. Short stale time, but we never poll —
    // countdowns tick locally from serverTime.
    staleTime: 30_000,
  });
}

export function useOnboardingStep() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: OnboardingStepRequest) => api.post('/onboarding/step', body),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.session });
      void qc.invalidateQueries({ queryKey: qk.home });
    },
  });
}

export function useUpdateProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateProfileRequest) => api.patch<{ profile: UserProfile }>('/profile', body),
    onSuccess: (_res, body) => {
      void qc.invalidateQueries({ queryKey: qk.session });
      // A new goal or focus area re-orders the Home rails (§6.3).
      if (body.healthGoal || body.concern) void qc.invalidateQueries({ queryKey: qk.home });
    },
  });
}

export function useSkipOnboarding() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post('/onboarding/skip'),
    onSuccess: () => void qc.invalidateQueries({ queryKey: qk.session }),
  });
}

// ── Yoga ────────────────────────────────────────────────────────────────────

export function useYogaToday() {
  return useQuery({
    queryKey: qk.yogaToday,
    queryFn: () => api.get<YogaTodayResponse>('/yoga/today'),
    staleTime: 30_000,
    // Which batch is live/next moves with the clock; only while on screen.
    refetchInterval: 60_000,
  });
}

export function useYogaCatalog() {
  return useQuery({
    queryKey: qk.yogaCatalog,
    queryFn: () => api.get<YogaCatalogResponse>('/yoga/catalog'),
    staleTime: 5 * 60_000,
  });
}

export function useYogaAttendance(enabled: boolean) {
  return useQuery({
    queryKey: qk.yogaAttendance,
    queryFn: () => api.get<YogaAttendance>('/yoga/attendance'),
    enabled,
    staleTime: 60_000,
  });
}

export function useJoinSession() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (batchId: string) => api.post<JoinSessionResponse>('/yoga/join', { batchId }),
    onSuccess: () => {
      // The join wrote an attendance mark server-side, so the streak moved.
      void qc.invalidateQueries({ queryKey: qk.yogaAttendance });
      void qc.invalidateQueries({ queryKey: qk.home });
    },
  });
}

export function useSetReminderSlot() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (batchId: string) => api.put('/yoga/reminder-slot', { batchId }),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: qk.yogaToday });
      void qc.invalidateQueries({ queryKey: qk.session });
    },
  });
}

export function useMySessions() {
  return useQuery({
    queryKey: qk.yogaMine,
    queryFn: () =>
      api.get<{ savedSessionIds: string[]; completedSessionIds: string[] }>('/yoga/me/sessions'),
  });
}

type MySessions = { savedSessionIds: string[]; completedSessionIds: string[] };

/**
 * Save / complete send the state the user wants (not "toggle"), show it at
 * once (optimistic), and run one at a time — so a double tap ends where the
 * user's last tap left it, never flipped back by a request arriving late.
 */
function useSetSessionFlag(
  field: keyof MySessions,
  path: 'save' | 'complete',
  bodyKey: 'saved' | 'completed',
) {
  const qc = useQueryClient();
  return useMutation({
    scope: { id: 'yoga-my-sessions' },
    mutationFn: ({ id, on }: { id: string; on: boolean }) =>
      api.post(`/yoga/sessions/${id}/${path}`, { [bodyKey]: on }),
    onMutate: async ({ id, on }) => {
      await qc.cancelQueries({ queryKey: qk.yogaMine });
      qc.setQueryData<MySessions>(qk.yogaMine, (d) => {
        if (!d) return d;
        const rest = d[field].filter((x) => x !== id);
        return { ...d, [field]: on ? [...rest, id] : rest };
      });
    },
    // Success or failure, the server's copy is the truth.
    onSettled: () => void qc.invalidateQueries({ queryKey: qk.yogaMine }),
  });
}

export const useSetSaved = () => useSetSessionFlag('savedSessionIds', 'save', 'saved');
export const useSetCompleted = () => useSetSessionFlag('completedSessionIds', 'complete', 'completed');

// ── Marathon ────────────────────────────────────────────────────────────────

export function useMarathonEvents(coords?: { lat: number; lng: number }) {
  return useQuery({
    queryKey: qk.marathonEvents(coords?.lat, coords?.lng),
    queryFn: () =>
      api.get<MarathonListResponse>('/marathon/events', {
        lat: coords?.lat,
        lng: coords?.lng,
      }),
    staleTime: 5 * 60_000,
    // Coordinates are part of the key: when location arrives, keep showing
    // the date-ordered list until the nearest-first one lands, rather than
    // blanking the tab to a spinner (and losing the scroll position).
    placeholderData: keepPreviousData,
  });
}

export function useRaceDetail(id: string) {
  return useQuery({
    queryKey: qk.raceDetail(id),
    queryFn: async () => {
      const gen = offlinePassGeneration();
      const detail = await api.get<RaceDetailResponse>(`/marathon/events/${id}`);
      // Keep the race pass on the phone for the stadium gate, where signal
      // fails (§8.3) — and drop it if the server no longer issues one.
      void (detail.bib ? saveOfflinePass(id, detail.bib, gen, detail.event) : removeOfflinePass(id, gen)).catch(
        () => undefined,
      );
      return detail;
    },
    enabled: Boolean(id),
  });
}

/** PRD §8.3 Refer & Win. The server issues the coded link on first request. */
export function useReferral(enabled: boolean) {
  return useQuery({
    queryKey: qk.referral,
    queryFn: () => api.get<ReferralState>('/marathon/referral'),
    enabled,
    staleTime: 5 * 60_000,
  });
}

/** PRD §8.3 "Edit participant details" — t-shirt size and emergency contact. */
export function useUpdateParticipant(eventId: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Omit<UpdateParticipantRequest, 'eventId'>) =>
      api.patch(`/marathon/events/${eventId}/participant`, body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: qk.raceDetail(eventId) }),
  });
}

/**
 * The QR token is short-lived by design (docs/04 T1), so the bib screen
 * refreshes it while it is on screen rather than caching it.
 */
export function useBibToken(eventId: string, enabled: boolean) {
  return useQuery({
    queryKey: ['bib-token', eventId],
    queryFn: () =>
      api.get<{ qrToken: string; qrExpiresAt: string }>(`/marathon/events/${eventId}/bib-token`),
    enabled,
    refetchInterval: 45_000,
    staleTime: 0,
    gcTime: 0,
  });
}

// ── Runs ────────────────────────────────────────────────────────────────────

export function useRunHistory() {
  return useQuery({
    queryKey: qk.runs,
    queryFn: () => api.get<RunHistoryResponse>('/runs'),
  });
}

export function useUploadRun() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UploadRunRequest) => api.post<{ id: string }>('/runs', body),
    onSuccess: () => void qc.invalidateQueries({ queryKey: qk.runs }),
  });
}

// ── Diet / workshops / content ──────────────────────────────────────────────

export function useSubmitDietLead() {
  return useMutation({
    mutationFn: (body: DietLeadRequest) => api.post<DietLeadResponse>('/diet/leads', body),
  });
}

export function useWorkshops() {
  return useQuery({
    queryKey: qk.workshops,
    queryFn: () => api.get<WorkshopListResponse>('/workshops'),
    staleTime: 60_000,
  });
}

export function useToggleWorkshop() {
  const qc = useQueryClient();
  return useMutation({
    // Sends the state wanted, so a double tap or retry can't undo a booking.
    mutationFn: ({ id, register }: { id: string; register: boolean }) =>
      api.post<{ registered: boolean }>(`/workshops/${id}/register`, { registered: register }),
    onSuccess: () => {
      // The same workshops render on Home (via the feed) and in the tabs.
      void qc.invalidateQueries({ queryKey: qk.workshops });
      void qc.invalidateQueries({ queryKey: qk.home });
    },
  });
}

// ── Commerce ────────────────────────────────────────────────────────────────

/**
 * Create order → pay → poll until the server grants entitlement.
 *
 * The client never decides that a purchase succeeded. It asks the server to
 * price and create the order, hands off to the gateway, then waits for the
 * server to report `entitlementGranted` — which only the webhook can set.
 *
 * In STUB mode (no merchant account yet) "pay" calls the development
 * simulator, which runs the same settlement code as the real webhook.
 */
/**
 * Entitlement changed: refresh what resolves against it — not every cached
 * query at once (a self-inflicted burst of 8+ requests per purchase).
 */
export function invalidateAfterPurchase(qc: ReturnType<typeof useQueryClient>): void {
  for (const key of [qk.session, qk.home, ['yoga'], ['marathon'], qk.workshops]) {
    void qc.invalidateQueries({ queryKey: key });
  }
}

const isSettled = (s: OrderStatusResponse) =>
  s.entitlementGranted || s.status === 'FAILED' || s.status === 'PAID_NOT_GRANTED';

/** How long the sheet waits for a payment to settle before offering Refresh. */
const SETTLE_WAIT_MS = 20_000;

export function useCheckout() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (body: CreateOrderRequest): Promise<OrderStatusResponse> => {
      const key = purchaseKey(body);
      const unconfirmed = (orderId: string): OrderStatusResponse => ({
        orderId,
        status: 'PENDING',
        entitlementGranted: false,
      });

      // An earlier order for the same thing that never confirmed (the sheet
      // was closed mid-settle): re-check it rather than charging again.
      let order = getPendingOrder(key);
      if (order) {
        const prior = await api.get<OrderStatusResponse>(`/orders/${order.orderId}`).catch(() => null);
        if (!prior) return unconfirmed(order.orderId);
        if (isSettled(prior)) {
          clearPendingOrder(key);
          // A failed payment took nothing — a fresh attempt is safe.
          if (prior.status !== 'FAILED') return prior;
          order = null;
        } else if (prior.status !== 'CREATED') {
          // Paid or in flight at the gateway: wait for it, never pay twice.
          return prior;
        }
        // CREATED = never paid: complete THIS order (idempotent at the gateway).
      }

      if (!order) {
        // Refusals here (sold out, already registered, bad referral code) are
        // errors: nothing has been paid, so the user can fix it and retry.
        const created = await api.post<CreateOrderResponse>('/orders', body);
        order = { orderId: created.orderId, gateway: created.gateway };
      }
      const { orderId } = order;

      // From here on money may have moved. Never surface an error that would
      // re-enable Pay (a double charge): an outcome we couldn't confirm is
      // reported as PENDING with the order id, and the sheet offers Refresh.
      setPendingOrder(key, order);

      if (order.gateway === 'STUB') {
        try {
          await api.post(`/orders/${orderId}/simulate-payment`);
        } catch {
          return unconfirmed(orderId);
        }
      } else {
        // TODO(payments): open Razorpay checkout with the order's gateway id.
        // The webhook grants entitlement; we only poll. (Thrown before any
        // payment is taken, so Pay can safely re-enable.)
        clearPendingOrder(key);
        throw new Error('Live payments are not enabled in this build.');
      }

      // Poll briefly for the webhook to land — with a real gateway this covers
      // the gap between paying and the webhook arriving. A failed poll is a
      // blip, not an outcome. Bounded by wall-clock time, so the sheet is
      // never stuck for minutes on a slow network.
      let last = unconfirmed(orderId);
      const deadline = Date.now() + SETTLE_WAIT_MS;
      while (Date.now() < deadline) {
        try {
          last = await api.get<OrderStatusResponse>(`/orders/${orderId}`);
          if (isSettled(last)) {
            clearPendingOrder(key);
            return last;
          }
        } catch {
          // fall through to the next attempt
        }
        await new Promise((r) => setTimeout(r, 800));
      }
      return last;
    },
    onSuccess: (status) => {
      if (status.entitlementGranted) invalidateAfterPurchase(qc);
    },
  });
}

/** A fresh signed playback URL for the player (catalogue URLs expire). */
export function usePlayback(sessionId: string, enabled = true) {
  return useQuery({
    queryKey: ['yoga', 'playback', sessionId],
    queryFn: () => api.get<{ playbackUrl: string }>(`/yoga/sessions/${sessionId}/playback`),
    enabled: enabled && Boolean(sessionId),
    staleTime: 0,
    gcTime: 0,
  });
}

/** Refer & Win: record the friend's code this user came with (§8.3). */
export function useApplyReferralCode() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => api.post<{ applied: boolean }>('/marathon/referral/apply', { code }),
    onSuccess: () => void qc.invalidateQueries({ queryKey: qk.session }),
  });
}

/** Claims the free Premium upgrade earned with 5 referrals (§8.3). */
export function useClaimUpgrade() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (eventId: string) =>
      api.post<{ ok: true; tier: 'PREMIUM' }>(`/marathon/events/${eventId}/claim-upgrade`),
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['marathon'] });
      void qc.invalidateQueries({ queryKey: qk.session });
      void qc.invalidateQueries({ queryKey: qk.home });
    },
  });
}

export function useContent() {
  return useQuery({
    queryKey: qk.content,
    queryFn: () => api.get<ContentResponse>('/content'),
    staleTime: 10 * 60_000,
  });
}

/** The TopHeader bell: every push this user was sent, newest first. */
export function useNotificationInbox(enabled = true) {
  return useQuery({
    queryKey: ['notifications'],
    queryFn: () => api.get<NotificationListResponse>('/notifications'),
    staleTime: 60_000,
    enabled,
  });
}
