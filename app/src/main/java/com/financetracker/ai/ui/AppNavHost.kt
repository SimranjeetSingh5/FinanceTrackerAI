package com.financetracker.ai.ui

import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.financetracker.ai.ui.components.BottomBarItem
import com.financetracker.ai.ui.components.FinanceBottomBar
import com.financetracker.ai.ui.components.LocalCurrencyCode
import com.financetracker.ai.ui.screens.*
import com.financetracker.ai.viewmodel.*

private sealed class Dest(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Dest("dashboard", "Home", Icons.Filled.Home)
    object Budgets : Dest("budgets", "Budgets", Icons.Filled.PieChart)
    object Add : Dest("add", "Add", Icons.Filled.Add)
    object Analytics : Dest("analytics", "Analytics", Icons.Filled.Insights)
    object More : Dest("more", "More", Icons.Filled.MoreHoriz)
}

/** More-hub routes, named so the "Ask AI" navigation can't drift from the graph. */
const val CHAT_ROUTE = "chat"
const val TRANSACTIONS_ROUTE = "transactions"
const val IMPORT_ROUTE = "import_statement"
const val MARKETS_ROUTE = "markets"

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

    // Provided once so every screen formats money with the user's chosen currency.
    val currencyCode by financeViewModel.currency.collectAsState()

    // A pending question tapped on an "Ask AI" chip: navigates to chat, seeds the input, and
    // clears itself so a back-navigation doesn't re-seed a stale question.
    var pendingQuestion by rememberSaveable { mutableStateOf<String?>(null) }
    val openChatWith: (String) -> Unit = { question ->
        pendingQuestion = question
        navController.navigate(CHAT_ROUTE) { launchSingleTop = true }
    }
    val isModelReady = modelState is ModelState.Ready

    val bottomItems = listOf(
        BottomBarItem("dashboard", "Home", Icons.Filled.Home, Icons.Outlined.Home),
        BottomBarItem("budgets", "Budgets", Icons.Filled.PieChart, Icons.Outlined.PieChart),
        BottomBarItem("add", "Add transaction", Icons.Filled.Add, Icons.Filled.Add),
        BottomBarItem("analytics", "Analytics", Icons.Filled.Insights, Icons.Outlined.Insights),
        BottomBarItem("more", "More", Icons.Filled.MoreHoriz, Icons.Outlined.MoreHoriz)
    )

    Scaffold(
        bottomBar = {
            FinanceBottomBar(
                navController = navController,
                items = bottomItems
            )
        }
    ) { padding ->
        CompositionLocalProvider(LocalCurrencyCode provides currencyCode) {
            NavHost(
                navController = navController,
                startDestination = Dest.Dashboard.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Dest.Dashboard.route) {
                    DashboardScreen(financeViewModel, onAsk = openChatWith)
                }
                composable(Dest.Budgets.route) {
                    BudgetsScreen(
                        financeViewModel,
                        budgetsViewModel,
                        isModelReady = isModelReady,
                        onAsk = openChatWith
                    )
                }
                composable(Dest.Add.route) {
                    AddTransactionScreen(
                        viewModel = financeViewModel,
                        onDone = { navController.navigate(Dest.Dashboard.route) },
                        onImportStatement = { navController.navigate(IMPORT_ROUTE) }
                    )
                }
                composable(Dest.Analytics.route) {
                    AnalyticsScreen(analyticsViewModel, isModelReady = isModelReady, onAsk = openChatWith)
                }
                composable(Dest.More.route) { MoreScreen(onNavigate = { route -> navController.navigate(route) }) }

                // Screens reached from the More hub
                composable(TRANSACTIONS_ROUTE) {
                    TransactionsScreen(
                        isModelReady = isModelReady,
                        onAsk = openChatWith,
                        onImportStatement = { navController.navigate(IMPORT_ROUTE) },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(IMPORT_ROUTE) {
                    ImportStatementScreen(
                        financeViewModel = financeViewModel,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("accounts") {
                    AccountsScreen(
                        financeViewModel,
                        isModelReady = isModelReady,
                        onAsk = openChatWith,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("recurring") {
                    RecurringScreen(
                        financeViewModel,
                        recurringViewModel,
                        isModelReady = isModelReady,
                        onAsk = openChatWith,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable("goals") {
                    GoalsScreen(
                        goalsViewModel,
                        isModelReady = isModelReady,
                        onAsk = openChatWith,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(CHAT_ROUTE) {
                    ChatScreen(
                        chatViewModel,
                        onBack = { navController.popBackStack() },
                        initialQuestion = pendingQuestion,
                        onInitialQuestionConsumed = { pendingQuestion = null }
                    )
                }
                composable(MARKETS_ROUTE) {
                    MarketScreen(onBack = { navController.popBackStack() })
                }
                composable("setup") { ModelSetupScreen(financeViewModel, onBack = { navController.popBackStack() }) }
                composable("settings") { SettingsScreen({ navController.popBackStack() }, settingsViewModel) }
            }
        }
    }
}
