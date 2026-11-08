package com.oneai.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import androidx.lifecycle.lifecycleScope
import com.oneai.app.ai.AiProviderClient
import com.oneai.app.ai.AiConversation
import com.oneai.app.bubble.BubbleUiPolicy
import com.oneai.app.bubble.FloatingBubbleService
import com.oneai.app.commands.CommandRouter
import com.oneai.app.commands.CommandStatus
import com.oneai.app.core.*
import com.oneai.app.plugins.AccessOverview
import com.oneai.app.plugins.AccessOverviewData
import com.oneai.app.plugins.CatalogueListing
import com.oneai.app.plugins.CatalogueRefreshGate
import com.oneai.app.plugins.CatalogueSources
import com.oneai.app.plugins.CatalogueView
import com.oneai.app.plugins.CertificateFingerprint
import com.oneai.app.plugins.Compatibility
import com.oneai.app.plugins.DiscoveredPlugin
import com.oneai.app.plugins.ExternalPluginCenter
import com.oneai.app.plugins.ExternalStatus
import com.oneai.app.plugins.PluginCenterSnapshot
import com.oneai.app.plugins.PluginConfigureForm
import com.oneai.app.plugins.PluginActivityView
import com.oneai.app.plugins.PluginDetailsView
import com.oneai.app.plugins.PluginSettingType
import com.oneai.app.plugins.PluginSettingsSchema
import com.oneai.app.plugins.PluginUpdateCheckGate
import com.oneai.app.plugins.PluginCompatibility
import com.oneai.app.plugins.PluginManager
import com.oneai.app.security.CredentialStatus
import com.oneai.app.security.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Ink=Color(0xFF101114); private val Panel=Color(0xFF1A1C20); private val Gold=Color(0xFFD6AE58)
private val Muted=Color(0xFF9699A1); private val White=Color(0xFFF5F3EE)

class MainActivity : ComponentActivity() {
    private lateinit var store: OneAiStore
    private lateinit var plugins: PluginManager
    private lateinit var commandRouter: CommandRouter
    private val conversation = AiConversation()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val services=AppServices.get(application)
        store=services.store; plugins=services.plugins; commandRouter=services.commandRouter
        setContent { OneAiApp(store,plugins,services.center,services.vault,services.aiProvider,commandRouter,services.pluginRestoration,services.catalogueSources,services.catalogueRefreshGate,
            onBubbleToggle={ enabled -> toggleBubble(enabled) },
            onAsk={ prompt,done -> lifecycleScope.launch { runCatching { services.aiOrchestrator.respond(prompt, conversation).content }.onSuccess { done(it) }.onFailure { done("Error: ${it.message}") } } },
            onNewConversation={ conversation.clear() },
            onRunCommand={ id,done -> lifecycleScope.launch { services.pluginRestoration.awaitReady(); val r=services.commandExecutor.execute("core/run_saved", mapOf("id" to id)); done(if(r.status==CommandStatus.COMPLETED) r.content else "Error: ${r.error?.message ?: r.content}") } }
        ) }
    }
    private fun toggleBubble(enabled:Boolean) {
        store.setBubbleEnabled(enabled)
        if(enabled) {
            if(Build.VERSION.SDK_INT>=23 && !Settings.canDrawOverlays(this)) {
                store.setBubbleEnabled(false)
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                return
            }
            if(Build.VERSION.SDK_INT>=26) startForegroundService(Intent(this,FloatingBubbleService::class.java))
            else startService(Intent(this,FloatingBubbleService::class.java))
        } else stopService(Intent(this,FloatingBubbleService::class.java))
    }
}

/** Five permanent navigation destinations. [Commands] is a Home sub-screen (Command Manager), not a destination. */
internal enum class Page(val title:String) {
    Home("Home"), Plugins("Plugins"), Develop("Develop"), Commands("Command Manager"), Activity("Activity"), Settings("Settings");
    companion object { val destinations = listOf(Home, Plugins, Develop, Activity, Settings) }
}

@Composable
private fun OneAiApp(store:OneAiStore, manager:PluginManager, center:ExternalPluginCenter, vault:SecretVault, ai:AiProviderClient, commandRouter:CommandRouter, restoration:PluginRestorationGate,
                     catalogueSources:CatalogueSources, catalogueRefreshGate:CatalogueRefreshGate,
                     onBubbleToggle:(Boolean)->Unit, onAsk:(String,(String)->Unit)->Unit, onNewConversation:()->Unit,
                     onRunCommand:(String,(String)->Unit)->Unit) {
    var page by remember { mutableStateOf(Page.Home) }; var refresh by remember { mutableIntStateOf(0) }
    var chatResult by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var pluginRestored by remember { mutableStateOf(restoration.isReady) }
    LaunchedEffect(restoration) { restoration.awaitReady(); pluginRestored=true }
    val refreshNow: () -> Unit = { refresh++; Unit }
    MaterialTheme(colorScheme=darkColorScheme(primary=Gold,background=Ink,surface=Panel,onPrimary=Ink,onBackground=White,onSurface=White)) {
        Scaffold(containerColor=Ink, bottomBar={
            NavigationBar(containerColor=Panel) {
                Page.destinations.forEach { p ->
                    val selected = page==p || (p==Page.Home && page==Page.Commands)
                    NavigationBarItem(selected=selected,onClick={page=p;refresh++},icon={
                        Icon(when(p){Page.Home->Icons.Default.Home;Page.Plugins->Icons.Default.Extension;Page.Develop->Icons.Default.Code;Page.Activity->Icons.Default.History;else->Icons.Default.Settings},p.title)
                    },label={Text(p.title,fontSize=10.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Gold,selectedTextColor=Gold,indicatorColor=Gold.copy(alpha=.14f),unselectedIconColor=Muted,unselectedTextColor=Muted))
                }
            }
        }) { pad ->
            BackHandler(enabled=page==Page.Commands) { page=Page.Home }
            when(page) {
                Page.Home -> HomePage(Modifier.padding(pad),store,{ target -> page=target },chatResult,busy,{ prompt -> busy=true;onAsk(prompt){chatResult=it;busy=false} },{ onNewConversation();chatResult="" })
                Page.Plugins -> PluginsPage(Modifier.padding(pad),manager,center,restoration,catalogueSources,catalogueRefreshGate,{store.activityEntries()},refreshNow)
                Page.Develop -> DeveloperPage(Modifier.padding(pad),store,manager,pluginRestored,refreshNow)
                Page.Commands -> CommandsPage(Modifier.padding(pad),store,commandRouter,refreshNow,onRunCommand,{page=Page.Home})
                Page.Activity -> ActivityPage(Modifier.padding(pad),store,refresh)
                Page.Settings -> SettingsPage(Modifier.padding(pad),store,vault,{ center.bubbleBindings() },{ center.accessOverview() },pluginRestored,onBubbleToggle,refreshNow)
            }
        }
    }
}

@Composable private fun Header(title:String,subtitle:String) { Column(Modifier.padding(bottom=16.dp)) { Text(title,color=White,fontSize=27.sp,fontWeight=FontWeight.Bold);Text(subtitle,color=Muted,fontSize=13.sp) } }
@Composable private fun CardBox(content:@Composable ColumnScope.()->Unit) { Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(16.dp),content=content) }
@Composable private fun ActionRow(title:String,detail:String,action:@Composable RowScope.()->Unit) { Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){Text(title,color=White,fontWeight=FontWeight.SemiBold);Text(detail,color=Muted,fontSize=12.sp)};action() } }

