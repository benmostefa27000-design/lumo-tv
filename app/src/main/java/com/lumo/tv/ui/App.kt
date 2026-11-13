package com.lumo.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumo.tv.AppModel
import com.lumo.tv.Screen
import com.lumo.tv.Tab
import com.lumo.tv.player.PlayerScreen

@Composable
fun LumoApp(m: AppModel) {
    LumoTheme {
        Box(Modifier.fillMaxSize().background(C.Bg)) {
            BackHandler(enabled = m.stack.size > 1 || (m.current == Screen.Main && m.tab != Tab.Home)) {
                if (!m.back() && m.current == Screen.Main) m.tab = Tab.Home
            }
            when (val s = m.current) {
                Screen.Login -> LoginScreen(m)
                Screen.Profiles -> ProfilesScreen(m)
                is Screen.ProfileEdit -> ProfileEditScreen(m, s.profile)
                Screen.Main -> MainScreen(m)
                is Screen.MovieDetail -> MovieDetailScreen(m, s.vod)
                is Screen.SeriesDetail -> SeriesDetailScreen(m, s.series)
                is Screen.ChannelGuide -> ChannelGuideScreen(m, s.stream, s.list)
                is Screen.Player -> PlayerScreen(m, s.item)
                Screen.Search -> SearchScreen(m)
                Screen.Categories -> CategoriesScreen(m)
                Screen.DeletedCategories -> DeletedCategoriesScreen(m)
                Screen.PairPhone -> PairPhoneScreen(m)
            }

            m.toast?.let { t ->
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        t,
                        color = Color.White,
                        modifier = Modifier.background(Color(0xEE2A2A2A), RoundedCornerShape(8.dp)).padding(horizontal = 18.dp, vertical = 14.dp)
                    )
                }
            }

            val up = m.update
            if (up != null && m.current !is Screen.Player) {
                AlertDialog(
                    onDismissRequest = { m.update = null },
                    containerColor = C.Card,
                    title = { Text("Nouvelle version disponible", color = Color.White) },
                    text = { Text(m.updateProgress ?: "Lumo version 1.0.${up.version} est prête. L'installer maintenant ?", color = C.Sub) },
                    confirmButton = { TextButton(onClick = { m.installUpdate(up) }) { Text("Installer", color = C.Red) } },
                    dismissButton = { TextButton(onClick = { m.update = null }) { Text("Plus tard", color = C.Sub) } }
                )
            }
        }
    }
}

@Composable
fun MainScreen(m: AppModel) {
    if (m.isTv) {
        Column(Modifier.fillMaxSize()) {
            TvTopBar(m)
            Box(Modifier.weight(1f)) { TabContent(m) }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            PhoneTopBar(m)
            Box(Modifier.weight(1f)) { TabContent(m) }
            PhoneBottomBar(m)
        }
    }
}

@Composable
private fun TabContent(m: AppModel) {
    CompositionLocalProvider(LocalPad provides if (m.isTv) 48.dp else 16.dp) { TabBody(m) }
}

@Composable
private fun TabBody(m: AppModel) {
    when (m.tab) {
        Tab.Home -> HomeScreen(m)
        Tab.Live -> LiveScreen(m)
        Tab.Movies -> VodBrowser(m, series = false)
        Tab.Series -> VodBrowser(m, series = true)
        Tab.MyList -> MyListScreen(m)
        Tab.Settings -> SettingsScreen(m)
    }
}

@Composable
private fun TvTopBar(m: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 48.dp, end = 48.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LumoLogo(34)
        Spacer(Modifier.width(18.dp))
        listOf(Tab.Home, Tab.Live, Tab.Movies, Tab.Series, Tab.MyList).forEach { t ->
            FocusBox(shape = RoundedCornerShape(6.dp), scale = 1.05f, onClick = { m.tab = t }) { f ->
                Text(
                    t.label,
                    color = if (m.tab == t || f) Color.White else C.Sub,
                    fontWeight = if (m.tab == t) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        IconFocus(Icons.Default.Search, "Rechercher") { m.go(Screen.Search) }
        IconFocus(Icons.Default.Settings, "Réglages") { m.tab = Tab.Settings }
        ProfileBadge(m) { m.reset(Screen.Profiles) }
    }
}

@Composable
private fun PhoneTopBar(m: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LumoLogo(28)
        Spacer(Modifier.weight(1f))
        IconFocus(Icons.Default.Search, "Rechercher") { m.go(Screen.Search) }
        ProfileBadge(m) { m.reset(Screen.Profiles) }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun PhoneBottomBar(m: AppModel) {
    val items = listOf(
        Triple(Tab.Home, Icons.Default.Home, "Accueil"),
        Triple(Tab.Live, Icons.Default.PlayArrow, "Direct"),
        Triple(Tab.Movies, Icons.Default.Star, "Films"),
        Triple(Tab.Series, Icons.Default.List, "Séries"),
        Triple(Tab.Settings, Icons.Default.Settings, "Réglages")
    )
    NavigationBar(containerColor = Color(0xFF0A0A0A), tonalElevation = 0.dp) {
        items.forEach { (t, icon, label) ->
            NavigationBarItem(
                selected = m.tab == t,
                onClick = { m.tab = t },
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White,
                    selectedTextColor = Color.White,
                    unselectedIconColor = C.Dim,
                    unselectedTextColor = C.Dim,
                    indicatorColor = Color(0xFF2A2A2A)
                )
            )
        }
    }
}

@Composable
fun IconFocus(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    FocusBox(shape = RoundedCornerShape(50), scale = 1.1f, onClick = onClick) { _ ->
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint)
        }
    }
}

@Composable
private fun ProfileBadge(m: AppModel, onClick: () -> Unit) {
    val p = m.profile ?: return
    FocusBox(shape = RoundedCornerShape(4.dp), scale = 1.1f, onClick = onClick) { _ ->
        Box(Modifier.size(32.dp).background(Color(p.color)), contentAlignment = Alignment.Center) {
            Text(p.name.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black)
        }
    }
}
