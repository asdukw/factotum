#include <jni.h>
#include <string>
#include <cstdlib>
#include <cstring>
#include "node.h"
#include <pthread.h>
#include <unistd.h>
#include <android/log.h>

// 内嵌 Node 的 stdio 接线：
// - stdin：宿主 Kotlin 经 JNI 写入，Node 侧由 dsh-sdk-jsonrpc-server 读取（JSON-RPC 请求）
// - stdout：JSON-RPC 帧，逐行回调给 Kotlin（**不写 logcat**：帧里可能含屏幕/聊天正文）
// - stderr：诊断信息，转发到 logcat（只应是短日志，不承载 payload）
static int pipe_stdin[2];
static int pipe_stdout[2];
static int pipe_stderr[2];
static pthread_t thread_stdout;
static pthread_t thread_stderr;
static bool stdio_redirected = false;
static const char *ADBTAG = "NODEJS-MOBILE";

static JavaVM *java_vm = nullptr;
static jobject line_listener = nullptr;
static jmethodID listener_on_line = nullptr;

static void forward_line_to_kotlin(JNIEnv *env, const char *line) {
    if (line_listener == nullptr || listener_on_line == nullptr) {
        __android_log_write(ANDROID_LOG_WARN, ADBTAG, "listener not ready; line dropped");
        return;
    }
    jstring jline = env->NewStringUTF(line);
    if (jline == nullptr) return;
    env->CallVoidMethod(line_listener, listener_on_line, jline);
    env->DeleteLocalRef(jline);
    if (env->ExceptionCheck()) {
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "listener callback raised");
        env->ExceptionClear();
    }
}

static void *thread_stderr_func(void *) {
    ssize_t redirect_size;
    char buf[2048];
    while ((redirect_size = read(pipe_stderr[0], buf, sizeof buf - 1)) > 0) {
        if (buf[redirect_size - 1] == '\n') --redirect_size;
        buf[redirect_size] = 0;
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, buf);
    }
    return nullptr;
}

static void *thread_stdout_func(void *) {
    JNIEnv *env = nullptr;
    bool attached = false;
    if (java_vm != nullptr) {
        if (java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
    }
    if (env == nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "stdout thread without JNIEnv; frames only logged");
    }

    std::string pending;
    pending.reserve(4096);
    char buf[4096];
    ssize_t count;
    while ((count = read(pipe_stdout[0], buf, sizeof buf)) > 0) {
        pending.append(buf, static_cast<size_t>(count));
        size_t newline;
        while ((newline = pending.find('\n')) != std::string::npos) {
            std::string line = pending.substr(0, newline);
            pending.erase(0, newline + 1);
            // 只记帧长度，不记帧内容（可能含屏幕/聊天正文）。
            __android_log_print(ANDROID_LOG_VERBOSE, ADBTAG, "stdout frame %zu bytes", line.size());
            if (env != nullptr) forward_line_to_kotlin(env, line.c_str());
        }
        // 防御：异常超长且无换行的输出直接丢弃，避免内存失控。
        if (pending.size() > (1u << 20)) {
            __android_log_print(ANDROID_LOG_WARN, ADBTAG, "stdout frame overflow, dropped %zu bytes", pending.size());
            pending.clear();
        }
    }

    if (attached) java_vm->DetachCurrentThread();
    return nullptr;
}

static int start_redirecting_stdin_stdout_stderr() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    pipe(pipe_stdout);
    dup2(pipe_stdout[1], STDOUT_FILENO);

    setvbuf(stderr, nullptr, _IONBF, 0);
    pipe(pipe_stderr);
    dup2(pipe_stderr[1], STDERR_FILENO);

    pipe(pipe_stdin);
    dup2(pipe_stdin[0], STDIN_FILENO);

    if (pthread_create(&thread_stdout, nullptr, thread_stdout_func, nullptr) == -1) return -1;
    pthread_detach(thread_stdout);

    if (pthread_create(&thread_stderr, nullptr, thread_stderr_func, nullptr) == -1) return -1;
    pthread_detach(thread_stderr);

    return 0;
}

