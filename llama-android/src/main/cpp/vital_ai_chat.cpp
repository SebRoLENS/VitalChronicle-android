// llama.cpp's current Android example uses __android_log_is_loggable, which
// Android introduced in API 30. VitalChronicle still supports API 26, so use
// the equivalent priority check on older targets while keeping every inference
// operation in the pinned upstream implementation.
#include <android/log.h>

#if __ANDROID_API__ < 30
#define __android_log_is_loggable(priority, tag, minimum) ((priority) >= (minimum))
#endif

#include "../../../../third_party/llama.cpp/examples/llama.android/lib/src/main/cpp/ai_chat.cpp"
