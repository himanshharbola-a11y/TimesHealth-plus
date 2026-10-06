package timeshealth.app.core.domain

/*
 * Prices. The API sends integer paise and computes every amount itself — the
 * client never sends a price (docs/04 T8) — so formatting is all the app does.
 */

/**
 * Integer paise → whole rupees with Indian digit grouping: 197700 → "₹1,977",
 * 10000000 → "₹1,00,000" (lakh), 1234567800 → "₹1,23,45,678" (crore).
 *
 * Rounded half-up to the rupee like JavaScript's `Math.round` (₹0.50 → ₹1);
 * not Kotlin's `round()`, which rounds half to even. Grouping is done by hand
 * because `java.text` can't group in lakhs.
 */
fun formatPaise(paise: Long): String = "₹${groupIndian(Math.round(paise / 100.0))}"

/** 1234567 → "12,34,567": the last three digits, then pairs. */
internal fun groupIndian(n: Long): String {
    val digits = if (n < 0) (-n).toString() else n.toString()
    val sign = if (n < 0) "-" else ""
    if (digits.length <= 3) return sign + digits
    val head = digits.dropLast(3)
    val pairs = head.reversed().chunked(2).joinToString(",").reversed()
    return "$sign$pairs,${digits.takeLast(3)}"
}
