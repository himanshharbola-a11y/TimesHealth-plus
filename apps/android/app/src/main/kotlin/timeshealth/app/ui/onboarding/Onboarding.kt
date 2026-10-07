package timeshealth.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timeshealth.app.core.data.repository.ProfileRepository
import timeshealth.app.core.domain.CONCERNS
import timeshealth.app.core.domain.GOALS
import timeshealth.app.core.domain.isValidEmail
import timeshealth.app.core.domain.toIndianE164
import timeshealth.app.core.model.Concern
import timeshealth.app.core.model.HealthGoal
import timeshealth.app.core.model.OnboardingStepRequest
import timeshealth.app.core.model.UserProfile
import timeshealth.app.core.network.ApiRequestException
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.session.AccountGateway
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

/** What onboarding needs: the profile to prefill, and the step / skip saves. */
interface OnboardingGateway {
    suspend fun profile(): UserProfile?
    suspend fun saveStep(body: OnboardingStepRequest)
    suspend fun skip()
}

class RepositoryOnboardingGateway @Inject constructor(
    private val account: AccountGateway,
    private val profiles: ProfileRepository,
) : OnboardingGateway {
    override suspend fun profile(): UserProfile? = runCatching { account.session().profile }.getOrNull()
    override suspend fun saveStep(body: OnboardingStepRequest) {
        profiles.onboardingStep(body)
    }
    override suspend fun skip() {
        profiles.skipOnboarding()
    }
}

data class OnboardingUi(
    val step: Int = 1,
    val name: String = "",
    val phone: String = "",
    val email: String = "",
    val goal: String? = null,
    val concern: String? = null,
    val phoneError: String? = null,
    val emailError: String? = null,
    /** A quiet line about a save that didn't land; never blocks moving on. */
    val notice: String? = null,
    val finishing: Boolean = false,
)

private val COPY = mapOf(
    1 to ("What should we call you?" to "We use this to greet you on Home and personalize your class certificates."),
    2 to ("Where can we reach you?" to "Your daily WhatsApp class links and race bib updates will be delivered here."),
    3 to ("What is your main focus?" to "This sets the primary recommendation stack on your Home screen."),
    4 to ("Any area needing special care?" to "We will place relevant mobility and recovery sessions at the top of your feed."),
)

internal const val RETRY_DELAY_MS = 3_000L

