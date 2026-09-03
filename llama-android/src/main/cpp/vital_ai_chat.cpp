// VitalChronicle adapter around llama.cpp's Android example.
//
// 1. Android API < 30 compatibility for logging.
// 2. Prefer real device-specific accelerators, then specific GPU backends, then
//    generic Vulkan, and ask llama.cpp to offload every supported layer.
// 3. If an accelerator/driver rejects the model, retry the same GGUF on CPU.
//
// Backends themselves are loaded dynamically by upstream ai_chat.cpp from the
// application's nativeLibraryDir, so future signed APKs can add a device-specific
// backend without changing this inference policy.
#include <android/log.h>
#include <algorithm>
#include <cctype>
#include <string>
#include <vector>
#include "ggml-backend.h"
#include "llama.h"

#if __ANDROID_API__ < 30
#define __android_log_is_loggable(priority, tag, minimum) ((priority) >= (minimum))
#endif

static std::vector<ggml_backend_dev_t> vital_accelerator_devices;

static std::string vital_lower(std::string text) {
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return text;
}

static int vital_device_priority(ggml_backend_dev_t device) {
    const std::string name = ggml_backend_dev_name(device) != nullptr
            ? ggml_backend_dev_name(device) : "";
    const std::string description = ggml_backend_dev_description(device) != nullptr
            ? ggml_backend_dev_description(device) : "";
    const std::string identity = vital_lower(name + " " + description);

    // NPU/TPU/DSP-style backends are the most device-specific option. This also
    // covers future dynamically packaged backends without hard-coding one SoC.
    for (const char * hint : {"hexagon", "htp", "qnn", "npu", "tpu", "neuron", "apu", "dsp"}) {
        if (identity.find(hint) != std::string::npos) return 400;
    }
    // Vendor/device-specific GPU paths are preferred over the generic Vulkan path.
    for (const char * hint : {"opencl", "adreno", "mali", "powervr", "xclipse"}) {
        if (identity.find(hint) != std::string::npos) return 300;
    }
    if (identity.find("vulkan") != std::string::npos) return 200;
    return 250; // Unknown non-CPU backend: prefer it to generic Vulkan conservatively.
}

static llama_model_params vital_model_default_params() {
    auto params = llama_model_default_params();
    params.n_gpu_layers = 999;

    vital_accelerator_devices.clear();
    for (size_t i = 0; i < ggml_backend_dev_count(); ++i) {
        auto * device = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(device);
        if (type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            vital_accelerator_devices.push_back(device);
        }
    }
    std::stable_sort(
        vital_accelerator_devices.begin(),
        vital_accelerator_devices.end(),
        [](ggml_backend_dev_t left, ggml_backend_dev_t right) {
            return vital_device_priority(left) > vital_device_priority(right);
        });

    if (!vital_accelerator_devices.empty()) {
        vital_accelerator_devices.push_back(nullptr);
        params.devices = vital_accelerator_devices.data();
        auto * preferred = vital_accelerator_devices.front();
        __android_log_print(
            ANDROID_LOG_INFO,
            "VitalChronicleAI",
            "Preferred GGUF accelerator: %s (%s)",
            ggml_backend_dev_name(preferred),
            ggml_backend_dev_description(preferred));
    }
    return params;
}

static llama_model * vital_model_load_from_file(
        const char * path,
        llama_model_params params) {
    auto * model = llama_model_load_from_file(path, params);
    if (model == nullptr && params.n_gpu_layers > 0) {
        __android_log_print(
            ANDROID_LOG_WARN,
            "VitalChronicleAI",
            "Accelerated GGUF load failed; retrying CPU-only backend");
        params.n_gpu_layers = 0;
        params.devices = nullptr;
        model = llama_model_load_from_file(path, params);
    }
    return model;
}

// Keep the pinned upstream Android implementation while overriding only its
// model-loading policy. The wrapper functions above call the real llama.cpp
// symbols because these macros are intentionally defined afterwards.
#define llama_model_default_params vital_model_default_params
#define llama_model_load_from_file vital_model_load_from_file
#include "../../../../third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
#undef llama_model_load_from_file
#undef llama_model_default_params
