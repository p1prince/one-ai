package com.oneai.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.oneai.app.ai.AiProviderClient
import com.oneai.app.ai.AiOrchestrator
import com.oneai.app.bubble.FloatingBubbleService
import com.oneai.app.commands.CommandRouter
import com.oneai.app.core.*
import com.oneai.app.plugins.PluginManager
import com.oneai.app.security.SecretVault
import kotlinx.coroutines.launch

private val Ink=Color(0xFF101114); private val Panel=Color(0xFF1A1C20); private val Gold=Color(0xFFD6AE58)
private val Muted=Color(0xFF9699A1); private val White=Color(0xFFF5F3EE)

class MainActivity : ComponentActivity() {
    private lateinit var store: OneAiStore
    private lateinit var plugins: PluginManager
    private lateinit var vault: SecretVault
    private lateinit var ai: AiProviderClient
    private lateinit var orchestrator: AiOrchestrator
    private lateinit var commandRouter: CommandRouter
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store=OneAiStore(this); plugins=PluginManager(store); vault=SecretVault(this); ai=AiProviderClient(store,vault); orchestrator=AiOrchestrator(ai,store); commandRouter=CommandRouter(store,orchestrator)
        setContent { OneAiApp(store,plugins,vault,ai,commandRouter,
            onBubbleToggle={ enabled -> toggleBubble(enabled) },
            onAsk={ prompt,done -> lifecycleScope.launch { runCatching { orchestrator.respond(prompt).content }.onSuccess { done(it) }.onFailure { done("Error: ${it.message}") } } },
            onRunCommand={ id,done -> lifecycleScope.launch { runCatching { commandRouter.run(id).content }.onSuccess { done(it) }.onFailure { done("Error: ${it.message}") } } }
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

private enum class Page(val title:String) { Home("Home"), Plugins("Plugins"), Develop("Develop"), Commands("Commands"), Activity("Activity"), Settings("Settings") }

@Composable
private fun OneAiApp(store:OneAiStore, manager:PluginManager, vault:SecretVault, ai:AiProviderClient, commandRouter:CommandRouter,
                     onBubbleToggle:(Boolean)->Unit, onAsk:(String,(String)->Unit)->Unit,
                     onRunCommand:(String,(String)->Unit)->Unit) {
    var page by remember { mutableStateOf(Page.Home) }; var refresh by remember { mutableIntStateOf(0) }
    var chatResult by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val refreshNow={ refresh++ }
    MaterialTheme(colorScheme=darkColorScheme(primary=Gold,background=Ink,surface=Panel,onPrimary=Ink,onBackground=White,onSurface=White)) {
        Scaffold(containerColor=Ink, bottomBar={
            NavigationBar(containerColor=Panel) {
                listOf(Page.Home,Page.Plugins,Page.Develop,Page.Commands,Page.Activity,Page.Settings).forEach { p ->
                    NavigationBarItem(selected=page==p,onClick={page=p;refresh++},icon={
                        Icon(when(p){Page.Home->Icons.Default.Home;Page.Plugins->Icons.Default.Extension;Page.Develop->Icons.Default.Code;Page.Commands->Icons.Default.Terminal;Page.Activity->Icons.Default.History;Page.Settings->Icons.Default.Settings},p.title)
                    },label={Text(p.title,fontSize=9.sp)},colors=NavigationBarItemDefaults.colors(selectedIconColor=Gold,selectedTextColor=Gold,indicatorColor=Gold.copy(alpha=.12f),unselectedIconColor=Muted,unselectedTextColor=Muted))
                }
            }
        }) { pad ->
            when(page) {
                Page.Home -> HomePage(Modifier.padding(pad),store,{page=Page.Plugins},{page=Page.Commands},chatResult,busy,{ prompt -> busy=true;onAsk(prompt){chatResult=it;busy=false} })
                Page.Plugins -> PluginsPage(Modifier.padding(pad),manager,refreshNow)
                Page.Develop -> DeveloperPage(Modifier.padding(pad),store,manager,refreshNow)
                Page.Commands -> CommandsPage(Modifier.padding(pad),store,commandRouter,refreshNow,onRunCommand)
                Page.Activity -> ActivityPage(Modifier.padding(pad),store,refresh)
                Page.Settings -> SettingsPage(Modifier.padding(pad),store,vault,onBubbleToggle,refreshNow)
            }
        }
    }
}

@Composable private fun Header(title:String,subtitle:String) { Column(Modifier.padding(bottom=16.dp)) { Text(title,color=White,fontSize=27.sp,fontWeight=FontWeight.Bold);Text(subtitle,color=Muted,fontSize=13.sp) } }
@Composable private fun CardBox(content:@Composable ColumnScope.()->Unit) { Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(16.dp)).padding(16.dp),content=content) }
@Composable private fun ActionRow(title:String,detail:String,action:@Composable RowScope.()->Unit) { Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically) { Column(Modifier.weight(1f)){Text(title,color=White,fontWeight=FontWeight.SemiBold);Text(detail,color=Muted,fontSize=12.sp)};action() } }