@Composable private fun HomePage(modifier:Modifier,store:OneAiStore,go:(Page)->Unit,result:String,busy:Boolean,onAsk:(String)->Unit,onNewConversation:()->Unit) {
    var prompt by remember { mutableStateOf("") }
    val installed = store.plugins()
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=26.dp,bottom=28.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()){
            Box(Modifier.size(42.dp).background(Gold,RoundedCornerShape(13.dp)),contentAlignment=Alignment.Center){Text("1",color=Ink,fontSize=25.sp,fontWeight=FontWeight.Black)}
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)){Text("ONE AI",color=White,fontSize=20.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("YOUR INTELLIGENT PLATFORM",color=Muted,fontSize=9.sp,letterSpacing=1.5.sp)}
            IconButton(onClick={go(Page.Settings)}){Icon(Icons.Default.Settings,"Settings",tint=Muted)}
        } }
        item { Column { Text("Your Control Centre",color=White,fontSize=27.sp,fontWeight=FontWeight.Bold);Text("One platform. Infinite possibilities.",color=Muted,fontSize=14.sp) } }
        item { Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(18.dp)).padding(16.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically){
                Icon(Icons.Default.AutoAwesome,null,tint=Gold,modifier=Modifier.size(25.dp));Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)){Text("Command ONE",color=White,fontWeight=FontWeight.SemiBold);Text("Ask ONE AI to help with your next task.",color=Muted,fontSize=12.sp)}
                Icon(Icons.Default.ArrowForward,null,tint=Gold)
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("What would you like ONE AI to do?")},minLines=2)
            Spacer(Modifier.height(8.dp))
            Button(onClick={if(prompt.isNotBlank())onAsk(prompt)},enabled=prompt.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"Thinking…" else "Send command")}
            if(result.isNotBlank()){Spacer(Modifier.height(10.dp));Text(result,color=White,fontSize=13.sp);TextButton(onClick={prompt="";onNewConversation()}){Text("New conversation",color=Gold)}}
        } }
        item { Text("PLATFORM MODULES",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.4.sp) }
        item { Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            ModuleCard(Icons.Default.Extension,"Plugin Centre","Install and manage capabilities"){go(Page.Plugins)}
            ModuleCard(Icons.Default.BubbleChart,"Floating Bubble","Independent quick-access interface"){go(Page.Settings)}
            ModuleCard(Icons.Default.Code,"Developer Mode","Create and test your own plugins"){go(Page.Develop)}
            ModuleCard(Icons.Default.Tune,"Command Manager","Organise commands and shortcuts"){go(Page.Commands)}
        } }
        item { Text("SYSTEM STATUS",color=Muted,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.4.sp) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            StatusCard("AI CORE","Foundation",Modifier.weight(1f))
            StatusCard("EXTENSIONS",if(installed.isEmpty())"None installed" else "${installed.size} installed",Modifier.weight(1f))
            StatusCard("SECURITY","Ready",Modifier.weight(1f))
        } }
    }
}

@Composable private fun ModuleCard(icon:androidx.compose.ui.graphics.vector.ImageVector,title:String,subtitle:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(15.dp)).clickable(onClick=onClick).padding(15.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(Gold.copy(alpha=0.12f),RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Icon(icon,null,tint=Gold)}
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)){Text(title,color=White,fontWeight=FontWeight.SemiBold);Text(subtitle,color=Muted,fontSize=12.sp)}
        Icon(Icons.Default.ChevronRight,null,tint=Muted)
    }
}

@Composable private fun StatusCard(title:String,value:String,modifier:Modifier=Modifier) {
    Column(modifier.background(Panel,RoundedCornerShape(13.dp)).padding(11.dp)) {
        Text(title,color=Muted,fontSize=9.sp,fontWeight=FontWeight.Bold)
        Spacer(Modifier.height(7.dp))
        Text(value,color=Gold,fontSize=11.sp,fontWeight=FontWeight.SemiBold)
    }
}

