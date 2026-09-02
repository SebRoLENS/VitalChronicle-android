#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path: str, old: str, new: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}\n--- needle ---\n{old[:500]}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8")


# 1) General, vendor-neutral hardware profile.
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/Models.kt",
    '''data class HardwareProfile(\n    val ramGb: Int,\n    val cpuThreads: Int,\n    val device: String,\n    val abi: String,\n    val freeStorageBytes: Long,\n    val lowRamDevice: Boolean,\n)\n''',
    '''data class HardwareProfile(\n    val ramGb: Int,\n    val cpuThreads: Int,\n    val device: String,\n    val abi: String,\n    val freeStorageBytes: Long,\n    val lowRamDevice: Boolean,\n    val socManufacturer: String,\n    val socModel: String,\n    val vulkanCompute: Boolean,\n    val vulkanVersion: Int,\n    val packagedGgufBackends: Set<String>,\n) {\n    val socDescription: String\n        get() = listOf(socManufacturer, socModel)\n            .filter { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }\n            .joinToString(" ")\n            .ifBlank { "SoC not reported by Android" }\n\n    val ggufHardwareAccelerated: Boolean\n        get() = when {\n            "HEXAGON" in packagedGgufBackends && socManufacturer.contains("qualcomm", ignoreCase = true) -> true\n            "VULKAN" in packagedGgufBackends && vulkanCompute -> true\n            "OPENCL" in packagedGgufBackends -> true\n            else -> false\n        }\n\n    val ggufAccelerationBackend: String\n        get() = when {\n            "HEXAGON" in packagedGgufBackends && socManufacturer.contains("qualcomm", ignoreCase = true) -> "Snapdragon Hexagon NPU"\n            "VULKAN" in packagedGgufBackends && vulkanCompute -> "Vulkan GPU"\n            "OPENCL" in packagedGgufBackends -> "OpenCL GPU"\n            else -> "ARM CPU · KleidiAI"\n        }\n\n    val accelerationSummary: String\n        get() = buildString {\n            append("GGUF: ").append(ggufAccelerationBackend)\n            if (!ggufHardwareAccelerated && vulkanCompute) {\n                append(" · Vulkan compute detected, but this APK has no Vulkan GGUF backend")\n            }\n        }\n}\n\ndata class NanoCapability(\n    val supported: Boolean = false,\n    val ready: Boolean = false,\n    val modelName: String? = null,\n    val status: String = "Checking",\n) {\n    val runtimeLabel: String\n        get() = if (supported) "Android AICore · optimized on-device runtime" else "Android AICore unavailable"\n}\n'''
)

# 2) Ask ML Kit/AICore itself instead of guessing support from a phone model list.
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/GeminiNanoEngine.kt",
    '''    suspend fun modelName(): String? = runCatching {\n        val selection = selectModel(allowDownload = false)\n        if (selection.model.checkStatus() == FeatureStatus.AVAILABLE) {\n            "${selection.model.getBaseModelName()} · ${selection.profile}"\n        } else {\n            null\n        }\n    }.getOrNull()\n''',
    '''    suspend fun capability(): NanoCapability {\n        val candidates = listOf(\n            Triple(accurateModel, "Accurate local · FULL", true),\n            Triple(compatibleModel, "Accurate local · compatible", false),\n        )\n        for ((model, profile, _) in candidates) {\n            val status = runCatching { model.checkStatus() }.getOrNull() ?: continue\n            val supported = status == FeatureStatus.AVAILABLE ||\n                status == FeatureStatus.DOWNLOADABLE ||\n                status == FeatureStatus.DOWNLOADING\n            if (!supported) continue\n            val name = runCatching { model.getBaseModelName() }.getOrNull()\n            return NanoCapability(\n                supported = true,\n                ready = status == FeatureStatus.AVAILABLE,\n                modelName = name?.let { "$it · $profile" } ?: profile,\n                status = status.toString(),\n            )\n        }\n        return NanoCapability(status = "Unavailable")\n    }\n\n    suspend fun modelName(): String? = capability().takeIf { it.ready }?.modelName\n'''
)

