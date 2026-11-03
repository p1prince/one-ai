package com.oneai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Ink = Color(0xFF101114)
private val Panel = Color(0xFF1A1C20)
private val Gold = Color(0xFFD6AE58)
private val Muted = Color(0xFF9699A1)
private val White = Color(0xFFF5F3EE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { OneAiApp() }
    }
}

private enum class Destination(val label: String) {
    Home("Home"), Plugins("Plugins"), Develop("Develop"), Activity("Activity"), Settings("Settings")
}

@Composable
private fun OneAiApp() {
    var selected by remember { mutableStateOf(Destination.Home) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Gold, background = Ink, surface = Panel,
        onPrimary = Ink, onBackground = White, onSurface = White
    )) {
        Scaffold(
            containerColor = Ink,
            bottomBar = {
                NavigationBar(containerColor = Panel, contentColor = Gold) {
                    Destination.entries.forEach { destination ->
                        val icon = when (destination) {
                            Destination.Home -> Icons.Default.Home
                            Destination.Plugins -> Icons.Default.Extension
                            Destination.Develop -> Icons.Default.Code
                            Destination.Activity -> Icons.Default.History
                            Destination.Settings -> Icons.Default.Settings
                        }
                        NavigationBarItem(
                            selected = selected == destination,
                            onClick = { selected = destination },
                            icon = { Icon(icon, contentDescription = destination.label) },
                            label = { Text(destination.label, fontSize = 10.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Gold, selectedTextColor = Gold,
                                indicatorColor = Gold.copy(alpha = 0.14f),
                                unselectedIconColor = Muted, unselectedTextColor = Muted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                Destination.Home -> HomeScreen(Modifier.padding(padding), onPlugins = { selected = Destination.Plugins })
                Destination.Plugins -> SimpleScreen(Modifier.padding(padding), "Plugin Centre", "Your platform starts with a clean slate.", "No plugins installed yet.")
                Destination.Develop -> SimpleScreen(Modifier.padding(padding), "Developer Mode", "Build extensions for ONE AI.", "Developer tools will appear here.")
                Destination.Activity -> SimpleScreen(Modifier.padding(padding), "Activity", "Your ONE AI activity will appear here.", "No activity yet.")
                Destination.Settings -> SimpleScreen(Modifier.padding(padding), "Settings", "Manage your ONE AI experience.", "Core settings are coming in the next milestone.")
            }
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier, onPlugins: () -> Unit) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 26.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.size(42.dp).background(Gold, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                    Text("1", color = Ink, fontSize = 25.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("ONE AI", color = White, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text("YOUR INTELLIGENT PLATFORM", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
                }
                IconButton(onClick = {}) { Icon(Icons.Default.Settings, "Settings", tint = Muted) }
            }
        }
        item {
            Column {
                Text("Your Control Centre", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Text("One platform. Infinite possibilities.", color = Muted, fontSize = 14.sp)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.AutoAwesome, null, tint = Gold, modifier = Modifier.size(25.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Command ONE", color = White, fontWeight = FontWeight.SemiBold)
                    Text("Your AI command interface is being prepared.", color = Muted, fontSize = 12.sp)
                }
                Icon(Icons.Default.ArrowForward, null, tint = Gold)
            }
        }
        item { Text("PLATFORM MODULES", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ModuleCard(Icons.Default.Extension, "Plugin Centre", "Install and manage capabilities", onPlugins)
                ModuleCard(Icons.Default.BubbleChart, "Floating Bubble", "Independent quick-access interface") {}
                ModuleCard(Icons.Default.Code, "Developer Mode", "Create and test your own plugins") {}
                ModuleCard(Icons.Default.Tune, "Command Manager", "Organise commands and shortcuts") {}
            }
        }
        item { Text("SYSTEM STATUS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusCard("AI CORE", "Foundation", Modifier.weight(1f))
                StatusCard("EXTENSIONS", "None installed", Modifier.weight(1f))
                StatusCard("SECURITY", "Ready", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModuleCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).clickable(onClick = onClick).padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).background(Gold.copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Gold)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, null, tint = Muted)
    }
}

@Composable
private fun StatusCard(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(Panel, RoundedCornerShape(13.dp)).padding(11.dp)) {
        Text(title, color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(value, color = Gold, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SimpleScreen(modifier: Modifier, title: String, subtitle: String, empty: String) {
    Column(modifier.fillMaxSize().padding(22.dp)) {
        Text(title, color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = Muted)
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(18.dp)).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Layers, null, tint = Gold, modifier = Modifier.size(35.dp))
            Spacer(Modifier.height(12.dp))
            Text(empty, color = White, fontWeight = FontWeight.Medium)
        }
    }
}