@Composable private fun PluginsPage(modifier:Modifier,manager:PluginManager,center:ExternalPluginCenter,restoration:PluginRestorationGate,sources:CatalogueSources,refreshGate:CatalogueRefreshGate,activity:()->List<ActivityEntry>,refresh:()->Unit) {
    var tick by remember { mutableIntStateOf(0) }
    var operationMessage by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf(PluginCenterSnapshot.EMPTY) }
    var scanning by remember { mutableStateOf(true) }
    var restored by remember { mutableStateOf(restoration.isReady) }
    var reviewing by remember { mutableStateOf<DiscoveredPlugin?>(null) }
    var reviewingUpdate by remember { mutableStateOf<String?>(null) }
    var detailsFor by remember { mutableStateOf<String?>(null) }
    var configuring by remember { mutableStateOf<String?>(null) }
    val updateCheckGate = remember { PluginUpdateCheckGate() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Catalogue listings are read from storage and re-validated off the main thread; they never touch the network.
    var listings by remember { mutableStateOf<List<CatalogueListing>>(emptyList()) }
    var catalogueTick by remember { mutableIntStateOf(0) }
    var showSources by remember { mutableStateOf(false) }
    var refreshingCatalogue by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(catalogueTick) {
        listings=withContext(Dispatchers.IO) { runCatching { sources.listings() }.getOrDefault(emptyList()) }
    }
    // Screen redraws only refresh the snapshot; update checks are one-shot user actions.
    LaunchedEffect(tick) {
        scanning=true
        restoration.awaitReady()
        restored=true
        snapshot=withContext(Dispatchers.IO) {
            runCatching { center.snapshot() }.getOrDefault(PluginCenterSnapshot.EMPTY)
        }
        scanning=false
    }
    val installed=manager.installed(); val available=manager.catalogue.filterNot { t->installed.any{it.id==t.id} }
    val candidates=snapshot.candidates.filterNot { it.imported }
    val rows=remember(listings,snapshot) { CatalogueView.available(listings,snapshot.catalogueFacts,snapshot.enabledPluginIds) }
    // Only an explicit Refresh reaches the network, and only once per request.
    val refreshCatalogue:()->Unit={
        if(!refreshingCatalogue) {
            refreshGate.request()
            scope.launch {
                refreshingCatalogue=true
                val outcomes=withContext(Dispatchers.IO) {
                    if(refreshGate.consume()) runCatching{ sources.refreshAll() }.getOrNull() else null
                }
                catalogueTick++
                operationMessage=CatalogueView.refreshMessage(outcomes)
                refreshingCatalogue=false
            }
        }
    }
    val changed:(Result<*>,String)->Unit={ result,success -> result.onFailure { error -> operationMessage=error.message ?: "That change failed" }.onSuccess { operationMessage=success };tick++;refresh() }
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Header("Plugin Centre","Optional capabilities. Your shell stays independent.") }
                TextButton(onClick={
                    if (!scanning && restored) {
                        updateCheckGate.request()
                        scope.launch {
                            scanning=true
                            snapshot=withContext(Dispatchers.IO) {
                                runCatching {
                                    if (updateCheckGate.consume()) center.checkForUpdates()
                                    center.snapshot()
                                }.getOrDefault(PluginCenterSnapshot.EMPTY)
                            }
                            scanning=false
                        }
                    }
                },enabled=!scanning && restored) { Text("Check updates",color=Gold) }
            }
        }
        item { Text("INSTALLED",color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold) }
        if(installed.isEmpty()) item { CardBox { Text("No plugins installed",color=White,fontWeight=FontWeight.SemiBold);Text("ONE AI starts empty. Install only the capabilities you want.",color=Muted,fontSize=12.sp) } }
        items(installed,key={it.id}) { p ->
            val external=center.isExternal(p.id); val status=snapshot.statuses[p.id]
            val bubbleService=manager.bubbleServiceOf(p.id)
            val declared=snapshot.candidates.firstOrNull { it.plugin.packageName==p.id }?.plugin?.manifest?.getOrNull()
            CardBox {
                Text(p.name,color=White,fontWeight=FontWeight.SemiBold);Text(p.description,color=Muted,fontSize=12.sp)
                Text("v${p.version} • ${p.capabilities.joinToString()}"+(if(external) " • separate app" else ""),color=Gold,fontSize=10.sp)
                when(status) {
                    is ExternalStatus.UpdatePending -> Text("An update asks for more access. The plugin is off until you review it.",color=Color(0xFFFF8A80),fontSize=11.sp)
                    ExternalStatus.SignatureChanged -> Text("Signed with a different certificate than the one you trusted. Blocked.",color=Color(0xFFFF8A80),fontSize=11.sp)
                    ExternalStatus.NotInstalled -> Text("The plugin app is not installed.",color=Color(0xFFFF8A80),fontSize=11.sp)
                    else -> Unit
                }
                PluginConfigureForm.needsConfigurationNote(snapshot.needsConfiguration[p.id].orEmpty(),p.enabled)?.let { Text(it,color=Color(0xFFFFD27A),fontSize=11.sp) }
                Row(verticalAlignment=Alignment.CenterVertically){Text(if(p.enabled)"Enabled" else "Disabled",color=White,modifier=Modifier.weight(1f));Switch(p.enabled,{ changed(center.setEnabled(p.id,it),"") },enabled=restored)}
                if(bubbleService!=null) Row(verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){Text("Show in Floating Bubble",color=White);Text(if(p.enabled)"Separate from Enabled. Off until you allow it." else "Enable the plugin first.",color=Muted,fontSize=11.sp)}
                    Switch(manager.hasBubbleAccess(p.id),{ changed(manager.setBubbleAccess(p.id,it),"") },enabled=PluginStartupAccessPolicy.canChangeBubbleAccess(restored,p.enabled))
                } else if(external && declared?.bubble!=null && center.bubbleServiceConflict(declared)) Text("Its Bubble service name is already taken, so it cannot appear in the Bubble.",color=Muted,fontSize=11.sp)
                Row {
                    if(status is ExternalStatus.UpdatePending) TextButton(onClick={reviewingUpdate=p.id},enabled=restored){Text("Review update",color=Gold)}
                    if(snapshot.trustedEntries.containsKey(p.id)) TextButton(onClick={detailsFor=p.id}){Text("Details",color=Gold)}
                    if(snapshot.configurable.containsKey(p.id)) TextButton(onClick={configuring=p.id},enabled=restored){Text("Configure",color=Gold)}
                    TextButton(onClick={ changed(manager.remove(p.id),"") },enabled=restored){Text("Remove",color=Color(0xFFFF8A80))}
                }
            }
        }
        item { Row(verticalAlignment=Alignment.CenterVertically){Text("FOUND ON THIS DEVICE",color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));TextButton(onClick={tick++},enabled=restored){Text(if(scanning)"Scanning…" else "Scan again",color=Gold,fontSize=12.sp)}} }
        if(candidates.isEmpty()) item { CardBox { Text(if(scanning)"Looking for plugin apps…" else "No new plugin apps found",color=White,fontWeight=FontWeight.SemiBold);Text("Plugins are separate apps you install like any other app. ONE AI never installs or runs them for you, and trusts one only after you review it.",color=Muted,fontSize=12.sp) } }
        items(candidates,key={it.plugin.packageName}) { c ->
            val d=c.plugin; val m=d.manifest.getOrNull(); val compat=m?.let { PluginCompatibility.check(it) }
            CardBox {
                Text(m?.name ?: d.appLabel.ifBlank{d.packageName},color=White,fontWeight=FontWeight.SemiBold);Text(d.packageName,color=Muted,fontSize=11.sp)
                if(m==null) Text("Cannot be imported: ${d.manifest.exceptionOrNull()?.message ?: "invalid manifest"}",color=Color(0xFFFF8A80),fontSize=11.sp)
                else {
                    if(m.description.isNotBlank()) Text(m.description,color=Muted,fontSize=12.sp)
                    Text("v${m.versionName} • ${m.capabilities.joinToString()} • ${m.commands.size} command(s)",color=Gold,fontSize=10.sp)
                    if(m.bubble!=null) Text("Offers a Floating Bubble service. It stays off until you allow it.",color=Muted,fontSize=11.sp)
                    when(compat) { is Compatibility.Incompatible -> Text(compat.reason,color=Color(0xFFFF8A80),fontSize=11.sp); is Compatibility.CompatibleWithWarning -> Text(compat.warning,color=Muted,fontSize=11.sp); else -> Unit }
                    Button(onClick={reviewing=d},enabled=restored && compat?.usable==true,modifier=Modifier.fillMaxWidth()){Text("Review and import")}
                }
            }
        }
        item { Row(verticalAlignment=Alignment.CenterVertically){
            Text("CATALOGUE",color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f))
            TextButton(onClick={showSources=true}){Text("Sources",color=Gold,fontSize=12.sp)}
            TextButton(onClick=refreshCatalogue,enabled=!refreshingCatalogue && listings.isNotEmpty()){Text(if(refreshingCatalogue)"Refreshing…" else "Refresh",color=Gold,fontSize=12.sp)}
        } }
        if(listings.isEmpty()) item { CardBox { Text("No catalogue sources",color=White,fontWeight=FontWeight.SemiBold);Text("ONE AI ships with none. Add an https source to browse what plugin developers list. A listing never installs, trusts or turns on anything.",color=Muted,fontSize=12.sp) } }
        else if(rows.isEmpty()) item { CardBox { Text("Nothing listed yet",color=White,fontWeight=FontWeight.SemiBold);Text("Tap Refresh to load your sources.",color=Muted,fontSize=12.sp) } }
        items(rows,key={it.key}) { row ->
            val notes=CatalogueView.notes(row.status)
            CardBox {
                Text(CatalogueView.clean(row.entry.name,CatalogueView.MAX_SHOWN_NAME),color=White,fontWeight=FontWeight.SemiBold)
                Text("from ${row.sourceName}",color=Muted,fontSize=11.sp)
                if(row.entry.summary.isNotBlank()) Text(CatalogueView.clean(row.entry.summary,CatalogueView.MAX_SHOWN_SUMMARY),color=Muted,fontSize=12.sp)
                Text(CatalogueView.summaryLine(row.entry),color=Gold,fontSize=10.sp)
                Text(CatalogueView.stateLabel(row.status),color=White,fontSize=12.sp)
                notes.firstOrNull()?.let { Text(it,color=Color(0xFFFFD27A),fontSize=11.sp) }
                TextButton(onClick={viewing=row.key}){Text("Details",color=Gold)}
            }
        }
        item { Text("AVAILABLE CAPABILITIES",color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold) }
        items(available,key={it.id}) { t -> CardBox { Text(t.name,color=White,fontWeight=FontWeight.SemiBold);Text(t.description,color=Muted,fontSize=12.sp);Text(t.capabilities.joinToString(" • "),color=Gold,fontSize=10.sp);Button(onClick={ changed(manager.install(t),"Installed ${t.name}; it remains disabled.") },enabled=restored,modifier=Modifier.fillMaxWidth()){Text("Install (starts disabled)")}} }
        if(operationMessage.isNotBlank()) item { Text(operationMessage,color=Gold,fontSize=12.sp) }
    }
    reviewing?.let { d ->
        val m=d.manifest.getOrNull()
        // Shown to the user and passed back unchanged; the import itself compares it with the installed certificate again.
        val fingerprint=remember(d.packageName) { center.fingerprint(d.packageName) }
        AlertDialog(
            onDismissRequest={reviewing=null},
            title={Text("Trust ${m?.name ?: d.packageName}?")},
            text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("It will be able to run only the commands below, and every sensitive action still needs your approval each time.",fontSize=13.sp)
                if(m!=null) {
                    Text("Capabilities: ${m.capabilities.joinToString()}",fontSize=12.sp)
                    m.commands.forEach { Text("• ${it.title} (${it.capability})",fontSize=12.sp) }
                    Text(if(m.bubble!=null) "It can offer a Floating Bubble service, but only after you allow that separately." else "It does not offer a Floating Bubble service.",fontSize=12.sp)
                }
                fingerprint.fold(
                    { fp -> Text("Signing certificate (SHA-256):\n${CertificateFingerprint.display(fp)}",fontSize=11.sp,fontFamily=FontFamily.Monospace) },
                    { error -> Text(error.message ?: "The certificate could not be read.",color=Color(0xFFFF8A80),fontSize=12.sp) }
                )
                Text("Only continue if you installed this app from a developer you trust. ONE AI remembers this certificate and blocks the plugin if it is ever signed with a different one.",fontSize=12.sp)
            } },
            confirmButton={TextButton(enabled=restored && m!=null && fingerprint.isSuccess,onClick={
                val fp=fingerprint.getOrNull(); val manifest=d.manifest.getOrNull()
                reviewing=null
                if(fp!=null && manifest!=null) changed(center.import(d.packageName,manifest.versionCode,fp),"Imported ${manifest.name}. It is off, and has no Bubble access until you allow them.")
            }){Text("Trust and import",color=Gold)}},
            dismissButton={TextButton(onClick={reviewing=null}){Text("Cancel")}}
        )
    }
    if(showSources) SourcesDialog(sources,listings,onChanged={catalogueTick++},onClose={showSources=false})
    viewing?.let { key ->
        val row=rows.firstOrNull { it.key==key }
        // A listing that disappeared after a refresh or a removed source simply shows no dialog.
        if(row!=null) {
            val notes=CatalogueView.notes(row.status)
            val pageUrl=CatalogueView.openableUrl(row.entry)
            val importCandidate=CatalogueView.importable(row,snapshot.candidates)
            AlertDialog(
                onDismissRequest={viewing=null},
                title={Text(CatalogueView.clean(row.entry.name,CatalogueView.MAX_SHOWN_NAME))},
                text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Listed by ${row.sourceName}. Everything below is the source's claim, shown as plain text. A listing is not trust: only the app on your device, and the certificate you confirm, decide that.",fontSize=12.sp)
                    if(row.entry.summary.isNotBlank()) Text(CatalogueView.clean(row.entry.summary,CatalogueView.MAX_SHOWN_SUMMARY),fontSize=13.sp)
                    Text("Plugin ID: ${row.entry.id}",fontSize=12.sp,fontFamily=FontFamily.Monospace)
                    Text("Version ${CatalogueView.clean(row.entry.versionName,40)} (build ${row.entry.versionCode})",fontSize=12.sp)
                    Text("Needs plugin API ${row.entry.minCoreApi}, built for ${row.entry.targetCoreApi}",fontSize=12.sp)
                    if(row.entry.capabilities.isNotEmpty()) Text("Capabilities: ${row.entry.capabilities.joinToString()}",fontSize=12.sp)
                    if(row.entry.dependencies.isNotEmpty()) Text("Depends on: ${row.entry.dependencies.joinToString()}",fontSize=12.sp)
                    Text("Signing certificate(s) the source lists (SHA-256, not verified by ONE AI):",fontSize=12.sp)
                    row.entry.signerSha256.forEach { Text(CertificateFingerprint.display(it),fontSize=11.sp,fontFamily=FontFamily.Monospace) }
                    Text("On this device: ${CatalogueView.stateLabel(row.status)}",fontSize=12.sp)
                    notes.forEach { Text(it,color=Color(0xFFFF8A80),fontSize=12.sp) }
                    Text(if(pageUrl!=null) "Get opens ${row.entry.getUrl} in your browser or store. ONE AI never downloads or installs it. After you install it, it appears under Found on this device, and you review and trust it there." else "This listing has no page ONE AI can open.",fontSize=12.sp)
                } },
                confirmButton={ Row {
                    if(importCandidate!=null) TextButton(enabled=restored,onClick={ viewing=null; reviewing=importCandidate }){Text("Review and import",color=Gold)}
                    if(pageUrl!=null) TextButton(onClick={ viewing=null; if(!openListedPage(context,pageUrl)) operationMessage="No app could open that page." }){Text("Get",color=Gold)}
                } },
                dismissButton={TextButton(onClick={viewing=null}){Text("Close")}}
            )
        }
    }
    configuring?.let { id ->
        val schema=snapshot.configurable[id]
        // A plugin that stopped being configurable (for example after a signer change) simply shows no dialog.
        if(schema!=null) ConfigureDialog(
            title=manager.installed().firstOrNull { it.id==id }?.name ?: id,
            schema=schema,
            effective=remember(id) { center.effectiveSettings(id) },
            onSave={ values -> center.saveSettings(id,values).also { r -> r.onSuccess { operationMessage="Settings saved. Enabled state and Bubble access are unchanged." };tick++;refresh() } },
            onReset={ center.resetSettings(id).map { center.effectiveSettings(id) }.also { tick++;refresh() } },
            onClose={configuring=null}
        )
    }
    detailsFor?.let { id ->
        val trustedEntry=snapshot.trustedEntries[id]
        if(trustedEntry==null) { detailsFor=null } else {
            val details=PluginDetailsView.build(
                entry=trustedEntry,status=snapshot.statuses[id],
                enabled=installed.firstOrNull { it.id==id }?.enabled==true,
                bubbleAccess=manager.hasBubbleAccess(id),
                needsConfiguration=snapshot.needsConfiguration[id].orEmpty(),
                enabledPluginIds=snapshot.enabledPluginIds,
                recentActivity=PluginActivityView.forPlugin(activity(),id)
            )
            AlertDialog(
                onDismissRequest={detailsFor=null},
                title={Text(details.title)},
                text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    details.sections.forEach { section ->
                        Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(section.heading.uppercase(),color=Gold,fontSize=10.sp,fontWeight=FontWeight.Bold)
                            section.lines.forEach { Text(it,fontSize=12.sp) }
                        }
                    }
                } },
                confirmButton={TextButton(onClick={detailsFor=null}){Text("Close",color=Gold)}}
            )
        }
    }
    reviewingUpdate?.let { id ->
        val pending=snapshot.pendingUpdates[id]
        AlertDialog(
            onDismissRequest={reviewingUpdate=null},
            title={Text("Review update")},
            text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(if(pending!=null) "This update asks for more access than you approved:" else "This update is no longer waiting for approval.",fontSize=13.sp)
                pending?.reasons?.forEach { Text("• $it",fontSize=12.sp) }
                Text("After you approve it the plugin stays off until you turn it on again.",fontSize=12.sp)
            } },
            confirmButton={TextButton(enabled=restored && pending!=null,onClick={ reviewingUpdate=null; changed(center.approveUpdate(id),"Update approved. The plugin is still off.") }){Text("Approve update",color=Gold)}},
            dismissButton={TextButton(onClick={reviewingUpdate=null}){Text("Not now")}}
        )
    }
}

