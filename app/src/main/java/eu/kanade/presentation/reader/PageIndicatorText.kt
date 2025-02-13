package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalTextApi::class)
@Composable
fun PageIndicatorText(
    // SY -->
    currentPage: String,
    // SY <--
    totalPages: Int,
) {
    if (currentPage.isEmpty() || totalPages <= 0) return

    val text = "$currentPage / $totalPages"

    Box {
        Text(
            text = text,
            color = Color(45, 45, 45),
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            style = TextStyle.Default.copy(
                drawStyle = Stroke(width = 4f),
            ),
        )

        Text(
            text = text,
            color = Color(235, 235, 235),
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}
