package timeshealth.app.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.data.repository.DietRepository
import timeshealth.app.core.domain.toIndianE164
import timeshealth.app.core.integrations.analytics.Analytics
import timeshealth.app.core.integrations.analytics.AnalyticsEvents
import timeshealth.app.core.model.DietLeadRequest
import timeshealth.app.core.model.DietLeadResponse
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.session.AccountGateway

/** What the Diet tab needs: the profile to prefill the form, and the lead submission. */
interface DietGateway {
    /** The cached session if held; never blocks the form on the network. */
    suspend fun session(): SessionResponse?
    suspend fun submit(lead: DietLeadRequest): DietLeadResponse
}

class RepositoryDietGateway @Inject constructor(
    private val account: AccountGateway,
    private val diet: DietRepository,
    private val analytics: Analytics,
) : DietGateway {
    override suspend fun session(): SessionResponse? = runCatching { account.session() }.getOrNull()
    override suspend fun submit(lead: DietLeadRequest): DietLeadResponse =
        diet.submitLead(lead).also { analytics.track(AnalyticsEvents.dietLeadSubmitted()) }
}

/** The lead sheet's state. */
data class LeadForm(
    val name: String = "",
    val phone: String = "",
    val concern: String = DIET_CONCERNS.first(),
    val callTime: String = CALL_TIMES.first(),
    val nameError: String? = null,
    val phoneError: String? = null,
    val formError: String? = null,
    val sending: Boolean = false,
)

/** The confirmation that replaces the sheet. */
data class LeadDone(val title: String, val message: String)

internal val DIET_CONCERNS = listOf("Weight loss", "PCOS / PCOD", "Type 2 diabetes", "Pre-diabetes", "Thyroid health", "Iron deficiency")
internal val CALL_TIMES = listOf("Morning", "Afternoon", "Evening")

internal const val DONE_MESSAGE =
    "A TimesHealth+ certified dietitian will call your registered number within one working day to conduct your 15-minute consultation."

/**
 * The Diet tab (PRD §9): the same for every user, no entitlements. Value
 * proposition → CTA → lead form → "our dietitian will call you". No plans,
 * prices or payment in V1.
 */
@HiltViewModel
class DietViewModel @Inject constructor(private val gateway: DietGateway) : ViewModel() {

    private val _form = MutableStateFlow(LeadForm())
    val form: StateFlow<LeadForm> = _form.asStateFlow()

    private val _done = MutableStateFlow<LeadDone?>(null)
    val done: StateFlow<LeadDone?> = _done.asStateFlow()

    private var prefilled = false

    /** The sheet opened: prefill name and mobile from the profile, once, without overwriting typing. */
    fun opened() {
        _form.value = _form.value.copy(formError = null)
        if (prefilled) return
        prefilled = true
        viewModelScope.launch {
            val profile = gateway.session()?.profile ?: return@launch
            val f = _form.value
            _form.value = f.copy(
                name = f.name.ifEmpty { profile.name.orEmpty() },
                phone = f.phone.ifEmpty { profile.phone?.let { toIndianE164(it)?.removePrefix("+91") ?: it }.orEmpty() },
            )
        }
    }

    fun setName(v: String) {
        _form.value = _form.value.copy(name = v.take(80), nameError = null)
    }

    fun setPhone(v: String) {
        _form.value = _form.value.copy(phone = v.take(16), phoneError = null)
    }

    fun setConcern(v: String) {
        _form.value = _form.value.copy(concern = v)
    }

    fun setCallTime(v: String) {
        _form.value = _form.value.copy(callTime = v)
    }

    fun dismissDone() {
        _done.value = null
    }

    fun submit(onDone: () -> Unit) {
        val f = _form.value
        if (f.sending) return
        val name = f.name.trim()
        val e164 = toIndianE164(f.phone)
        _form.value = f.copy(
            nameError = if (name.isEmpty()) "Enter your name so the dietitian knows who to ask for." else null,
            phoneError = if (e164 == null) "Enter a valid 10-digit Indian mobile number." else null,
            formError = null,
        )
        if (name.isEmpty() || e164 == null) return
        _form.value = _form.value.copy(sending = true)
        viewModelScope.launch {
            try {
                val res = gateway.submit(DietLeadRequest(name = name, phone = e164, condition = f.concern, cuisinePreference = null, bestTimeToCall = f.callTime))
                _done.value = LeadDone("You’re on the list!", res.message.ifBlank { DONE_MESSAGE })
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiRequestException) {
                when {
                    e.error.code == "INVALID_PHONE" -> _form.value = _form.value.copy(phoneError = e.error.message)
                    // Already asked recently: not an error to the person, just where they stand.
                    e.status == 429 -> {
                        _done.value = LeadDone("You’re already on the list", e.error.message)
                        onDone()
                    }
                    else -> _form.value = _form.value.copy(formError = "We couldn’t submit that. Check your connection and try again.")
                }
            } catch (e: Exception) {
                _form.value = _form.value.copy(formError = "We couldn’t submit that. Check your connection and try again.")
            } finally {
                _form.value = _form.value.copy(sending = false)
            }
        }
    }
}
