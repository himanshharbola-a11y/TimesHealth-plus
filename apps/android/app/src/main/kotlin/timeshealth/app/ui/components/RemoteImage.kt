package timeshealth.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import timeshealth.app.ui.theme.ImagePlaceholderGradient
import timeshealth.app.ui.theme.ImageScrimGradient
import timeshealth.app.ui.theme.PREVIEW_CANVAS_ARGB
import timeshealth.app.ui.theme.ThShapes
import timeshealth.app.ui.theme.TimesHealthTheme

/** ThCardImage's default placeholder: vertical PlumDeep → Carbon950. */
val ImagePlaceholderBrush: Brush = Brush.verticalGradient(ImagePlaceholderGradient)

/** ThCardImage's default scrim: vertical transparent → black 65%, for text over photos. */
val ImageScrimBrush: Brush = Brush.verticalGradient(ImageScrimGradient)

/**
 * A network image (Coil 3) over a placeholder.
 *
 * The [placeholder] paints first and stays visible while the image loads, when
 * [url] is null/blank, and when the load fails, so a card never shows a hole.
 * Size it with [modifier]; the image fills it ([contentScale], crop by default,
 * as every image in the design).
 *
 * Images come from the content CDN through Coil's own OkHttp client (no auth
 * header, its own disk cache); http(s) only.
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    placeholder: Brush = ImagePlaceholderBrush,
) {
    Box(modifier.background(placeholder)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

/**
 * The design's card image: CommonComponentsKt.ThCardImage. A [RemoteImage]
 * with a [scrim] gradient on top, so white text laid over the bottom of a photo
 * stays readable. Clip it with the card's shape at the call site.
 */
@Composable
fun ThCardImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholder: Brush = ImagePlaceholderBrush,
    scrim: Brush = ImageScrimBrush,
) {
    Box(modifier) {
        RemoteImage(url, contentDescription, Modifier.fillMaxSize(), placeholder = placeholder)
        Box(Modifier.fillMaxSize().background(scrim))
    }
}

@Preview(showBackground = true, backgroundColor = PREVIEW_CANVAS_ARGB)
@Composable
private fun RemoteImagePreview() {
    TimesHealthTheme {
        Row(Modifier.padding(12.dp)) {
            ThCardImage(
                url = null,
                contentDescription = null,
                modifier = Modifier.size(160.dp, 100.dp).clip(ThShapes.Lg),
            )
        }
    }
}