/** Opens a listed https page in the user's browser or store. Returns false when nothing could open it. */
private fun openListedPage(context:Context,url:String):Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch(e:Exception) { false }

/**
 * The user's catalogue sources (Stage 9.7d). Adding or removing a source never contacts it; only Refresh does. Storage
 * work runs off the main thread. Text from a source is cleaned by [CatalogueView] and drawn as plain text.
 */
@Composable private fun SourcesDialog(sources:CatalogueSources,listings:List<CatalogueListing>,onChanged:()->Unit,onClose:()->Unit) {
    var address by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    AlertDialog(
        onDismissRequest=onClose,
        title={Text("Catalogue sources")},
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("A source is an https address that lists plugins. ONE AI contacts it only when you tap Refresh, and a listing never installs, trusts or turns on a plugin.",fontSize=12.sp)
            if(listings.isEmpty()) Text("No sources added.",fontSize=12.sp)
            listings.forEach { listing ->
                val line=CatalogueView.sourceLine(listing)
                Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(line.title,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
                    Text(line.url,fontSize=11.sp,fontFamily=FontFamily.Monospace)
                    Text(line.detail,fontSize=11.sp,color=if(line.isError) Color(0xFFFF8A80) else Muted)
                    TextButton(onClick={ scope.launch { withContext(Dispatchers.IO){ sources.remove(line.url) }; message="Source removed. Installed plugins are not affected.";onChanged() } }){Text("Remove",color=Color(0xFFFF8A80))}
                }
            }
            OutlinedTextField(value=address,onValueChange={address=it},singleLine=true,label={Text("https://…")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())
            TextButton(enabled=address.isNotBlank(),onClick={
                val typed=address
                scope.launch {
                    val result=withContext(Dispatchers.IO){ sources.add(typed) }
                    result.onSuccess { address="" }
                    message=result.fold({ "Source added. Tap Refresh to load it." },{ CatalogueView.problem(it) })
                    onChanged()
                }
            }){Text("Add source",color=Gold)}
            if(message.isNotBlank()) Text(message,fontSize=12.sp)
        } },
        confirmButton={TextButton(onClick=onClose){Text("Done",color=Gold)}}
    )
}

