package timeshealth.app.core.data.repository

import javax.inject.Inject
import javax.inject.Singleton
import timeshealth.app.core.model.DietLeadRequest
import timeshealth.app.core.model.DietLeadResponse
import timeshealth.app.core.network.TimesHealthApi

/** Diet (PRD §9): lead capture only in V1. */
@Singleton
class DietRepository @Inject constructor(private val api: TimesHealthApi) {

    /** A dietitian call-back request. 400 with per-field messages; 429 after 3 in a day. */
    suspend fun submitLead(lead: DietLeadRequest): DietLeadResponse = api.submitDietLead(lead)
}
