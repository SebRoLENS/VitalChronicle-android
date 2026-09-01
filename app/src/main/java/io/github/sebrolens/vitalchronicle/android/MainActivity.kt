package io.github.sebrolens.vitalchronicle.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private val vm: VitalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VitalTheme { VitalApp(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.resumePendingUpdateInstallation(this)
    }
}

enum class Screen(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.Home), Data("Data", Icons.Default.ShowChart), AI("AI", Icons.Default.AutoAwesome), Settings("Settings", Icons.Default.Settings)
}

@Composable fun VitalTheme(content: @Composable () -> Unit) {
    val scheme = if (androidx.compose.foundation.isSystemInDarkTheme()) darkColorScheme(
        primary=Color(0xFF8BD8FF), secondary=Color(0xFFB7D66C), tertiary=Color(0xFFFFB59B), surface=Color(0xFF111820)
    ) else lightColorScheme(primary=Color(0xFF00658A), secondary=Color(0xFF526800), tertiary=Color(0xFF99461A), surface=Color(0xFFF8FAFC))
    MaterialTheme(colorScheme=scheme, typography=Typography(), content=content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun VitalApp(vm: VitalViewModel = viewModel()) {
    var screen by remember { mutableStateOf(Screen.Overview) }
    val activity = LocalContext.current as? Activity
    val readyUpdate = vm.updateState as? AppUpdateState.Ready

    if (readyUpdate != null && !vm.updatePromptDismissed) {
        AlertDialog(
            onDismissRequest = vm::dismissUpdatePrompt,
            icon = { Icon(Icons.Default.SystemUpdate, null) },
            title = { Text("VitalChronicle ${readyUpdate.info.version} is ready") },
            text = {
                Text(
                    "The signed update was downloaded from GitHub and verified. " +
                        "Android will ask you to confirm installation. The temporary APK " +
                        "is deleted automatically the next time VitalChronicle starts."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { activity?.let { vm.installUpdate(it) } },
                    enabled = activity != null,
                ) { Text("Install now") }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissUpdatePrompt) { Text("Later") }
            },
        )
    }
    Scaffold(
        topBar={ TopAppBar(title={ Column { Text("VitalChronicle", fontWeight=FontWeight.SemiBold); Text("Android · ${BuildConfig.VERSION_NAME}", style=MaterialTheme.typography.labelSmall) } }, actions={ if(vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth=2.dp) }) },
        bottomBar={ NavigationBar { Screen.entries.forEach { item -> NavigationBarItem(selected=screen==item,onClick={screen=item},icon={Icon(item.icon,null)},label={Text(item.label)}) } } }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when(screen) {
                Screen.Overview -> OverviewScreen(vm)
                Screen.Data -> DataScreen(vm)
                Screen.AI -> AiScreen(vm)
                Screen.Settings -> SettingsScreen(vm)
            }
        }
    }
}

@Composable fun OverviewScreen(vm: VitalViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            HeroCard(
                title = if(vm.counts.isEmpty()) "Your health history, privately understood." else "${vm.counts.values.sum()} local health records",
                subtitle = if(vm.googleConnected) "Google connected · ${vm.status}" else "Connect your Google account from Settings using native Android authorization.",
                icon = Icons.Default.Favorite
            )
        }
        if(vm.metrics.isEmpty()) item {
            EmptyCard(
                if(vm.counts.isEmpty()) "No local measurements yet" else "Building local summaries",
                if(vm.counts.isEmpty()) "Connect Google and run a sync. Health records stay on this device." else "Your downloaded health records are stored locally. VitalChronicle is preparing the same deterministic summaries used by desktop."
            )
        }
        vm.lastError?.let { err -> item { ErrorCard(err) } }
        items(vm.metrics, key={it.dataType}) { MetricCardView(it) }
        item { Button(onClick=vm::sync, enabled=!vm.busy && vm.googleConnected, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync,null); Spacer(Modifier.width(8.dp)); Text("Download / update") } }
    }
}

