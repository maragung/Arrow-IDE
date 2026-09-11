/*
 * Arrow IDE - JNI PTY backend.
 *
 * Forks a child process attached to a newly-allocated pseudo terminal
 * (via /dev/ptmx) and returns the child pid plus the master fd to Kotlin
 * (see com.maragung.arrowide.terminal.Pty for the other side).
 *
 * Conventions:
 *  - All functions use blocking I/O; callers own threading.
 *  - create() returns the child pid (>= 0) on success and -1 on failure;
 *    on failure outFds[0] is -1 and outFds[1] carries the errno value.
 */

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>

/* ---------------------------------------------------------------------- */
/* Helpers                                                                 */
/* ---------------------------------------------------------------------- */

/*
 * Convert a Java String[] into a NULL-terminated char** allocated with
 * calloc/strdup. Returns NULL on allocation failure. The caller frees the
 * array with free_string_array().
 */
static char **to_string_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) {
        return NULL;
    }
    jsize count = (*env)->GetArrayLength(env, array);
    if (count < 0) {
        return NULL;
    }
    char **result = (char **) calloc((size_t) count + 1, sizeof(char *));
    if (result == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < count; i++) {
        jstring element = (jstring) (*env)->GetObjectArrayElement(env, array, i);
        if (element == NULL) {
            /* Keep an empty slot rather than failing; execve would fail. */
            result[i] = strdup("");
            if (result[i] == NULL) {
                goto fail;
            }
            continue;
        }
        const char *chars = (*env)->GetStringUTFChars(env, element, NULL);
        if (chars == NULL) {
            goto fail;
        }
        result[i] = strdup(chars);
        (*env)->ReleaseStringUTFChars(env, element, chars);
        (*env)->DeleteLocalRef(env, element);
        if (result[i] == NULL) {
            goto fail;
        }
    }
    result[count] = NULL;
    return result;

fail:
    for (jsize i = 0; i < count; i++) {
        free(result[i]);
    }
    free(result);
    return NULL;
}

static void free_string_array(char **array) {
    if (array == NULL) {
        return;
    }
    for (size_t i = 0; array[i] != NULL; i++) {
        free(array[i]);
    }
    free(array);
}

/* Store (masterFd, errNo) into the two-element IntArray handed over by Kotlin. */
static void report_fds(JNIEnv *env, jintArray outFds, jint masterFd, jint errNo) {
    if (outFds == NULL) {
        return;
    }
    jint values[2];
    values[0] = masterFd;
    values[1] = errNo;
    (*env)->SetIntArrayRegion(env, outFds, 0, 2, values);
}

/* ---------------------------------------------------------------------- */
/* JNI methods for com.maragung.arrowide.terminal.Pty                     */
/* ---------------------------------------------------------------------- */

/*
 * public external fun create(
 *     cmd: String, argv: Array<String>, env: Array<String>, cwd: String,
 *     rows: Int, cols: Int, outFds: IntArray
 * ): Long
 */
JNIEXPORT jlong JNICALL
Java_com_maragung_arrowide_terminal_Pty_create(JNIEnv *env, jobject thiz,
        jstring cmd, jobjectArray argv, jobjectArray envp, jstring cwd,
        jint rows, jint cols, jintArray outFds) {
    (void) thiz;

    report_fds(env, outFds, -1, 0);

    if (cmd == NULL || argv == NULL || envp == NULL || cwd == NULL) {
        report_fds(env, outFds, -1, EINVAL);
        return (jlong) -1;
    }

    const char *cmd_c = (*env)->GetStringUTFChars(env, cmd, NULL);
    if (cmd_c == NULL) {
        return (jlong) -1;
    }
    const char *cwd_c = (*env)->GetStringUTFChars(env, cwd, NULL);
    if (cwd_c == NULL) {
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);
        return (jlong) -1;
    }

    char **argv_c = to_string_array(env, argv);
    char **envp_c = to_string_array(env, envp);
    if (argv_c == NULL || envp_c == NULL) {
        free_string_array(argv_c);
        free_string_array(envp_c);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);
        report_fds(env, outFds, -1, ENOMEM);
        return (jlong) -1;
    }

    int master = open("/dev/ptmx", O_RDWR | O_NOCTTY);
    if (master < 0) {
        int err = errno;
        free_string_array(argv_c);
        free_string_array(envp_c);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);
        report_fds(env, outFds, -1, err);
        return (jlong) -1;
    }

    /* grantpt() is a no-op on bionic; unlockpt() makes the slave usable. */
    if (unlockpt(master) != 0) {
        int err = errno;
        close(master);
        free_string_array(argv_c);
        free_string_array(envp_c);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);
        report_fds(env, outFds, -1, err);
        return (jlong) -1;
    }

    char slave_path[64];
    memset(slave_path, 0, sizeof(slave_path));
    if (ptsname_r(master, slave_path, sizeof(slave_path)) != 0) {
        int err = errno;
        close(master);
        free_string_array(argv_c);
        free_string_array(envp_c);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);
        report_fds(env, outFds, -1, err);
        return (jlong) -1;
    }

    int slave = open(slave_path, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        int err = errno;
        close(master);
        free_string_array(argv_c);
        free_string_array(envp_c);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);
        report_fds(env, outFds, -1, err);
        return (jlong) -1;
    }

    /* Publish the initial window size before the child can observe the tty. */
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(master, TIOCSWINSZ, &ws);

    pid_t pid = fork();
    if (pid < 0) {
        int err = errno;
        close(slave);
        close(master);
        free_string_array(argv_c);
        free_string_array(envp_c);
        (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
        (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);
        report_fds(env, outFds, -1, err);
        return (jlong) -1;
    }

    if (pid == 0) {
        /* Child. Only async-signal-safe calls until execve(). */
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) {
            close(slave);
        }
        close(master);

        /* Do not inherit the JVM's signal mask. */
        sigset_t empty;
        sigemptyset(&empty);
        sigprocmask(SIG_SETMASK, &empty, NULL);

        if (chdir(cwd_c) != 0) {
            _exit(126);
        }
        execve(cmd_c, argv_c, envp_c);
        _exit(127);
    }

    /* Parent. */
    close(slave);
    free_string_array(argv_c);
    free_string_array(envp_c);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_c);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_c);

    report_fds(env, outFds, (jint) master, 0);
    return (jlong) pid;
}

