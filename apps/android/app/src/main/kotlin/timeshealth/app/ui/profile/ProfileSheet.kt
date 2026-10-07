package timeshealth.app.ui.profile

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import timeshealth.app.BuildConfig
import timeshealth.app.core.domain.CONCERNS
import timeshealth.app.core.domain.GENDERS
import timeshealth.app.core.domain.GOALS
import timeshealth.app.core.domain.IST
import timeshealth.app.core.domain.LabelOption
import timeshealth.app.core.domain.PRIVACY_URL
import timeshealth.app.core.domain.SUPPORT_WHATSAPP_URL
import timeshealth.app.core.domain.concernLabel
import timeshealth.app.core.domain.formatPhone
import timeshealth.app.core.domain.genderLabel
import timeshealth.app.core.domain.goalLabel
import timeshealth.app.core.domain.isSafeExternalUrl
import timeshealth.app.core.domain.parseIsoInstant
import timeshealth.app.core.domain.toIndianE164
import timeshealth.app.core.model.Faq
import timeshealth.app.core.model.MarathonEntitlement
import timeshealth.app.core.model.RaceLifecycleStatus
import timeshealth.app.core.model.RaceTier
import timeshealth.app.core.model.SessionResponse
import timeshealth.app.core.model.Units
import timeshealth.app.core.model.UserProfile
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.state.UiState
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.BorderSubtle
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CoralTint
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.SageTint
import timeshealth.app.ui.theme.SurfaceSand
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

/** Where the drawer leaves to. */
sealed interface ProfileDestination {
    data object Paywall : ProfileDestination
    data object MarathonTab : ProfileDestination
    data class Race(val eventId: String) : ProfileDestination
    data class RaceParticipant(val eventId: String) : ProfileDestination
    data class RaceResults(val eventId: String) : ProfileDestination
}

/**
 * Answers about the app itself, alongside the yoga FAQs the API serves. Each
 * describes what this build actually does: keep them in step with it.
 */
private val APP_FAQS = listOf(
    Faq(
        "How do I delete my account?",
        "Open your profile and tap “Delete my account” at the bottom. It permanently removes your profile, attendance history, runs and saved sessions, and can’t be undone. Active subscriptions and race registrations are not refunded automatically — message support first if you need a refund.",
    ),
    Faq(
        "Who do I contact about a payment or refund?",
        "Message TimesHealth+ support on WhatsApp from Help & Support. Paid workshop seats can’t be cancelled in the app either — support handles the cancellation and the refund together.",
    ),
    Faq(
        "Why am I not getting app notifications?",
        "App alerts need notification permission. Profile → Notifications opens your phone’s settings for TimesHealth+, where you can switch them on. Class reminders and race-day updates also come on WhatsApp, so the essentials still reach you.",
    ),
    Faq(
        "Will my race pass work without signal at the venue?",
        "Yes. Open your race in the app once while you have signal and your digital bib and QR pass are saved on this phone, so they open at the gate with no network. Saved passes are removed when you sign out.",
    ),
    Faq(
        "Can I change the email or mobile number I sign in with?",
        "Not from the app — it’s marked “Used to sign in” under Personal details. Your other contact detail can be edited there.",
    ),
)

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("en-IN"))

/** An ISO instant as an IST date, "7 Oct 2026". */
internal fun formatDate(iso: String): String = parseIsoInstant(iso)?.atZone(IST)?.toLocalDate()?.let(DATE::format) ?: "—"

/** The stored DOB is a UTC midnight: read the date part as is, so no timezone shifts the day. */
private fun dobDate(iso: String?): LocalDate? = iso?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

private fun bibOrRef(r: MarathonEntitlement) = r.bibNumber?.let { "Bib #$it" } ?: "Ref ${r.registrationRef}"
private fun tierLabel(r: MarathonEntitlement) = if (r.tier == RaceTier.PREMIUM) "Premium VIP" else "Classic"

