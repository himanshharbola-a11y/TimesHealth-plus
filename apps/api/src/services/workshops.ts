import type { LiveWorkshop } from '@th/types';
import { prisma } from '../db.js';

/**
 * Live workshops — in the design, not the PRD; in scope by explicit decision.
 * A fourth commercial model: paid, capacity-limited, separately bookable.
 *
 * Shared by GET /workshops and the Home feed so the two can never disagree on
 * price, capacity or registration state.
 */

/** Workshops stay listed for two hours after start, so late joiners can find them. */
const GRACE_MS = 2 * 3600_000;
/** The join link is handed out only this close to the start. */
const JOIN_WINDOW_MS = 3600_000;

export async function loadWorkshopsFor(
  userId: string,
  hasYoga: boolean,
  now: Date = new Date(),
): Promise<LiveWorkshop[]> {
  const [rows, paidOrders] = await Promise.all([
    prisma.liveWorkshop.findMany({
      where: { startsAt: { gte: new Date(now.getTime() - GRACE_MS) } },
      include: {
        _count: { select: { registrations: true } },
        registrations: { where: { userId }, select: { id: true } },
      },
      orderBy: { startsAt: 'asc' },
    }),
    prisma.order.findMany({
      where: { userId, productType: 'WORKSHOP', status: 'PAID' },
      select: { productId: true },
    }),
  ]);
  const paidFor = new Set(paidOrders.map((o) => o.productId));

  return rows.map((w) => {
    const isRegistered = w.registrations.length > 0;
    // Design copy: "Free for Members". A yoga subscriber pays nothing for
    // yoga workshops; everything else is priced.
    const freeForMember = hasYoga && w.category === 'YOGA';
    return {
      id: w.id,
      title: w.title,
      description: w.description,
      category: w.category as LiveWorkshop['category'],
      focusArea: w.focusArea,
      imageUrl: w.imageUrl,
      startsAt: w.startsAt.toISOString(),
      durationMinutes: w.durationMinutes,
      level: w.level,
      platform: w.platform,
      instructorName: w.instructorName,
      instructorTitle: w.instructorTitle,
      instructorAvatarUrl: w.instructorAvatarUrl,
      pricePaise: freeForMember ? null : w.pricePaise,
      // Computed from live counts rather than stored, so it cannot drift.
      spotsRemaining: Math.max(0, w.totalCapacity - w._count.registrations),
      totalCapacity: w.totalCapacity,
      isRegistered,
      paidSeat: isRegistered && paidFor.has(w.id),
      joinUrl:
        isRegistered && w.startsAt.getTime() - now.getTime() < JOIN_WINDOW_MS ? w.joinUrl : null,
    };
  });
}
