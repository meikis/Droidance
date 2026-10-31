cmake_minimum_required(VERSION 3.22)
project(droidance_adb_pairing LANGUAGES C CXX ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
add_compile_definitions(OPENSSL_SMALL)
add_compile_options(-Os -fdata-sections -ffunction-sections -fvisibility=hidden
    -Werror=format -ffile-prefix-map=${SHIZUKU_SOURCE}=/src/shizuku
    -ffile-prefix-map=${BORINGSSL_SOURCE}=/src/boringssl)
add_compile_options($<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-threadsafe-statics>)

# Build only the crypto target used by Shizuku's unchanged pairing JNI implementation.
add_subdirectory("${BORINGSSL_SOURCE}" boringssl EXCLUDE_FROM_ALL)
add_library(adb SHARED "${SHIZUKU_SOURCE}/manager/src/main/jni/adb_pairing.cpp")
target_link_libraries(adb PRIVATE crypto log)
target_link_options(adb PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384 -Wl,--build-id=sha1)
