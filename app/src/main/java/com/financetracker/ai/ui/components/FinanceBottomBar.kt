package com.financetracker.ai.ui.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * A pill-shaped bottom bar with a sliding white indicator — the same visual language as the
 * Growise nav, rebuilt on this app's own navigation graph and theme.
 *
 * Two things differ from that original, both deliberate:
 *
 *  - **It uses Material icons rather than drawable resources.** The original loads ten vector
 *    assets (`ic_home`, `ic_home_filled`, …); this app ships none, and `material-icons-extended`
 *    is already a dependency, so the filled/outlined pairs come from there instead.
 *  - **The selected icon is tinted with [MaterialTheme.colorScheme.onSurface] rather than a
 *    hardcoded near-black.** The original hardcodes `Color(0xFF1C1C1C)`, which happens to work on
 *    a light theme and disappear on a dark one.
 */
@Composable
fun FinanceBottomBar(
    navController: NavHostController,
    items: List<BottomBarItem>,
    modifier: Modifier = Modifier
) {
    // Entering animation, mirroring the original's expand-in.
    var appeared by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { appeared = true }

    val entranceProgress by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 350, easing = FastOutSlowInEasing),
        label = "bottomBarEntrance"
    )

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val selectedIndex = items.indexOfFirst { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }.takeIf { it >= 0 } ?: 0

    PillNavigationBar(
        selectedIndex = selectedIndex,
        items = items,
        entranceProgress = entranceProgress,
        onSelect = { index ->
            val item = items[index]
            navController.navigate(item.route) {
                // Standard bottom-bar behaviour: one entry per tab on the back stack, and
                // each tab's scroll position and state is preserved when switching away.
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        modifier = modifier
    )
}

data class BottomBarItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@Composable
private fun PillNavigationBar(
    selectedIndex: Int,
    items: List<BottomBarItem>,
    entranceProgress: Float,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    var barWidthPx by remember { mutableFloatStateOf(0f) }
    val tabWidthDp = if (barWidthPx > 0f) {
        with(density) { (barWidthPx / items.size).toDp() }
    } else {
        0.dp
    }

    // The indicator springs between tabs. Overshoot is damped rather than fully bouncy: with
    // five tabs a strong overshoot makes the pill visibly clip the bar's rounded ends mid-flight.
    val indicatorOffset by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "indicatorOffset"
    )

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Horizontal inset keeps the pill clear of the screen edges; the navigation-bar
                // inset at the bottom keeps it clear of the gesture bar / 3-button nav, which
                // matters because this app draws edge to edge.
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp, vertical = 10.dp)
                .scale(lerp(0.9f, 1f, entranceProgress))
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                // Inner hairline: the single detail that reads as "glass" rather than as a
                // translucent bar. Drawn as a border so it follows the pill's rounded shape.
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            Color.White.copy(alpha = 0.12f)
                        )
                    ),
                    shape = RoundedCornerShape(percent = 50)
                )
                .padding(4.dp)
                .onGloballyPositioned { coordinates ->
                    barWidthPx = coordinates.size.width.toFloat()
                }
                .height(60.dp)
        ) {
            if (tabWidthDp > 0.dp) {
                Box(
                    modifier = Modifier
                        .offset(x = tabWidthDp * indicatorOffset)
                        .width(tabWidthDp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
                )
            }

            // Equal-width columns rather than Arrangement.SpaceEvenly. SpaceEvenly distributes
            // the *gaps* evenly, which leaves the first and last icons inset from the ends of
            // the bar and misaligned with the indicator when they're selected. A column per tab
            // puts every icon at the centre of its own segment, which is exactly where the
            // pill lands.
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                items.forEachIndexed { index, item ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        BottomBarTab(
                            item = item,
                            selected = index == selectedIndex,
                            opacity = staggeredOpacity(index, items.size, entranceProgress),
                            onClick = { onSelect(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBarTab(
    item: BottomBarItem,
    selected: Boolean,
    opacity: Float,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(
                onClick = onClick,
                role = Role.Tab,
                // The pill is the selection affordance; the ripple would only muddy it.
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
            contentDescription = item.label,
            tint = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f * opacity)
            },
            modifier = Modifier.size(22.dp)
        )
    }
}

/** Items fade in one after another, as in the original. */
private fun staggeredOpacity(index: Int, count: Int, progress: Float): Float {
    val start = index.toFloat() / (count + 1) * 0.5f
    val span = 0.5f
    val local = ((progress - start) / span).coerceIn(0f, 1f)
    return LinearEasing.transform(0.2f, 1f, local)
}

private fun Easing.transform(start: Float, end: Float, fraction: Float): Float =
    lerp(start, end, this.transform(fraction))
