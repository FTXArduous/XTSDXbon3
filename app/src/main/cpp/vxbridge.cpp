#include <jni.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/ioctl.h>
#include <string>
#include <vector>
#include <linux/input.h>
#include <linux/uinput.h>

static const int kAbsCodes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_Z, ABS_RZ, ABS_HAT0X, ABS_HAT0Y};
static const int kBtnCodes[] = {BTN_SOUTH, BTN_EAST, BTN_NORTH, BTN_WEST, BTN_TL, BTN_TR,
                                BTN_SELECT, BTN_START, BTN_MODE, BTN_THUMBL, BTN_THUMBR};

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_xtsdx_virtualxbox_Native_##name

// Creates an Xbox-layout uinput device. Sticks span +-max, triggers 0..max.
FN(jint, uinputCreate)(JNIEnv *env, jclass, jstring jname, jint vid, jint pid, jint max) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) fd = open("/dev/input/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) return -1;

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);
    ioctl(fd, UI_SET_EVBIT, EV_SYN);
    for (int c : kBtnCodes) ioctl(fd, UI_SET_KEYBIT, c);
    for (int c : kAbsCodes) ioctl(fd, UI_SET_ABSBIT, c);

    uinput_user_dev dev;
    memset(&dev, 0, sizeof(dev));
    const char *name = env->GetStringUTFChars(jname, nullptr);
    strncpy(dev.name, name, UINPUT_MAX_NAME_SIZE - 1);
    env->ReleaseStringUTFChars(jname, name);
    dev.id.bustype = BUS_USB;
    dev.id.vendor = (uint16_t) vid;
    dev.id.product = (uint16_t) pid;
    dev.id.version = 0x0110;
    for (int c : {ABS_X, ABS_Y, ABS_RX, ABS_RY}) { dev.absmin[c] = -max; dev.absmax[c] = max; }
    for (int c : {ABS_Z, ABS_RZ}) { dev.absmin[c] = 0; dev.absmax[c] = max; }
    for (int c : {ABS_HAT0X, ABS_HAT0Y}) { dev.absmin[c] = -1; dev.absmax[c] = 1; }

    if (write(fd, &dev, sizeof(dev)) != (ssize_t) sizeof(dev) || ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -2;
    }
    return fd;
}

FN(void, uinputEmit)(JNIEnv *, jclass, jint fd, jint type, jint code, jint value) {
    input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (uint16_t) type;
    ev.code = (uint16_t) code;
    ev.value = value;
    if (write(fd, &ev, sizeof(ev)) < 0) { /* device gone */ }
}

FN(void, uinputClose)(JNIEnv *, jclass, jint fd) {
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

// Returns "path\tname\tvid\tpid" for every /dev/input/event* node.
FN(jobjectArray, evScan)(JNIEnv *env, jclass) {
    std::vector<std::string> rows;
    if (DIR *d = opendir("/dev/input")) {
        while (dirent *e = readdir(d)) {
            if (strncmp(e->d_name, "event", 5) != 0) continue;
            std::string path = std::string("/dev/input/") + e->d_name;
            int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK);
            if (fd < 0) continue;
            char name[256] = "";
            ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
            input_id id;
            memset(&id, 0, sizeof(id));
            ioctl(fd, EVIOCGID, &id);
            close(fd);
            for (char *p = name; *p; ++p) if (*p < 32 || *p > 126) *p = '?';
            char buf[512];
            snprintf(buf, sizeof(buf), "%s\t%s\t%d\t%d", path.c_str(), name, id.vendor, id.product);
            rows.emplace_back(buf);
        }
        closedir(d);
    }
    jobjectArray arr = env->NewObjectArray((jsize) rows.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < rows.size(); ++i)
        env->SetObjectArrayElement(arr, (jsize) i, env->NewStringUTF(rows[i].c_str()));
    return arr;
}

// Opens an input node; grab=true hides it from Android so only the virtual pad is seen.
FN(jint, evOpen)(JNIEnv *env, jclass, jstring jpath, jboolean grab) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    int fd = open(path, O_RDONLY | O_NONBLOCK);
    env->ReleaseStringUTFChars(jpath, path);
    if (fd < 0) return -1;
    if (grab && ioctl(fd, EVIOCGRAB, 1) < 0) { close(fd); return -2; }
    return fd;
}

FN(void, evClose)(JNIEnv *, jclass, jint fd) { close(fd); }

// out = {min, max, flat, value}; false when the axis is absent.
FN(jboolean, evAbs)(JNIEnv *env, jclass, jint fd, jint code, jintArray out) {
    unsigned char bits[ABS_MAX / 8 + 1];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(bits)), bits) < 0) return JNI_FALSE;
    if (!(bits[code / 8] & (1 << (code % 8)))) return JNI_FALSE;
    input_absinfo ai;
    if (ioctl(fd, EVIOCGABS(code), &ai) < 0) return JNI_FALSE;
    jint v[4] = {ai.minimum, ai.maximum, ai.flat, ai.value};
    env->SetIntArrayRegion(out, 0, 4, v);
    return JNI_TRUE;
}

FN(jboolean, evKey)(JNIEnv *, jclass, jint fd, jint code) {
    unsigned char bits[KEY_MAX / 8 + 1];
    memset(bits, 0, sizeof(bits));
    if (ioctl(fd, EVIOCGKEY(sizeof(bits)), bits) < 0) return JNI_FALSE;
    return ((bits[code / 8] >> (code % 8)) & 1) ? JNI_TRUE : JNI_FALSE;
}

// 1 = event in out{type,code,value}, 0 = timeout, -1 = device error.
FN(jint, evRead)(JNIEnv *env, jclass, jint fd, jintArray out) {
    pollfd p = {fd, POLLIN, 0};
    int r = poll(&p, 1, 200);
    if (r == 0) return 0;
    if (r < 0) return errno == EINTR ? 0 : -1;
    if (p.revents & (POLLERR | POLLHUP | POLLNVAL)) return -1;
    input_event ev;
    ssize_t n = read(fd, &ev, sizeof(ev));
    if (n != (ssize_t) sizeof(ev)) return (n < 0 && (errno == EAGAIN || errno == EINTR)) ? 0 : -1;
    jint v[3] = {ev.type, ev.code, ev.value};
    env->SetIntArrayRegion(out, 0, 3, v);
    return 1;
}
