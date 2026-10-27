package com.tervyn.vpn.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tervyn.vpn.core.AppState
import com.tervyn.vpn.core.Core
import com.tervyn.vpn.core.PRESETS
import com.tervyn.vpn.core.Prefs
import com.tervyn.vpn.core.normalizeSite

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SitesScreen(st: AppState) {
    val ctx = LocalContext.current
    var input by rememberSaveable { mutableStateOf("") }
    val prefs = st.prefs

    fun add() {
        val list = input.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (list.isEmpty()) return
        if (list.none { normalizeSite(it).isNotEmpty() }) {
            Toast.makeText(ctx, "That doesn't look like a website address. Example: youtube.com", Toast.LENGTH_LONG).show()
            return
        }
        Core.addSites(list)
        input = ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Muted("In \"Only my sites\" mode, just these sites go through the VPN — in every app and browser. Everything else uses your normal connection. Subdomains are included.")
        if (!prefs.targeted) {
            VSpace(10)
            Card(onClick = { Core.setMode(Prefs.MODE_TARGETED) }) {
                Text("You're in \"All apps\" mode, so everything is protected. Tap to switch to \"Only my sites\".", color = C.accentLight, fontSize = 13.sp)
            }
        }
        VSpace(12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Field(
                input, { input = it }, "example.com", Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
            )
            PrimaryButton("Add", enabled = input.isNotBlank()) { add() }
        }
        VSpace(6)
        Muted("Tip: in any browser or app, tap Share → Tervyn VPN to add that site.")

        SectionLabel("Quick add")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (p in PRESETS) {
                val added = p.domains.all { it in prefs.sites }
                Chip(p.name, added) { if (added) Core.removeSites(p.domains) else Core.addSites(p.domains) }
            }
        }

        SectionLabel("Your sites" + if (prefs.sites.isNotEmpty()) " (${prefs.sites.size})" else "")
        if (prefs.sites.isEmpty()) Muted("No sites yet. Add one above or use Quick add.")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (d in prefs.sites) SiteRow(d) { Core.removeSites(listOf(d)) }
        }
        VSpace(20)
    }
}