@Composable private fun HomePage(modifier:Modifier,store:OneAiStore,toPlugins:()->Unit,toCommands:()->Unit,result:String,busy:Boolean,onAsk:(String)->Unit) {
    var prompt by remember { mutableStateOf("") }
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(15.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(42.dp).background(Gold,RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center){Text("1",color=Ink,fontSize=25.sp,fontWeight=FontWeight.Black)};Spacer(Modifier.width(12.dp));Column{Text("ONE AI",color=White,fontSize=20.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("YOUR INTELLIGENT PLATFORM",color=Muted,fontSize=9.sp)} } }
        item { Header("Your Control Centre","One platform. Infinite possibilities.") }
        item { CardBox { Text("COMMAND ONE",color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold);Spacer(Modifier.height(8.dp));OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth(),placeholder={Text("What would you like ONE AI to do?")},minLines=2);Spacer(Modifier.height(8.dp));Button(onClick={if(prompt.isNotBlank())onAsk(prompt)},enabled=prompt.isNotBlank()&&!busy,modifier=Modifier.fillMaxWidth()){Text(if(busy)"Thinking…" else "Send command")};if(result.isNotBlank()){Spacer(Modifier.height(10.dp));Text(result,color=White,fontSize=13.sp)} } }
        item { CardBox { Text("PLATFORM MODULES",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp);ActionRow("Plugin Centre","Install and manage optional capabilities"){TextButton(onClick=toPlugins){Text("OPEN")}};ActionRow("Command Manager","Create reusable instructions"){TextButton(onClick=toCommands){Text("OPEN")}};ActionRow("Floating Bubble",if(store.bubbleEnabled())"Enabled in settings" else "Off by default"){Text(if(store.bubbleEnabled())"ON" else "OFF",color=Gold)} } }
        item { CardBox { Text("SYSTEM STATUS",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp);Text("AI CORE     Ready for provider setup",color=White,fontSize=12.sp);Text("PLUGINS     ${store.plugins().count{it.enabled}} enabled / ${store.plugins().size} installed",color=White,fontSize=12.sp);Text("SECURITY    Explicit capabilities • no arbitrary code loading",color=White,fontSize=12.sp) } }
    }
}

@Composable private fun PluginsPage(modifier:Modifier,manager:PluginManager,refresh:()->Unit) {
    var tick by remember { mutableIntStateOf(0) }
    val installed=manager.installed(); val available=manager.catalogue.filterNot { t->installed.any{it.id==t.id} }
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Header("Plugin Centre","Optional capabilities. Your shell stays independent.") }
        item { Text("INSTALLED",color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold) }
        if(installed.isEmpty()) item { CardBox { Text("No plugins installed",color=White,fontWeight=FontWeight.SemiBold);Text("ONE AI starts empty. Install only the capabilities you want.",color=Muted,fontSize=12.sp) } }
        items(installed,key={it.id}) { p -> CardBox { Text(p.name,color=White,fontWeight=FontWeight.SemiBold);Text(p.description,color=Muted,fontSize=12.sp);Text("v${p.version} • ${p.capabilities.joinToString()}",color=Gold,fontSize=10.sp);Row(verticalAlignment=Alignment.CenterVertically){Text(if(p.enabled)"Enabled" else "Disabled",color=White,modifier=Modifier.weight(1f));Switch(p.enabled,{manager.setEnabled(p.id,it);tick++;refresh()});TextButton(onClick={manager.remove(p.id);tick++;refresh()}){Text("Remove",color=Color(0xFFFF8A80))}} } }
        item { Text("AVAILABLE CAPABILITIES",color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold) }
        items(available,key={it.id}) { t -> CardBox { Text(t.name,color=White,fontWeight=FontWeight.SemiBold);Text(t.description,color=Muted,fontSize=12.sp);Text(t.capabilities.joinToString(" • "),color=Gold,fontSize=10.sp);Button(onClick={manager.install(t);tick++;refresh()},modifier=Modifier.fillMaxWidth()){Text("Install (starts disabled)")}} }
    }
}

