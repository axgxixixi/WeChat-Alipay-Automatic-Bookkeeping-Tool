package com.example.myapplication.ui.navigation

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.example.myapplication.R

enum class BottomTab(
    val label: String,
    val iconResId: Int
) {
    HOME("首页", R.drawable.tab_home),
    RECORDS("记录", R.drawable.tab_records),
    PROFILE("我的", R.drawable.tab_profile)
}

@Composable
fun BottomNavBar(
    currentTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit
) {
    NavigationBar {
        BottomTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        painter = painterResource(id = tab.iconResId),
                        contentDescription = tab.label,
                        modifier = Modifier.size(24.dp)
                    )
                },
                label = { Text(tab.label) }
            )
        }
    }
}