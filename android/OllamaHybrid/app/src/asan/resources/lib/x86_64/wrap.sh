#!/system/bin/sh
# NEXUS CI AddressSanitizer job only (gradle -Pnexus.asan=true): Android starts a
# debuggable app through this script, which preloads the sanitizer runtime that
# ships next to the engine. See https://developer.android.com/ndk/guides/wrap-script
HERE="$(cd "$(dirname "$0")" && pwd)"
export ASAN_OPTIONS=log_to_syslog=false,allow_user_segv_handler=1,detect_leaks=0
ASAN_LIB=$(ls "$HERE"/libclang_rt.asan-*-android.so)
if [ -f "$HERE/libc++_shared.so" ]; then
    # https://github.com/android/ndk/issues/988
    export LD_PRELOAD="$ASAN_LIB $HERE/libc++_shared.so"
else
    export LD_PRELOAD="$ASAN_LIB"
fi
"$@"