/**
 * PROFILE (PRD §10), the design's ProfileSheet: header, MY PRODUCTS, ACCOUNT &
 * SETTINGS, Log Out. Personal details, their edit panels and Help & Support
 * open inside the sheet (back returns), so no second modal stacks on it.
 *
 * "My Products" is why it exists: the yoga membership and race registrations
 * in one place. Diet never appears: diet purchases can't be mapped to app
 * accounts in V1 (§9).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileSheet(viewModel: ProfileViewModel, onNavigate: (ProfileDestination) -> Unit, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var screen by rememberSaveable { mutableStateOf("main") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val leave = { d: ProfileDestination ->
        onDismiss()
        onNavigate(d)
    }
    // Back inside the sheet steps back a screen; on the main one it closes.
    val back = {
        screen = when (screen) {
            "main" -> "main".also { onDismiss() }
            "personal", "help" -> "main"
            else -> "personal"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PaperWhite,
    ) {
        androidx.activity.compose.BackHandler(enabled = screen != "main") { back() }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)
                .navigationBarsPadding().imePadding(),
        ) {
            when (val s = state) {
                UiState.Loading -> Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { ThSpinner(size = 24.dp) }
                is UiState.Failed -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.error.message, color = TextMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
                    TextButton(onClick = viewModel::retry) { Text("Try again", color = CoralBrand, fontWeight = FontWeight.Bold) }
                    LogOut(viewModel::signOut)
                }
                is UiState.Ready -> {
                    val data = s.data
                    when (screen) {
                        "main" -> Main(
                            data, saving, message,
                            onPersonal = { screen = "personal" },
                            onHelp = {
                                viewModel.loadFaqs()
                                screen = "help"
                            },
                            onUnits = { viewModel.toggleUnits(data.profile.units != Units.IMPERIAL) },
                            onLeave = leave,
                            onClose = onDismiss,
                            onSignOut = viewModel::signOut,
                            onDelete = { confirmDelete = true },
                            onMessageShown = viewModel::consumeMessage,
                        )
                        "personal" -> Personal(
                            data,
                            onBack = back,
                            onClose = onDismiss,
                            onEdit = {
                                viewModel.clearEditError()
                                screen = it.name
                            },
                            onOpenRace = { leave(ProfileDestination.RaceParticipant(it)) },
                        )
                        "help" -> Help(viewModel, onBack = back, onClose = onDismiss)
                        else -> EditField.entries.firstOrNull { it.name == screen }?.let { field ->
                            EditPanel(field, data.profile, viewModel, onBack = back, onClose = onDismiss)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val data = (state as? UiState.Ready)?.data
        val losing = listOfNotNull(
            data?.entitlements?.yoga?.takeIf { it.active }?.let { "your Yoga membership (valid until ${formatDate(it.expiresAt)})" },
        ) + data?.entitlements?.marathon.orEmpty()
            .filter { it.status == RaceLifecycleStatus.UPCOMING || it.status == RaceLifecycleStatus.RACE_DAY }
            .map { "your ${it.eventName} registration" }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete your account?") },
            text = {
                Text(
                    "This permanently removes your profile, attendance history, runs and saved sessions." +
                        if (losing.isNotEmpty()) {
                            "\n\nYou will also lose ${losing.joinToString(", ")}. These are not refunded automatically — message support first if you need a refund."
                        } else {
                            ""
                        },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteAccount()
                }) { Text("Delete permanently", color = CrimsonAlert, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = TextPrimary) } },
            containerColor = PaperWhite,
        )
    }
}

@Composable
private fun ColumnScope.Main(
    data: SessionResponse,
    saving: Boolean,
    message: String?,
    onPersonal: () -> Unit,
    onHelp: () -> Unit,
    onUnits: () -> Unit,
    onLeave: (ProfileDestination) -> Unit,
    onClose: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val profile = data.profile
    val name = profile.name?.trim()?.takeIf { it.isNotEmpty() }
    val contact = listOfNotNull(profile.phone?.let(::formatPhone), profile.email).joinToString(" · ")
    val metric = profile.units != Units.IMPERIAL
    val yoga = data.entitlements.yoga
    val races = data.entitlements.marathon
    val active = races.filter { it.status != RaceLifecycleStatus.COMPLETED }
    val completed = races.filter { it.status == RaceLifecycleStatus.COMPLETED }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(54.dp).clip(CircleShape).background(PlumDeep), contentAlignment = Alignment.Center) {
                Text((name ?: "P").take(1).uppercase(), color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f)) {
                Text(name ?: "Your profile", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (contact.isNotEmpty()) Text(contact, color = TextMuted, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
        CloseButton(onClose)
    }
    Spacer(Modifier.height(18.dp))

    Column(
        Modifier.fillMaxWidth().padding(bottom = 18.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Profile ${profile.profileCompletion}% complete. Open personal details." }
            .clickable(role = Role.Button, onClick = onPersonal),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Profile ${profile.profileCompletion}% complete", color = TextMuted, fontSize = 11.5.sp)
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(SurfaceSand)) {
            Box(Modifier.fillMaxWidth(profile.profileCompletion.coerceIn(0, 100) / 100f).height(4.dp).clip(CircleShape).background(CoralBrand))
        }
    }

    SectionLabel("MY PRODUCTS")
    when {
        yoga?.active == true -> ProductCard(
            "🧘", PlumTint, "Yoga Membership", "${yoga.planLabel} · ${if (yoga.autoRenews) "Renews" else "Valid until"} ${formatDate(yoga.expiresAt)}",
            onClick = { onLeave(ProfileDestination.Paywall) },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TagPill("ACTIVE", TagTone.EMERALD)
                // The paywall greets a member with "Extend your membership".
                SmallButton("Extend", plum = false) { onLeave(ProfileDestination.Paywall) }
            }
        }
        yoga != null -> ProductCard("🧘", PlumTint, "Yoga Membership", "Expired ${formatDate(yoga.expiresAt)}", onClick = { onLeave(ProfileDestination.Paywall) }) {
            SmallButton("Renew", plum = true) { onLeave(ProfileDestination.Paywall) }
        }
        else -> ProductCard("🧘", PlumTint, "Yoga Membership", "Not subscribed · 8 live classes daily", onClick = { onLeave(ProfileDestination.Paywall) }) {
            SmallButton("Explore", plum = true) { onLeave(ProfileDestination.Paywall) }
        }
    }
    if (active.isEmpty()) {
        ProductCard("🏃", CoralTint, "Marathon Registrations", "No active registrations", onClick = { onLeave(ProfileDestination.MarathonTab) }) {
            TagPill("NONE", TagTone.NEUTRAL)
        }
    } else {
        active.forEach { r ->
            ProductCard(
                "🏃", CoralTint,
                if (active.size == 1) "Marathon Registrations" else r.eventName,
                if (active.size == 1) "${r.eventName} · ${bibOrRef(r)}" else "${r.category} ${tierLabel(r)} · ${bibOrRef(r)}",
                onClick = { onLeave(ProfileDestination.Race(r.eventId)) },
            ) { TagPill("REGISTERED", TagTone.CORAL) }
        }
    }
    completed.forEach { r ->
        val open = { onLeave(ProfileDestination.RaceResults(r.eventId)) }
        ProductCard(null, null, r.eventName, "${r.category} ${tierLabel(r)} · Result & certificate", titleSize = 13, onClick = open) {
            SmallButton("Result", plum = false, onClick = open)
        }
    }
    Text(
        "Diet consultations are arranged with your dietitian on WhatsApp, so they don’t appear here.",
        color = TextMuted, fontSize = 10.5.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
    )

    SectionLabel("ACCOUNT & SETTINGS")
    SettingRow("Personal details", "Name, DOB, gender and marathon medical contacts", onClick = onPersonal)
    SettingRow("Notifications", "Class reminders & race-day alerts", onClick = {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    })
    SettingRow(
        "Units & Measurement",
        when {
            saving -> "Switching…"
            message != null -> message
            metric -> "Kilometres and metric pace"
            else -> "Miles and imperial pace"
        },
        trailing = if (metric) "km" else "mi",
        onClick = if (saving) null else onUnits,
    )
    LaunchedEffect(message) {
        if (message != null) {
            kotlinx.coroutines.delay(4_000)
            onMessageShown()
        }
    }
    // §10 lists language; English only in V1: information, not a dead control.
    SettingRow("Language", "English")
    SettingRow("Help & Support", "FAQs and WhatsApp support", onClick = onHelp)
    SettingRow("Privacy & Terms", "TimesHealth+ policy and data guidelines", onClick = {
        if (isSafeExternalUrl(PRIVACY_URL, BuildConfig.DEBUG)) runCatching { uri.openUri(PRIVACY_URL) }
    })

    Spacer(Modifier.height(18.dp))
    LogOut(onSignOut)
    // A store requirement the design doesn't draw: kept, but quiet and last.
    Text(
        "Delete my account", color = TextMuted, fontSize = 11.5.sp,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp).clickable(role = Role.Button, onClick = onDelete).padding(12.dp),
    )
}

@Composable
private fun LogOut(onSignOut: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).clip(ThShapes.Md).border(1.dp, BorderRule, ThShapes.Md).clickable(role = Role.Button, onClick = onSignOut),
        contentAlignment = Alignment.Center,
    ) { Text("Log Out of TimesHealth+", color = CrimsonAlert, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun Personal(data: SessionResponse, onBack: () -> Unit, onClose: () -> Unit, onEdit: (EditField) -> Unit, onOpenRace: (String) -> Unit) {
    val p = data.profile
    val upcoming = data.entitlements.marathon.filter { it.status == RaceLifecycleStatus.UPCOMING }
    SubHeader("Personal details", onBack, onClose)
    SettingRow("Name", p.name?.takeIf { it.isNotBlank() } ?: "Not added", onClick = { onEdit(EditField.NAME) })
    ContactRow("Email", p.email, locked = p.emailIsLogin) { onEdit(EditField.EMAIL) }
    ContactRow("Mobile", p.phone?.let(::formatPhone), locked = p.phoneIsLogin) { onEdit(EditField.PHONE) }
    SettingRow("Date of birth", dobDate(p.dob)?.let(DATE::format) ?: "Not added", onClick = { onEdit(EditField.DOB) })
    SettingRow("Gender", p.gender?.let(::genderLabel)?.takeIf { it != "—" } ?: "Not added", onClick = { onEdit(EditField.GENDER) })
    // Chosen at onboarding; they order the Home feed and are shown as set.
    SettingRow("Health goal", goalLabel(p.healthGoal?.name), onClick = { onEdit(EditField.GOAL) })
    SettingRow("Focus area", concernLabel(p.concern?.name), onClick = { onEdit(EditField.CONCERN) })
    if (upcoming.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        SectionLabel("MARATHON")
        upcoming.forEach { r ->
            SettingRow("Race participant details", "${r.eventName} · T-shirt size & emergency contact", onClick = { onOpenRace(r.eventId) })
        }
    }
}

/** The sign-in email / phone belongs to the identity provider: read-only, with a note. */
@Composable
private fun ContactRow(title: String, value: String?, locked: Boolean, onClick: () -> Unit) {
    if (!locked) return SettingRow(title, value ?: "Not added", onClick = onClick)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$title, ${value.orEmpty()}, used to sign in" }) {
        Column(Modifier.padding(vertical = 10.dp)) {
            Text(title, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            Text(value ?: "—", color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 1)
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = TextMuted, modifier = Modifier.size(11.dp))
                Text("Used to sign in", color = TextMuted, fontSize = 10.5.sp)
            }
        }
        Divider()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditPanel(field: EditField, profile: UserProfile, viewModel: ProfileViewModel, onBack: () -> Unit, onClose: () -> Unit) {
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val error by viewModel.editError.collectAsStateWithLifecycle()
    val dob = dobDate(profile.dob)
    var text by rememberSaveable(field) {
        mutableStateOf(
            when (field) {
                EditField.NAME -> profile.name.orEmpty()
                EditField.EMAIL -> profile.email.orEmpty()
                EditField.PHONE -> profile.phone?.let { toIndianE164(it)?.removePrefix("+91") ?: it }.orEmpty()
                else -> ""
            },
        )
    }
    var dd by rememberSaveable(field) { mutableStateOf(dob?.dayOfMonth?.toString()?.padStart(2, '0').orEmpty()) }
    var mm by rememberSaveable(field) { mutableStateOf(dob?.monthValue?.toString()?.padStart(2, '0').orEmpty()) }
    var yyyy by rememberSaveable(field) { mutableStateOf(dob?.year?.toString().orEmpty()) }
    var choice by rememberSaveable(field) {
        mutableStateOf(
            when (field) {
                EditField.GENDER -> profile.gender?.takeIf { g -> GENDERS.any { it.value == g } }
                EditField.GOAL -> profile.healthGoal?.name
                EditField.CONCERN -> profile.concern?.name
                else -> null
            },
        )
    }
    val save = { viewModel.save(field, EditInput(text, dd, mm, yyyy, choice), done = onBack) }

    SubHeader(field.title, onBack, onClose)
    Text(field.hint, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 14.dp))

    when (field) {
        EditField.DOB -> {
            val mmFocus = remember { FocusRequester() }
            val yyyyFocus = remember { FocusRequester() }
            val ddFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { ddFocus.requestFocus() } }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DobPart("DD", dd, 2, 64.dp, Modifier.focusRequester(ddFocus)) {
                    dd = it
                    viewModel.clearEditError()
                    if (it.length == 2) runCatching { mmFocus.requestFocus() }
                }
                Text("/", color = TextMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 14.dp))
                DobPart("MM", mm, 2, 64.dp, Modifier.focusRequester(mmFocus)) {
                    mm = it
                    viewModel.clearEditError()
                    if (it.length == 2) runCatching { yyyyFocus.requestFocus() }
                }
                Text("/", color = TextMuted, fontSize = 18.sp, modifier = Modifier.padding(bottom = 14.dp))
                DobPart("YYYY", yyyy, 4, 96.dp, Modifier.focusRequester(yyyyFocus), onDone = save) {
                    yyyy = it
                    viewModel.clearEditError()
                }
            }
        }
        EditField.GENDER, EditField.GOAL, EditField.CONCERN -> {
            val options: List<LabelOption> = when (field) {
                EditField.GENDER -> GENDERS
                EditField.GOAL -> GOALS
                else -> CONCERNS
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                options.forEach { o ->
                    val on = choice == o.value
                    Box(
                        Modifier.weight(1f).height(46.dp).clip(ThShapes.Md).background(if (on) PlumTint else Color.Transparent)
                            .border(1.dp, if (on) PlumBrand else BorderRule, ThShapes.Md)
                            .selectable(selected = on, role = Role.RadioButton) {
                                choice = o.value
                                viewModel.clearEditError()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(o.label, color = if (on) PlumDeep else TextPrimary, fontSize = 13.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 6.dp))
                    }
                }
            }
        }
        else -> {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.take(if (field == EditField.PHONE) 16 else if (field == EditField.EMAIL) 160 else 80)
                    viewModel.clearEditError()
                },
                singleLine = true,
                isError = error != null,
                placeholder = { Text(if (field == EditField.PHONE) "10-digit mobile number" else if (field == EditField.EMAIL) "name@example.com" else "Full name", color = TextMuted) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field) {
                        EditField.PHONE -> KeyboardType.Phone
                        EditField.EMAIL -> KeyboardType.Email
                        else -> KeyboardType.Text
                    },
                    capitalization = if (field == EditField.NAME) KeyboardCapitalization.Words else KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { save() }),
                shape = ThShapes.Md,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PlumBrand, unfocusedBorderColor = BorderRule, errorBorderColor = CrimsonAlert),
                textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = field.title },
            )
        }
    }

    error?.let { Text(it, color = CrimsonAlert, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp)) }

    Box(
        Modifier.padding(top = 18.dp).fillMaxWidth().height(48.dp).clip(ThShapes.Md).background(PlumBrand)
            .clickable(enabled = !saving, role = Role.Button, onClick = save),
        contentAlignment = Alignment.Center,
    ) {
        if (saving) ThSpinner(color = PaperWhite, size = 20.dp) else Text("Save", color = PaperWhite, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun DobPart(label: String, value: String, maxLength: Int, width: Dp, modifier: Modifier, onDone: (() -> Unit)? = null, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp)
        OutlinedTextField(
            value = value,
            onValueChange = { v -> onChange(v.filter(Char::isDigit).take(maxLength)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            shape = ThShapes.Md,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PlumBrand, unfocusedBorderColor = BorderRule),
            textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary, textAlign = TextAlign.Center, letterSpacing = 1.sp),
            modifier = modifier.width(width).semantics { contentDescription = "Date of birth, $label" },
        )
    }
}

