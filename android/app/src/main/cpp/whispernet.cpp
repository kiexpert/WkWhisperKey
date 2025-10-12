#include <jni.h>
#include <string>
#include <vector>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "WkWhisperKey", __VA_ARGS__)

static void* trtEngine = nullptr;
static int sampleRate = 16000;

extern std::vector<float> extractMelSpectrogram(const short* samples, int len);

std::string runInference(const std::vector<float>& mel) {
    // TODO: connect to TensorRT or NNAPI delegate
    return "hello"; // stub
}

extern "C" JNIEXPORT void JNICALL
Java_ai_willkim_wkwhisperkey_core_WkWhisperEngine_nativeInit(
        JNIEnv* env, jobject thiz, jobject assetMgr) {
    AAssetManager* mgr = AAssetManager_fromJava(env, assetMgr);
    LOGI("Loading wk_whisper_small_int8.engine...");
    // TODO: load TensorRT engine
}

extern "C" JNIEXPORT jstring JNICALL
Java_ai_willkim_wkwhisperkey_core_WkWhisperEngine_nativeInfer(
        JNIEnv* env, jobject thiz, jshortArray samples) {
    jsize len = env->GetArrayLength(samples);
    jshort* data = env->GetShortArrayElements(samples, nullptr);
    auto mel = extractMelSpectrogram(data, len);
    std::string text = runInference(mel);
    env->ReleaseShortArrayElements(samples, data, JNI_ABORT);
    return env->NewStringUTF(text.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_ai_willkim_wkwhisperkey_core_WkWhisperEngine_nativeRelease(
        JNIEnv* env, jobject thiz) {
    LOGI("Releasing wkwhispercore...");
    trtEngine = nullptr;
}
