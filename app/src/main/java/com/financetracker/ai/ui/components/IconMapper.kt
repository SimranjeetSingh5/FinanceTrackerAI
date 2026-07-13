package com.financetracker.ai.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.financetracker.ai.data.AccountType

fun iconFor(key: String): ImageVector = when (key) {
    "restaurant" -> Icons.Filled.Restaurant
    "cart" -> Icons.Filled.ShoppingCart
    "car" -> Icons.Filled.DirectionsCar
    "home" -> Icons.Filled.Home
    "bolt" -> Icons.Filled.Bolt
    "movie" -> Icons.Filled.Movie
    "bag" -> Icons.Filled.ShoppingBag
    "health" -> Icons.Filled.LocalHospital
    "cash" -> Icons.Filled.AttachMoney
    "subscription" -> Icons.Filled.Autorenew
    "wallet" -> Icons.Filled.AccountBalanceWallet
    "flag" -> Icons.Filled.Flag
    "bank" -> Icons.Filled.AccountBalance
    "creditcard" -> Icons.Filled.CreditCard
    else -> Icons.Filled.MoreHoriz
}

fun iconForAccountType(type: AccountType): ImageVector = when (type) {
    AccountType.CHECKING -> Icons.Filled.AccountBalance
    AccountType.SAVINGS -> Icons.Filled.Savings
    AccountType.CREDIT_CARD -> Icons.Filled.CreditCard
    AccountType.CASH -> Icons.Filled.AccountBalanceWallet
    AccountType.INVESTMENT -> Icons.Filled.TrendingUp
}