# 3) Hardware probing in the ViewModel.
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt",
    '''import android.content.Intent\nimport android.os.Build\n''',
    '''import android.content.Intent\nimport android.content.pm.PackageManager\nimport android.os.Build\n'''
)
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt",
    '''    var aiEngine by mutableStateOf(AiEngine.AUTOMATIC)\n    var aiModelName by mutableStateOf<String?>(null); private set\n''',
    '''    var aiEngine by mutableStateOf(AiEngine.AUTOMATIC)\n    var aiModelName by mutableStateOf<String?>(null); private set\n    var nanoCapability by mutableStateOf(NanoCapability()); private set\n'''
)
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt",
    '''    val hardware: HardwareProfile = run {\n        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager\n        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)\n        HardwareProfile(\n            ramGb = ((info.totalMem + 536_870_912L) / 1_073_741_824L).toInt(),\n            cpuThreads = Runtime.getRuntime().availableProcessors(),\n            device = "${Build.MANUFACTURER} ${Build.MODEL}",\n            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },\n            freeStorageBytes = ollamaModels.freeStorageBytes(),\n            lowRamDevice = am.isLowRamDevice,\n        )\n    }\n    val ollamaCatalog: List<OllamaModelSpec> = OllamaModelCatalog.models\n    val recommendedOllamaModel: OllamaModelSpec = OllamaModelCatalog.recommended(hardware)\n''',
    '''    val hardware: HardwareProfile = run {\n        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager\n        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)\n        val pm = app.packageManager\n        val nativeLibraries = File(app.applicationInfo.nativeLibraryDir)\n            .listFiles()\n            ?.map { it.name.lowercase() }\n            .orEmpty()\n        val packagedBackends = buildSet {\n            if (nativeLibraries.any { "vulkan" in it }) add("VULKAN")\n            if (nativeLibraries.any { "hexagon" in it || "ggml-htp" in it }) add("HEXAGON")\n            if (nativeLibraries.any { "opencl" in it }) add("OPENCL")\n        }\n        val socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else Build.HARDWARE\n        val socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.BOARD\n        val vulkanVersion = pm.systemAvailableFeatures\n            .firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }\n            ?.version ?: 0\n        HardwareProfile(\n            ramGb = ((info.totalMem + 536_870_912L) / 1_073_741_824L).toInt(),\n            cpuThreads = Runtime.getRuntime().availableProcessors(),\n            device = "${Build.MANUFACTURER} ${Build.MODEL}",\n            abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" },\n            freeStorageBytes = ollamaModels.freeStorageBytes(),\n            lowRamDevice = am.isLowRamDevice,\n            socManufacturer = socManufacturer.ifBlank { "unknown" },\n            socModel = socModel.ifBlank { "unknown" },\n            vulkanCompute = pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE),\n            vulkanVersion = vulkanVersion,\n            packagedGgufBackends = packagedBackends,\n        )\n    }\n    val ollamaCatalog: List<OllamaModelSpec> = OllamaModelCatalog.models\n    val recommendedOllamaModel: OllamaModelSpec = OllamaModelCatalog.recommended(hardware)\n    val recommendedAiPath: String\n        get() = when {\n            nanoCapability.supported -> "${nanoCapability.modelName ?: "Gemini Nano"} · ${nanoCapability.runtimeLabel}"\n            hardware.ggufHardwareAccelerated -> "${recommendedOllamaModel.id} · ${hardware.ggufAccelerationBackend}"\n            else -> "${recommendedOllamaModel.id} · ${hardware.ggufAccelerationBackend}"\n        }\n'''
)

# Automatic mode now prefers an actually accelerated path, not simply an installed GGUF.
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt",
    '''                    val useDownloadedModel = aiEngine == AiEngine.OLLAMA_LOCAL ||\n                        (aiEngine == AiEngine.AUTOMATIC && installedModel != null)\n''',
    '''                    val useDownloadedModel = aiEngine == AiEngine.OLLAMA_LOCAL ||\n                        (aiEngine == AiEngine.AUTOMATIC && installedModel != null && hardware.ggufHardwareAccelerated)\n'''
)
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt",
    '''        } catch (e: LinkageError) {\n            useDeterministicFallback(e, ollamaFailure, databasePath, start, end)\n        } catch (e: Exception) {\n            useDeterministicFallback(e, ollamaFailure, databasePath, start, end)\n        }\n    }\n\n    private suspend fun useDeterministicFallback(\n''',
    '''        } catch (e: LinkageError) {\n            fallbackAfterNano(e, ollamaFailure, question, evidence, databasePath, start, end)\n        } catch (e: Exception) {\n            fallbackAfterNano(e, ollamaFailure, question, evidence, databasePath, start, end)\n        }\n    }\n\n    private suspend fun fallbackAfterNano(\n        nanoFailure: Throwable,\n        earlierOllamaFailure: Throwable?,\n        question: String,\n        evidence: String,\n        databasePath: String,\n        start: String,\n        end: String,\n    ) {\n        if (aiEngine == AiEngine.GEMINI_NANO) throw nanoFailure\n        if (aiEngine == AiEngine.AUTOMATIC && earlierOllamaFailure == null) {\n            val selected = ollamaCatalog.firstOrNull { it.id == selectedOllamaModelId }\n            val installed = selected?.let(ollamaModels::installedFile)\n            if (selected != null && installed != null) {\n                status = "Accelerated Android AI unavailable · trying ${selected.id} on ${hardware.ggufAccelerationBackend}…"\n                try {\n                    analyseWithOllama(selected, installed, question, evidence)\n                    return\n                } catch (e: CancellationException) {\n                    throw e\n                } catch (e: Throwable) {\n                    if (e is VirtualMachineError || e is ThreadDeath) throw e\n                    useDeterministicFallback(e, nanoFailure, databasePath, start, end)\n                    return\n                }\n            }\n        }\n        useDeterministicFallback(nanoFailure, earlierOllamaFailure, databasePath, start, end)\n    }\n\n    private suspend fun useDeterministicFallback(\n'''
)
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/VitalViewModel.kt",
    '''    private fun probeAi() {\n        val selected = ollamaCatalog.firstOrNull { it.id == selectedOllamaModelId }\n        if (selected != null && ollamaModels.installedFile(selected) != null) {\n            aiModelName = "${selected.id} · on-device llama.cpp"\n            return\n        }\n        viewModelScope.launch { aiModelName = nano.modelName() }\n    }\n''',
    '''    private fun probeAi() {\n        viewModelScope.launch {\n            nanoCapability = nano.capability()\n            val selected = ollamaCatalog.firstOrNull { it.id == selectedOllamaModelId }\n            val installed = selected?.let(ollamaModels::installedFile)\n            aiModelName = when {\n                selected != null && installed != null && hardware.ggufHardwareAccelerated ->\n                    "${selected.id} · ${hardware.ggufAccelerationBackend}"\n                nanoCapability.supported ->\n                    "${nanoCapability.modelName ?: "Gemini Nano"} · Android AICore"\n                selected != null && installed != null ->\n                    "${selected.id} · ${hardware.ggufAccelerationBackend}"\n                else -> null\n            }\n        }\n    }\n'''
)

