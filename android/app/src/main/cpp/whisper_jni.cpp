#include <jni.h>
#include <android/log.h>
//#include "wk_safety.cpp"   // 안전 핸들러 포함

#pragma once
#ifdef __cplusplus
extern "C" {
#endif

void register_native_safety();

#ifdef __cplusplus
}
#endif

extern "C"
JNIEXPORT jstring JNICALL
Java_ai_willkim_wkwhisperkey_whisper_native_WhisperCppEngine_nativeTranscribe(
        JNIEnv* env, jobject thiz, jobject buffer, jint length) {
    __android_log_print(ANDROID_LOG_INFO, "WkNative", "dummy nativeTranscribe called (%d bytes)", length);
    return env->NewStringUTF("[native stub: whisper not yet linked]");
}

/** JNI_OnLoad: 네이티브 라이브러리 로드시 자동 호출 */
jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    __android_log_print(ANDROID_LOG_INFO, "WkNative", "JNI_OnLoad called, registering safety hooks");
    register_native_safety();
    return JNI_VERSION_1_6;
}
