/**
 * Seed data.
 *
 * Content is lifted from the design prototype's TimesHealthRepository so the
 * app comes up looking like the design rather than like lorem ipsum. See
 * assets/design-reference/decompiled-compose/data/TimesHealthRepository.java.
 *
 * Imagery is Unsplash, exactly as the prototype had it — these are placeholders
 * awaiting real photography (docs/02 §3).
 *
 * Run: npm run seed
 */

import 'dotenv/config';
import { PrismaClient } from '@prisma/client';
import { istDateOnly } from '../src/time.js';

const prisma = new PrismaClient();

const IMG = {
  spine: 'https://images.unsplash.com/photo-1544367567-0f2fcb009e0b?auto=format&fit=crop&w=800&q=80',
  flex: 'https://images.unsplash.com/photo-1506126613408-eca07ce68773?auto=format&fit=crop&w=800&q=80',
  core: 'https://images.unsplash.com/photo-1575052814086-f385e2e2ad1b?auto=format&fit=crop&w=800&q=80',
  sleep: 'https://images.unsplash.com/photo-1545389336-cf090694435e?auto=format&fit=crop&w=800&q=80',
  morning: 'https://images.unsplash.com/photo-1518611012118-696072aa579a?auto=format&fit=crop&w=800&q=80',
  desk: 'https://images.unsplash.com/photo-1508214751196-bcfd4ca60f91?auto=format&fit=crop&w=800&q=80',
  delhi: 'https://images.unsplash.com/photo-1452626038306-9aae5e071dd3?auto=format&fit=crop&w=800&q=80',
  hyd: 'https://images.unsplash.com/photo-1476480862126-209bfaa8edc8?auto=format&fit=crop&w=800&q=80',
  blr: 'https://images.unsplash.com/photo-1571008887538-b36bb32f4571?auto=format&fit=crop&w=800&q=80',
  pune: 'https://images.unsplash.com/photo-1594882645126-14020914d58d?auto=format&fit=crop&w=800&q=80',
  mum: 'https://images.unsplash.com/photo-1513593771513-7b58b6c4af38?auto=format&fit=crop&w=800&q=80',
} as const;

const AVATAR = {
  apurva: 'https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=300&q=80',
  shynee: 'https://images.unsplash.com/photo-1573496359142-b8d87734a5a2?auto=format&fit=crop&w=300&q=80',
  vinay: 'https://images.unsplash.com/photo-1507003211169-0a1dd7228f2d?auto=format&fit=crop&w=300&q=80',
  sneha: 'https://images.unsplash.com/photo-1580489944761-15a19d654956?auto=format&fit=crop&w=300&q=80',
} as const;

/** Rupees → paise. Prices in the prototype were whole rupees. */
const rs = (rupees: number) => rupees * 100;

/**
 * Race days are RELATIVE to when the seed runs, so the demo never expires.
 * Fixed dates (the prototype used 20 Oct 2026, etc.) would flip every race to
 * "completed" the day after it passed. Re-run `npm run seed` to refresh.
 */
const raceDay = (daysFromNow: number) => istDateOnly(new Date(Date.now() + daysFromNow * 86_400_000));
/** "29 Oct 2026" in IST — promo copy follows the race date instead of going stale. */
const istLongDate = (d: Date) =>
  new Date(d.getTime() + 330 * 60_000).toLocaleDateString('en-GB', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    timeZone: 'UTC',
  });