@Composable private fun DeveloperPage(modifier:Modifier,store:OneAiStore,manager:PluginManager,refresh:()->Unit) {
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
                Button(onClick={val r=manager.create(id,name,description,listOf("commands"));message=r.fold({"Created ${it.name}. It is disabled until you enable it."},{it.message?:"Could not create plugin"});refresh()},modifier=Modifier.fillMaxWidth(),enabled=id.isNotBlank()&&name.isNotBlank()){Text("Create plugin descriptor")}
                Text("Generated plugins are declarative descriptors. ONE AI does not load arbitrary executable code into its core process.",color=Muted,fontSize=11.sp)
            } else Text("Turn on Developer Mode to access plugin creation tools.",color=Muted)
            if(message.isNotBlank())Text(message,color=Gold)
        }
    }
}

@Composable private fun CommandsPage(modifier:Modifier,store:OneAiStore,router:CommandRouter,refresh:()->Unit,onRun:(String,(String)->Unit)->Unit) {
    var title by remember{mutableStateOf("")};var prompt by remember{mutableStateOf("")};var msg by remember{mutableStateOf("")};var result by remember{mutableStateOf("")};var running by remember{mutableStateOf(false)}
    val commands=store.commands()
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Header("Command Manager","Build reusable instructions. Commands are not auto-enabled.") }
        item { CardBox { OutlinedTextField(title,{title=it},modifier=Modifier.fillMaxWidth(),label={Text("Command name")});OutlinedTextField(prompt,{prompt=it},modifier=Modifier.fillMaxWidth(),label={Text("Instruction")},minLines=2);Button(onClick={if(title.isNotBlank()&&prompt.isNotBlank()){router.add(title,prompt).onSuccess { title="";prompt="";msg="Command saved (disabled by default)";refresh() }.onFailure { msg=it.message ?: "Could not save command" }}},modifier=Modifier.fillMaxWidth()){Text("Save command")};if(msg.isNotBlank())Text(msg,color=Gold) } }
        if(commands.isEmpty()) item { CardBox { Text("No commands created yet.",color=White);Text("Create commands explicitly; installing a plugin does not add commands automatically.",color=Muted,fontSize=12.sp) } }
        items(commands,key={it.id}) { c -> CardBox { Text(c.title,color=White,fontWeight=FontWeight.SemiBold);Text(c.prompt,color=Muted,fontSize=12.sp);Row(verticalAlignment=Alignment.CenterVertically){Text(if(c.enabled)"Enabled" else "Disabled",color=Gold,modifier=Modifier.weight(1f));Switch(c.enabled,{router.setEnabled(c.id,it);refresh()});TextButton(onClick={router.remove(c.id);refresh()}){Text("Delete",color=Color(0xFFFF8A80))}};Button(onClick={running=true;onRun(c.id){result=it;running=false}},enabled=c.enabled&&!running,modifier=Modifier.fillMaxWidth()){Text(if(running)"Running…" else "Run with AI")};if(result.isNotBlank())Text(result,color=White,fontSize=12.sp) } }
    }
}

@Composable private fun ActivityPage(modifier:Modifier,store:OneAiStore,refresh:Int) {
    val entries=store.activity()
    LazyColumn(modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) { item { Header("Activity","Recent actions performed within ONE AI.") };if(entries.isEmpty())item{CardBox{Text("Nothing here yet.",color=White)}};items(entries){CardBox{Text(it,color=White,fontSize=13.sp)}} }
}

