#include <signal.h>
#include <android/log.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WkNativeSafety", __VA_ARGS__)

/**
 * 네이티브 안전 핸들러
 * ---------------------
 * JNI/Whisper 네이티브 코드에서 SIGSEGV, SIGABRT 등 감지.
 * 실제 복구는 불가능하지만 로그로 남기고 크래시 원인 추적 가능.
 */

static void sig_handler(int sig) {
    LOGE("⚠️ Caught native signal %d", sig);
}

extern "C" void register_native_safety() {
    signal(SIGABRT, sig_handler);
    signal(SIGSEGV, sig_handler);
    signal(SIGBUS,  sig_handler);
    signal(SIGFPE,  sig_handler);
}
