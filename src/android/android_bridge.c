/*
 * Android/SDL entry point and the very small JNI surface used by the
 * launcher. The game itself remains the same nfsu2_game_main() used by the
 * desktop build; this file only translates Android lifecycle/configuration
 * into the existing environment and XInput abstractions.
 */
#if defined(NFSU2_ANDROID)

#define SDL_MAIN_HANDLED
#include <SDL.h>
#include <jni.h>

#include <pthread.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "../../xboxrecomp/src/input/android_input.h"

extern int nfsu2_game_main(void);

static pthread_mutex_t s_config_lock = PTHREAD_MUTEX_INITIALIZER;
static char s_game_dir[1024];

/* SDL's Android activity invokes this symbol from its native main thread. */
int SDL_main(int argc, char **argv)
{
    (void)argc;
    (void)argv;
    return nfsu2_game_main();
}

const char *nfsu2_android_game_dir(void)
{
    return s_game_dir[0] ? s_game_dir : NULL;
}

static void copy_jstring(char *dst, size_t cap, JNIEnv *env, jstring value)
{
    const char *utf;
    if (!value) {
        dst[0] = 0;
        return;
    }
    utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf) {
        snprintf(dst, cap, "%s", utf);
        (*env)->ReleaseStringUTFChars(env, value, utf);
    } else {
        dst[0] = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_nfsu2x_GameActivity_nativeSetGameDirectory(JNIEnv *env, jclass cls,
                                                    jstring path)
{
    (void)cls;
    pthread_mutex_lock(&s_config_lock);
    copy_jstring(s_game_dir, sizeof s_game_dir, env, path);
    if (s_game_dir[0]) {
        char save_dir[1024];
        setenv("NFSU2_GAME_DIR", s_game_dir, 1);
        /* Android has no writable process CWD. Keep Xbox TitleData,
         * UserData and Cache beside the imported game in app-private files. */
        snprintf(save_dir, sizeof save_dir, "%s/../save", s_game_dir);
        setenv("NFSU2_SAVE_DIR", save_dir, 1);
    }
    pthread_mutex_unlock(&s_config_lock);
}

JNIEXPORT void JNICALL
Java_com_nfsu2x_GameActivity_nativeSetLogPath(JNIEnv *env, jclass cls,
                                              jstring path)
{
    char value[1024];
    int fd;
    (void)cls;
    copy_jstring(value, sizeof value, env, path);
    if (!value[0])
        return;

    fd = open(value, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0)
        return;
    /* Both streams go to one file so a crash report keeps the last native
     * and game messages in their original order. */
    if (dup2(fd, STDOUT_FILENO) >= 0)
        dup2(fd, STDERR_FILENO);
    close(fd);
    setvbuf(stdout, NULL, _IONBF, 0);
    setvbuf(stderr, NULL, _IONBF, 0);
    setenv("NFSU2_LOG_PATH", value, 1);
}

JNIEXPORT void JNICALL
Java_com_nfsu2x_GameActivity_nativeSetRuntimeOptions(JNIEnv *env, jclass cls,
                                                     jfloat scale, jboolean vsync)
{
    char value[32];
    (void)env;
    (void)cls;
    if (scale >= 0.5f && scale <= 2.0f) {
        snprintf(value, sizeof value, "%.3f", (double)scale);
        setenv("RECOMP_GL_SCALE", value, 1);
    }
    /* Android uses SDL's swap interval as the pacing primitive. Keep the
     * option explicit so a troubleshooting build can turn it off without a
     * second native binary. */
    setenv("NFSU2_ANDROID_VSYNC", vsync ? "1" : "0", 1);
}

JNIEXPORT void JNICALL
Java_com_nfsu2x_GameActivity_nativeTouchState(JNIEnv *env, jclass cls,
                                               jint digital, jbyteArray analog,
                                               jint lx, jint ly, jint rx, jint ry)
{
    jbyte values[8] = { 0 };
    int16_t thumbs[4];
    uint8_t buttons[8];
    jsize n;
    int i;
    (void)cls;
    n = analog ? (*env)->GetArrayLength(env, analog) : 0;
    if (analog && n > 0)
        (*env)->GetByteArrayRegion(env, analog, 0, n > 8 ? 8 : n, values);
    for (i = 0; i < 8; i++)
        buttons[i] = (uint8_t)(unsigned char)values[i];
    thumbs[0] = (int16_t)(lx < -32767 ? -32767 : lx > 32767 ? 32767 : lx);
    thumbs[1] = (int16_t)(ly < -32767 ? -32767 : ly > 32767 ? 32767 : ly);
    thumbs[2] = (int16_t)(rx < -32767 ? -32767 : rx > 32767 ? 32767 : rx);
    thumbs[3] = (int16_t)(ry < -32767 ? -32767 : ry > 32767 ? 32767 : ry);
    android_input_set_state((uint16_t)digital, buttons, thumbs);
}

#endif /* NFSU2_ANDROID */
