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
    LaunchedEffect(m.tmdbKey) { com.lumo.tv.data.TmdbArt.key = m.tmdbKey }
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
                Screen.PinCheck -> PinCheckScreen(m)
                is Screen.Remote -> RemoteScreen(m, s.deviceId, s.name)
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

            m.removeAsk?.let { p ->
                AlertDialog(
                    onDismissRequest = { m.removeAsk = null },
                    containerColor = C.Card,
                    title = { Text("Reprendre la lecture", color = Color.White) },
                    text = { Text("Retirer « ${p.title} » de la liste ?", color = C.Sub) },
                    confirmButton = { TextButton(onClick = { m.removeFromContinue(p); m.removeAsk = null }) { Text("Retirer", color = C.Red) } },
                    dismissButton = { TextButton(onClick = { m.removeAsk = null }) { Text("Annuler", color = C.Sub) } }
                )
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
    } else if (isPhoneLandscape(false)) {
        // Téléphone à l'horizontale : onglets en haut (comme sur la TV), pas de barre en bas
        Column(Modifier.fillMaxSize()) {
            PhoneLandscapeTopBar(m)
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
        val fr = rememberAutoFocus(true)
        listOf(Tab.Home, Tab.Live, Tab.Movies, Tab.Series, Tab.MyList).forEach { t ->
            FocusBox(shape = RoundedCornerShape(50), scale = 1.06f, requester = if (t == m.tab) fr else null, onClick = { m.tab = t }) { f ->
                TabChip(t, selected = m.tab == t, focused = f)
            }
        }
        Spacer(Modifier.weight(1f))
        IconFocus(Icons.Default.Search, "Rechercher") { m.go(Screen.Search) }
        IconFocus(Icons.Default.Settings, "Réglages") { m.tab = Tab.Settings }
        ProfileBadge(m) { m.openProfiles() }
    }
}

@Composable
private fun PhoneLandscapeTopBar(m: AppModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        LumoLogo(24)
        Spacer(Modifier.width(14.dp))
        listOf(Tab.Home, Tab.Live, Tab.Movies, Tab.Series, Tab.MyList).forEach { t ->
            FocusBox(shape = RoundedCornerShape(50), scale = 1.03f, onClick = { m.tab = t }) { f ->
                TabChip(t, selected = m.tab == t, focused = f, small = true)
            }
        }
        Spacer(Modifier.weight(1f))
        IconFocus(Icons.Default.Search, "Rechercher") { m.go(Screen.Search) }
        IconFocus(Icons.Default.Settings, "Réglages") { m.tab = Tab.Settings }
        ProfileBadge(m) { m.openProfiles() }
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
        ProfileBadge(m) { m.openProfiles() }
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
    FocusBox(shape = androidx.compose.foundation.shape.CircleShape, scale = 1.1f, onClick = onClick) { _ ->
        ProfileAvatar(p.name, p.color, p.photo, 34.dp)
    }
}


private fun tabIcon(t: Tab) = when (t) {
    Tab.Home -> Icons.Default.Home
    Tab.Live -> Icons.Default.PlayArrow
    Tab.Movies -> Icons.Default.Star
    Tab.Series -> Icons.Default.List
    Tab.MyList -> Icons.Default.Favorite
    Tab.Settings -> Icons.Default.Settings
}

/** Onglet avec icône + nom (en pastille quand il est choisi). */
@Composable
private fun TabChip(t: Tab, selected: Boolean, focused: Boolean, small: Boolean = false) {
    val bg = when { focused -> Color.White; selected -> Color(0x33FFFFFF); else -> Color.Transparent }
    val fg = when { focused -> Color.Black; selected -> Color.White; else -> C.Sub }
    Row(
        Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = if (small) 10.dp else 14.dp, vertical = if (small) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(tabIcon(t), null, tint = fg, modifier = Modifier.size(if (small) 18.dp else 22.dp))
        Text(if (t == Tab.Live) "Direct" else t.label, color = fg, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal, fontSize = if (small) 14.sp else 16.sp)
    }
}