static jboolean ensure_stdio_redirected() {
    if (stdio_redirected) return JNI_TRUE;
    if (start_redirecting_stdin_stdout_stderr() == -1) return JNI_FALSE;
    stdio_redirected = true;
    return JNI_TRUE;
}

// 提前建好 stdin/stdout/stderr 管道（在 node::Start 之前调用），
// 这样 Kotlin 在状态变为 Running 后写 stdin 一定不会落空。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_factotum_app_node_NodeRuntime_prepareStdio(
        JNIEnv *env,
        jobject /* this */) {
    env->GetJavaVM(&java_vm);
    return ensure_stdio_redirected();
}

// libUV 要求参数在连续内存里。
extern "C" JNIEXPORT jint JNICALL
Java_com_factotum_app_node_NodeRuntime_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {

    env->GetJavaVM(&java_vm);

    jsize argument_count = env->GetArrayLength(arguments);

    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        const char *arg = env->GetStringUTFChars((jstring) env->GetObjectArrayElement(arguments, i), nullptr);
        c_arguments_size += static_cast<int>(strlen(arg));
        c_arguments_size++;
        env->ReleaseStringUTFChars((jstring) env->GetObjectArrayElement(arguments, i), arg);
    }

    char *args_buffer = (char *) calloc(c_arguments_size, sizeof(char));
    if (args_buffer == nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "calloc for argv failed");
        return -1;
    }
    char *argv[argument_count];
    char *current_args_position = args_buffer;

    for (int i = 0; i < argument_count; i++) {
        const char *current_argument = env->GetStringUTFChars((jstring) env->GetObjectArrayElement(arguments, i), nullptr);
        strncpy(current_args_position, current_argument, strlen(current_argument));
        argv[i] = current_args_position;
        current_args_position += strlen(current_args_position) + 1;
        env->ReleaseStringUTFChars((jstring) env->GetObjectArrayElement(arguments, i), current_argument);
    }

    if (!ensure_stdio_redirected()) {
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "Couldn't start redirecting stdio");
    }

    int exit_code = node::Start(argument_count, argv);
    free(args_buffer);
    return exit_code;
}

// 注册 stdout 逐行回调（NodeRuntime 单例；在 node::Start 之前调用）。
extern "C" JNIEXPORT void JNICALL
Java_com_factotum_app_node_NodeRuntime_setLineListener(
        JNIEnv *env,
        jobject /* this */,
        jobject listener) {
    env->GetJavaVM(&java_vm);
    if (line_listener != nullptr) {
        env->DeleteGlobalRef(line_listener);
        line_listener = nullptr;
        listener_on_line = nullptr;
    }
    if (listener == nullptr) return;
    line_listener = env->NewGlobalRef(listener);
    jclass cls = env->GetObjectClass(listener);
    listener_on_line = env->GetMethodID(cls, "onLine", "(Ljava/lang/String;)V");
    if (listener_on_line == nullptr) {
        __android_log_write(ANDROID_LOG_ERROR, ADBTAG, "onLine(String) not found on listener");
        if (env->ExceptionCheck()) env->ExceptionClear();
    } else {
        __android_log_write(ANDROID_LOG_INFO, ADBTAG, "stdout line listener registered");
    }
}

// 向 Node 的 stdin 写一行 JSON-RPC 请求（Kotlin 侧已带换行）。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_factotum_app_node_NodeRuntime_writeStdin(
        JNIEnv *env,
        jobject /* this */,
        jbyteArray data) {
    if (pipe_stdin[1] <= 0) return JNI_FALSE;
    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    jsize written = 0;
    while (written < length) {
        ssize_t count = write(pipe_stdin[1], bytes + written, static_cast<size_t>(length - written));
        if (count <= 0) break;
        written += static_cast<jsize>(count);
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return written == length ? JNI_TRUE : JNI_FALSE;
}
