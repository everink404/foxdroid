#include <jni.h>
#include <cstdint>
#include <cstring>

extern "C" JNIEXPORT jboolean JNICALL Java_dev_foxdroid_app_PcmConversion_convert(
    JNIEnv* env, jobject, jobject input, jint offset, jint frames, jint channels, jint bytes, jobject output) {
    auto* src=static_cast<const uint8_t*>(env->GetDirectBufferAddress(input));
    auto* dst=static_cast<uint8_t*>(env->GetDirectBufferAddress(output));
    const auto inSize=env->GetDirectBufferCapacity(input), outSize=env->GetDirectBufferCapacity(output);
    if (!src || !dst || offset<0 || frames<0 || (channels!=1 && channels!=2) || (bytes!=2 && bytes!=4) ||
        static_cast<int64_t>(offset)+static_cast<int64_t>(frames)*channels*bytes>inSize ||
        static_cast<int64_t>(frames)*8>outSize) return JNI_FALSE;
    src+=offset;
    for (int i=0; i<frames; ++i) {
        float left, right;
        if (bytes==2) {
            int16_t sample; std::memcpy(&sample,src,2); src+=2; left=sample/32768.f;
            if (channels==2) { std::memcpy(&sample,src,2); src+=2; right=sample/32768.f; } else right=left;
        } else {
            std::memcpy(&left,src,4); src+=4;
            if (channels==2) { std::memcpy(&right,src,4); src+=4; } else right=left;
        }
        std::memcpy(dst,&left,4); std::memcpy(dst+4,&right,4); dst+=8;
    }
    return JNI_TRUE;
}
