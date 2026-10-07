package timeshealth.app.ui.diet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import timeshealth.app.core.model.UserQuote
import timeshealth.app.ui.components.TagPill
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.feed.FeedImage
import timeshealth.app.ui.feed.GradientDir
import timeshealth.app.ui.feed.QuoteCard
import timeshealth.app.ui.feed.gradient
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.BorderSubtle
import timeshealth.app.ui.theme.CanvasBg
import timeshealth.app.ui.theme.Carbon950
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.PlumDeep
import timeshealth.app.ui.theme.PlumLine
import timeshealth.app.ui.theme.PlumTint
import timeshealth.app.ui.theme.SageBrand
import timeshealth.app.ui.theme.SageSecondary
import timeshealth.app.ui.theme.SageTint
import timeshealth.app.ui.theme.TagTone
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.TextSecondary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThLayout
import timeshealth.app.ui.theme.ThShapes

private const val HERO_IMAGE = "https://images.unsplash.com/photo-1490645935967-10de6ba17061?auto=format&fit=crop&w=800&q=80"
private const val KITCHEN_IMAGE = "https://images.unsplash.com/photo-1512621776951-a57141f2eefd?auto=format&fit=crop&w=800&q=80"

/** The hero's proof row. */
private val HERO_STATS = listOf("Daily" to "WhatsApp support", "Weekly" to "1-on-1 consults", "8 Cuisines" to "Regional Indian meals")

/** "Why it sticks", the design's copy verbatim. */
private val WHY = listOf(
    "2,500+ Indian meal combinations" to "From Punjabi dal to South Indian rasam — food you actually eat and cook every day.",
    "Qualified certified clinical dietitians" to "Master's degree nutritionists who build and modify your plan as your life shifts.",
    "No crash diets or deprivation" to "Built to work with social dinners, travel, and family routines without guilt.",
    "16+ medical conditions supported" to "Evidence-based protocols for PCOS, Type 2 Diabetes, Thyroid, and Cholesterol.",
)

private val CONDITIONS = listOf(
    "Weight loss", "Lower back / Joint pain", "PCOS / PCOD", "Type 2 Diabetes", "Thyroid imbalance",
    "Acid reflux / GERD", "Hypertension", "Fatty liver", "Marathon race fuel",
)

/**
 * "Real member results": the DESIGN PROTOTYPE'S PLACEHOLDERS, as in the RN app.
 * Before launch, replace them with real testimonials the members consented to,
 * with the health outcomes ("HbA1c dropped", "Lost 9 kg") verified: app-store
 * reviewers scrutinise medical claims.
 */
private val RESULTS = listOf(
    UserQuote("diet-sonia", "Other plans stopped working after two weeks. This one actually fit my kitchen.", "Sonia Goyal", "Lost 9 kg · Mumbai"),
    UserQuote("diet-rajeshwari", "Proper portions, normal meals. My HbA1c dropped in two months.", "Rajeshwari M.", "Type 2 Diabetes"),
    UserQuote("diet-vikram", "My dietitian calls on time every week. That accountability made all the difference.", "Vikram S.", "Lost 7 kg · Bengaluru"),
)

/**
 * DIET TAB (PRD §9, design DietScreenKt): a plum hero with its proof row, the
 * Indian Home Kitchen card, why it sticks, the conditions grid, member quotes
 * and a closing CTA. Both CTAs open the lead sheet; success is a dialog.
 * The shared TopHeader sits above, so this screen draws no header.
 */
