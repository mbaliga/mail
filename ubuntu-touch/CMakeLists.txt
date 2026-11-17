cmake_minimum_required(VERSION 3.10)
project(youvegotmail LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)
set(CMAKE_INCLUDE_CURRENT_DIR ON)

find_package(Qt5 REQUIRED COMPONENTS Core Gui Qml Quick Concurrent)

# ---- the Rust core (core/) as a static library -------------------------------------------------
# cargo runs inside the build container for the container's architecture (clickable --arch arm64
# runs an arm64 userland), so no --target is passed. Release profile: size-optimised, LTO.
find_program(CARGO cargo HINTS "$ENV{HOME}/.cargo/bin" "$ENV{CARGO_HOME}/bin" REQUIRED)
set(CORE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/core)
set(CORE_TARGET_DIR ${CMAKE_CURRENT_BINARY_DIR}/cargo)
set(CORE_LIB ${CORE_TARGET_DIR}/release/libmailcore.a)
add_custom_command(
    OUTPUT ${CORE_LIB}
    COMMAND ${CMAKE_COMMAND} -E env CARGO_TARGET_DIR=${CORE_TARGET_DIR} CARGO_HOME=${CMAKE_CURRENT_BINARY_DIR}/cargo-home ${CARGO} build --release --locked
            --manifest-path ${CORE_DIR}/Cargo.toml
    DEPENDS ${CORE_DIR}/Cargo.toml ${CORE_DIR}/Cargo.lock
    COMMENT "cargo build (mailcore)" VERBATIM)
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
