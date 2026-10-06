package timeshealth.app.ui.components

import android.view.WindowManager
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import timeshealth.app.ui.theme.PaperWhite

/**
 * A navigation destination presented as a bottom SHEET over the current
 * screen: the design's PaywallSheetKt is an M3 ModalBottomSheet (PaperWhite,
 * M3's 28dp top corners = Radii.Sheet), and RN presented the paywall route as
 * a transparent modal sliding up from the bottom.
 *
 * Use it as the content of a `dialog<Route.X>` destination (AppNavHost); the
 * sheet's own window draws M3's scrim, drag-to-dismiss and back handling, so
 * the hosting dialog window is told not to dim as well. [onDismiss] must pop
 * the destination. To close it from inside (after a purchase), hide [sheetState]
 * first, then call [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetDestination(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val hostWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        hostWindow?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        hostWindow?.setDimAmount(0f)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PaperWhite,
        content = content,
    )
}