/**
 * The 4-step personalisation (PRD §5): name, contact, goal, focus area. Every
 * step can be skipped. Saves run in the background so a slow network never
 * holds the user on a step: a transient failure is retried once, then the
 * user is told they can add it later in their profile.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(private val gateway: OnboardingGateway) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val p = gateway.profile() ?: return@launch
            val u = _ui.value
            _ui.value = u.copy(
                name = u.name.ifEmpty { p.name.orEmpty() },
                phone = u.phone.ifEmpty { p.phone?.let { toIndianE164(it)?.removePrefix("+91") ?: it }.orEmpty() },
                email = u.email.ifEmpty { p.email.orEmpty() },
                goal = u.goal ?: p.healthGoal?.name,
                concern = u.concern ?: p.concern?.name,
            )
        }
    }

    fun setName(v: String) { _ui.value = _ui.value.copy(name = v.take(80)) }
    fun setPhone(v: String) { _ui.value = _ui.value.copy(phone = v.filter { it.isDigit() || it in "+ -" }.take(16), phoneError = null) }
    fun setEmail(v: String) { _ui.value = _ui.value.copy(email = v.take(160), emailError = null) }
    fun setGoal(v: String) { _ui.value = _ui.value.copy(goal = v) }
    fun setConcern(v: String) { _ui.value = _ui.value.copy(concern = v) }

    /** Continue: validate (step 2 only), save in the background, move on. Step 4 finishes. */
    fun advance(finish: () -> Unit) {
        val u = _ui.value
        when (u.step) {
            1 -> persist(OnboardingStepRequest(step = 1, name = u.name.trim().ifEmpty { null }))
            2 -> {
                val p = u.phone.trim()
                val e = u.email.trim()
                val e164 = p.takeIf { it.isNotEmpty() }?.let(::toIndianE164)
                val pErr = if (p.isNotEmpty() && e164 == null) "Enter a valid 10-digit Indian mobile number." else null
                val eErr = if (e.isNotEmpty() && !isValidEmail(e)) "Enter a valid email address, like name@example.com." else null
                if (pErr != null || eErr != null) {
                    _ui.value = u.copy(phoneError = pErr, emailError = eErr)
                    return
                }
                persist(OnboardingStepRequest(step = 2, phone = e164, email = e.ifEmpty { null }))
            }
            3 -> persist(OnboardingStepRequest(step = 3, healthGoal = u.goal?.let { g -> HealthGoal.entries.firstOrNull { it.name == g } }))
            else -> {
                _ui.value = u.copy(finishing = true)
                viewModelScope.launch {
                    save(OnboardingStepRequest(step = 4, concern = u.concern?.let { c -> Concern.entries.firstOrNull { it.name == c } }))
                    finish()
                }
                return
            }
        }
        _ui.value = _ui.value.copy(step = u.step + 1)
    }

    /** Skip: steps 1–3 move on; the last one marks onboarding done without a focus area. */
    fun skip(finish: () -> Unit) {
        val u = _ui.value
        if (u.step < 4) {
            _ui.value = u.copy(step = u.step + 1, phoneError = null, emailError = null)
            return
        }
        _ui.value = u.copy(finishing = true)
        viewModelScope.launch {
            runCatching { gateway.skip() }.onFailure { if (it is CancellationException) throw it }
            finish()
        }
    }

    fun back() {
        val u = _ui.value
        if (u.step > 1) _ui.value = u.copy(step = u.step - 1)
    }

    private fun persist(body: OnboardingStepRequest) {
        viewModelScope.launch { save(body) }
    }

    private suspend fun save(body: OnboardingStepRequest) {
        try {
            gateway.saveStep(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val transient = e !is ApiRequestException || e.isNetworkFailure || e.status >= 500
            if (!transient) {
                _ui.value = _ui.value.copy(notice = "Some details couldn’t be saved. You can add them later in your profile.")
                return
            }
            _ui.value = _ui.value.copy(notice = "Couldn’t save just now — trying again in the background.")
            delay(RETRY_DELAY_MS)
            try {
                gateway.saveStep(body)
                _ui.value = _ui.value.copy(notice = null)
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                _ui.value = _ui.value.copy(notice = "Still couldn’t save. You can add these details later in your profile.")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingRoute(viewModel: OnboardingViewModel, onDone: () -> Unit) {
    val u by viewModel.ui.collectAsStateWithLifecycle()
    androidx.activity.compose.BackHandler(enabled = u.step > 1) { viewModel.back() }
    val (title, sub) = COPY.getValue(u.step)
    Column(Modifier.fillMaxSize().background(CanvasBg).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..4).forEach { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (i <= u.step) PlumBrand else BorderRule))
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            TagPill("Step ${u.step} of 4", TagTone.CORAL, Modifier.padding(top = 8.dp))
            Text(title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            Text(sub, color = TextSecondary, fontSize = 13.5.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
            when (u.step) {
                1 -> Field("Your full name", u.name, null, KeyboardType.Text, viewModel::setName)
                2 -> {
                    Field("Mobile number (for WhatsApp links)", u.phone, u.phoneError, KeyboardType.Phone, viewModel::setPhone)
                    Field("Email address", u.email, u.emailError, KeyboardType.Email, viewModel::setEmail, Modifier.padding(top = 12.dp))
                }
                3 -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    GOALS.forEach { g ->
                        val on = u.goal == g.value
                        Row(
                            Modifier.fillMaxWidth().clip(ThShapes.Lg).background(if (on) PlumTint else PaperWhite)
                                .border(if (on) 2.dp else 1.dp, if (on) PlumBrand else BorderRule, ThShapes.Lg)
                                .selectable(on, role = Role.RadioButton) { viewModel.setGoal(g.value) }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(g.emoji.orEmpty(), fontSize = 22.sp)
                            Text(g.label, color = if (on) PlumDeep else TextPrimary, fontSize = 14.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                    CONCERNS.forEach { c ->
                        val on = u.concern == c.value
                        Box(
                            Modifier.weight(1f).height(56.dp).clip(ThShapes.Lg).background(if (on) PlumTint else PaperWhite)
                                .border(if (on) 2.dp else 1.dp, if (on) PlumBrand else BorderRule, ThShapes.Lg)
                                .selectable(on, role = Role.RadioButton) { viewModel.setConcern(c.value) },
                            contentAlignment = Alignment.Center,
                        ) { Text(c.label, color = if (on) PlumDeep else TextPrimary, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium) }
                    }
                }
            }
            u.notice?.let { Text(it, color = TextMuted, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 16.dp)) }
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            Box(
                Modifier.fillMaxWidth().height(52.dp).clip(ThShapes.Md).background(PlumBrand)
                    .clickable(enabled = !u.finishing, role = Role.Button) { viewModel.advance(onDone) },
                contentAlignment = Alignment.Center,
            ) {
                if (u.finishing) ThSpinner(color = PaperWhite, size = 20.dp)
                else Text(if (u.step == 4) "Finish & Go to Home" else "Continue", color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Skip for now", color = TextMuted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)
                    .clickable(enabled = !u.finishing, role = Role.Button) { viewModel.skip(onDone) }.padding(10.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, error: String?, type: KeyboardType, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it, color = CrimsonAlert) } },
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = if (type == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None, autoCorrectEnabled = false),
        shape = ThShapes.Md,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PlumBrand, focusedLabelColor = PlumBrand, unfocusedBorderColor = BorderRule, errorBorderColor = CrimsonAlert),
        textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary),
        modifier = modifier.fillMaxWidth(),
    )
}
