cmake_minimum_required(VERSION 3.10)
project(youvegotmail LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_INCLUDE_CURRENT_DIR ON)

find_package(Qt5 REQUIRED COMPONENTS Core Gui Qml Quick Concurrent)

# ---- the Rust core (core/) as a static library -------------------------------------------------
# Clickable builds arm64/armhf in an x86-64 container with a cross g++ (aarch64-linux-gnu-g++), NOT an emulated arm
# userland, so cargo must cross-compile for the same target the C++ is built for. The Rust target is installed by
# image_setup in clickable.yaml. amd64 builds with the native toolchain and passes no --target.
# Release profile: size-optimised, LTO.
find_program(CARGO cargo HINTS "$ENV{HOME}/.cargo/bin" "$ENV{CARGO_HOME}/bin" REQUIRED)
set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/core)
set(CORE_TARGET_DIR ${CMAKE_CURRENT_BINARY_DIR}/cargo)
set(CARGO_CROSS_ARGS "")
set(CARGO_CROSS_ENV "")
set(CORE_LIB_SUBDIR release)
if(CMAKE_CXX_COMPILER MATCHES "aarch64-linux-gnu")
  set(RUST_TARGET aarch64-unknown-linux-gnu)
  set(RUST_TARGET_ENV AARCH64_UNKNOWN_LINUX_GNU)
  set(RUST_CC aarch64-linux-gnu-gcc)
elseif(CMAKE_CXX_COMPILER MATCHES "arm-linux-gnueabihf")
  set(RUST_TARGET armv7-unknown-linux-gnueabihf)
  set(RUST_TARGET_ENV ARMV7_UNKNOWN_LINUX_GNUEABIHF)
  set(RUST_CC arm-linux-gnueabihf-gcc)
endif()
if(RUST_TARGET)
  string(TOLOWER "${RUST_TARGET_ENV}" RUST_TARGET_ENV_LC)
  set(CARGO_CROSS_ARGS --target ${RUST_TARGET})
  set(CARGO_CROSS_ENV CARGO_TARGET_${RUST_TARGET_ENV}_LINKER=${RUST_CC} CC_${RUST_TARGET_ENV_LC}=${RUST_CC})
  set(CORE_LIB_SUBDIR ${RUST_TARGET}/release)
endif()
set(CORE_LIB ${CORE_TARGET_DIR}/${CORE_LIB_SUBDIR}/libmailcore.a)
add_custom_command(
    OUTPUT ${CORE_LIB}
    COMMAND ${CMAKE_COMMAND} -E env CARGO_TARGET_DIR=${CORE_TARGET_DIR} CARGO_HOME=${CMAKE_CURRENT_BINARY_DIR}/cargo-home ${CARGO_CROSS_ENV}
            ${CARGO} build --release --locked ${CARGO_CROSS_ARGS}
            --manifest-path ${CORE_DIR}/Cargo.toml
    DEPENDS ${CORE_DIR}/Cargo.toml ${CORE_DIR}/Cargo.lock
    COMMENT "cargo build (mailcore) ${RUST_TARGET}" VERBATIM)
add_custom_target(mailcore_build DEPENDS ${CORE_LIB})
add_library(mailcore STATIC IMPORTED GLOBAL)
set_target_properties(mailcore PROPERTIES IMPORTED_LOCATION ${CORE_LIB})
add_dependencies(mailcore mailcore_build)

# ---- the Qt shell -------------------------------------------------------------------------------
qt5_add_resources(QML_RES qml.qrc)
add_executable(youvegotmail cpp/main.cpp cpp/mailbackend.cpp cpp/mailbackend.h ${QML_RES})
target_link_libraries(youvegotmail PRIVATE Qt5::Core Qt5::Gui Qt5::Qml Qt5::Quick Qt5::Concurrent mailcore pthread dl m)

# ---- click payload --------------------------------------------------------------------------------
# clickable passes -DCMAKE_INSTALL_PREFIX=<click root>; everything is installed at its top level.
execute_process(COMMAND dpkg-architecture -qDEB_HOST_ARCH OUTPUT_VARIABLE CLICK_ARCH OUTPUT_STRIP_TRAILING_WHITESPACE ERROR_QUIET)
if(NOT CLICK_ARCH)
    set(CLICK_ARCH arm64)
endif()
configure_file(manifest.json.in ${CMAKE_CURRENT_BINARY_DIR}/manifest.json @ONLY)
install(TARGETS youvegotmail RUNTIME DESTINATION ${CMAKE_INSTALL_PREFIX})
install(FILES ${CMAKE_CURRENT_BINARY_DIR}/manifest.json youvegotmail.apparmor youvegotmail.desktop DESTINATION ${CMAKE_INSTALL_PREFIX})
install(FILES assets/logo.svg DESTINATION ${CMAKE_INSTALL_PREFIX}/assets)