/**
 * Host-rendered settings form (Stage 9.6d). Field labels and descriptions come from the plugin and are drawn as plain
 * text. Validation and the values to save come from [PluginConfigureForm]; saving never enables the plugin or grants
 * Bubble access.
 */
@Composable private fun ConfigureDialog(title:String,schema:PluginSettingsSchema,effective:Map<String,String>,onSave:(Map<String,String>)->Result<*>,onReset:()->Result<Map<String,String>>,onClose:()->Unit) {
    var baseline by remember { mutableStateOf(effective) }
    var draft by remember { mutableStateOf(PluginConfigureForm.initialDraft(schema,effective)) }
    var showErrors by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val errors=PluginConfigureForm.errors(schema,draft)
    AlertDialog(
        onDismissRequest=onClose,
        title={Text("Configure $title")},
        text={ Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("These settings are defined by the plugin and shown as plain text. Saving them does not turn the plugin on or give it Bubble access.",fontSize=12.sp)
            schema.settings.forEach { s ->
                val value=draft[s.key].orEmpty(); val error=if(showErrors) errors[s.key] else null
                val label=s.title+if(s.required) " *" else ""
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    when(s.type) {
                        PluginSettingType.TOGGLE -> Row(verticalAlignment=Alignment.CenterVertically){Text(label,modifier=Modifier.weight(1f));Switch(value=="true",{ draft=draft+(s.key to it.toString()) })}
                        PluginSettingType.TEXT -> OutlinedTextField(value,{ draft=draft+(s.key to it) },label={Text(label)},singleLine=true,isError=error!=null,modifier=Modifier.fillMaxWidth())
                        PluginSettingType.NUMBER -> OutlinedTextField(value,{ draft=draft+(s.key to it) },label={Text(label)},singleLine=true,isError=error!=null,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
                        PluginSettingType.CHOICE -> Column {
                            Text(label)
                            s.options.forEach { o -> Row(Modifier.fillMaxWidth().clickable { draft=draft+(s.key to o.value) },verticalAlignment=Alignment.CenterVertically){RadioButton(value==o.value,{ draft=draft+(s.key to o.value) });Text(o.label)} }
                        }
                    }
                    if(s.description.isNotBlank()) Text(s.description,color=Muted,fontSize=11.sp)
                    error?.let { Text(it,color=Color(0xFFFF8A80),fontSize=11.sp) }
                }
            }
            if(message.isNotBlank()) Text(message,color=Gold,fontSize=12.sp)
        } },
        confirmButton={TextButton(enabled=PluginConfigureForm.hasChanges(schema,baseline,draft),onClick={
            if(errors.isNotEmpty()) showErrors=true
            else onSave(PluginConfigureForm.valuesToSave(schema,draft)).onSuccess{onClose()}.onFailure{message=it.message ?: "Could not save these settings"}
        }){Text("Save",color=Gold)}},
        dismissButton={ Row {
            TextButton(onClick={ onReset().onSuccess { fresh -> baseline=fresh;draft=PluginConfigureForm.initialDraft(schema,fresh);showErrors=false;message="Back to the defaults." }.onFailure { message=it.message ?: "Could not reset" } }){Text("Reset to defaults")}
            TextButton(onClick=onClose){Text("Close")}
        } }
    )
}

