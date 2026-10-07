package timeshealth.app.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.data.repository.ContentRepository
import timeshealth.app.core.data.repository.ProfileRepository
import timeshealth.app.core.domain.DobResult
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.isValidEmail
import timeshealth.app.core.domain.parseDob
import timeshealth.app.core.domain.toIndianE164
import timeshealth.app.core.model.Concern
import timeshealth.app.core.model.Faq
import timeshealth.app.core.model.Gender
import timeshealth.app.core.model.HealthGoal
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.Units
import timeshealth.app.core.model.UpdateProfileRequest
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.core.network.ServerClock
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.session.SessionGateway
import timeshealth.app.ui.state.Loadable
import timeshealth.app.ui.state.UiState

/** What the profile drawer needs from core:data. */
interface ProfileGateway {
    suspend fun session(refresh: Boolean = false): SessionResponse
    val sessionChanges: Flow<Unit>
    suspend fun update(body: UpdateProfileRequest)
    suspend fun yogaFaqs(): List<Faq>

    /** Permanently deletes the account, then signs out. Throws when nothing was deleted. */
    suspend fun deleteAccount()
    suspend fun signOut()
    fun today(): LocalDate
}

class RepositoryProfileGateway @Inject constructor(
    private val account: AccountGateway,
    private val profiles: ProfileRepository,
    private val content: ContentRepository,
    private val sessions: SessionGateway,
    private val clock: ServerClock,
) : ProfileGateway {
    override suspend fun session(refresh: Boolean) = account.session(refresh)
    override val sessionChanges: Flow<Unit> get() = account.sessionChanges
    override suspend fun update(body: UpdateProfileRequest) {
        profiles.updateProfile(body)
    }
    override suspend fun yogaFaqs(): List<Faq> = content.content.get().yogaFaqs
    override suspend fun deleteAccount() {
        profiles.deleteAccount()
    }
    override suspend fun signOut() = sessions.signOut()
    override fun today(): LocalDate = Instant.ofEpochMilli(clock.now()).atZone(IST).toLocalDate()
}

/** The fields Personal details can edit, each its own panel. */
enum class EditField(val title: String, val hint: String) {
    NAME("Your name", "As you’d like your instructor and dietitian to address you."),
    EMAIL("Email", "For receipts and race updates."),
    PHONE("Mobile number", "An Indian mobile for class reminders and race-day calls."),
    DOB("Date of birth", "Used for race categories and age-group results. You need to be 13 or older."),
    GENDER("Gender", "Used for race categories and age-group results."),
    GOAL("Health goal", "Shapes what we put first on your Home feed."),
    CONCERN("Focus area", "Sessions for this area lead your Home feed."),
}

/** What an edit panel holds: text, the three DOB parts, or a choice. */
data class EditInput(val text: String = "", val dd: String = "", val mm: String = "", val yyyy: String = "", val choice: String? = null)

/** The patch for [field] from [input], or the sentence saying what to fix. */
internal fun patchFor(field: EditField, input: EditInput, today: LocalDate): Result<UpdateProfileRequest> {
    fun fix(message: String) = Result.failure<UpdateProfileRequest>(IllegalArgumentException(message))
    return when (field) {
        EditField.NAME -> {
            val name = input.text.trim()
            when {
                name.isEmpty() -> fix("Enter your name.")
                name.length > 80 -> fix("Keep your name under 80 characters.")
                else -> Result.success(UpdateProfileRequest(name = name))
            }
        }
        EditField.EMAIL -> input.text.trim().let { email ->
            if (isValidEmail(email)) Result.success(UpdateProfileRequest(email = email)) else fix("Enter a valid email address, like name@example.com.")
        }
        EditField.PHONE -> toIndianE164(input.text)?.let { Result.success(UpdateProfileRequest(phone = it)) }
            ?: fix("Enter a valid 10-digit Indian mobile number.")
        EditField.DOB -> when (val r = parseDob(input.dd, input.mm, input.yyyy, today)) {
            is DobResult.Valid -> Result.success(UpdateProfileRequest(dob = r.iso))
            is DobResult.Invalid -> fix(r.error.message)
        }
        EditField.GENDER -> input.choice?.let { c -> Gender.entries.firstOrNull { it.name == c } }
            ?.let { Result.success(UpdateProfileRequest(gender = it)) } ?: fix("Choose one option.")
        EditField.GOAL -> input.choice?.let { c -> HealthGoal.entries.firstOrNull { it.name == c } }
            ?.let { Result.success(UpdateProfileRequest(healthGoal = it)) } ?: fix("Choose one option.")
        EditField.CONCERN -> input.choice?.let { c -> Concern.entries.firstOrNull { it.name == c } }
            ?.let { Result.success(UpdateProfileRequest(concern = it)) } ?: fix("Choose one option.")
    }
}