@Composable
private fun Help(viewModel: ProfileViewModel, onBack: () -> Unit, onClose: () -> Unit) {
    val faqs by viewModel.faqs.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    SubHeader("Help & Support", onBack, onClose)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CanvasBg).border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { runCatching { uri.openUri(SUPPORT_WHATSAPP_URL) } }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(SageTint), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.Chat, null, tint = SageBrand, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text("Chat with support on WhatsApp", color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
            Text("Class link issues, payments, refunds and cancellations", color = TextMuted, fontSize = 11.5.sp)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
    }
    Spacer(Modifier.height(18.dp))
    SectionLabel("YOGA CLASSES")
    when (val f = faqs) {
        null, UiState.Loading -> Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { ThSpinner(color = PlumBrand, size = 20.dp) }
        is UiState.Ready -> if (f.data.isEmpty()) {
            Text("These answers couldn’t load right now. Support can help on WhatsApp.", color = TextMuted, fontSize = 11.5.sp)
        } else {
            f.data.forEach { FaqItem(it) }
        }
        is UiState.Failed -> Text("These answers couldn’t load right now. Support can help on WhatsApp.", color = TextMuted, fontSize = 11.5.sp)
    }
    Spacer(Modifier.height(18.dp))
    SectionLabel("ACCOUNT & APP")
    APP_FAQS.forEach { FaqItem(it) }
}

