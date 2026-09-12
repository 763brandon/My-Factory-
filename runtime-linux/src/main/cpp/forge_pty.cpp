// JNI bridge to a real pseudo-terminal.
//
// A pipe-based command runner cannot do job control, cannot report a window
// size, and makes ncurses programs (vim, htop, an npm progress bar) render as
// garbage. Allocating a PTY and handing the master file descriptor back to
// Kotlin fixes all three, and costs about two hundred lines.
//
// Portability notes for the 32-bit build, which is the higher-risk target:
//  - Only POSIX calls with a stable 32-bit ABI are used here.
//  - No off_t, no time_t and no struct stat cross the JNI boundary, so
//    _FILE_OFFSET_BITS cannot change the layout of anything shared.
//  - int is used for file descriptors and pid_t rather than jlong, matching
//    the kernel on both ILP32 and LP64.

#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <pty.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define LOG_TAG "ForgePty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Throws java.io.IOException with errno appended, then returns so the caller
// can bail out. JNI exceptions are not raised until control returns to the JVM.
void throwIoException(JNIEnv* env, const char* message) {
    char buffer[512];
    snprintf(buffer, sizeof(buffer), "%s: %s (errno %d)", message, strerror(errno), errno);
    jclass clazz = env->FindClass("java/io/IOException");
    if (clazz != nullptr) {
        env->ThrowNew(clazz, buffer);
    }
}

// Turns a Java String[] into the NULL-terminated char*[] execvp wants.
// Returns nullptr on allocation failure; the caller must free with freeArgv.
char** toNativeArray(JNIEnv* env, jobjectArray array, int* outCount) {
    const jsize count = array == nullptr ? 0 : env->GetArrayLength(array);
    char** result = static_cast<char**>(calloc(static_cast<size_t>(count) + 1, sizeof(char*)));
    if (result == nullptr) {
        return nullptr;
    }
    for (jsize i = 0; i < count; ++i) {
        jstring element = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        if (element == nullptr) {
            result[i] = strdup("");
            continue;
        }
        const char* chars = env->GetStringUTFChars(element, nullptr);
        result[i] = strdup(chars == nullptr ? "" : chars);
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(element, chars);
        }
        env->DeleteLocalRef(element);
    }
    result[count] = nullptr;
    if (outCount != nullptr) {
        *outCount = static_cast<int>(count);
    }
    return result;
}

void freeArgv(char** argv) {
    if (argv == nullptr) {
        return;
    }
    for (char** cursor = argv; *cursor != nullptr; ++cursor) {
        free(*cursor);
    }
    free(argv);
}

// Sets the terminal modes a shell expects. Without this the caller sees no
// echo and no line editing, which looks like a hung terminal.
void configureTerminal(int fd) {
    struct termios settings;
    if (tcgetattr(fd, &settings) != 0) {
        return;
    }
    settings.c_iflag |= (IXON | IXANY | IMAXBEL | BRKINT | ICRNL);
    settings.c_iflag &= ~(IGNCR | INLCR);
    settings.c_oflag |= (OPOST | ONLCR);
    settings.c_lflag |= (ECHO | ECHOE | ECHOK | ECHOKE | ICANON | ISIG | IEXTEN);
    settings.c_cflag |= (CREAD | CS8 | HUPCL);
    settings.c_cc[VMIN] = 1;
    settings.c_cc[VTIME] = 0;
    tcsetattr(fd, TCSANOW, &settings);
}

}  // namespace