@Composable private fun DeveloperPage(modifier:Modifier,store:OneAiStore,manager:PluginManager,restorationReady:Boolean,refresh:()->Unit) {
    var id by remember{mutableStateOf("")};var name by remember{mutableStateOf("")};var description by remember{mutableStateOf("")};var message by remember{mutableStateOf("")}
    var dev by remember{mutableStateOf(store.developerMode())}
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Header("Developer Mode","Create safe plugin descriptors for your own extensions.")
        CardBox {
            ActionRow("Developer Mode","Required before creating a plugin"){Switch(dev,{dev=it;store.setDeveloperMode(it)})}
            if(dev) {
                OutlinedTextField(id,{id=it},modifier=Modifier.fillMaxWidth(),label={Text("Plugin ID (dev.example.plugin)")})
                OutlinedTextField(name,{name=it},modifier=Modifier.fillMaxWidth(),label={Text("Plugin name")})
                OutlinedTextField(description,{description=it},modifier=Modifier.fillMaxWidth(),label={Text("Description")})
                Spacer(Modifier.height(8.dp))
                if(!restorationReady) Text("Plugin creation will unlock when startup restoration finishes.",color=Muted,fontSize=11.sp)
                Button(onClick={val r=manager.create(id,name,description,listOf("commands"));message=r.fold({"Created ${it.name}. It is disabled until you enable it."},{it.message?:"Could not create plugin"});refresh()},modifier=Modifier.fillMaxWidth(),enabled=PluginStartupAccessPolicy.canCreateDeveloperPlugin(restorationReady,dev,id,name)){Text("Create plugin descriptor")}
                Text("Generated plugins are declarative descriptors. ONE AI does not load arbitrary executable code into its core process.",color=Muted,fontSize=11.sp)
            } else Text("Turn on Developer Mode to access plugin creation tools.",color=Muted)
            if(message.isNotBlank())Text(message,color=Gold)
        }
    }
}

