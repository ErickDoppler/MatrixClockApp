/*
 * Thin JNI bridge over the SVOX Pico C API.
 *
 * Pico is a small, entirely offline synthesiser that was part of Android for years, which makes it
 * a good fit for old hardware that cannot run a neural engine. This wrapper deliberately exposes
 * only what a speech service needs: open a voice, synthesise a string to 16-bit PCM, close.
 *
 * Synthesis is pull based — text goes in with pico_putTextUtf8, then pico_getData is called
 * repeatedly until Pico reports it has gone idle.
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "pico/picoapi.h"
#include "pico/picodefs.h"

#define TAG "PicoNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

/* Pico works out of one caller-supplied block; 2.5 MB is what Android's own Pico service used. */
#define PICO_MEM_SIZE (2500000)
#define VOICE_NAME ((const pico_Char *) "MatrixVoice")
#define OUT_CHUNK 4096

typedef struct {
    void *memory;
    pico_System system;
    pico_Resource textAnalysis;
    pico_Resource signalGeneration;
    pico_Engine engine;
} PicoContext;

static void picoClose(PicoContext *ctx) {
    if (ctx == NULL) return;
    if (ctx->engine != NULL) pico_disposeEngine(ctx->system, &ctx->engine);
    if (ctx->signalGeneration != NULL) pico_unloadResource(ctx->system, &ctx->signalGeneration);
    if (ctx->textAnalysis != NULL) pico_unloadResource(ctx->system, &ctx->textAnalysis);
    if (ctx->system != NULL) pico_terminate(&ctx->system);
    free(ctx->memory);
    free(ctx);
}

/*
 * Loads one voice: a text-analysis resource and a signal-generation resource, bound together under
 * a single voice definition. Returns 0 on failure, otherwise an opaque handle.
 */
JNIEXPORT jlong JNICALL
Java_com_example_picotts_PicoNative_nativeOpen(JNIEnv *env, jclass clazz, jstring jTa, jstring jSg) {
    const char *taPath = (*env)->GetStringUTFChars(env, jTa, NULL);
    const char *sgPath = (*env)->GetStringUTFChars(env, jSg, NULL);

    PicoContext *ctx = calloc(1, sizeof(PicoContext));
    if (ctx == NULL) goto fail;

    ctx->memory = malloc(PICO_MEM_SIZE);
    if (ctx->memory == NULL) goto fail;

    if (pico_initialize(ctx->memory, PICO_MEM_SIZE, &ctx->system) != PICO_OK) {
        LOGE("pico_initialize failed");
        goto fail;
    }
    if (pico_loadResource(ctx->system, (const pico_Char *) taPath, &ctx->textAnalysis) != PICO_OK) {
        LOGE("could not load text analysis resource %s", taPath);
        goto fail;
    }
    if (pico_loadResource(ctx->system, (const pico_Char *) sgPath, &ctx->signalGeneration) != PICO_OK) {
        LOGE("could not load signal generation resource %s", sgPath);
        goto fail;
    }

    pico_Char taName[PICO_MAX_RESOURCE_NAME_SIZE];
    pico_Char sgName[PICO_MAX_RESOURCE_NAME_SIZE];
    if (pico_getResourceName(ctx->system, ctx->textAnalysis, (char *) taName) != PICO_OK ||
        pico_getResourceName(ctx->system, ctx->signalGeneration, (char *) sgName) != PICO_OK) {
        LOGE("could not read resource names");
        goto fail;
    }

    if (pico_createVoiceDefinition(ctx->system, VOICE_NAME) != PICO_OK ||
        pico_addResourceToVoiceDefinition(ctx->system, VOICE_NAME, taName) != PICO_OK ||
        pico_addResourceToVoiceDefinition(ctx->system, VOICE_NAME, sgName) != PICO_OK) {
        LOGE("could not define voice");
        goto fail;
    }

    if (pico_newEngine(ctx->system, VOICE_NAME, &ctx->engine) != PICO_OK) {
        LOGE("could not create engine");
        goto fail;
    }

    (*env)->ReleaseStringUTFChars(env, jTa, taPath);
    (*env)->ReleaseStringUTFChars(env, jSg, sgPath);
    LOGI("Pico voice ready");
    return (jlong) (intptr_t) ctx;

fail:
    (*env)->ReleaseStringUTFChars(env, jTa, taPath);
    (*env)->ReleaseStringUTFChars(env, jSg, sgPath);
    picoClose(ctx);
    return 0;
}