/** A failed save in words the user can act on. */
internal fun saveErrorText(e: Exception, field: EditField): String = when {
    e is ApiRequestException && (e.error.code == "INVALID_PHONE" || e.error.code == "LOGIN_IDENTIFIER") -> e.error.message
    e is ApiRequestException && e.error.code == "INVALID_BODY" && field == EditField.DOB -> "Enter a date of birth for an age between 13 and 100."
    e is ApiRequestException && e.error.code == "INVALID_BODY" && field == EditField.EMAIL -> "Enter a valid email address, like name@example.com."
    e is ApiRequestException && e.status in 400..499 -> e.error.message.ifBlank { "Couldn’t save that. Try again." }
    else -> "Couldn’t save that. Check your connection and try again."
}

/**
 * The profile drawer (PRD §10): who you are, MY PRODUCTS, settings, sign out
 * and account deletion. Opened from the avatar in the TopHeader on every tab.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(private val gateway: ProfileGateway) : ViewModel() {

    private val session = Loadable(viewModelScope, { r -> gateway.session(r) }, gateway.sessionChanges)
    val state: StateFlow<UiState<SessionResponse>> = session.state

    /** A save in flight (an edit panel or the units switch). */
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** The edit panel's error line. */
    private val _editError = MutableStateFlow<String?>(null)
    val editError: StateFlow<String?> = _editError.asStateFlow()

    /** A one-off message (units, deletion). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _faqs = MutableStateFlow<UiState<List<Faq>>?>(null)
    val faqs: StateFlow<UiState<List<Faq>>?> = _faqs.asStateFlow()

    fun retry() = session.retry()

    fun consumeMessage() {
        _message.value = null
    }

    fun clearEditError() {
        _editError.value = null
    }

    /** Validate and save [field]; [done] runs once it is saved (back to Personal details). */
    fun save(field: EditField, input: EditInput, done: () -> Unit) {
        if (_saving.value) return
        val patch = patchFor(field, input, gateway.today()).getOrElse {
            _editError.value = it.message
            return
        }
        _editError.value = null
        _saving.value = true
        viewModelScope.launch {
            try {
                gateway.update(patch)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editError.value = saveErrorText(e, field)
            } finally {
                _saving.value = false
            }
        }
    }

    fun toggleUnits(metric: Boolean) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                gateway.update(UpdateProfileRequest(units = if (metric) Units.IMPERIAL else Units.METRIC))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn’t change units. Check your connection and try again."
            } finally {
                _saving.value = false
            }
        }
    }

    /** Help & Support's yoga answers, loaded when it opens. */
    fun loadFaqs() {
        if (_faqs.value is UiState.Ready) return
        _faqs.value = UiState.Loading
        viewModelScope.launch {
            _faqs.value = try {
                UiState.Ready(gateway.yogaFaqs())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UiState.Ready(emptyList())
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { gateway.signOut() }
    }

    /** Two-step on purpose: it cascades history, runs and registrations, and can't be undone. */
    fun deleteAccount() {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                // Signs out on success: the signed-out guard takes the app to Login.
                gateway.deleteAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Couldn’t delete your account. Check your connection and try again. Nothing has been deleted."
            } finally {
                _saving.value = false
            }
        }
    }
}
