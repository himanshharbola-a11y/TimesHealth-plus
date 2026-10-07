package timeshealth.app.ui.marathon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import timeshealth.app.core.model.RaceParticipant
import timeshealth.app.ui.components.ThSpinner
import timeshealth.app.ui.theme.BorderRule
import timeshealth.app.ui.theme.CoralBrand
import timeshealth.app.ui.theme.CrimsonAlert
import timeshealth.app.ui.theme.PaperWhite
import timeshealth.app.ui.theme.PlumBrand
import timeshealth.app.ui.theme.TextMuted
import timeshealth.app.ui.theme.TextPrimary
import timeshealth.app.ui.theme.ThFonts
import timeshealth.app.ui.theme.ThShapes

/** XS and XXL kept beyond the design's S–XL, so no runner is stranded outside the list. */
private val SIZES = listOf("XS", "S", "M", "L", "XL", "XXL")

/**
 * Edit participant details (PRD §8.3, design RaceDetailScreenKt's "Edit
 * Details" dialog): T-shirt size and emergency contact. Name and date of birth
 * are not editable here: they are tied to the timing chip and event insurance,
 * so changing them is a support action. The contact is two fields because the
 * API stores name and number apart.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ParticipantEditor(
    initial: RaceParticipant?,
    saving: Boolean,
    error: String?,
    onSave: (size: String?, contactName: String, contactPhone: String) -> Unit,
    onEdited: () -> Unit,
    onClose: () -> Unit,
) {
    var size by rememberSaveable { mutableStateOf(initial?.tshirtSize) }
    var contactName by rememberSaveable { mutableStateOf(initial?.emergencyContactName.orEmpty()) }
    var contactPhone by rememberSaveable { mutableStateOf(initial?.emergencyContactPhone.orEmpty().removePrefix("+91")) }
    val sizes = size?.takeIf { it !in SIZES }?.let { SIZES + it } ?: SIZES

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = PaperWhite,
        title = { Text("Edit Details", fontFamily = ThFonts.Serif, fontWeight = FontWeight.Bold, color = TextPrimary) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("T-Shirt Size", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                FlowRow(Modifier.padding(top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sizes.forEach { s ->
                        val on = size == s
                        Box(
                            Modifier.size(44.dp).clip(ThShapes.Md).background(if (on) CoralBrand else PaperWhite)
                                .border(1.dp, if (on) CoralBrand else BorderRule, ThShapes.Md)
                                .selectable(selected = on, role = Role.RadioButton) {
                                    size = s
                                    onEdited()
                                },
                            contentAlignment = Alignment.Center,
                        ) { Text(s, color = if (on) PaperWhite else TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                    }
                }
                Text("Emergency Contact", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Field(contactName, "Contact name", KeyboardType.Text) {
                    contactName = it.take(80)
                    onEdited()
                }
                Field(contactPhone, "Contact mobile number", KeyboardType.Phone) {
                    contactPhone = it.filter { c -> c.isDigit() }.take(10)
                    onEdited()
                }
                Text(
                    "Name and date of birth are tied to your timing chip and event insurance — contact support to change them.",
                    color = TextMuted, fontSize = 11.5.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 10.dp),
                )
                error?.let { Text(it, color = CrimsonAlert, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            Box(
                Modifier.height(40.dp).clip(ThShapes.Pill).background(PlumBrand)
                    .selectable(selected = false, enabled = !saving, role = Role.Button) { onSave(size, contactName, contactPhone) }
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (saving) ThSpinner(color = PaperWhite, size = 18.dp) else Text("Save Changes", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel", color = TextPrimary) } },
    )
}

@Composable
private fun Field(value: String, placeholder: String, type: KeyboardType, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder, color = TextMuted) },
        keyboardOptions = KeyboardOptions(keyboardType = type),
        shape = ThShapes.Md,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = PlumBrand, unfocusedBorderColor = BorderRule),
        textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
