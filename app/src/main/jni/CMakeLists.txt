cmake_minimum_required(VERSION 3.22)
project(temproot)
enable_language(ASM)

set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR})

set(CMAKE_ASM_FLAGS "${CMAKE_ASM_FLAGS} -I${JNI_DIR}")
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS} ${C_FLAGS} -I${JNI_DIR} -Os -fvisibility=hidden -fdata-sections -ffunction-sections -Wall -Wextra -fno-stack-protector -fomit-frame-pointer")

set(SPLICEHELPER "${JNI_DIR}/splicehelper")
add_custom_command(
    OUTPUT "${SPLICEHELPER}"
    COMMAND "${CMAKE_C_COMPILER}"
            --target=${CMAKE_C_COMPILER_TARGET}
            --sysroot=${CMAKE_SYSROOT}
            "${JNI_DIR}/splicehelper.c"
            -o "${SPLICEHELPER}"
            -nodefaultlibs -nostartfiles -ffreestanding -static
    COMMAND "${CMAKE_STRIP}" "${SPLICEHELPER}"
    DEPENDS "${JNI_DIR}/splicehelper.c"
    COMMENT "Building splicehelper"
)
add_custom_target(splicehelper_bin DEPENDS "${SPLICEHELPER}")

set(KO_FILES
    "${JNI_DIR}/ko/temproot-android12-5.10.ko"
    "${JNI_DIR}/ko/temproot-android13-5.10.ko"
    "${JNI_DIR}/ko/temproot-android13-5.15.ko"
    "${JNI_DIR}/ko/temproot-android14-5.15.ko"
    "${JNI_DIR}/ko/temproot-android14-6.1.ko"
    "${JNI_DIR}/ko/temproot-android15-6.6.ko"
    "${JNI_DIR}/ko/temproot-android16-6.12.ko"
    "${JNI_DIR}/ko/temproot-android17-6.18.ko"
)

# AGP only packages lib*.so, so the executable is named libtemproot.so and lands
# in the APK's native lib dir. The app execs it from nativeLibraryDir, which is
# executable (app data dir is not).
add_executable(temproot exp.c libcxx.S elf_parser.c)
add_dependencies(temproot splicehelper_bin)
set_target_properties(temproot PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")

set_source_files_properties(exp.c PROPERTIES
    OBJECT_DEPENDS "${KO_FILES};${SPLICEHELPER}")

target_link_libraries(temproot log)
target_link_options(temproot PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--strip-all)

add_executable(bootstrap bootstrap.c)
set_target_properties(bootstrap PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_link_options(bootstrap PRIVATE -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--strip-all)