/*
 * public external fun readFd(fd: Int, buf: ByteArray): Int
 *
 * Blocking read of up to buf.size bytes. Returns the number of bytes read
 * (0 on EOF) or -1 on error. EINTR is retried.
 */
JNIEXPORT jint JNICALL
Java_com_maragung_arrowide_terminal_Pty_readFd(JNIEnv *env, jobject thiz,
        jint fd, jbyteArray buf) {
    (void) thiz;
    if (buf == NULL) {
        errno = EINVAL;
        return -1;
    }
    jsize capacity = (*env)->GetArrayLength(env, buf);
    if (capacity <= 0) {
        return 0;
    }
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (data == NULL) {
        return -1;
    }
    ssize_t readBytes;
    for (;;) {
        readBytes = read((int) fd, data, (size_t) capacity);
        if (readBytes < 0 && errno == EINTR) {
            continue;
        }
        break;
    }
    if (readBytes > 0) {
        /* Mode 0 copies the whole buffer back; the untouched tail is
         * unchanged, so this is equivalent to copying just the read bytes. */
        (*env)->ReleaseByteArrayElements(env, buf, data, 0);
    } else {
        /* Nothing changed; skip the copy back. */
        (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    }
    return (jint) readBytes;
}

/*
 * public external fun writeFd(fd: Int, buf: ByteArray, off: Int, len: Int): Int
 *
 * Full write loop with EINTR handling. Returns the number of bytes written
 * or -1 if nothing could be written due to an error.
 */
JNIEXPORT jint JNICALL
Java_com_maragung_arrowide_terminal_Pty_writeFd(JNIEnv *env, jobject thiz,
        jint fd, jbyteArray buf, jint off, jint len) {
    (void) thiz;
    if (buf == NULL || off < 0 || len < 0) {
        errno = EINVAL;
        return -1;
    }
    jsize capacity = (*env)->GetArrayLength(env, buf);
    if ((jsize) off + (jsize) len > capacity) {
        errno = EINVAL;
        return -1;
    }
    if (len == 0) {
        return 0;
    }
    jbyte *data = (*env)->GetByteArrayElements(env, buf, NULL);
    if (data == NULL) {
        return -1;
    }
    size_t total = 0;
    jint result = -1;
    while (total < (size_t) len) {
        ssize_t written = write((int) fd, data + off + total,
                (size_t) len - total);
        if (written < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (written == 0) {
            break;
        }
        total += (size_t) written;
    }
    if (total > 0) {
        result = (jint) total;
    }
    /* We only read from the array; no need to copy anything back. */
    (*env)->ReleaseByteArrayElements(env, buf, data, JNI_ABORT);
    return result;
}

/*
 * public external fun resizePty(fd: Int, rows: Int, cols: Int)
 */
JNIEXPORT void JNICALL
Java_com_maragung_arrowide_terminal_Pty_resizePty(JNIEnv *env, jobject thiz,
        jint fd, jint rows, jint cols) {
    (void) env;
    (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl((int) fd, TIOCSWINSZ, &ws);
}

/*
 * public external fun waitFor(pid: Long): Int
 *
 * Blocking waitpid. Returns the exit status (WEXITSTATUS), or 128+signal if
 * the child was killed by a signal, or -1 on waitpid failure. EINTR retried.
 */
JNIEXPORT jint JNICALL
Java_com_maragung_arrowide_terminal_Pty_waitFor(JNIEnv *env, jobject thiz,
        jlong pid) {
    (void) env;
    (void) thiz;
    int status = 0;
    pid_t waited;
    for (;;) {
        waited = waitpid((pid_t) pid, &status, 0);
        if (waited < 0 && errno == EINTR) {
            continue;
        }
        break;
    }
    if (waited < 0) {
        return -1;
    }
    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    if (WIFSIGNALED(status)) {
        return 128 + WTERMSIG(status);
    }
    return 0;
}

/*
 * public external fun closeFd(fd: Int)
 */
JNIEXPORT void JNICALL
Java_com_maragung_arrowide_terminal_Pty_closeFd(JNIEnv *env, jobject thiz,
        jint fd) {
    (void) env;
    (void) thiz;
    close((int) fd);
}

/*
 * public external fun killPid(pid: Long, sig: Int)
 */
JNIEXPORT void JNICALL
Java_com_maragung_arrowide_terminal_Pty_killPid(JNIEnv *env, jobject thiz,
        jlong pid, jint sig) {
    (void) env;
    (void) thiz;
    kill((pid_t) pid, (int) sig);
}
