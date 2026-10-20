SHELL       := bash
.SHELLFLAGS := -euo pipefail -c
MAKEFLAGS   += --no-print-directory

ARCH      := aarch64
ABI       := arm64-v8a
ENGINE    := podman

KMIS :=        \
    android12-5.10  \
    android13-5.10  \
    android13-5.15  \
    android14-5.15  \
    android14-6.1   \
    android15-6.6   \
    android16-6.12  \
    android17-6.18

DDK_WF      := .github/workflows/build.yml

DDK_IMAGE   ?= $(shell awk '/image: ghcr\.io\//{gsub(/.*image: /,"");gsub(/:.*$$/,"");print;exit}' $(DDK_WF))
DDK_RELEASE ?= $(shell awk '/image: ghcr\.io\/ylarod\/ddk-min:/{sub(/.*-/,"");print;exit}' $(DDK_WF))

SIGN_PASS  := df-ksu
SIGN_ALIAS := df-ksu

LKM_SRCS    := lkm/temproot.c lkm/Makefile
LKM_KOS     := $(KMIS:%=app/src/main/jni/ko/temproot-%.ko)
APK_SRCS    := $(wildcard app/src/main/java/build/bytes/temproot/*.java \
                               app/src/main/jni/*.c app/src/main/jni/*.h \
                               app/src/main/jni/*.S app/src/main/jni/*.inc \
                               app/src/main/res/layout/*.xml \
                               app/src/main/res/values/*.xml) \
                   app/src/main/AndroidManifest.xml \
                   app/build.gradle.kts \
                   app/src/main/jni/CMakeLists.txt
APK   := out/TemporaryRoot.apk


.PHONY: all clean

all: $(APK)

keystore.jks:
	keytool -genkeypair -keystore $@ -storetype JKS \
	    -storepass $(SIGN_PASS) -keypass $(SIGN_PASS) \
	    -alias $(SIGN_ALIAS) -keyalg RSA -keysize 2048 -validity 10000 \
	    -dname "CN=Temporary Root"

signing.properties: keystore.jks
	printf 'KEYSTORE_FILE=../keystore.jks\nKEYSTORE_PASSWORD=%s\nKEY_ALIAS=%s\nKEY_PASSWORD=%s\n' \
	    $(SIGN_PASS) $(SIGN_ALIAS) $(SIGN_PASS) > $@

$(LKM_KOS): $(LKM_SRCS)
	@printf '\n\033[1;35m══  Building LKMs  ══\033[0m\n'; \
	for kmi in $(KMIS); do \
	    printf '\n\033[1;33m▶  PODMAN  %s\033[0m\n' "$$kmi"; \
	    $(ENGINE) run --rm --pid=host --network=none \
	        -v $(abspath .):/proj \
	        -e KMI=$$kmi \
	        $(DDK_IMAGE):$$kmi-$(DDK_RELEASE) \
	        bash -c 'set -e; \
	            cd /proj/lkm; \
	            make -j$$(nproc); \
	            llvm-objcopy --strip-unneeded \
	              -R .comment -R .note.gnu.property \
	              -R .note.Linux -R .note.GNU-stack \
	              -R .BTF -R .BTF.base -R .llvm_addrsig \
	              -R .hyp.text -R .hyp.bss -R .hyp.rodata -R .hyp.event_ids \
	            -R .hyp.patchable_function_entries -R .hyp.data temproot.ko; \
	            mkdir -p /proj/app/src/main/jni/ko; \
	            cp temproot.ko /proj/app/src/main/jni/ko/temproot-$$KMI.ko; \
	            make clean; \
	            printf "\033[1;32m  ✔  Temporary Root LKM   $$KMI\033[0m\n"; \
	        ' || exit 1; \
	done; \

$(APK): signing.properties $(LKM_KOS) $(APK_SRCS)
	@printf '\n\033[1;36m══  Temporary Root APK  ══\033[0m\n'
	@printf '\033[1;35m  → gradle assembleRelease\033[0m\n'
	./gradlew :app:assembleRelease
	mkdir -p $(dir $@)
	cp app/build/outputs/apk/release/TemporaryRoot_*.apk $@
	@printf '\033[1;32m  ✔  Temporary Root APK  →  $@\033[0m\n'

clean:
	rm -f app/src/main/jni/splicehelper
	rm -f lkm/*.o lkm/*.cmd lkm/.*.cmd lkm/*.mod lkm/*.mod.c lkm/*.lto.o lkm/modules.order lkm/Module.symvers lkm/temproot.lds lkm/temproot.ko
	rm -rf app/src/main/jni/ko
	rm -f .project app/.project app/.classpath
	rm -rf .settings app/.settings
	rm -rf build out app/.cxx app/build .gradle