JNIEXPORT void JNICALL
Java_com_example_picotts_PicoNative_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    picoClose((PicoContext *) (intptr_t) handle);
}

/*
 * Synthesises one string, handing each block of 16 kHz mono 16-bit PCM to the sink as Pico
 * produces it.
 *
 * Streaming rather than returning one finished array matters on slow hardware: Pico synthesises
 * several times faster than real time, so playback can start on the first block instead of waiting
 * for the whole sentence. Returns 0 on success, or -1 if Pico failed.
 *
 * Pico accepts the text in pieces and emits audio as it goes, so both the feeding and the draining
 * loops have to run to completion; stopping early truncates the sentence.
 */
JNIEXPORT jint JNICALL
Java_com_example_picotts_PicoNative_nativeSynthesize(JNIEnv *env, jclass clazz, jlong handle,
                                                    jstring jText, jobject sink) {
    PicoContext *ctx = (PicoContext *) (intptr_t) handle;
    if (ctx == NULL || ctx->engine == NULL) return -1;

    jclass sinkClass = (*env)->GetObjectClass(env, sink);
    jmethodID onAudio = (*env)->GetMethodID(env, sinkClass, "onAudio", "([BI)Z");
    if (onAudio == NULL) {
        LOGE("sink is missing onAudio([BI)Z");
        return -1;
    }

    jbyteArray buffer = (*env)->NewByteArray(env, OUT_CHUNK);
    if (buffer == NULL) return -1;

    const char *text = (*env)->GetStringUTFChars(env, jText, NULL);
    if (text == NULL) return -1;

    /* Pico needs the terminating zero, which is what tells it the sentence has ended. */
    pico_Int16 remaining = (pico_Int16) (strlen(text) + 1);
    const pico_Char *cursor = (const pico_Char *) text;
    jint result = 0;

    while (remaining > 0 && result == 0) {
        pico_Int16 consumed = 0;
        if (pico_putTextUtf8(ctx->engine, cursor, remaining, &consumed) != PICO_OK) {
            LOGE("pico_putTextUtf8 failed");
            result = -1;
            break;
        }
        cursor += consumed;
        remaining = (pico_Int16) (remaining - consumed);

        pico_Status status = PICO_STEP_BUSY;
        while (status == PICO_STEP_BUSY) {
            char chunk[OUT_CHUNK];
            pico_Int16 received = 0;
            pico_Int16 sampleType = 0;
            status = pico_getData(ctx->engine, chunk, OUT_CHUNK, &received, &sampleType);
            if (status != PICO_STEP_BUSY && status != PICO_STEP_IDLE) {
                LOGE("pico_getData failed with %d", (int) status);
                result = -1;
                break;
            }
            if (received <= 0) continue;

            (*env)->SetByteArrayRegion(env, buffer, 0, received, (const jbyte *) chunk);
            jboolean keepGoing = (*env)->CallBooleanMethod(env, sink, onAudio, buffer, (jint) received);
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                result = -1;
                break;
            }
            /* The service says stop when the framework cancels the utterance. */
            if (!keepGoing) {
                result = 0;
                remaining = 0;
                break;
            }
        }
    }

    (*env)->ReleaseStringUTFChars(env, jText, text);
    (*env)->DeleteLocalRef(env, buffer);
    return result;
}