@Composable private fun CommandsPage(modifier:Modifier,store:OneAiStore,router:CommandRouter,refresh:()->Unit,onRun:(String,(String)->Unit)->Unit,onBack:()->Unit) {
    var title by remember{mutableStateOf("")};var prompt by remember{mutableStateOf("")};var msg by remember{mutableStateOf("")};var result by remember{mutableStateOf("")};var running by remember{mutableStateOf(false)}
    val commands=store.commands()
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null,tint=Gold);Spacer(Modifier.width(6.dp));Text("Home",color=Gold)} }
        item { Header("Command Manager","Build reusable instructions. Commands are not auto-enabled.") }
        item { CardBox { OutlinedTextField(title,{title=it},modifier=Modifier.fillMaxWidth(),label={Text("Command name")});OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth(),label={Text("Instruction")},minLines=2);Button(onClick={if(title.isNotBlank()&&prompt.isNotBlank()){router.add(title,prompt).onSuccess { title="";prompt="";msg="Command saved (disabled by default)";refresh() }.onFailure { msg=it.message ?: "Could not save command" }}},modifier=Modifier.fillMaxWidth()){Text("Save command")};if(msg.isNotBlank())Text(msg,color=Gold) } }
        if(commands.isEmpty()) item { CardBox { Text("No commands created yet.",color=White);Text("Create commands explicitly; installing a plugin does not add commands automatically.",color=Muted,fontSize=12.sp) } }
        items(commands,key={it.id}) { c -> CardBox { Text(c.title,color=White,fontWeight=FontWeight.SemiBold);Text(c.prompt,color=Muted,fontSize=12.sp);Row(verticalAlignment=Alignment.CenterVertically){Text(if(c.enabled)"Enabled" else "Disabled",color=Gold,modifier=Modifier.weight(1f));Switch(c.enabled,{router.setEnabled(c.id,it);refresh()});TextButton(onClick={router.remove(c.id);refresh()}){Text("Delete",color=Color(0xFFFF8A80))}};Button(onClick={running=true;onRun(c.id){result=it;running=false}},enabled=c.enabled&&!running,modifier=Modifier.fillMaxWidth()){Text(if(running)"Running…" else "Run with AI")};if(result.isNotBlank())Text(result,color=White,fontSize=12.sp) } }
    }
}

@Composable private fun ActivityPage(modifier:Modifier,store:OneAiStore,refresh:Int) {
    val entries=store.activityEntries()
    var confirmClear by remember { mutableStateOf(false) }
    // Stage 10, slice 10.3: display-only filter; not persisted, never deletes or changes stored entries.
    var filter by remember { mutableStateOf(ActivityFilter.ALL) }
    val shown=ActivityFilter.apply(entries,filter)
    LazyColumn(modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Header("Activity","A local record of recent ONE AI actions and outcomes.") }
                TextButton(onClick={confirmClear=true},enabled=entries.isNotEmpty()) { Text("Clear history",color=Color(0xFFFF8A80)) }
            }
            Text(if(filter==ActivityFilter.ALL) "${entries.size} of 150 recent events • Stored on this device" else "${shown.size} of ${entries.size} events shown • Stored on this device",color=Muted,fontSize=11.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                ActivityFilter.values().forEach { option ->
                    val selected=option==filter
                    Text(option.label,color=if(selected) Color.Black else White,fontSize=12.sp,
                        modifier=Modifier.background(if(selected) Gold else Panel,RoundedCornerShape(50)).clickable{filter=option}.padding(horizontal=14.dp,vertical=8.dp))
                }
            }
        }
        if(entries.isEmpty()) item { CardBox { Text("Nothing here yet.",color=White);Text("AI requests, command runs and security decisions will appear here.",color=Muted,fontSize=12.sp) } }
        else if(shown.isEmpty()) item { CardBox { Text("No ${filter.label} events.",color=White);Text("Nothing is hidden or deleted. Choose All to see every event.",color=Muted,fontSize=12.sp) } }
        items(shown,key={"${it.timestamp}:${it.message}"}) {
            CardBox {
                Text(if(it.timestamp>0) DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(it.timestamp)) else "Earlier activity",color=Gold,fontSize=10.sp)
                Spacer(Modifier.height(4.dp))
                // Stage 10, A2: log lines can contain plugin-supplied text (names, error messages), so strip hidden and reordering characters first.
                Text(CatalogueView.clean(it.message,ActivityLog.DISPLAY_MAX),color=White,fontSize=13.sp)
            }
        }
    }
    if(confirmClear) AlertDialog(
        onDismissRequest={confirmClear=false},
        title={Text("Clear activity history?")},
        text={Text("This permanently removes the locally stored activity entries from this device. It does not change your settings, plugins or commands.")},
        confirmButton={TextButton(onClick={store.clearActivity();confirmClear=false}){Text("Clear history",color=Color(0xFFFF8A80))}},
        dismissButton={TextButton(onClick={confirmClear=false}){Text("Cancel")}}
    )
}

