import type { CreateOrderRequest } from '@th/types';

/**
 * Orders whose payment may have gone through but isn't confirmed yet, keyed by
 * what was bought.
 *
 * The checkout sheet can be closed while an order is still settling, and the
 * buy button is still on screen (nothing has been granted yet). Without this a
 * second tap would create — and charge — a second order. With it, "Pay" for
 * the same product re-checks the earlier order first (useCheckout).
 *
 * Memory only; the server's idempotent order creation covers an app restart.
 * Cleared on sign-out so one user's checkout never resumes for another.
 */

export interface PendingOrder {
  orderId: string;
  gateway: 'RAZORPAY' | 'STUB';
}

const pending = new Map<string, PendingOrder>();

export function purchaseKey(req: CreateOrderRequest): string {
  return [req.productType, req.productId, req.eventId ?? '', req.category ?? '', req.tier ?? ''].join('|');
}

export const getPendingOrder = (key: string) => pending.get(key) ?? null;
export const setPendingOrder = (key: string, order: PendingOrder) => void pending.set(key, order);
export const clearPendingOrder = (key: string) => void pending.delete(key);
export const forgetPendingOrders = () => pending.clear();
