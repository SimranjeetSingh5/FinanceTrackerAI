package com.financetracker.ai.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financetracker.ai.ui.screens.*
import com.financetracker.ai.viewmodel.*

private sealed class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Dest("dashboard", "Home", Icons.Filled.Home)
    object Budgets : Dest("budgets", "Budgets", Icons.Filled.PieChart)
    object Add : Dest("add", "Add", Icons.Filled.Add)
    object Analytics : Dest("analytics", "Analytics", Icons.Filled.Insights)
    object More : Dest("more", "More", Icons.Filled.MoreHoriz)
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    val financeViewModel: FinanceViewModel = viewModel()
    val chatViewModel: ChatViewModel = viewModel()
    val budgetsViewModel: BudgetsViewModel = viewModel()
    val goalsViewModel: GoalsViewModel = viewModel()
    val recurringViewModel: RecurringViewModel = viewModel()
    val analyticsViewModel: AnalyticsViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val modelState by financeViewModel.modelState.collectAsState()

    val bottomItems = listOf(Dest.Dashboard, Dest.Budgets, Dest.Add, Dest.Analytics, Dest.More)

    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = backStackEntry?.destination
                bottomItems.forEach { dest ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            if (dest == Dest.More && modelState !is ModelState.Ready) {
                                BadgedBox(badge = { Badge() }) { Icon(dest.icon, contentDescription = dest.label) }
                            } else {
                                Icon(dest.icon, contentDescription = dest.label)
                            }
                        },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Dashboard.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Dashboard.route) { DashboardScreen(financeViewModel) }
            composable(Dest.Budgets.route) { BudgetsScreen(financeViewModel, budgetsViewModel) }
            composable(Dest.Add.route) {
                AddTransactionScreen(financeViewModel) { navController.navigate(Dest.Dashboard.route) }
            }
            composable(Dest.Analytics.route) { AnalyticsScreen(analyticsViewModel) }
            composable(Dest.More.route) { MoreScreen(onNavigate = { route -> navController.navigate(route) }) }

            // Screens reached from the More hub
            composable("accounts") { AccountsScreen(financeViewModel) }
            composable("recurring") { RecurringScreen(financeViewModel, recurringViewModel) }
            composable("goals") { GoalsScreen(goalsViewModel) }
            composable("chat") { ChatScreen(chatViewModel) }
            composable("setup") { ModelSetupScreen(financeViewModel) }
            composable("settings") { SettingsScreen({navController.popBackStack()}, settingsViewModel) }
        }
    }
}
