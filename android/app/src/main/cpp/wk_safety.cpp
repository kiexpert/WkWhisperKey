#include <jni.h>
#include <signal.h>
#include <android/log.h>
#include <stdio.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "WkNativeSafety", __VA_ARGS__)

/**
 * 네이티브 오류 감시 핸들러
 * --------------------------
 * - SIGSEGV/SIGABRT 등 감지
 * - Java 쪽 LogReporter.dumpLogAndNotify() 호출
 */
static JavaVM* g_vm = nullptr;
static jobject g_appContext = nullptr; // ApplicationContext 보관

extern "C" JNIEXPORT void JNICALL
Java_ai_willkim_wkwhisperkey_whisper_native_WkSafetyBridge_registerContext(
        JNIEnv* env, jclass, jobject context) {
    if (g_appContext == nullptr) {
        g_appContext = env->NewGlobalRef(context);
        env->GetJavaVM(&g_vm);
        LOGE("✅ Native context registered for safety reporting");
    }
}

static void sendToJava(const char* reason) {
    if (!g_vm || !g_appContext) return;
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;

    jclass cls = env->FindClass("ai/willkim/wkwhisperkey/system/LogReporter");
    if (!cls) return;

    jmethodID mid = env->GetStaticMethodID(cls,
                                           "dumpLogAndNotify",
                                           "(Landroid/content/Context;Ljava/lang/String;)V");
    if (!mid) return;

    jstring jreason = env->NewStringUTF(reason);
    env->CallStaticVoidMethod(cls, mid, g_appContext, jreason);
    env->DeleteLocalRef(jreason);
}

static void sig_handler(int sig) {
    char msg[128];
    snprintf(msg, sizeof(msg), "⚠️ Native signal %d caught", sig);
    LOGE("%s", msg);
    sendToJava(msg);
}

extern "C" void register_native_safety() {
    signal(SIGABRT, sig_handler);
    signal(SIGSEGV, sig_handler);
    signal(SIGBUS,  sig_handler);
    signal(SIGFPE,  sig_handler);
    LOGE("🛡️ Native safety hooks active");
}
