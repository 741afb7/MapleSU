#define _GNU_SOURCE
#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/system_properties.h>

JNIEXPORT jstring JNICALL
Java_dev_maplesu_acceptance_su_ProbeActivity_nativeIdentity(JNIEnv *env, jclass type) {
    (void)type;
    uid_t ruid = 0, euid = 0, suid = 0;
    gid_t rgid = 0, egid = 0, sgid = 0;
    int uid_error = getresuid(&ruid, &euid, &suid) == 0 ? 0 : errno;
    int gid_error = getresgid(&rgid, &egid, &sgid) == 0 ? 0 : errno;
    char result[320];
    snprintf(result, sizeof(result),
        "{\"pointerBits\":%u,\"pid\":%u,\"uidError\":%d,\"gidError\":%d,"
        "\"uids\":[%u,%u,%u],\"gids\":[%u,%u,%u]}",
        (unsigned)(sizeof(void *) * 8), (unsigned)getpid(), uid_error, gid_error,
        (unsigned)ruid, (unsigned)euid, (unsigned)suid,
        (unsigned)rgid, (unsigned)egid, (unsigned)sgid);
    return (*env)->NewStringUTF(env, result);
}

/* Read through this ordinary app's Bionic property access, never getprop or hidden APIs. */
JNIEXPORT jobjectArray JNICALL
Java_dev_maplesu_acceptance_su_ProbeActivity_nativeProperty(JNIEnv *env, jclass type, jstring name) {
    (void)type;
    const char *key = (*env)->GetStringUTFChars(env, name, NULL);
    if (key == NULL) return NULL;
    char value[PROP_VALUE_MAX] = {0};
    errno = 0;
    int length = __system_property_get(key, value);
    int observed_errno = errno;
    (*env)->ReleaseStringUTFChars(env, name, key);
    jclass string_type = (*env)->FindClass(env, "java/lang/String");
    if (string_type == NULL) return NULL;
    jobjectArray result = (*env)->NewObjectArray(env, 3, string_type, NULL);
    if (result == NULL) return NULL;
    char number[32];
    snprintf(number, sizeof(number), "%d", length);
    (*env)->SetObjectArrayElement(env, result, 0, (*env)->NewStringUTF(env, number));
    snprintf(number, sizeof(number), "%d", observed_errno);
    (*env)->SetObjectArrayElement(env, result, 1, (*env)->NewStringUTF(env, number));
    (*env)->SetObjectArrayElement(env, result, 2, (*env)->NewStringUTF(env, value));
    return result;
}
