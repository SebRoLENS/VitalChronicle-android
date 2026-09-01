package io.github.sebrolens.vitalchronicle.android

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { VitalTheme { VitalApp() } }
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
                subtitle = if(vm.vault.token()!=null) "Google connected · ${vm.status}" else "Import your Google OAuth JSON in Settings, then connect your account.",
                icon = Icons.Default.Favorite
            )
        }
        if(vm.metrics.isEmpty()) item { EmptyCard("No local measurements yet", "Connect Google and run a sync. Health records stay on this device.") }
        items(vm.metrics, key={it.dataType}) { MetricCardView(it) }
        item { Button(onClick=vm::sync, enabled=!vm.busy && vm.vault.token()!=null, modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.Sync,null); Spacer(Modifier.width(8.dp)); Text("Download / update") } }
    }
}

@Composable fun MetricCardView(metric: MetricCard) {
    val color = metricColor(metric.dataType)
    Card { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, RoundedCornerShape(50)))
            Spacer(Modifier.width(8.dp)); Text(metric.label, fontWeight=FontWeight.SemiBold, modifier=Modifier.weight(1f))
            metric.deltaPercent?.let { Text("${if(it>=0) "+" else ""}${"%.1f".format(it)}%", color=if(it>=0) color else MaterialTheme.colorScheme.error) }
        }
        Spacer(Modifier.height(8.dp))
        Text(metric.current?.let { "${formatMetric(it)} ${metric.unit}" } ?: "—", style=MaterialTheme.typography.headlineMedium, fontWeight=FontWeight.Bold)
        if(metric.sparkline.size>1) { Spacer(Modifier.height(10.dp)); Sparkline(metric.sparkline,color,Modifier.fillMaxWidth().height(54.dp)) }
        metric.baseline?.let { Text("7-day baseline · ${formatMetric(it)} ${metric.unit}", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
    } }
}

@Composable fun Sparkline(values: List<Double>, color: Color, modifier: Modifier=Modifier) { Canvas(modifier) {
    val lo=values.minOrNull()?:0.0; val hi=values.maxOrNull()?:1.0; val span=max(1e-9,hi-lo); val step=size.width/(values.size-1)
    val path=Path(); values.forEachIndexed { i,v -> val x=i*step; val y=size.height-((v-lo)/span*size.height).toFloat(); if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { HeroCard("Private local AI", vm.aiModelName?.let{"Android built-in AI detected · $it"}?:"Automatic mode uses Gemini Nano when Android exposes it, otherwise deterministic evidence.", Icons.Default.AutoAwesome) }
        item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf(7,28,90).forEach { d -> FilterChip(selected=vm.analysisDays==d,onClick={vm.analysisDays=d},label={Text("${d}d")}) } } }
        item { OutlinedTextField(question,{question=it},label={Text("Ask about your data")},modifier=Modifier.fillMaxWidth(),minLines=3,maxLines=7) }
        item { Button(onClick={vm.analyse(question)},enabled=!vm.busy && vm.counts.isNotEmpty(),modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome,null); Spacer(Modifier.width(8.dp)); Text("Analyse locally") } }
        if(vm.busy) item { Card { Row(Modifier.padding(14.dp), verticalAlignment=Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp); Spacer(Modifier.width(12.dp)); Column { Text("VitalChronicle is working",fontWeight=FontWeight.SemiBold); Text(vm.status,style=MaterialTheme.typography.bodySmall) } } } }
        if(vm.aiAnswer.isNotBlank()) item { Card { SelectionContainer { Text(vm.aiAnswer,Modifier.padding(16.dp)) } } }
        vm.lastError?.let { err -> item { ErrorCard(err) } }
    }
}