@Composable private fun SettingsPage(modifier:Modifier,store:OneAiStore,vault:SecretVault,externalBindings:()->Map<String,String>,accessOverview:()->AccessOverviewData,restorationReady:Boolean,onBubbleToggle:(Boolean)->Unit,refresh:()->Unit) {
    var bubble by remember{mutableStateOf(store.bubbleEnabled())};var services by remember{mutableStateOf(store.bubbleServices())}
    var endpoint by remember{mutableStateOf(store.aiEndpoint())};var model by remember{mutableStateOf(store.aiModel())};var provider by remember{mutableStateOf(store.aiProviderName())}
    var apiKey by remember{mutableStateOf("")};var status by remember{mutableStateOf("")}
    var confirmClearAiKey by remember { mutableStateOf(false) }
    val endpointIssue = com.oneai.app.ai.ProviderSettingsPolicy.endpointError(endpoint)
    val aiKeyStatus = CredentialStatus.of(vault.readResult())
    val hasAiKey = aiKeyStatus == CredentialStatus.SAVED
    val providerReady = endpointIssue == null && provider.isNotBlank() && model.isNotBlank() && hasAiKey
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Header("Settings","Configure the core, AI provider and floating interface.") }
        item { CardBox {
            Text("SYSTEM STATUS",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp)
            ActionRow("AI provider",if(providerReady) "Configured • credentials stored securely" else if(aiKeyStatus==CredentialStatus.UNREADABLE) CredentialStatus.UNREADABLE_MESSAGE else if(!hasAiKey) "Needs an API key" else endpointIssue ?: "Complete provider name and model") {
                Text(if(providerReady) "READY" else "SETUP",color=if(providerReady) Gold else Muted,fontSize=11.sp,fontWeight=FontWeight.Bold)
            }
            ActionRow("Floating Bubble",if(bubble) "Enabled (Android overlay access may still be required)" else "Disabled") {
                Text(if(bubble) "ON" else "OFF",color=Gold,fontSize=11.sp)
            }
            Text("${store.plugins().count { it.enabled }} enabled plugins • ${store.commands().count { it.enabled }} enabled commands",color=Muted,fontSize=11.sp)
        } }
        item { CardBox {
            Text("FLOATING BUBBLE",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp)
            ActionRow("Enable floating bubble","Requires Android overlay permission"){Switch(bubble,{bubble=it;onBubbleToggle(it);bubble=store.bubbleEnabled()})}
            Text(if(restorationReady) "Services appear only after you explicitly enable them below. Newly available services start off." else "Restoring trusted plugin services… service controls will unlock when startup restoration finishes.",color=Muted,fontSize=11.sp)
            val installedAndEnabled = store.plugins().filter { it.enabled }.map { it.id }.toSet()
            val availableOptions = if(restorationReady) BubbleUiPolicy.availableServices(installedAndEnabled, externalBindings()).toList() else emptyList()
            val savedOrder = store.bubbleServiceOrder().filter { it in availableOptions }
            val orderedOptions = savedOrder + availableOptions.filterNot { it in savedOrder }
            orderedOptions.forEachIndexed { index, id ->
                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().padding(vertical=3.dp)) {
                    Text(com.oneai.app.bubble.BubbleProviders.registry.provider(id)?.title ?: id.replace('_',' '),color=White,modifier=Modifier.weight(1f))
                    IconButton(onClick={ if(index>0){val next=orderedOptions.toMutableList();val item=next.removeAt(index);next.add(index-1,item);store.setBubbleServiceOrder(next);refresh()} },enabled=restorationReady && index>0){Icon(Icons.Default.ArrowUpward,"Move up",tint=Gold)}
                    IconButton(onClick={ if(index<orderedOptions.lastIndex){val next=orderedOptions.toMutableList();val item=next.removeAt(index);next.add(index+1,item);store.setBubbleServiceOrder(next);refresh()} },enabled=restorationReady && index<orderedOptions.lastIndex){Icon(Icons.Default.ArrowDownward,"Move down",tint=Gold)}
                    Switch(id in services,{services=if(it)services+id else services-id;store.setBubbleServices(services)},enabled=restorationReady)
                }
            }
            if (availableOptions.size == 1) Text("Install and enable optional plugins in Plugin Centre to expose their bubble services here.",color=Muted,fontSize=11.sp)
        } }
        item { CardBox {
            Text("AI PROVIDER",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp)
            OutlinedTextField(provider,{provider=it},modifier=Modifier.fillMaxWidth(),label={Text("Provider label")})
            OutlinedTextField(endpoint,{endpoint=it},modifier=Modifier.fillMaxWidth(),label={Text("HTTPS chat-completions endpoint")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),singleLine=true)
            OutlinedTextField(model,{model=it},modifier=Modifier.fillMaxWidth(),label={Text("Model")},singleLine=true)
            OutlinedTextField(apiKey,{apiKey=it},modifier=Modifier.fillMaxWidth(),label={Text("API key (stored encrypted on device)")},singleLine=true,visualTransformation=PasswordVisualTransformation())
            if(endpointIssue!=null) Text(endpointIssue,color=Color(0xFFFF8A80),fontSize=11.sp)
            Button(onClick={store.setAiProviderName(provider.trim());store.setAiEndpoint(endpoint.trim());store.setAiModel(model.trim());if(apiKey.isNotBlank())vault.save(apiKey.trim());apiKey="";status="Provider settings saved.";refresh()},enabled=endpointIssue==null&&provider.isNotBlank()&&model.isNotBlank(),modifier=Modifier.fillMaxWidth()){Text("Save provider settings")}
            Text("Credential status: ${aiKeyStatus.settingsText}. Leave the key field blank to keep the existing key.",color=Muted,fontSize=11.sp)
            Text("ONE AI supports OpenAI-compatible HTTPS chat-completions APIs. Credentials use Android Keystore encryption.",color=Muted,fontSize=11.sp)
            TextButton(onClick={confirmClearAiKey=true},enabled=aiKeyStatus!=CredentialStatus.NOT_SAVED){Text("Remove AI provider key",color=Color(0xFFFF8A80))}
            if(status.isNotBlank())Text(status,color=Gold)
        } }
        item {
            // Stage 10.1: read-only overview of what each trusted plugin may ask for; it changes nothing.
            val overview = accessOverview()
            CardBox {
                Text("ACCESS OVERVIEW",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp)
                if(overview.isEmpty) Text(AccessOverview.EMPTY_TEXT,color=Muted,fontSize=12.sp)
                overview.rows.forEach { row ->
                    Spacer(Modifier.height(8.dp))
                    Text(row.title,color=White,fontWeight=FontWeight.SemiBold,fontSize=13.sp)
                    row.lines.forEach { line -> Text(line,color=Muted,fontSize=11.sp) }
                }
                if(overview.moreCount>0) { Spacer(Modifier.height(8.dp));Text("and ${overview.moreCount} more. Open the Plugins screen to see them all.",color=Muted,fontSize=11.sp) }
                Spacer(Modifier.height(10.dp))
                AccessOverview.explanation.forEach { line -> Text("\u2022 $line",color=White,fontSize=12.sp) }
            }
        }
    }
    if(confirmClearAiKey) AlertDialog(
        onDismissRequest={confirmClearAiKey=false},
        title={Text("Remove AI provider key?")},
        text={Text("ONE AI requests will stop working until you save a new provider key. Your provider name, endpoint and model settings will remain unchanged.")},
        confirmButton={TextButton(onClick={vault.save("");store.log("Removed AI provider credential");confirmClearAiKey=false;status="AI provider key removed.";refresh()}){Text("Remove key",color=Color(0xFFFF8A80))}},
        dismissButton={TextButton(onClick={confirmClearAiKey=false}){Text("Cancel")}}
    )
}
