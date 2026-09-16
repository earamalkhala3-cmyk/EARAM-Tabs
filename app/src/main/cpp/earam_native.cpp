#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_earam_tabs_NativeAudio_nativeHello(JNIEnv* env, jobject) {
    return env->NewStringUTF("EARAM native audio engine ready");
}
