// VitalChronicle adapter around llama.cpp's Android example.
//
// 1. Android API < 30 compatibility for logging.
// 2. Ask llama.cpp to offload every supported layer to an available accelerator.
// 3. If an accelerator/driver rejects the model, retry the same GGUF on CPU.
//
// Backends themselves are loaded dynamically by upstream ai_chat.cpp from the
// application's nativeLibraryDir, so future signed APKs can add a device-specific
// backend without changing this inference policy.
#include <android/log.h>
#include "llama.h"

#if __ANDROID_API__ < 30
#define __android_log_is_loggable(priority, tag, minimum) ((priority) >= (minimum))
#endif

static llama_model_params vital_model_default_params() {
    auto params = llama_model_default_params();
    params.n_gpu_layers = 999;
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
