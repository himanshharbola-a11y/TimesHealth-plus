package timeshealth.app.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Marathon endpoints: PRD §8. */
class MarathonContractTest {

    private fun detail(variant: String) = Fixtures.decode<RaceDetailResponse>("race-detail.$variant.json")

    @Test
    fun `registered race - bib, kit, referral, upgrade offer and participant`() {
        val race = detail("registered")

        assertThat(race.event.registration?.status).isEqualTo(RaceLifecycleStatus.UPCOMING)
        assertThat(race.event.registration?.tier).isEqualTo(RaceTier.CLASSIC)

        val offer = checkNotNull(race.upgradeOffer)
        assertThat(offer.available).isTrue()
        assertThat(offer.netDifferencePaise).isEqualTo(202_200L)
        assertThat(offer.freeClaim).isFalse()
        assertThat(offer.benefits).contains("On-site physio support")
        assertThat(offer.benefits).hasSize(4)

        assertThat(race.participant).isEqualTo(
            RaceParticipant(tshirtSize = "M", emergencyContactName = null, emergencyContactPhone = null),
        )

        val bib = checkNotNull(race.bib)
        assertThat(bib.bibNumber).isEqualTo("DEL-8892A")
        assertThat(bib.tier).isEqualTo(RaceTier.CLASSIC)
        assertThat(bib.qrToken).startsWith("LIVE.")
        assertThat(bib.offlinePayload).startsWith("PASS.")

        assertThat(race.kit?.status).isEqualTo(KitStatus.IN_TRANSIT)
        assertThat(race.kit?.courierName).isEqualTo("BlueDart")
        assertThat(race.referral?.confirmedReferrals).isEqualTo(1)
        assertThat(race.result).isNull()
        assertThat(race.event.expo?.requiredDocuments).containsExactly("Valid Government Photo ID")
    }

    @Test
    fun `registered race - prices are integer paise per tier`() {
        val halfMarathon = detail("registered").event.distanceOptions.single { it.code == "21K" }

        assertThat(halfMarathon.pricePaise).containsExactly(RaceTier.CLASSIC, 317_700L, RaceTier.PREMIUM, 519_900L)
        assertThat(halfMarathon.wasPricePaise).isNull()
        // The upgrade offer is exactly the tier difference.
        assertThat(halfMarathon.pricePaise.getValue(RaceTier.PREMIUM) - halfMarathon.pricePaise.getValue(RaceTier.CLASSIC))
            .isEqualTo(detail("registered").upgradeOffer?.netDifferencePaise)
    }

    @Test
    fun `unregistered race - no personal sections, premium sold-out flag per distance (PRD 8_3)`() {
        val race = detail("unregistered")

        assertThat(race.event.registration).isNull()
        assertThat(race.upgradeOffer).isNull()
        assertThat(race.participant).isNull()
        assertThat(race.bib).isNull()
        assertThat(race.kit).isNull()
        assertThat(race.referral).isNull()
        assertThat(race.faqs).hasSize(3)
        assertThat(race.event.registrationOpen).isTrue()
        assertThat(race.event.distanceOptions.associate { it.code to it.premiumSoldOut })
            .containsExactly("5K", false, "10K", false, "21K", true)
    }

    @Test
    fun `completed race - result pending, registration closed, no upgrade`() {
        val race = detail("completed")

        assertThat(race.event.registration?.status).isEqualTo(RaceLifecycleStatus.COMPLETED)
        assertThat(race.event.registrationOpen).isFalse()
        assertThat(race.upgradeOffer).isNull()
        assertThat(race.participant?.tshirtSize).isEqualTo("M")

        val result = checkNotNull(race.result)
        assertThat(result.published).isFalse()
        assertThat(result.finishTime).isNull()
        assertThat(result.overallRank).isNull()
        assertThat(result.splits).isEmpty()
        assertThat(result.photoUrls).isEmpty()
        assertThat(result.category).isEqualTo("10K")
    }

    @Test
    fun `event list is ordered server-side - registered first, else by date (PRD 8_2)`() {
        val free = Fixtures.decode<MarathonListResponse>("marathon-events.free.json")
        assertThat(free.orderedBy).isEqualTo(EventOrdering.DATE)
        assertThat(free.events.mapNotNull { it.registration }).isEmpty()
        assertThat(free.events.map { it.startsAt }).isInStrictOrder()

        val marathon = Fixtures.decode<MarathonListResponse>("marathon-events.marathon.json")
        assertThat(marathon.orderedBy).isEqualTo(EventOrdering.REGISTRATION)
        assertThat(marathon.events.first().registration?.registrationRef).isEqualTo("TH-QA_MARATHON")

        val finisher = Fixtures.decode<MarathonListResponse>("marathon-events.finisher.json")
        assertThat(finisher.events.first().id).isEqualTo("pune_run")
        assertThat(finisher.events.first().registration?.status).isEqualTo(RaceLifecycleStatus.COMPLETED)
        assertThat(finisher.events.first().expo).isNull()
        assertThat(finisher.events.map { it.distanceFromUserKm }.distinct()).containsExactly(null)
    }

    @Test
    fun `bib token and referral`() {
        val token = Fixtures.decode<BibTokenResponse>("bib-token.json")
        assertThat(token.qrToken).startsWith("LIVE.TH-QA_MARATHON.")
        assertThat(token.qrExpiresAt).isEqualTo("2026-10-06T08:28:20.000Z")

        val referral: Referral = Fixtures.decode<ReferralState>("referral.json")
        assertThat(referral.code).isEqualTo("THATHON")
        assertThat(referral.luckyDrawEntries).isEqualTo(1)
        assertThat(referral.guaranteedUpgradeUnlocked).isFalse()
        assertThat(referral.upgradeClaimed).isFalse()
    }
}