@Composable private fun SettingsPage(modifier:Modifier,store:OneAiStore,vault:SecretVault,onBubbleToggle:(Boolean)->Unit,refresh:()->Unit) {
    var bubble by remember{mutableStateOf(store.bubbleEnabled())};var services by remember{mutableStateOf(store.bubbleServices())}
    var endpoint by remember{mutableStateOf(store.aiEndpoint())};var model by remember{mutableStateOf(store.aiModel())};var provider by remember{mutableStateOf(store.aiProviderName())}
    var apiKey by remember{mutableStateOf("")};var status by remember{mutableStateOf("")}
    LazyColumn(modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(top=24.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Header("Settings","Configure the core, AI provider and floating interface.") }
        item { CardBox {
            Text("FLOATING BUBBLE",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp)
            ActionRow("Enable floating bubble","Requires Android overlay permission"){Switch(bubble,{bubble=it;onBubbleToggle(it);bubble=store.bubbleEnabled()})}
            Text("Services appear only after you explicitly enable them below. Newly available services start off.",color=Muted,fontSize=11.sp)
            val installedAndEnabled = store.plugins().filter { it.enabled }.map { it.id }.toSet()
            val availableOptions = buildList {
                add("command_manager")
                if ("one.web.research" in installedAndEnabled) add("web_research")
                if ("one.screen.intelligence" in installedAndEnabled) add("screen_intelligence")
                if ("one.voice.interface" in installedAndEnabled) add("voice_interface")
                if ("one.automation.engine" in installedAndEnabled) add("automation")
            }
            val savedOrder = store.bubbleServiceOrder().filter { it in availableOptions }
            val orderedOptions = savedOrder + availableOptions.filterNot { it in savedOrder }
            orderedOptions.forEachIndexed { index, id ->
                Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth().padding(vertical=3.dp)) {
                    Text(id.replace('_',' '),color=White,modifier=Modifier.weight(1f))
                    IconButton(onClick={ if(index>0){val next=orderedOptions.toMutableList();val item=next.removeAt(index);next.add(index-1,item);store.setBubbleServiceOrder(next);refresh()} },enabled=index>0){Icon(Icons.Default.ArrowUpward,"Move up",tint=Gold)}
                    IconButton(onClick={ if(index<orderedOptions.lastIndex){val next=orderedOptions.toMutableList();val item=next.removeAt(index);next.add(index+1,item);store.setBubbleServiceOrder(next);refresh()} },enabled=index<orderedOptions.lastIndex){Icon(Icons.Default.ArrowDownward,"Move down",tint=Gold)}
                    Switch(id in services,{services=if(it)services+id else services-id;store.setBubbleServices(services)})
                }
            }
            if (availableOptions.size == 1) Text("Install and enable optional plugins in Plugin Centre to expose their bubble services here.",color=Muted,fontSize=11.sp)
        } }
        item { CardBox {
            Text("AI PROVIDER",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp)
            OutlinedTextField(provider,{provider=it},modifier=Modifier.fillMaxWidth(),label={Text("Provider label")})
            OutlinedTextField(endpoint,{endpoint=it},modifier=Modifier.fillMaxWidth(),label={Text("HTTPS chat-completions endpoint")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),singleLine=true)
            OutlinedTextField(model,{model=it},modifier=Modifier.fillMaxWidth(),label={Text("Model")},singleLine=true)
            OutlinedTextField(apiKey,{apiKey=it},modifier=Modifier.fillMaxWidth(),label={Text("API key (stored encrypted on device)")},singleLine=true)
            Button(onClick={store.setAiProviderName(provider);store.setAiEndpoint(endpoint);store.setAiModel(model);if(apiKey.isNotBlank())vault.save(apiKey);apiKey="";status="Provider settings saved.";refresh()},modifier=Modifier.fillMaxWidth()){Text("Save provider settings")}
            Text("ONE AI currently supports OpenAI-compatible HTTPS chat-completions APIs. Your key stays on this device and is encrypted with Android Keystore.",color=Muted,fontSize=11.sp)
            if(status.isNotBlank())Text(status,color=Gold)
        } }
        item { CardBox { Text("SECURITY",color=Gold,fontWeight=FontWeight.Bold,fontSize=11.sp);Text("• Plugins declare capabilities and start disabled.\n• The shell does not execute downloaded plugin code.\n• Provider credentials are encrypted with Android Keystore.\n• Android permissions are requested only when a feature needs them.",color=White,fontSize=12.sp) } }
    }
}