@Composable
fun DietRoute(viewModel: DietViewModel) {
    val done by viewModel.done.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val open = {
        viewModel.opened()
        sheetOpen = true
    }
    LazyColumn(Modifier.fillMaxSize().background(CanvasBg), contentPadding = PaddingValues(bottom = 90.dp)) {
        item(key = "hero") { Hero(open) }
        item(key = "kitchen") { KitchenCard(Modifier.padding(top = 14.dp)) }
        item(key = "why-title") { Header("Why it sticks", Modifier.padding(top = 16.dp)) }
        items(WHY, key = { it.first }) { (title, body) ->
            Column(
                Modifier.padding(horizontal = ThLayout.Gutter, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PaperWhite)
                    .border(1.dp, BorderRule, RoundedCornerShape(14.dp)).padding(14.dp),
            ) {
                Text(title, color = TextPrimary, fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                Text(body, color = TextSecondary, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        item(key = "conditions-title") { Header("Conditions we help manage", Modifier.padding(top = 16.dp)) }
        item(key = "conditions") { ConditionGrid() }
        item(key = "results-title") { Header("Real member results", Modifier.padding(top = 18.dp)) }
        item(key = "results") {
            LazyRow(contentPadding = PaddingValues(horizontal = ThLayout.Gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(RESULTS, key = { it.id }) { QuoteCard(it) }
            }
        }
        item(key = "cta") {
            Column(
                Modifier.padding(top = 18.dp).padding(horizontal = ThLayout.Gutter).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PlumTint)
                    .border(1.dp, PlumLine, RoundedCornerShape(18.dp)).padding(18.dp),
            ) {
                Text("Start with a Free 15-Min Call", color = PlumDeep, fontFamily = ThFonts.Serif, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Talk to a certified clinical dietitian. No commitment, no payment required in V1.",
                    color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                Box(
                    Modifier.fillMaxWidth().height(48.dp).clip(ThShapes.Md).background(PlumBrand).clickable(role = Role.Button, onClick = open),
                    contentAlignment = Alignment.Center,
                ) { Text("Book My Free Call", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (sheetOpen) LeadSheet(viewModel, onClose = { sheetOpen = false })

    done?.let { d ->
        AlertDialog(
            onDismissRequest = viewModel::dismissDone,
            title = { Text(d.title, fontFamily = ThFonts.Serif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary) },
            text = { Text(d.message, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp) },
            confirmButton = {
                Box(
                    Modifier.height(40.dp).clip(ThShapes.Pill).background(Carbon950).clickable(role = Role.Button, onClick = viewModel::dismissDone).padding(horizontal = 24.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Done", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            },
            containerColor = PaperWhite,
        )
    }
}

@Composable
private fun Hero(onBook: () -> Unit) {
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))) {
        FeedImage(HERO_IMAGE, gradient(PlumBrand, PlumDeep), gradient(Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.9f), GradientDir.VERTICAL), Modifier.matchParentSize())
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
            TagPill("Dietitian-led nutrition", TagTone.GOLD)
            Text(
                "Your goal. Your food.\nYour dietitian.", color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 32.sp, lineHeight = 36.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp).semantics { heading() },
            )
            Text(
                "A personalized nutrition plan built around what you already cook at home, with a dedicated dietitian checking in weekly.",
                color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp, bottom = 18.dp),
            )
            Box(
                Modifier.fillMaxWidth().height(48.dp).clip(ThShapes.Md).background(PaperWhite).clickable(role = Role.Button, onClick = onBook),
                contentAlignment = Alignment.Center,
            ) { Text("Book a Free Consult", color = PlumDeep, fontSize = 13.5.sp, fontWeight = FontWeight.Bold) }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                HERO_STATS.forEach { (value, label) ->
                    Column {
                        Text(value, color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun KitchenCard(modifier: Modifier) {
    Column(
        modifier.padding(horizontal = ThLayout.Gutter).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(PaperWhite)
            .border(1.dp, BorderSubtle, RoundedCornerShape(18.dp)),
    ) {
        Box(Modifier.fillMaxWidth().height(130.dp)) {
            FeedImage(KITCHEN_IMAGE, gradient(SageBrand, SageSecondary, GradientDir.HORIZONTAL), gradient(Color.Transparent, Color.Black.copy(alpha = 0.7f), GradientDir.VERTICAL), Modifier.matchParentSize())
            TagPill("Indian home kitchen", TagTone.EMERALD, Modifier.padding(12.dp))
            Text(
                "2,500+ Macro-Balanced Regional Recipes", color = PaperWhite, fontFamily = ThFonts.Serif, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
        Text(
            "Eat what your family eats. Your dedicated dietitian designs portion macros around your favorite home-cooked dishes, lentils, and grains.",
            color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(14.dp),
        )
    }
}

/** Rows of three equal pills; a short last row keeps the grid. */
@Composable
private fun ConditionGrid() {
    Column(Modifier.padding(horizontal = ThLayout.Gutter), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CONDITIONS.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { c ->
                    Text(
                        c, color = TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(PaperWhite).border(1.dp, BorderRule, RoundedCornerShape(20.dp))
                            .padding(horizontal = 4.dp, vertical = 7.dp),
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Header(title: String, modifier: Modifier = Modifier) {
    Text(
        title, color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = ThLayout.Gutter, vertical = 12.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeadSheet(viewModel: DietViewModel, onClose: () -> Unit) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PaperWhite,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 20.dp).navigationBarsPadding().imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Book Your Free 15-Min Consult", color = TextPrimary, fontFamily = ThFonts.Serif, fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = TextMuted) }
            }
            Text("A qualified clinical dietitian will call you within one working day.", color = TextSecondary, fontSize = 12.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(14.dp))
            Field("Your Full Name", form.name, form.nameError, KeyboardType.Text, viewModel::setName)
            Spacer(Modifier.height(10.dp))
            Field("Mobile Number (Call & WhatsApp)", form.phone, form.phoneError, KeyboardType.Phone, viewModel::setPhone)
            Text("What would you like help with?", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DIET_CONCERNS.forEach { Chip(it, form.concern == it) { viewModel.setConcern(it) } }
            }
            Text("Preferred Call Time", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CALL_TIMES.forEach { Chip(it, form.callTime == it) { viewModel.setCallTime(it) } }
            }
            Box(
                Modifier.padding(top = 18.dp).fillMaxWidth().height(50.dp).clip(ThShapes.Md).background(PlumBrand)
                    .clickable(enabled = !form.sending, role = Role.Button) { viewModel.submit(onDone = onClose) },
                contentAlignment = Alignment.Center,
            ) {
                if (form.sending) ThSpinner(color = PaperWhite, size = 20.dp) else Text("Request Free Dietitian Call", color = PaperWhite, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            form.formError?.let {
                Text(it, color = CrimsonAlert, fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, error: String?, type: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it, color = CrimsonAlert, fontSize = 11.5.sp, lineHeight = 15.sp) } },
        keyboardOptions = KeyboardOptions(keyboardType = type, capitalization = if (type == KeyboardType.Text) KeyboardCapitalization.Words else KeyboardCapitalization.None, autoCorrectEnabled = false),
        shape = ThShapes.Md,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PlumBrand, focusedLabelColor = PlumBrand, unfocusedBorderColor = BorderRule, unfocusedLabelColor = TextMuted,
            errorBorderColor = CrimsonAlert, errorLabelColor = CrimsonAlert,
        ),
        textStyle = TextStyle(fontSize = 15.sp, color = TextPrimary),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** M3 FilterChip look: 32dp, R8; selected is sage. */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(32.dp).clip(ThShapes.Sm).background(if (selected) SageTint else Color.Transparent)
            .border(1.dp, if (selected) SageTint else BorderRule, ThShapes.Sm)
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (selected) SageBrand else TextSecondary, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
}