# 4) Make acceleration visible on every GGUF model card.
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/OllamaModelCard.kt",
    '''                if(model.supportsThinking) AssistChip(onClick={},label={Text("Thinking")},leadingIcon={Icon(Icons.Default.Psychology,null)})\n                Text(\n                    if(hardwareFit) "Fits this phone" else "Needs about ${model.minimumRamGb} GB RAM",\n''',
    '''                if(model.supportsThinking) AssistChip(onClick={},label={Text("Thinking")},leadingIcon={Icon(Icons.Default.Psychology,null)})\n                AssistChip(\n                    onClick={},\n                    label={Text(if(vm.hardware.ggufHardwareAccelerated) "Accelerated · ${vm.hardware.ggufAccelerationBackend}" else "CPU · ${vm.hardware.ggufAccelerationBackend}")}\n                )\n                Text(\n                    if(hardwareFit) "Fits this phone" else "Needs about ${model.minimumRamGb} GB RAM",\n'''
)

# 5) Settings explains the overall recommendation instead of only RAM-based Ollama choice.
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/MainActivity.kt",
    '''                        Text("Recommended · ${vm.recommendedOllamaModel.id}",fontWeight=FontWeight.Bold)\n                    }\n                    Text(\n                        "Chosen from ${vm.hardware.ramGb} GB RAM, ${vm.hardware.cpuThreads} CPU threads, ${vm.hardware.abi} and ${formatBytes(vm.hardware.freeStorageBytes)} free when VitalChronicle started.",\n''',
    '''                        Text("Automatic recommendation · ${vm.recommendedAiPath}",fontWeight=FontWeight.Bold)\n                    }\n                    Text(\n                        "${vm.hardware.device} · ${vm.hardware.socDescription}. Chosen from ${vm.hardware.ramGb} GB RAM, ${vm.hardware.cpuThreads} CPU threads, ${vm.hardware.abi}, ${formatBytes(vm.hardware.freeStorageBytes)} free and the acceleration backends actually available to this APK.",\n'''
)
replace_once(
    "app/src/main/java/io/github/sebrolens/vitalchronicle/android/MainActivity.kt",
    '''                Text("${vm.hardware.device} · ${vm.hardware.ramGb} GB RAM · ${vm.hardware.cpuThreads} CPU threads · ${vm.hardware.abi}",style=MaterialTheme.typography.bodySmall)\n                Text("Model storage available at launch · ${formatBytes(vm.hardware.freeStorageBytes)}${if(vm.hardware.lowRamDevice) " · Android low-RAM device" else ""}",style=MaterialTheme.typography.bodySmall)\n''',
    '''                Text("${vm.hardware.device} · ${vm.hardware.socDescription} · ${vm.hardware.ramGb} GB RAM · ${vm.hardware.cpuThreads} CPU threads · ${vm.hardware.abi}",style=MaterialTheme.typography.bodySmall)\n                Text(vm.hardware.accelerationSummary,style=MaterialTheme.typography.bodySmall)\n                Text("Gemini Nano · ${if(vm.nanoCapability.supported) vm.nanoCapability.runtimeLabel else vm.nanoCapability.status}",style=MaterialTheme.typography.bodySmall)\n                Text("Model storage available at launch · ${formatBytes(vm.hardware.freeStorageBytes)}${if(vm.hardware.lowRamDevice) " · Android low-RAM device" else ""}",style=MaterialTheme.typography.bodySmall)\n'''
)

print("Hardware-aware AI routing patch applied successfully")