@Composable fun MetricCardView(metric: MetricCard) {
    val color = metricColor(metric.dataType)
    val isHeartToday = metric.dataType == "heart-rate-today"
    Card { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp)); Text(metric.label, fontWeight=FontWeight.SemiBold, modifier=Modifier.weight(1f))
            metric.deltaPercent?.let { Text("${if(it>=0) "+" else ""}${"%.1f".format(it)}%", color=if(it>=0) color else MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(8.dp))
        Text(metric.current?.let { "${formatMetric(it)} ${metric.unit}" } ?: "—", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)

        if (isHeartToday && metric.heartDaySmoothed.size > 1) {
            Spacer(Modifier.height(10.dp))
            Sparkline(metric.heartDaySmoothed, color, Modifier.fillMaxWidth().height(72.dp))
            Spacer(Modifier.height(6.dp))
            Text(
                "Today · ${metric.heartSmoothingMinutes.takeIf { it > 0 } ?: 5}-min averages · ${metric.heartDaySampleCount} intervals",
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (metric.heartDayMin != null && metric.heartDayMean != null && metric.heartDayMax != null) {
                Text(
                    "5-min avg range · min ${formatMetric(metric.heartDayMin)} · mean ${formatMetric(metric.heartDayMean)} · max ${formatMetric(metric.heartDayMax)} ${metric.unit}",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if(metric.sparkline.size>1) {
            Spacer(Modifier.height(10.dp))
            Sparkline(
                metric.sparkline,
                color,
                Modifier.fillMaxWidth().height(62.dp),
                metric.sparklineMean,
                metric.sparklineStd,
            )
            if (metric.sparklineMean != null && metric.sparklineStd != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "7-day history · shaded band = mean ± 1 SD",
                    style=MaterialTheme.typography.bodySmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (metric.completion && metric.percentage != null) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (metric.percentage / 100.0).coerceIn(0.0, 1.0).toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = color,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "${"%.0f".format(metric.percentage)}% of the previous 7-day mean",
                style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        metric.baseline?.let { Text("7-day baseline · ${formatMetric(it)} ${metric.unit}", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if (metric.latestAvailable) {
            Text("Latest available · ${metric.valueDate}", style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } }
}

@Composable fun Sparkline(
    values: List<Double>,
    color: Color,
    modifier: Modifier=Modifier,
    mean: Double? = null,
    standardDeviation: Double? = null,
) { Canvas(modifier) {
    val finite = values.filter { it.isFinite() }
    if (finite.size < 2) return@Canvas
    val bandLow = if (mean != null && standardDeviation != null && mean.isFinite() && standardDeviation.isFinite()) mean-standardDeviation else null
    val bandHigh = if (mean != null && standardDeviation != null && mean.isFinite() && standardDeviation.isFinite()) mean+standardDeviation else null
    val scaleValues = buildList { addAll(finite); bandLow?.let(::add); bandHigh?.let(::add) }
    val lo=scaleValues.minOrNull()?:0.0; val hi=scaleValues.maxOrNull()?:1.0; val span=max(1e-9,hi-lo)
    fun y(value: Double) = size.height-((value-lo)/span*size.height).toFloat()

    if (bandLow != null && bandHigh != null) {
        val top = y(bandHigh).coerceAtMost(y(bandLow))
        val bottom = y(bandHigh).coerceAtLeast(y(bandLow))
        drawRect(
            color.copy(alpha=0.14f),
            topLeft=androidx.compose.ui.geometry.Offset(0f, top),
            size=androidx.compose.ui.geometry.Size(size.width, max(1f,bottom-top)),
        )
        mean?.let {
            val meanY = y(it)
            drawLine(
                color.copy(alpha=0.55f),
                androidx.compose.ui.geometry.Offset(0f,meanY),
                androidx.compose.ui.geometry.Offset(size.width,meanY),
                strokeWidth=2f,
            )
        }
    }

    val step=size.width/(finite.size-1)
    val path=Path(); finite.forEachIndexed { i,v -> val x=i*step; val yy=y(v); if(i==0) path.moveTo(x,yy) else path.lineTo(x,yy) }
    drawPath(path,color,style=androidx.compose.ui.graphics.drawscope.Stroke(width=3f))
} }

@Composable fun DataScreen(vm: VitalViewModel) {
    var selected by remember { mutableStateOf<String?>(null) }
    val records = remember(selected, vm.counts) { selected?.let { vm.database.recentRecords(it,25) }.orEmpty() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Local data archive", "SQLite schema and deterministic core are compatible with VitalChronicle desktop.") }
        if(selected!=null) {
            item { TextButton(onClick={selected=null}) { Icon(Icons.Default.ArrowBack,null); Text("All categories") } }
            items(records) { raw -> Card { Text(raw, Modifier.padding(12.dp), maxLines=12, overflow=TextOverflow.Ellipsis, style=MaterialTheme.typography.bodySmall) } }
        } else {
            val entries=vm.counts.entries.sortedByDescending { it.value }
            if(entries.isEmpty()) item { EmptyCard("Archive is empty", "Run the first Google Health sync from Overview or Settings.") }
            items(entries,key={it.key}) { e -> ListItem(headlineContent={Text(vm.specs.firstOrNull{it.key==e.key}?.label?:e.key)},supportingContent={Text(e.key)},trailingContent={Text(e.value.toString(),fontWeight=FontWeight.Bold)},modifier=Modifier.clickable{selected=e.key}) }
        }
    }
}

@Composable fun AiScreen(vm: VitalViewModel) {
    var question by remember { mutableStateOf("What are the most meaningful patterns in my recent health data?") }
    var thinkingOpen by remember { mutableStateOf(true) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { HeroCard("Private local AI", vm.aiModelName?.let{"Active local model · $it"}?:"Download an Ollama model or use Android's built-in Gemini Nano. Health evidence stays on this device.", Icons.Default.AutoAwesome) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(7,28,90).forEach { d -> FilterChip(selected=vm.analysisDays==d,onClick={vm.analysisDays=d},label={Text("${d}d")}) } } }
        item { OutlinedTextField(question,{question=it},label={Text("Ask about your data")},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=7) }
        item {
            if (vm.busy) {
                OutlinedButton(onClick=vm::cancelAnalysis,modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop,null); Spacer(Modifier.width(8.dp)); Text("Stop analysis")
                }
            } else {
                Button(onClick={vm.analyse(question)},enabled=vm.counts.isNotEmpty(),modifier=Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AutoAwesome,null); Spacer(Modifier.width(8.dp)); Text("Analyse locally")
                }
            }
        }
        if(vm.busy) item {
            Card { Row(Modifier.padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("VitalChronicle is working",fontWeight=FontWeight.SemiBold)
                    Text(vm.status,style=MaterialTheme.typography.bodySmall)
                }
            } }
        }
        if(vm.aiGeneratedTokens > 0) item {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Default.DataUsage,null,tint=MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${vm.aiGeneratedTokens} / ${vm.aiMaximumTokens} output tokens",fontWeight=FontWeight.SemiBold)
                        Text("${"%.1f".format(vm.aiTokensPerSecond)} tokens/s · counted live by llama.cpp",style=MaterialTheme.typography.bodySmall)
                    }
                    if(vm.aiThinkingActive) AssistChip(onClick={},label={Text("Thinking")},leadingIcon={Icon(Icons.Default.Psychology,null,Modifier.size(18.dp))})
                }
            }
        }
        if(vm.aiThinking.isNotBlank()) item {
            Card {
                Column {
                    ListItem(
                        headlineContent={Text("Model thinking",fontWeight=FontWeight.SemiBold)},
                        supportingContent={Text(if(vm.aiThinkingActive) "Updating in real time" else "Reasoning completed")},
                        leadingContent={Icon(Icons.Default.Psychology,null,tint=MaterialTheme.colorScheme.tertiary)},
                        trailingContent={Icon(if(thinkingOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)},
                        modifier=Modifier.clickable{thinkingOpen=!thinkingOpen},
                    )
                    AnimatedVisibility(thinkingOpen) {
                        SelectionContainer {
                            MarkdownAnswer(vm.aiThinking,Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp))
                        }
                    }
                }
            }
        }
        if(vm.aiAnswer.isNotBlank()) item {
            Card { Column {
                ListItem(
                    headlineContent={Text("Answer",fontWeight=FontWeight.SemiBold)},
                    leadingContent={Icon(Icons.Default.AutoAwesome,null,tint=MaterialTheme.colorScheme.primary)},
                )
                SelectionContainer { MarkdownAnswer(vm.aiAnswer,Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp)) }
            } }
        }
        vm.lastError?.let { err -> item { ErrorCard(err) } }
    }
}

