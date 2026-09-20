package app.nester.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ChipState { Selected, Neutral, Progress, Error }

@Composable
fun StatusChip(
    label: String,
    state: ChipState,
    leading: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    val (bg, fg) = when (state) {
        ChipState.Selected -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        ChipState.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
        ChipState.Progress -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ChipState.Error -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(
        color = bg,
        contentColor = fg,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 10.dp),
        ) {
            leading?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(14.dp),
                )
            }
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun LeadingIconCircle(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
fun NesterListItem(
    title: String,
    supporting: String? = null,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        leading()
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

/** M3 Expressive stacked list: 3dp gaps, 28dp outer corners, 8dp inner corners. */
@Composable
fun StackedList(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
        modifier = modifier,
    ) {
        content()
    }
}

@Composable
fun StackedItem(
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(8.dp),
        onClick = { onClick?.invoke() },
        enabled = onClick != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        content()
    }
}

@Composable
fun StackedListOuter(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(3.dp),
        ) {
            content()
        }
    }
}

@Composable
fun EmptyPane(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Small neutral pane used for empty tabs/lists inside a bounded area. */
@Composable
fun EmptyPaneBounded(icon: ImageVector, title: String, subtitle: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 240.dp)
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        subtitle?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

fun formatCompact(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000f)
    count >= 1_000 -> "%.1fk".format(count / 1_000f)
    else -> count.toString()
}

@Composable
fun Modifier.pressScale(interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return this.then(
        Modifier.scale(scale),
    )
}