async function main() {
  console.log('Clearing existing seed data...');
  await prisma.$transaction([
    prisma.completedSession.deleteMany(),
    prisma.savedSession.deleteMany(),
    prisma.attendance.deleteMany(),
    prisma.workshopRegistration.deleteMany(),
    prisma.liveWorkshop.deleteMany(),
    prisma.raceResult.deleteMany(),
    prisma.kitDelivery.deleteMany(),
    prisma.marathonRegistration.deleteMany(),
    prisma.raceFaq.deleteMany(),
    prisma.raceDistanceOption.deleteMany(),
    prisma.marathonEvent.deleteMany(),
    prisma.yogaSession.deleteMany(),
    prisma.yogaCategory.deleteMany(),
    prisma.yogaBatch.deleteMany(),
    prisma.reel.deleteMany(),
    prisma.instructor.deleteMany(),
    prisma.article.deleteMany(),
    prisma.userQuote.deleteMany(),
    prisma.promoCampaign.deleteMany(),
  ]);

  // ── Instructors ──────────────────────────────────────────────────────────
  console.log('Seeding instructors...');
  const instructors = await Promise.all(
    [
      {
        id: 'ins_apurva',
        name: 'Apurva Tilwani',
        title: 'Lead Yoga Biomechanist & Hatha Acharya',
        specialty: 'Daily Asana & Spine Health',
        bio: 'Apurva specializes in spinal alignment, rehabilitation yoga, and posture correction with 14+ years of practical clinical teaching.',
        experience: '14+ yrs exp · 520k+ students taught',
        avatarUrl: AVATAR.apurva,
        rating: 4.95,
        handle: '@apurvatilwani',
        reelCount: 1450,
      },
      {
        id: 'ins_shynee',
        name: 'Shynee Narang',
        title: 'Ergonomic Yoga Specialist & Certified Physio-Yogi',
        specialty: 'Desk Posture & Mobility',
        bio: 'Shynee has coached thousands of corporate executives and athletes in eliminating tension headaches and postural kyphosis.',
        experience: '9+ yrs exp · 310k+ community',
        avatarUrl: AVATAR.shynee,
        rating: 4.88,
        handle: '@shyneenarang',
        reelCount: 980,
      },
      {
        id: 'ins_vinay',
        name: 'Vinay Zende',
        title: 'Senior Yoga Acharya',
        specialty: 'Pranayama & Meditation',
        bio: 'Vinay brings over 20 years of authentic classical yoga training from The Yoga Institute Mumbai, blending ancient kriyas with biomechanics.',
        experience: '20+ yrs exp · Master Acharya',
        avatarUrl: AVATAR.vinay,
        rating: 4.92,
        handle: '@the_yoga_institute',
        reelCount: 2100,
      },
      {
        id: 'ins_sneha',
        name: 'Sneha Rathi',
        title: 'Joint Health & Rehabilitation Specialist',
        specialty: 'Joint Health & Mobility',
        bio: 'Sneha focuses on sports injury prevention and neuromuscular flexibility.',
        experience: '8+ yrs exp · 180k+ students',
        avatarUrl: AVATAR.sneha,
        rating: 4.86,
        handle: '@the_yoga_institute',
        reelCount: 840,
      },
    ].map((data) => prisma.instructor.create({ data })),
  );
  const [apurva, shynee, vinay, sneha] = instructors;

  // ── Daily batches — the 8 live slots (PRD §2: 4 morning, 4 evening) ───────
  console.log('Seeding daily batches...');
  await prisma.yogaBatch.createMany({
    data: [
      { id: 'b1', time: '05:15', title: 'Morning Asana Flow', period: 'MORNING', sortOrder: 1, instructorId: shynee!.id },
      { id: 'b2', time: '06:30', title: 'Hip Mobility & Spine Release', period: 'MORNING', sortOrder: 2, instructorId: apurva!.id },
      { id: 'b3', time: '08:00', title: 'Gentle Asana & Breathwork', period: 'MORNING', sortOrder: 3, instructorId: sneha!.id },
      { id: 'b4', time: '09:00', title: 'Desk Posture Reset', period: 'MORNING', sortOrder: 4, instructorId: shynee!.id },
      { id: 'b5', time: '16:45', title: 'Joint Health Bootcamp', period: 'EVENING', sortOrder: 5, instructorId: sneha!.id },
      { id: 'b6', time: '18:00', title: 'Pranayama & Yogic Calm', period: 'EVENING', sortOrder: 6, instructorId: vinay!.id },
      { id: 'b7', time: '19:30', title: 'Evening Unwind Flow', period: 'EVENING', sortOrder: 7, instructorId: apurva!.id },
      { id: 'b8', time: '20:30', title: 'Yoga Nidra & Deep Sleep', period: 'EVENING', sortOrder: 8, instructorId: vinay!.id },
    ],
  });

  // ── Categories ───────────────────────────────────────────────────────────
  console.log('Seeding yoga categories...');
  await prisma.yogaCategory.createMany({
    data: [
      { id: 'cat_spine', name: 'Spine & Posture', tagline: 'Reverse desk slouch & decompress lumbar discs', bodyTargetSummary: 'Lumbar Spine, Thoracic Cage & Cervical Neck', imageUrl: IMG.spine, bannerTheme: 'PLUM', totalYogisJoined: 42300, sortOrder: 1, concernTag: 'LOWER_BACK' },
      { id: 'cat_flex', name: 'Hips & Flexibility', tagline: 'Release stored pelvic tension and tight hamstrings', bodyTargetSummary: 'Pelvis, Hamstrings, Glutes & Iliopsoas', imageUrl: IMG.flex, bannerTheme: 'CORAL', totalYogisJoined: 38150, sortOrder: 2, concernTag: 'HIPS_PELVIS' },
      { id: 'cat_core', name: 'Core & Metabolism', tagline: 'Ignite abdominal strength, stimulate Agni & protect lower back', bodyTargetSummary: 'Transverse Abdominis, Diaphragm & Internal Viscera', imageUrl: IMG.core, bannerTheme: 'AMBER', totalYogisJoined: 31600, sortOrder: 3, concernTag: null },
      { id: 'cat_sleep', name: 'Stress & Deep Sleep', tagline: 'Down-regulate nervous system & cultivate restorative REM', bodyTargetSummary: 'Vagus Nerve, Adrenals & Autonomic Nervous System', imageUrl: IMG.sleep, bannerTheme: 'OCEAN', totalYogisJoined: 49800, sortOrder: 4, concernTag: 'SLEEP_ENERGY' },
      { id: 'cat_morning', name: 'Morning Vitality', tagline: 'Boost metabolism, awaken joint synovial fluid & sharpen focus', bodyTargetSummary: 'Cardiovascular Flow, Shoulders & Full Kinetic Chain', imageUrl: IMG.morning, bannerTheme: 'EMERALD', totalYogisJoined: 36400, sortOrder: 5, concernTag: null },
      { id: 'cat_desk', name: 'Desk & Screen Relief', tagline: 'Instant 15-min workday micro-stretches for computer users', bodyTargetSummary: 'Wrists, Carpal Tunnel, Trapezius & Optical Nerves', imageUrl: IMG.desk, bannerTheme: 'SAGE', totalYogisJoined: 27900, sortOrder: 6, concernTag: 'NECK_SHOULDERS' },
    ],
  });

  // ── Sessions ─────────────────────────────────────────────────────────────
  console.log('Seeding sessions...');
  const sessions = [
    { id: 'ys_spine_1', categoryId: 'cat_spine', title: 'Lumbar Spine Decompression & Sacrum Release', description: 'Therapeutic slow-traction postures designed to relieve disc pressure, hydrate vertebral spaces, and release chronic lower back tightness.', durationMinutes: 45, level: 'All Levels', intensity: 'Gentle to Moderate', caloriesBurned: 160, isFree: true, bodyFocusTitle: 'Lower Back, Sacrum & Lumbar Decompression', targetBodyParts: ['L4-L5 Lumbar Spine', 'Sacroiliac Joint', 'Thoracic Spine', 'Hamstrings'], keyPoses: ['Supta Matsyendrasana (Supine Twist)', 'Bhujangasana (Gentle Cobra)', 'Marjaryasana (Cat-Cow Flow)', 'Balasana with Lat Traction'], lifestyleImpact: 'Counteracts 8+ hours of desk sitting by restoring disc hydration, relieving sciatic nerve compression, and preventing recurrent lower back spasm.', instructorId: apurva!.id, joinedCountTillDate: 28450, todayActiveCount: 1840, imageUrl: IMG.spine },
    { id: 'ys_spine_2', categoryId: 'cat_spine', title: 'Cervical Neck Reset & Thoracic Spine Opening', description: 'Targeted myofascial release for the upper trapezius, levator scapulae, and forward head posture caused by smartphone and laptop use.', durationMinutes: 30, level: 'Beginner', intensity: 'Gentle', caloriesBurned: 95, isFree: false, bodyFocusTitle: 'Upper Back, Neck & Trapezius Relief', targetBodyParts: ['Cervical Spine (C1-C7)', 'Upper Trapezius', 'Rhomboids', 'Suboccipital Muscles'], keyPoses: ['Gomukhasana Arms', 'Garudasana Shoulder Wrap', 'Thread the Needle', 'Sphinx Pose with Neck Circles'], lifestyleImpact: 'Relieves tension headaches, eliminates shoulder knotting, improves jaw clenching, and realigns forward head posture back into neutral balance.', instructorId: shynee!.id, joinedCountTillDate: 19200, todayActiveCount: 1120, imageUrl: IMG.spine },
    { id: 'ys_spine_3', categoryId: 'cat_spine', title: 'Deep Spinal Waves & Sciatic Nerve Flossing', description: 'Neural flossing and gentle rotational mobility to soothe pinched sciatic sensations while reinforcing lateral spinal stabilization.', durationMinutes: 40, level: 'Intermediate', intensity: 'Moderate', caloriesBurned: 150, isFree: false, bodyFocusTitle: 'Sciatic Nerve Pathway & Core Stabilizers', targetBodyParts: ['Sciatic Pathway', 'Quadratus Lumborum', 'Hip Rotators', 'Lumbar Erector Spinae'], keyPoses: ['Reclined Figure 4 Nerve Glide', 'Supta Padangusthasana', 'Ardha Matsyendrasana', 'Setu Bandhasana'], lifestyleImpact: 'Calms shooting nerve pain down legs, unlocks tight glutes after driving or commuting, and restores effortless walking agility.', instructorId: apurva!.id, joinedCountTillDate: 15600, todayActiveCount: 740, imageUrl: IMG.spine },
    { id: 'ys_flex_1', categoryId: 'cat_flex', title: 'Deep Hip Capsule Opening & Psoas Release', description: 'Long passive holds that unlock restricted external rotation and create space in tight hip sockets.', durationMinutes: 50, level: 'All Levels', intensity: 'Moderate', caloriesBurned: 180, isFree: false, bodyFocusTitle: 'Hip Joint, Psoas & Pelvic Basin', targetBodyParts: ['Hip Joint Capsule', 'Hip Flexors', 'Pelvic Floor', 'Biceps Femoris'], keyPoses: ['Baddha Konasana', 'Supta Baddha Konasana', 'Janu Sirsasana', 'Parivrtta Trikonasana'], lifestyleImpact: 'Unlock restricted external rotation, release emotional tension stored in the iliopsoas, and create space in tight hip sockets.', instructorId: sneha!.id, joinedCountTillDate: 22100, todayActiveCount: 960, imageUrl: IMG.flex },
    { id: 'ys_flex_2', categoryId: 'cat_flex', title: "Runner's Pelvic Alignment & Piriformis Rehabilitation", description: 'Targeted myofascial release for runners experiencing tight lower backs, piriformis pinch, or hip asymmetries after long miles.', durationMinutes: 35, level: 'Intermediate', intensity: 'Moderate', caloriesBurned: 140, isFree: false, bodyFocusTitle: 'Sacroiliac decompression, IT band relief, glute balance', targetBodyParts: ['Sacroiliac Joint', 'Hip Rotators', 'Achilles Tendon', 'Hamstrings'], keyPoses: ['Reclined Figure 4 Nerve Glide', 'Uttanasana with Micro-bend', 'Chair Spinal Twist', 'Supta Padangusthasana'], lifestyleImpact: 'Release lactic acid and tight hip flexors after long runs and restore symmetric stride mechanics.', instructorId: sneha!.id, joinedCountTillDate: 11800, todayActiveCount: 520, imageUrl: IMG.flex },
    { id: 'ys_flex_3', categoryId: 'cat_flex', title: 'Hamstring Elongation & Pelvic Tilt Alignment', description: 'Safe eccentric hamstring lengthening that prevents pelvic tucking and protects the knee joints during daily activities.', durationMinutes: 30, level: 'Beginner', intensity: 'Gentle', caloriesBurned: 110, isFree: true, bodyFocusTitle: 'Hamstrings, Calves & Posterior Kinetic Chain', targetBodyParts: ['Hamstrings', 'Biceps Femoris', 'Achilles Tendon', 'Lumbar Erector Spinae'], keyPoses: ['Uttanasana with Micro-bend', 'Janu Sirsasana', 'Supta Padangusthasana', 'Adho Mukha Svanasana'], lifestyleImpact: 'Safe eccentric hamstring lengthening that prevents pelvic tucking and protects the knee joints during daily activities.', instructorId: apurva!.id, joinedCountTillDate: 17400, todayActiveCount: 810, imageUrl: IMG.flex },
    { id: 'ys_core_1', categoryId: 'cat_core', title: 'Agni Deep Core & Visceral Digestion Flow', description: 'Dynamic twists combined with uddiyana bandha cues to massage internal organs, improve digestive transit, and build a protective corset around the spine.', durationMinutes: 40, level: 'Intermediate to Advanced', intensity: 'Strong', caloriesBurned: 220, isFree: false, bodyFocusTitle: 'Transverse Abdominis, Diaphragm & Gut Health', targetBodyParts: ['Transverse Abdominis', 'Internal Obliques', 'Pelvic Floor', 'Cardiorespiratory System'], keyPoses: ['Uddiyana Kriya', 'Ardha Matsyendrasana', 'Parivrtta Trikonasana', 'Setu Bandhasana'], lifestyleImpact: 'Relieves bloating, improves bowel regularity, stimulates metabolic fire, and creates abdominal support so your lower back never takes the strain.', instructorId: vinay!.id, joinedCountTillDate: 20300, todayActiveCount: 1010, imageUrl: IMG.core },
    { id: 'ys_sleep_1', categoryId: 'cat_sleep', title: 'Yoga Nidra & Vagus Nerve Parasympathetic Reset', description: 'The ultimate yogic conscious sleep technique that systematically relaxes brain wave frequencies from Beta down into Alpha and Theta.', durationMinutes: 45, level: 'All Levels', intensity: 'Ultra Restorative', caloriesBurned: 60, isFree: true, bodyFocusTitle: 'Nervous System, Vagus Nerve & Sleep Cycles', targetBodyParts: ['Vagus Nerve', 'Central Nervous System', 'Adrenal Cortex', 'Pineal Gland'], keyPoses: ['Supported Savasana', 'Nadi Shodhana Pranayama', 'Sankalpa Meditation', 'Supported Bridge Pose'], lifestyleImpact: 'Significantly increases restorative deep REM sleep, reduces evening cortisol spikes, and helps overcome racing thoughts and insomnia.', instructorId: vinay!.id, joinedCountTillDate: 41200, todayActiveCount: 2240, imageUrl: IMG.sleep },
    { id: 'ys_sleep_2', categoryId: 'cat_sleep', title: 'Evening Yin Asanas for Melatonin Release', description: 'Long passive holds using pillows or yoga blocks that calm the sympathetic nervous system and prepare the mind for unmedicated deep sleep.', durationMinutes: 35, level: 'Beginner', intensity: 'Gentle Restorative', caloriesBurned: 70, isFree: false, bodyFocusTitle: 'Pineal Gland, Shoulders & Restorative Joints', targetBodyParts: ['Pineal Gland', 'Shoulder Girdle', 'Hip Joint Capsule', 'Vagus Nerve'], keyPoses: ["Child's Pose with Bolster", 'Supta Baddha Konasana', 'Supported Savasana', 'Palming Eye Relaxation'], lifestyleImpact: 'Natural melatonin stimulator that halts late-night overthinking, relaxes jaw clenching, and prepares you to wake up revitalized.', instructorId: vinay!.id, joinedCountTillDate: 26700, todayActiveCount: 1390, imageUrl: IMG.sleep },
    { id: 'ys_morning_1', categoryId: 'cat_morning', title: 'Surya Namaskar Dynamic Breath Wave', description: 'A fluid progression of 12 traditional sun salutation variations synchronizing vigorous breath with multi-planar spinal flexion and extension.', durationMinutes: 30, level: 'All Levels', intensity: 'Moderate to Strong', caloriesBurned: 240, isFree: true, bodyFocusTitle: 'Cardiovascular Endurance & Full Kinetic Chain', targetBodyParts: ['Cardiorespiratory System', 'Shoulder Girdle', 'Hamstrings', 'Transverse Abdominis'], keyPoses: ['Surya Namaskar A', 'Virabhadrasana I & II', 'Adho Mukha Svanasana', 'Uttanasana with Micro-bend'], lifestyleImpact: 'Replaces morning caffeine dependence with natural endorphins, stimulates lymphatic circulation, and elevates sustained mental clarity.', instructorId: apurva!.id, joinedCountTillDate: 52100, todayActiveCount: 3180, imageUrl: IMG.morning },
    { id: 'ys_desk_1', categoryId: 'cat_desk', title: 'Micro-Break Desk Stretch & Carpal Tunnel Relief', description: 'Quick chair-friendly stretches for tired wrists, tight forearms, slouching shoulders, and eye strain from computer monitors.', durationMinutes: 15, level: 'All Levels', intensity: 'Express Workday', caloriesBurned: 45, isFree: true, bodyFocusTitle: 'Wrists, Forearms, Eyes & Chest Opening', targetBodyParts: ['Carpal Tunnel & Wrists', 'Upper Trapezius', 'Pectoralis Major', 'Cervical Flexors'], keyPoses: ['Wrist Flexor Glides', 'Seated Eagle Arms', 'Chair Spinal Twist', 'Palming Eye Relaxation'], lifestyleImpact: 'Prevents mouse-hand strain and carpal tunnel syndrome, restores open breathing capacity, and prevents 3 PM workday fatigue slumps.', instructorId: shynee!.id, joinedCountTillDate: 33900, todayActiveCount: 2050, imageUrl: IMG.desk },
    { id: 'ys_desk_2', categoryId: 'cat_desk', title: 'Gentle Shoulder & Neck Release', description: 'Instant relief for screen fatigue, built for a 15-minute gap between meetings.', durationMinutes: 15, level: 'Beginner', intensity: 'Gentle', caloriesBurned: 40, isFree: false, bodyFocusTitle: 'Neck & Trapezius', targetBodyParts: ['Neck & Trapezius', 'Shoulder Girdle', 'Suboccipital Muscles'], keyPoses: ['Thread the Needle', 'Gomukhasana Arms', 'Sphinx Pose with Neck Circles'], lifestyleImpact: 'Instant relief for screen fatigue and forward head posture.', instructorId: shynee!.id, joinedCountTillDate: 14100, todayActiveCount: 620, imageUrl: IMG.desk },
  ];
  for (const s of sessions) {
    await prisma.yogaSession.create({ data: { ...s, mediaKey: `sessions/${s.id}/master.m3u8` } });
  }

  // ── Reels (instructor rail, §6.3 rail 4) ─────────────────────────────────
  await prisma.reel.createMany({
    data: instructors.map((i, idx) => ({
      instructorId: i.id,
      thumbnailUrl: i.avatarUrl,
      playbackUrl: `https://media.timeshealthplus.invalid/reels/${i.id}.m3u8`,
      durationSeconds: 45 + idx * 10,
      sortOrder: idx,
    })),
  });

  // ── Marathon editions ────────────────────────────────────────────────────
  console.log('Seeding marathon editions...');
  const events = [
    { id: 'delhi_half', name: 'Delhi Half Marathon', city: 'Delhi NCR', venue: 'Jawaharlal Nehru Stadium, Gate 3', imageUrl: IMG.delhi, startsAt: raceDay(24), flagOffTime: '5:30 AM · Wave 2', lat: 28.5826, lng: 77.2338, distances: ['3K', '5K', '10K', '21K'], classic: 1977, premium: 3999, was: 2824 },
    { id: 'hyd_half', name: 'Hyderabad Half Marathon', city: 'Hyderabad', venue: 'Gachibowli Stadium', imageUrl: IMG.hyd, startsAt: raceDay(46), flagOffTime: '5:45 AM', lat: 17.4435, lng: 78.3479, distances: ['5K', '10K', '21K'], classic: 1750, premium: 3499, was: 2500 },
    { id: 'blr_run', name: 'Bengaluru Distance Run', city: 'Bengaluru', venue: 'Sree Kanteerava Stadium', imageUrl: IMG.blr, startsAt: raceDay(82), flagOffTime: '6:00 AM', lat: 12.9698, lng: 77.5936, distances: ['3K', '5K', '10K', '21K'], classic: 1850, premium: 3699, was: 2650 },
    { id: 'pune_run', name: 'Pune Monsoon Run', city: 'Pune', venue: 'Shree Shiv Chhatrapati Sports Complex', imageUrl: IMG.pune, startsAt: raceDay(-21), flagOffTime: '6:00 AM', lat: 18.5793, lng: 73.7611, distances: ['5K', '10K', '21K'], classic: 1499, premium: 2999, was: 1999 },
    { id: 'mum_half', name: 'Mumbai Coastal Half Marathon', city: 'Mumbai', imageUrl: IMG.mum, venue: 'Marine Drive Promenade', startsAt: raceDay(138), flagOffTime: '5:30 AM', lat: 18.944, lng: 72.8233, distances: ['5K', '10K', '21K'], classic: 1950, premium: 3850, was: 2800 },
  ];

  for (const [idx, e] of events.entries()) {
    await prisma.marathonEvent.create({
      data: {
        id: e.id,
        name: e.name,
        city: e.city,
        venue: e.venue,
        imageUrl: e.imageUrl,
        startsAt: e.startsAt,
        flagOffTime: e.flagOffTime,
        latitude: e.lat,
        longitude: e.lng,
        registrationOpen: true,
        expoVenue: idx === 0 ? 'Hall B, JLN Stadium' : null,
        expoAddress: idx === 0 ? 'Jawaharlal Nehru Stadium, New Delhi' : null,
        expoStartsAt: idx === 0 ? new Date(e.startsAt.getTime() - 2 * 86_400_000 + 10 * 3_600_000) : null,
        expoEndsAt: idx === 0 ? new Date(e.startsAt.getTime() - 1 * 86_400_000 + 18 * 3_600_000) : null,
        // Left null: the API derives the window from expoStartsAt/EndsAt, so it
        // tracks the relative race dates. Set it only to override that text.
        expoPickupWindow: null,
        expoInstructions:
          idx === 0
            ? 'Show this QR code at Expo Counter B to collect your race timing chip, bib, and t-shirt.'
            : null,
        expoDocuments: idx === 0 ? ['Valid Government Photo ID'] : [],
        distanceOptions: {
          create: e.distances.map((code, i) => ({
            code,
            label: code,
            priceClassicPaise: rs(e.classic + i * 400),
            pricePremiumPaise: rs(e.premium + i * 400),
            wasPriceClassicPaise: rs(e.was + i * 400),
            wasPricePremiumPaise: null,
            // §8.3 edge case exercised on one edition so the suppressed-banner
            // path is reachable without editing the database by hand.
            premiumSoldOut: e.id === 'hyd_half' && code === '21K',
            registrationOpen: !(e.id === 'blr_run' && code === '3K'),
            sortOrder: i,
          })),
        },
        faqs: {
          create: [
            { question: 'Can I transfer my bib to a friend?', answer: 'No. Bibs are legally tied to mandatory timing chip registration and event insurance, and cannot be transferred.', sortOrder: 1 },
            { question: 'What is included in the runner kit?', answer: 'Official event technical dry-fit t-shirt, timing chip bib, baggage tags, energy gels, and sponsor vouchers.', sortOrder: 3 },
            { question: 'What happens if the race is rescheduled?', answer: 'Your registration automatically transfers to the revised date with no penalty. You will receive immediate WhatsApp & push alerts.', sortOrder: 2 },
          ],
        },
      },
    });
  }

  // ── Content ──────────────────────────────────────────────────────────────
  console.log('Seeding content...');
  await prisma.article.createMany({
    data: [
      { title: 'Why running is becoming India’s favorite community ritual', source: 'THE TIMES OF INDIA', category: 'Marathon', imageUrl: IMG.delhi, readTimeMinutes: 4, url: 'https://timesofindia.indiatimes.com/', sortOrder: 1 },
      { title: 'The 10-minute morning spine routine that actually protects runners', source: 'ET DIGITAL', category: 'Wellness', imageUrl: IMG.spine, readTimeMinutes: 3, url: 'https://economictimes.indiatimes.com/', sortOrder: 2, concernTag: 'LOWER_BACK' },
      { title: '12 weeks to go: Your marathon doesn’t start at the start line', source: 'THE TIMES OF INDIA', category: 'Training', imageUrl: IMG.morning, readTimeMinutes: 5, url: 'https://timesofindia.indiatimes.com/', sortOrder: 3 },
      { title: 'Fueling for your 21.1K race?', source: 'ET DIGITAL', category: 'Nutrition', imageUrl: IMG.core, readTimeMinutes: 4, url: 'https://economictimes.indiatimes.com/', sortOrder: 4 },
    ],
  });

  await prisma.userQuote.createMany({
    data: [
      { quote: 'The teachers explain the why behind each posture, not just the steps.', author: 'Dr. Promila Batara', role: 'General Surgeon · 8 mo subscriber', sortOrder: 1 },
      { quote: 'Iss price pe daily live classes milna honestly best deal hai. Attendance streak keeps me going.', author: 'Sumit J.', role: 'Subscriber · Pune', sortOrder: 2 },
      { quote: 'Six months in and my chronic morning back stiffness is simply gone.', author: 'Dr. Ritu Sehgal', role: 'Delhi', sortOrder: 3 },
    ],
  });

  // ── Live workshops (design addition, in scope by decision) ───────────────
  console.log('Seeding workshops...');
  // Workshops start at a realistic clock time (7:00 PM IST by default), not at
  // whatever minute the seed happened to run — that produced "4:37 pm".
  const inDays = (d: number, hourIst = 19) =>
    new Date(istDateOnly(new Date(Date.now() + d * 86_400_000)).getTime() + hourIst * 3_600_000);
  await prisma.liveWorkshop.createMany({
    data: [
      { title: 'Sub-2 Hour Half Marathon Pacing & Fueling Masterclass', description: 'Master race-day execution for 10K & 21K runners. Learn how to conserve glycogen, handle hill surges, and avoid hitting the 16K wall.', category: 'MARATHON', focusArea: 'Negative split pacing, lactate threshold, carb loading', imageUrl: IMG.delhi, startsAt: inDays(6), durationMinutes: 90, level: 'Intermediate to Advanced', platform: 'Zoom Interactive Live', instructorName: 'Coach Tarun Walecha', instructorTitle: 'Elite Ultra-Marathoner & Master Running Coach', instructorAvatarUrl: AVATAR.apurva, pricePaise: rs(499), totalCapacity: 120, joinUrl: 'https://timeshealthplus.invalid/workshop/pacing' },
      { title: 'Cadence & VO2 Max Biomechanics Live Clinic', description: 'Live form analysis correcting foot strike braking forces and improving running economy so every step expends less energy.', category: 'MARATHON', focusArea: 'Optimal 180 spm cadence, knee flexion, ground contact', imageUrl: IMG.morning, startsAt: inDays(13), durationMinutes: 75, level: 'All Levels', platform: 'Zoom Interactive Live', instructorName: 'Sneha Rathi', instructorTitle: 'Ergonomic Sports Specialist & Physio-Yogi', instructorAvatarUrl: AVATAR.sneha, pricePaise: rs(399), totalCapacity: 80, joinUrl: 'https://timeshealthplus.invalid/workshop/cadence' },
      { title: 'Kundalini Breathwork & Vagus Nerve Deep Sleep Clinic', description: 'A therapeutic masterclass unlocking deep diaphragmatic breathing and nervous system downregulation for chronic stress recovery.', category: 'YOGA', focusArea: 'Pranic breathing, Vagus nerve activation, insomnia relief', imageUrl: IMG.sleep, startsAt: inDays(3), durationMinutes: 60, level: 'All Levels', platform: 'Zoom Interactive Live', instructorName: 'Surakshit Goswami', instructorTitle: 'Master Teacher at The Yoga Institute', instructorAvatarUrl: AVATAR.vinay, pricePaise: rs(299), totalCapacity: 150, joinUrl: 'https://timeshealthplus.invalid/workshop/kundalini' },
      { title: 'Mindful Eating & Digestion Masterclass', description: 'Ancient principles for gut harmony, translated into what you actually cook at home.', category: 'DIET', focusArea: 'Agni, portion awareness, meal timing', imageUrl: IMG.core, startsAt: inDays(9), durationMinutes: 60, level: 'All Levels', platform: 'Zoom Interactive Live', instructorName: 'Sonia Goyal', instructorTitle: 'Qualified Clinical Dietitian', instructorAvatarUrl: AVATAR.shynee, pricePaise: rs(249), totalCapacity: 100, joinUrl: 'https://timeshealthplus.invalid/workshop/eating' },
    ],
  });

  // ── Promo strip campaigns (§6.2) ─────────────────────────────────────────
  await prisma.promoCampaign.createMany({
    data: [
      { campaignId: 'marathon-delhi', title: 'Run the city with us.', subtitle: `Delhi Half Marathon · ${istLongDate(raceDay(24))}`, ctaLabel: 'View Races', imageUrl: IMG.delhi, backgroundColor: '#8A1E14', sellsProduct: 'MARATHON', action: { type: 'OPEN_RACE_DETAIL', eventId: 'delhi_half' }, priority: 10 },
      { campaignId: 'diet-consult', title: 'A dietitian who knows your food.', subtitle: 'First 15-min consultation is free. No crash diets.', ctaLabel: 'Book a Free Consult', imageUrl: IMG.core, backgroundColor: '#1B4931', sellsProduct: 'DIET', action: { type: 'OPEN_DIET_LEAD_FORM' }, priority: 5 },
      { campaignId: 'yoga-annual', title: 'Eight live yoga classes a day.', subtitle: 'Certified masters from The Yoga Institute.', ctaLabel: 'Explore Membership', imageUrl: IMG.morning, backgroundColor: '#2C1338', sellsProduct: 'YOGA', action: { type: 'OPEN_PAYWALL', productId: 'yoga_annual' }, priority: 1 },
    ],
  });

  await prisma.appConfig.upsert({
    where: { id: 1 },
    create: { id: 1, minSupportedAppVersion: '1.0.0' },
    update: { minSupportedAppVersion: '1.0.0' },
  });

  console.log('Seed complete.');
}

main()
  .catch((e) => {
    console.error(e);
    process.exit(1);
  })
  .finally(() => prisma.$disconnect());
