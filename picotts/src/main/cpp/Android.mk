# Builds the SVOX Pico synthesiser plus our JNI bridge into one shared library.
#
# ndk-build is used rather than CMake purely because this machine has an NDK but no CMake, and
# ndk-build ships with its own make.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := picotts

LOCAL_SRC_FILES := \
    picojni.c \
    pico/picoacph.c pico/picoapi.c pico/picobase.c pico/picocep.c pico/picoctrl.c \
    pico/picodata.c pico/picodbg.c pico/picoextapi.c pico/picofftsg.c pico/picokdbg.c \
    pico/picokdt.c pico/picokfst.c pico/picoklex.c pico/picoknow.c pico/picokpdf.c \
    pico/picokpr.c pico/picoktab.c pico/picoos.c pico/picopal.c pico/picopam.c \
    pico/picopr.c pico/picorsrc.c pico/picosa.c pico/picosig.c pico/picosig2.c \
    pico/picospho.c pico/picotok.c pico/picotrns.c pico/picowa.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)
# The Pico sources are from 2008 and trip several modern clang warnings that are not worth
# patching in vendored third-party code; they are warnings, not defects we introduced.
LOCAL_CFLAGS := -O2 -Wno-everything
LOCAL_LDLIBS := -llog

include $(BUILD_SHARED_LIBRARY)