/** The design's FaqAccordion: its own R12 card, chevron, answer on expand. */
@Composable
private fun FaqItem(faq: Faq) {
    var open by rememberSaveable(faq.question) { mutableStateOf(false) }
    Column(
        Modifier.padding(vertical = 3.dp).fillMaxWidth().clip(ThShapes.Md).border(1.dp, BorderRule, ThShapes.Md)
            .clickable(role = Role.Button) { open = !open }
            .semantics { contentDescription = "${faq.question}, ${if (open) "expanded" else "collapsed"}" }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(faq.question, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = TextMuted)
        }
        if (open) Text(faq.answer, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(end = 0.dp)) { Icon(Icons.Filled.Close, "Close profile", tint = TextMuted) }
}

@Composable
private fun SubHeader(title: String, onBack: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(end = 4.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary) }
        Text(
            title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        CloseButton(onClose)
    }
}

/** 10sp ExtraBold, +0.8, textMuted, then a 6dp gap. */
@Composable
private fun SectionLabel(text: String) {
    Text(text, color = TextMuted, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, modifier = Modifier.padding(bottom = 6.dp).semantics { heading() })
}

@Composable
private fun ProductCard(
    emoji: String?,
    tileBg: Color?,
    title: String,
    subtitle: String,
    titleSize: Int = 0,
    onClick: () -> Unit,
    right: @Composable () -> Unit,
) {
    Row(
        Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CanvasBg).border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (emoji != null && tileBg != null) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(tileBg), contentAlignment = Alignment.Center) { Text(emoji, fontSize = 18.sp) }
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = if (titleSize > 0) titleSize.sp else 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = TextMuted, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        right()
    }
}

/** h34, R8: PlumBrand "Renew" / "Explore", SurfaceSand "Result" / "Extend". */
@Composable
private fun SmallButton(label: String, plum: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).clip(ThShapes.Sm).background(if (plum) PlumBrand else SurfaceSand).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = if (plum) 12.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (plum) PaperWhite else TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}

/** A row without [onClick] is plain information: no chevron, no press feedback (a dead tap otherwise, §6.3). */
@Composable
private fun SettingRow(title: String, subtitle: String, trailing: String? = null, onClick: (() -> Unit)? = null) {
    Column {
        Row(
            Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier).padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = TextMuted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 2)
            }
            trailing?.let { Text(it, color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
            if (onClick != null) Icon(Icons.Filled.ChevronRight, null, tint = TextMuted)
        }
        Divider()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(0.6.dp).background(BorderRule))
}