@Composable fun SettingsScreen(vm: VitalViewModel) {
    var googleSetupOpen by remember { mutableStateOf(!vm.googleConnected) }
    val activity = LocalContext.current as? Activity
    val requiredScopes = remember(vm.specs) { vm.specs.map { it.scope }.filter { it.isNotBlank() }.distinct().sorted() }
    val authorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) vm.completeGoogleAuthorization(result.data)
        else vm.googleAuthorizationCancelled()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("Google Health", "Android setup is fully independent from VitalChronicle desktop. No desktop OAuth configuration, client-secret JSON, browser callback or localhost server is required.") }
        item {
            Card { Column {
                ListItem(
                    headlineContent={Text("Google Cloud setup assistant",fontWeight=FontWeight.SemiBold)},
                    supportingContent={Text(if(vm.googleConnected) "Google is connected. Reopen this guide whenever you need to configure another Android build." else "First time? Follow these steps in order. No previous Google Cloud knowledge is assumed.")},
                    leadingContent={Icon(Icons.Default.Cloud,null,tint=MaterialTheme.colorScheme.primary)},
                    trailingContent={Icon(if(googleSetupOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)},
                    modifier=Modifier.clickable{googleSetupOpen=!googleSetupOpen}
                )
                AnimatedVisibility(googleSetupOpen) {
                    Column(Modifier.padding(start=14.dp,end=14.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Use the same Google Cloud project for every step below. If you previously configured VitalChronicle desktop you may reuse that project, but this guide does not require or assume it.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)

                        GoogleSetupStep(
                            1,
                            "Create or select a Google Cloud project",
                            "Open Google Cloud, sign in, and create a project if you do not already have one. A name such as ‘VitalChronicle’ is fine; ‘No organization’ is fine for a personal account. After creating it, make sure that project stays selected for all following steps.",
                            "Create / select project",
                            "https://console.cloud.google.com/projectcreate"
                        )
                        GoogleSetupStep(
                            2,
                            "Enable the Google Health API",
                            "Open the API Library for the selected project. If Google Health API is not already enabled, tap Enable. The service used by VitalChronicle is health.googleapis.com.",
                            "Open Google Health API",
                            "https://console.cloud.google.com/apis/library/health.googleapis.com"
                        )
                        GoogleSetupStep(
                            3,
                            "Configure Google Auth Platform",
                            "Open Google Auth Platform. If it says that the platform is not configured, choose Get started. Use ‘VitalChronicle’ as the app name, choose your email as the support email, select External for a normal personal Google account, enter your contact email, accept the Google API Services User Data Policy, then create the configuration. ‘Internal’ is only appropriate for a managed Google Workspace organization.",
                            "Open Auth overview",
                            "https://console.cloud.google.com/auth/overview"
                        )
                        GoogleSetupStep(
                            4,
                            "Add your Google account as a test user",
                            "If Audience shows External and Publishing status is Testing, open Test users → Add users and enter the exact Google account you will connect in VitalChronicle. If the app is already in Production, this step can be skipped.",
                            "Open Audience",
                            "https://console.cloud.google.com/auth/audience"
                        )
                        GoogleSetupStep(
                            5,
                            "Allow the Google Health data scopes",
                            "Open Data Access → Add or remove scopes. Filter for Google Health API, select the read scopes VitalChronicle requests below, then Update and Save. Only health-data read access is requested.",
                            "Open Data Access",
                            "https://console.cloud.google.com/auth/scopes"
                        ) {
                            SelectionContainer {
                                Text(requiredScopes.joinToString("\n") { "• $it" },style=MaterialTheme.typography.labelSmall)
                            }
                        }
                        GoogleSetupStep(
                            6,
                            "Create the Android OAuth client",
                            "Open Clients → Create client. Choose Android as the application type. Set the name to ‘VitalChronicle Android’, then enter exactly the package name and SHA-1 shown below. Do not create a Desktop or Web client for this Android flow, and do not download a JSON secret.",
                            "Open OAuth clients",
                            "https://console.cloud.google.com/auth/clients"
                        ) {
                            SelectionContainer {
                                Text("Name: VitalChronicle Android\nPackage name: ${vm.googlePackageName}\nSHA-1: ${vm.googleSigningSha1}",style=MaterialTheme.typography.bodySmall,fontWeight=FontWeight.Medium)
                            }
                        }
                        GoogleSetupStep(
                            7,
                            "Connect the account in VitalChronicle",
                            "Return to this screen and tap Connect below. Choose the same account you added as a test user and approve the requested Google Health permissions. After the connection succeeds, use Download / update to populate the local archive."
                        )
                        GoogleSetupStep(
                            8,
                            "If you use Android Studio or another signed build",
                            "OAuth identifies Android apps using package name + signing-certificate SHA-1. A local Android Studio debug build, a GitHub APK, and a Play Store build can therefore have different SHA-1 values. Register an Android OAuth client for each signing certificate you actually use. Always copy the SHA-1 displayed by the installed build. With Play App Signing, the production SHA-1 comes from Play Console → App integrity. Normal users of a future centrally configured Play release should only need to tap Connect; this full Cloud procedure is mainly for development/self-hosted builds."
                        )
                    }
                }
            } }
        }
        item { SettingCard(Icons.Default.AccountCircle,"Google account",if(vm.googleConnected) "Connected with Google Identity Services" else "Not connected — complete the setup assistant above first") {
            if(!vm.googleConnected) Button(
                onClick={ vm.connectGoogle { pending -> authorizationLauncher.launch(IntentSenderRequest.Builder(pending).build()) } },
                enabled=!vm.busy
            ){Text("Connect")}
            else TextButton(onClick=vm::disconnectGoogle,enabled=!vm.busy){Text("Disconnect")}
        } }
        item { SettingCard(Icons.Default.Sync,"Sync history","Up to ${DataRetention.GENERAL_DAYS} days; high-frequency cardiac streams keep ${DataRetention.HIGH_VOLUME_CARDIAC_DAYS} days") { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){ listOf(30,90).forEach{d->FilterChip(selected=vm.historyDays==d,onClick={vm.historyDays=d},label={Text("${d}d")})} } } }

        item { SectionTitle("Local AI", "Automatic mode uses the selected downloaded Ollama model first, then Android's built-in Gemini Nano. No health data is sent to a cloud AI service.") }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("AI engine",fontWeight=FontWeight.SemiBold)
            AiEngine.entries.forEach { engine -> Row(Modifier.fillMaxWidth().clickable{vm.aiEngine=engine}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){ RadioButton(selected=vm.aiEngine==engine,onClick={vm.aiEngine=engine}); Column { Text(engine.title); if(engine==AiEngine.AUTOMATIC) Text("Recommended",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) } } }
            vm.aiModelName?.let { Text("Detected / selected model · $it",style=MaterialTheme.typography.bodySmall) }
            Text("Ollama models stream thinking and output tokens through the bundled llama.cpp runtime. Gemini Nano keeps the Accurate local profile (temperature 0.2 and FULL model when available).",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        } } }

        item {
            SectionTitle(
                "Ollama models on this phone",
                "Official Ollama GGUF layers run directly on-device through llama.cpp; no Ollama server or cloud inference is required.",
            )
        }
        item {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.Recommend,null,tint=MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(9.dp))
                        Text("Recommended · ${vm.recommendedOllamaModel.id}",fontWeight=FontWeight.Bold)
                    }
                    Text(
                        "Chosen from ${vm.hardware.ramGb} GB RAM, ${vm.hardware.cpuThreads} CPU threads, ${vm.hardware.abi} and ${formatBytes(vm.hardware.freeStorageBytes)} free when VitalChronicle started.",
                        style=MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        items(vm.ollamaCatalog,key={it.id}) { model ->
            OllamaModelCard(model,vm.ollamaModelStates[model.id] ?: OllamaInstallState.NotInstalled,vm)
        }
        vm.modelManagerMessage?.let { message -> item {
            Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
                Text(message,Modifier.padding(14.dp),style=MaterialTheme.typography.bodySmall)
            }
        } }

        item { Card { Column {
            ListItem(headlineContent={Text("Advanced settings")},supportingContent={Text("Hardware, analysis window and explicit engine override")},trailingContent={Icon(if(vm.advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)},modifier=Modifier.clickable{vm.advancedOpen=!vm.advancedOpen})
            AnimatedVisibility(vm.advancedOpen) { Column(Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("${vm.hardware.device} · ${vm.hardware.ramGb} GB RAM · ${vm.hardware.cpuThreads} CPU threads · ${vm.hardware.abi}",style=MaterialTheme.typography.bodySmall)
                Text("Model storage available at launch · ${formatBytes(vm.hardware.freeStorageBytes)}${if(vm.hardware.lowRamDevice) " · Android low-RAM device" else ""}",style=MaterialTheme.typography.bodySmall)
                Text("Analysis interval",fontWeight=FontWeight.Medium); Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(7,28,90).forEach{d->FilterChip(selected=vm.analysisDays==d,onClick={vm.analysisDays=d},label={Text("${d}d")})}}
                Text("Ollama downloads are SHA-256 verified. Gemini Nano availability is verified at runtime by ML Kit. Unsupported runtimes retain deterministic analysis.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        } } }
        item {
            SectionTitle(
                "Application updates",
                "VitalChronicle checks GitHub at launch, downloads a newer signed APK to private temporary storage and removes cached APKs automatically.",
            )
        }
        item { AppUpdateCard(vm, activity) }

        item { SectionTitle("Privacy & shared core", "VitalChronicle Android ${BuildConfig.VERSION_NAME} stores health records locally. General history is limited to ${DataRetention.GENERAL_DAYS} days and high-frequency cardiac data to ${DataRetention.HIGH_VOLUME_CARDIAC_DAYS} days. Deterministic metrics use the same Python core as desktop.") }
        item { OutlinedButton(onClick={vm.database.clearAll();vm.refresh()},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteOutline,null); Spacer(Modifier.width(8.dp)); Text("Delete local health archive") } }
        vm.lastError?.let { item { ErrorCard(it) } }
    }
}

@Composable fun AppUpdateCard(vm: VitalViewModel, activity: Activity?) {
    val state = vm.updateState
    val detail = when (state) {
        AppUpdateState.Idle -> "Waiting for the automatic check"
        AppUpdateState.Checking -> "Checking the latest GitHub release…"
        AppUpdateState.UpToDate -> "Version ${BuildConfig.VERSION_NAME} is up to date"
        is AppUpdateState.Downloading -> "Downloading signed version ${state.version}…"
        is AppUpdateState.Ready -> "Version ${state.info.version} is downloaded and verified"
        is AppUpdateState.Failed -> state.message
    }
    Card {
        ListItem(
            headlineContent = { Text("Automatic GitHub updates", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(detail) },
            leadingContent = {
                Icon(
                    Icons.Default.SystemUpdate,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            trailingContent = {
                when (state) {
                    AppUpdateState.Checking, is AppUpdateState.Downloading ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    is AppUpdateState.Ready ->
                        Button(
                            onClick = { activity?.let { vm.installUpdate(it) } },
                            enabled = activity != null,
                        ) { Text("Install") }
                    else ->
                        TextButton(onClick = vm::checkForAppUpdate) { Text("Check") }
                }
            },
        )
    }
}

@Composable fun GoogleSetupStep(number:Int,title:String,body:String,buttonLabel:String?=null,url:String?=null,content:@Composable ColumnScope.()->Unit={}) {
    Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.Top) {
                Surface(shape=RoundedCornerShape(50),color=MaterialTheme.colorScheme.primary) { Text(number.toString(),Modifier.padding(horizontal=9.dp,vertical=4.dp),color=MaterialTheme.colorScheme.onPrimary,fontWeight=FontWeight.Bold) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(title,fontWeight=FontWeight.SemiBold)
                    Spacer(Modifier.height(3.dp))
                    Text(body,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
            if(buttonLabel!=null && url!=null) ExternalLinkButton(buttonLabel,url)
        }
    }
}

@Composable fun ExternalLinkButton(label:String,url:String) {
    val context=LocalContext.current
    OutlinedButton(onClick={context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))},modifier=Modifier.fillMaxWidth()) {
        Icon(Icons.Default.OpenInNew,null); Spacer(Modifier.width(8.dp)); Text(label)
    }
}

@Composable fun HeroCard(title:String,subtitle:String,icon:ImageVector){ Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){ Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){ Icon(icon,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Column{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Spacer(Modifier.height(3.dp));Text(subtitle,style=MaterialTheme.typography.bodyMedium)} } } }
@Composable fun SectionTitle(title:String,subtitle:String){Column{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable fun EmptyCard(title:String,subtitle:String){Card{Column(Modifier.padding(18.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable fun ErrorCard(text:String){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Text(text,Modifier.padding(14.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
@Composable fun SettingCard(icon:ImageVector,title:String,subtitle:String,action: @Composable () -> Unit){Card{Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Spacer(Modifier.width(8.dp));action()}}}
fun metricColor(type:String)=when { type.contains("heart")->Color(0xFFE84C4F);type.contains("sleep")->Color(0xFF7E57C2);type.contains("oxygen")->Color(0xFF4285F4);type.contains("weight")->Color(0xFF5C6BC0);type.contains("step")->Color(0xFF34A853);type.contains("temperature")->Color(0xFFE91E63);else->Color(0xFFF39C12)}
fun formatMetric(v:Double)=if(kotlin.math.abs(v)>=100)"%.0f".format(v) else "%.1f".format(v)
