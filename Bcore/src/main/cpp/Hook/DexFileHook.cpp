#include "DexFileHook.h"
#include <IO.h>
#include <BoxCore.h>
#include "UnixFileSystemHook.h"
#import "JniHook/JniHook.h"
#include <sys/stat.h>
#include <cstring>

namespace {

bool isBaseDexClassLoader(JNIEnv *env, jobject loader) {
    if (loader == nullptr) {
        return false;
    }
    jclass baseDexClassLoader = env->FindClass("dalvik/system/BaseDexClassLoader");
    if (baseDexClassLoader == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return false;
    }
    bool result = env->IsInstanceOf(loader, baseDexClassLoader);
    env->DeleteLocalRef(baseDexClassLoader);
    return result;
}

jobject normalizeDexClassLoader(JNIEnv *env, jobject requestedLoader) {
    // Android 14/15 ART rejects arbitrary wrapper ClassLoaders in openDexFileNative with
    // "Unsupported class loader". This is common in protectors, plugin frameworks and hotfix
    // engines. Keep a valid BaseDexClassLoader untouched; otherwise use the virtual package's
    // LoadedApk ClassLoader instead of the host/system ClassLoader.
    if (requestedLoader == nullptr || isBaseDexClassLoader(env, requestedLoader)) {
        return requestedLoader;
    }

    jobject guestLoader = BoxCore::getAppClassLoader(env);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        guestLoader = nullptr;
    }
    if (guestLoader != nullptr && isBaseDexClassLoader(env, guestLoader)) {
        ALOGD("DexFileHook: normalized unsupported loader to guest BaseDexClassLoader");
        return guestLoader;
    }
    if (guestLoader != nullptr) {
        env->DeleteLocalRef(guestLoader);
    }
    return requestedLoader;
}

} // namespace

HOOK_JNI(jobject, openDexFileNative, JNIEnv *env, jobject obj, jstring sourceName,
         jstring outputName, jint flags, jobject loader, jobject elements) {
    const char *sourceNameC = nullptr;
    if (sourceName != nullptr) {
        sourceNameC = env->GetStringUTFChars(sourceName, JNI_FALSE);
        if (sourceNameC != nullptr) {
            ALOGD("openDexFileNative: %s", sourceNameC);
            // Android 14+ rejects writable dynamically loaded code. Only harden files owned by
            // the virtual store; never chmod framework/APEX or external files supplied by apps.
            if (strstr(sourceNameC, "/blackbox/") != nullptr) {
                DexFileHook::setFileReadonly(sourceNameC);
            }
        }
    }

    jobject normalizedLoader = normalizeDexClassLoader(env, loader);
    jobject result = orig_openDexFileNative(env, obj, sourceName, outputName, flags,
                                             normalizedLoader, elements);

    // If a protector supplied an unsupported wrapper and the first call still failed without a
    // Java exception, retry once with the guest loader. Do not swallow real verifier/format errors.
    if (result == nullptr && !env->ExceptionCheck() && normalizedLoader == loader &&
        loader != nullptr && !isBaseDexClassLoader(env, loader)) {
        jobject guestLoader = BoxCore::getAppClassLoader(env);
        if (!env->ExceptionCheck() && guestLoader != nullptr && isBaseDexClassLoader(env, guestLoader)) {
            ALOGD("DexFileHook: retrying openDexFileNative with guest ClassLoader");
            result = orig_openDexFileNative(env, obj, sourceName, outputName, flags,
                                             guestLoader, elements);
        }
        if (env->ExceptionCheck()) {
            // Preserve exceptions produced by the actual retry; callers expect ART semantics.
        }
        if (guestLoader != nullptr) env->DeleteLocalRef(guestLoader);
    }

    if (normalizedLoader != nullptr && normalizedLoader != loader) {
        env->DeleteLocalRef(normalizedLoader);
    }
    if (sourceNameC != nullptr) {
        env->ReleaseStringUTFChars(sourceName, sourceNameC);
    }
    return result;
}

void DexFileHook::init(JNIEnv *env) {
    if (BoxCore::getApiLevel() >= __ANDROID_API_U__) {
        const char *clazz = "dalvik/system/DexFile";
        JniHook::HookJniFun(
                env, clazz, "openDexFileNative",
                "(Ljava/lang/String;Ljava/lang/String;ILjava/lang/ClassLoader;[Ldalvik/system/DexPathList$Element;)Ljava/lang/Object;",
                (void *) new_openDexFileNative, (void **) (&orig_openDexFileNative), true);
    }
}

void DexFileHook::setFileReadonly(const char* filePath) {
    struct stat fileStat;
    if (filePath == nullptr || stat(filePath, &fileStat) != 0) {
        ALOGD("DexFileHook::setFileReadonly: %s does not exist", filePath ? filePath : "<null>");
        return;
    }

    // Keep execute/search bits and group readability where they already exist. Removing every
    // permission except S_IRUSR can break native/plugin loaders that reopen the APK from helpers.
    mode_t mode = fileStat.st_mode & 0777;
    mode &= ~(S_IWUSR | S_IWGRP | S_IWOTH);
    if (chmod(filePath, mode) != 0) {
        ALOGD("DexFileHook::setFileReadonly: failed for %s", filePath);
    } else {
        ALOGD("DexFileHook::setFileReadonly: hardened %s", filePath);
    }
}