extern "C" {

// Forks a child on a new pseudo-terminal and returns the master fd.
//
// The pid is written into element 0 of processIdOut, because JNI has no clean
// way to return two integers and a boxed pair would allocate on every spawn.
JNIEXPORT jint JNICALL
Java_com_myfactory_forge_runtime_pty_NativePty_createSubprocess(
        JNIEnv* env,
        jclass /* clazz */,
        jstring executable,
        jstring workingDirectory,
        jobjectArray arguments,
        jobjectArray environment,
        jintArray processIdOut,
        jint rows,
        jint columns) {

    if (executable == nullptr || processIdOut == nullptr) {
        throwIoException(env, "createSubprocess requires an executable and an output array");
        return -1;
    }
    if (env->GetArrayLength(processIdOut) < 1) {
        throwIoException(env, "processIdOut must have room for at least one int");
        return -1;
    }

    const char* executablePath = env->GetStringUTFChars(executable, nullptr);
    const char* cwd = workingDirectory == nullptr
            ? nullptr
            : env->GetStringUTFChars(workingDirectory, nullptr);

    char** argv = toNativeArray(env, arguments, nullptr);
    char** envp = toNativeArray(env, environment, nullptr);
    if (argv == nullptr || envp == nullptr) {
        freeArgv(argv);
        freeArgv(envp);
        env->ReleaseStringUTFChars(executable, executablePath);
        if (cwd != nullptr) env->ReleaseStringUTFChars(workingDirectory, cwd);
        throwIoException(env, "Out of memory preparing the subprocess");
        return -1;
    }

    // Give the child a sane initial window so a full-screen program does not
    // start at 0x0 and divide by zero.
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    size.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);

    int masterFd = -1;
    const pid_t pid = forkpty(&masterFd, nullptr, nullptr, &size);

    if (pid < 0) {
        freeArgv(argv);
        freeArgv(envp);
        env->ReleaseStringUTFChars(executable, executablePath);
        if (cwd != nullptr) env->ReleaseStringUTFChars(workingDirectory, cwd);
        throwIoException(env, "forkpty failed");
        return -1;
    }

    if (pid == 0) {
        // Child. Nothing here may allocate through the JVM or touch JNI: the
        // only safe calls after fork are async-signal-safe ones.
        if (cwd != nullptr && chdir(cwd) != 0) {
            // Falling back to the default directory beats refusing to start.
            fprintf(stderr, "forge: could not enter %s: %s\n", cwd, strerror(errno));
        }

        // Restore the default disposition for signals the JVM has handlers
        // for; a shell that inherits them behaves very strangely.
        for (int signalNumber = 1; signalNumber < NSIG; ++signalNumber) {
            signal(signalNumber, SIG_DFL);
        }
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, nullptr);

        execve(executablePath, argv, envp);
        // Only reachable if exec failed. The parent sees this on the PTY.
        fprintf(stderr, "forge: cannot execute %s: %s\n", executablePath, strerror(errno));
        _exit(127);
    }

    // Parent.
    freeArgv(argv);
    freeArgv(envp);
    env->ReleaseStringUTFChars(executable, executablePath);
    if (cwd != nullptr) {
        env->ReleaseStringUTFChars(workingDirectory, cwd);
    }

    configureTerminal(masterFd);
    // Blocking reads on a dedicated thread; the read loop needs no polling.
    const int flags = fcntl(masterFd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(masterFd, F_SETFL, flags & ~O_NONBLOCK);
    }

    jint pidValue = static_cast<jint>(pid);
    env->SetIntArrayRegion(processIdOut, 0, 1, &pidValue);
    return static_cast<jint>(masterFd);
}

// Tells the child its window changed and raises SIGWINCH, which is what makes
// a full-screen program redraw after a rotation or a keyboard appearing.
JNIEXPORT void JNICALL
Java_com_myfactory_forge_runtime_pty_NativePty_setWindowSize(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jint fd,
        jint rows,
        jint columns,
        jint pixelWidth,
        jint pixelHeight) {

    if (fd < 0) {
        return;
    }
    struct winsize size;
    memset(&size, 0, sizeof(size));
    size.ws_row = static_cast<unsigned short>(rows > 0 ? rows : 24);
    size.ws_col = static_cast<unsigned short>(columns > 0 ? columns : 80);
    size.ws_xpixel = static_cast<unsigned short>(pixelWidth > 0 ? pixelWidth : 0);
    size.ws_ypixel = static_cast<unsigned short>(pixelHeight > 0 ? pixelHeight : 0);
    ioctl(fd, TIOCSWINSZ, &size);
}

// Blocks until the process exits. Returns the exit status, or 128+signal for a
// process that was killed, matching the shell convention.
JNIEXPORT jint JNICALL
Java_com_myfactory_forge_runtime_pty_NativePty_waitFor(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jint processId) {

    if (processId <= 0) {
        return -1;
    }
    int status = 0;
    while (waitpid(static_cast<pid_t>(processId), &status, 0) < 0) {
        if (errno != EINTR) {
            return -1;
        }
    }
    if (WIFEXITED(status)) {
        return static_cast<jint>(WEXITSTATUS(status));
    }
    if (WIFSIGNALED(status)) {
        return static_cast<jint>(128 + WTERMSIG(status));
    }
    return -1;
}

// Signals the whole process group, so a shell's children die with it rather
// than being reparented and left running in the background.
JNIEXPORT void JNICALL
Java_com_myfactory_forge_runtime_pty_NativePty_sendSignal(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jint processId,
        jint signalNumber) {

    if (processId <= 0) {
        return;
    }
    if (killpg(static_cast<pid_t>(processId), signalNumber) != 0 && errno == ESRCH) {
        kill(static_cast<pid_t>(processId), signalNumber);
    }
}

JNIEXPORT void JNICALL
Java_com_myfactory_forge_runtime_pty_NativePty_closeFd(
        JNIEnv* /* env */,
        jclass /* clazz */,
        jint fd) {

    if (fd >= 0) {
        close(fd);
    }
}

// Confirms at runtime that the library loaded and matches this ABI. The tier
// detector calls it rather than assuming a shipped .so is a working .so.
JNIEXPORT jint JNICALL
Java_com_myfactory_forge_runtime_pty_NativePty_abiWordSize(
        JNIEnv* /* env */,
        jclass /* clazz */) {

    return static_cast<jint>(sizeof(void*) * 8);
}

}  // extern "C"