@Composable fun SettingsScreen(vm: VitalViewModel) {
    val context=LocalContext.current; val activity=context as Activity
    val credentialLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()} ?: error("Could not read file") }.onSuccess(vm::importCredentials)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { SectionTitle("Google Health", "The same OAuth client JSON used by VitalChronicle desktop can be imported. Credentials and tokens are encrypted with Android Keystore.") }
        item { SettingCard(Icons.Default.Key,"OAuth credentials",if(vm.vault.credentials()!=null) "Configured" else "Not configured") {
            OutlinedButton(onClick={credentialLauncher.launch(arrayOf("application/json","text/json","text/plain"))}) { Text("Import JSON") }
        } }
        item { SettingCard(Icons.Default.AccountCircle,"Google account",if(vm.vault.token()!=null) "Connected" else "Not connected") {
            if(vm.vault.token()==null) Button(onClick={vm.connectGoogle(activity)},enabled=!vm.busy && vm.vault.credentials()!=null){Text("Connect")}
            else TextButton(onClick=vm::disconnectGoogle){Text("Disconnect")}
        } }
        item { SettingCard(Icons.Default.Sync,"Sync history","Up to ${DataRetention.GENERAL_DAYS} days; high-frequency cardiac streams keep ${DataRetention.HIGH_VOLUME_CARDIAC_DAYS} days") { Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){ listOf(30,90).forEach{d->FilterChip(selected=vm.historyDays==d,onClick={vm.historyDays=d},label={Text("${d}d")})} } } }

        item { SectionTitle("Local AI", "Automatic mode prefers Android's built-in Gemini Nano/AICore. No health data is sent to a cloud AI service.") }
        item { Card { Column(Modifier.padding(16.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("AI engine",fontWeight=FontWeight.SemiBold)
            AiEngine.entries.forEach { engine -> Row(Modifier.fillMaxWidth().clickable{vm.aiEngine=engine}.padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){ RadioButton(selected=vm.aiEngine==engine,onClick={vm.aiEngine=engine}); Column { Text(engine.title); if(engine==AiEngine.AUTOMATIC) Text("Recommended",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary) } } }
            vm.aiModelName?.let { Text("Detected built-in model · $it",style=MaterialTheme.typography.bodySmall) }
        } } }

        item { Card { Column {
            ListItem(headlineContent={Text("Advanced settings")},supportingContent={Text("Hardware, analysis window and explicit engine override")},trailingContent={Icon(if(vm.advancedOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,null)},modifier=Modifier.clickable{vm.advancedOpen=!vm.advancedOpen})
            AnimatedVisibility(vm.advancedOpen) { Column(Modifier.padding(start=16.dp,end=16.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("${vm.hardware.device} · ${vm.hardware.ramGb} GB RAM exposed · ${vm.hardware.cpuThreads} CPU threads",style=MaterialTheme.typography.bodySmall)
                Text("Analysis interval",fontWeight=FontWeight.Medium); Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(7,28,90).forEach{d->FilterChip(selected=vm.analysisDays==d,onClick={vm.analysisDays=d},label={Text("${d}d")})}}
                Text("Gemini Nano availability is verified at runtime by ML Kit. Unsupported devices automatically retain deterministic analysis.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        } } }
        item { SectionTitle("Privacy & shared core", "VitalChronicle Android ${BuildConfig.VERSION_NAME} stores health records locally. General history is limited to ${DataRetention.GENERAL_DAYS} days and high-frequency cardiac raw data to ${DataRetention.HIGH_VOLUME_CARDIAC_DAYS} days. Deterministic metrics use the same Python core as desktop.") }
        item { OutlinedButton(onClick={vm.database.clearAll();vm.refresh()},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Default.DeleteOutline,null); Spacer(Modifier.width(8.dp)); Text("Delete local health archive") } }
        vm.lastError?.let { item { ErrorCard(it) } }
    }
}

@Composable fun HeroCard(title:String,subtitle:String,icon:ImageVector){ Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)){ Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){ Icon(icon,null,Modifier.size(38.dp),tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(14.dp));Column{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Spacer(Modifier.height(3.dp));Text(subtitle,style=MaterialTheme.typography.bodyMedium)} } } }
@Composable fun SectionTitle(title:String,subtitle:String){Column{Text(title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
@Composable fun EmptyCard(title:String,subtitle:String){Card{Column(Modifier.padding(18.dp)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
@Composable fun ErrorCard(text:String){Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Text(text,Modifier.padding(14.dp),color=MaterialTheme.colorScheme.onErrorContainer)}}
@Composable fun SettingCard(icon:ImageVector,title:String,subtitle:String,action: @Composable () -> Unit){Card{Row(Modifier.padding(16.dp),verticalAlignment=Alignment.CenterVertically){Icon(icon,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(12.dp));Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Spacer(Modifier.width(8.dp));action()}}}
fun metricColor(type:String)=when { type.contains("heart")->Color(0xFFE84C4F);type.contains("sleep")->Color(0xFF7E57C2);type.contains("oxygen")->Color(0xFF4285F4);type.contains("weight")->Color(0xFF5C6BC0);type.contains("step")->Color(0xFF34A853);type.contains("temperature")->Color(0xFFE91E63);else->Color(0xFFF39C12)}
fun formatMetric(v:Double)=if(kotlin.math.abs(v)>=100)"%.0f".format(v) else "%.1f".format(v)
