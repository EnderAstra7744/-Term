/*
 * DTFA - minimal pty + fork/exec JNI bridge.
 *
 * This gives the app a real Linux pseudo-terminal so that "proot" (and the
 * Debian shell running under it) behaves like a normal terminal: job
 * control, correct window size, signals (Ctrl+C), etc.
 *
 * It intentionally does ONLY this one job so it's easy to audit/replace.
 */

#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <signal.h>
#include <android/log.h>

#define LOG_TAG "DTFA-pty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static char *jstring_to_cstr(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *tmp = (*env)->GetStringUTFChars(env, s, NULL);
    char *out = strdup(tmp);
    (*env)->ReleaseStringUTFChars(env, s, tmp);
    return out;
}

// Converts a Java String[] into a NULL-terminated char** (caller frees).
static char **jobjectArray_to_argv(JNIEnv *env, jobjectArray arr, int extra_front) {
    jsize len = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char **argv = calloc((size_t)(len + extra_front + 1), sizeof(char *));
    for (jsize i = 0; i < len; i++) {
        jstring js = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        argv[i + extra_front] = jstring_to_cstr(env, js);
        if (js) (*env)->DeleteLocalRef(env, js);
    }
    argv[len + extra_front] = NULL;
    return argv;
}

static void free_argv(char **argv) {
    if (!argv) return;
    for (int i = 0; argv[i] != NULL; i++) free(argv[i]);
    free(argv);
}

JNIEXPORT jint JNICALL
Java_com_dtfa_terminal_PtyNative_createSubprocess(
        JNIEnv *env, jclass clazz,
        jstring j_cmd, jstring j_cwd, jobjectArray j_args, jobjectArray j_env,
        jintArray j_pidOut, jint rows, jint cols) {

    char *cmd = jstring_to_cstr(env, j_cmd);
    char *cwd = jstring_to_cstr(env, j_cwd);
    char **argv = jobjectArray_to_argv(env, j_args, 1);
    argv[0] = strdup(cmd);
    char **envp = jobjectArray_to_argv(env, j_env, 0);

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;

    int master_fd;
    pid_t pid = forkpty(&master_fd, NULL, NULL, &ws);

    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        free(cmd); free(cwd); free_argv(argv); free_argv(envp);
        return -1;
    }

    if (pid == 0) {
        // Child: becomes the process running inside the new pty.
        setsid();
        if (cwd && chdir(cwd) != 0) {
            // Non-fatal: fall back to whatever the default is.
        }
        if (envp && envp[0] != NULL) {
            // Replace environment entirely with what Java gave us.
            clearenv();
            for (int i = 0; envp[i] != NULL; i++) {
                putenv(envp[i]); // ownership transferred; do not free.
            }
        }
        execvp(cmd, argv);
        // If we reach here, exec failed.
        _exit(127);
    }

    // Parent.
    free(cmd);
    free(cwd);
    free_argv(argv);
    free_argv(envp);

    if (j_pidOut != NULL) {
        jint pidVal = (jint) pid;
        (*env)->SetIntArrayRegion(env, j_pidOut, 0, 1, &pidVal);
    }

    // Non-blocking-friendly: leave blocking mode; Java side reads on its own thread.
    return master_fd;
}

JNIEXPORT void JNICALL
Java_com_dtfa_terminal_PtyNative_setWindowSize(
        JNIEnv *env, jclass clazz, jint fd, jint rows, jint cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) rows;
    ws.ws_col = (unsigned short) cols;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT jint JNICALL
Java_com_dtfa_terminal_PtyNative_waitFor(
        JNIEnv *env, jclass clazz, jint pid) {
    int status = 0;
    waitpid((pid_t) pid, &status, 0);
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    return -1;
}

JNIEXPORT void JNICALL
Java_com_dtfa_terminal_PtyNative_closeFd(
        JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

JNIEXPORT void JNICALL
Java_com_dtfa_terminal_PtyNative_sendSignal(
        JNIEnv *env, jclass clazz, jint pid, jint signal) {
    kill((pid_t) pid, signal);
}
