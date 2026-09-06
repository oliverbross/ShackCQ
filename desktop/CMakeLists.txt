cmake_minimum_required(VERSION 3.28)
project(ShackCQDesktop VERSION 0.1.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_AUTOMOC ON)
set(CMAKE_AUTORCC ON)
set(CMAKE_AUTOUIC ON)
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")
include(FetchContent)

option(SHACKCQ_BUILD_TESTS "Build focused desktop tests" ON)
option(SHACKCQ_REQUIRE_HAMLIB "Fail configuration when the pinned Hamlib build is unavailable" OFF)
option(SHACKCQ_BUILD_NATIVE_DIGI "Build and link the existing native Rust modem engine" ON)

find_package(Qt6 6.11.2 EXACT REQUIRED COMPONENTS
    Core Gui Quick QuickControls2 Qml Network Sql SerialPort Multimedia WebSockets
    Concurrent Svg Positioning Location Test QuickTest Widgets)
find_package(OpenSSL 3 REQUIRED COMPONENTS Crypto SSL)

# Official Xiph.Org libopus, pinned to the peeled v1.5.2 commit. Remote RX
# media uses this single codec authority; PCM16 remains a LAN/debug fallback.
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
FetchContent_Declare(opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG ddbe48383984d56acd9e1ab6a090c54ca6b735a6
    GIT_SHALLOW FALSE)
FetchContent_MakeAvailable(opus)

if(UNIX AND NOT APPLE)
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(LIBSECRET REQUIRED IMPORTED_TARGET libsecret-1)
endif()

if(SHACKCQ_REQUIRE_HAMLIB)
    find_package(ShackCQHamlib REQUIRED)
else()
    find_package(ShackCQHamlib QUIET)
endif()

set(SHACKCQ_CORE_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../core" "${CMAKE_CURRENT_BINARY_DIR}/core" EXCLUDE_FROM_ALL)
include(ShackCQWarnings)

set(SHACKCQ_DESKTOP_SERVICE_SOURCES
    src/app/DesktopApplication.cpp
    src/core/DesktopModels.cpp
    src/intelligence/RfObservationModel.cpp
    src/intelligence/RfMapItem.cpp
    src/data/QsoDatabase.cpp
    src/data/AdifService.cpp
    src/network/WavelogSync.cpp
    src/network/ClusterController.cpp
    src/network/CloudAgentClient.cpp
    src/network/RemoteStationService.cpp
    src/network/RemoteStationClient.cpp
    src/platform/DesktopPlatform.cpp
    src/platform/DesktopEngagementControllers.cpp
    src/platform/DesktopParityPlatform.cpp
    src/platform/DesktopFunctionalOwners.cpp
    src/radio/DesktopRadioController.cpp
    src/radio/ReceiverListModel.cpp
    src/radio/TciClient.cpp
    src/rotator/DesktopRotatorController.cpp
    src/panadapter/DesktopPanadapter.cpp
    src/panadapter/PanadapterSceneItem.cpp
)
file(GLOB SHACKCQ_DESKTOP_SERVICE_HEADERS CONFIGURE_DEPENDS
    "include/shackcq/desktop/*.hpp")

add_library(shackcq_desktop_services STATIC
    ${SHACKCQ_DESKTOP_SERVICE_SOURCES}
    ${SHACKCQ_DESKTOP_SERVICE_HEADERS})
target_include_directories(shackcq_desktop_services PUBLIC include)
target_link_libraries(shackcq_desktop_services PUBLIC
    shackcq_core Qt6::Core Qt6::Gui Qt6::Quick Qt6::QuickControls2 Qt6::Qml
    Qt6::Network Qt6::Sql Qt6::SerialPort Qt6::Multimedia Qt6::WebSockets
    Qt6::Concurrent Qt6::Svg Qt6::Positioning Qt6::Location Qt6::Widgets
    OpenSSL::Crypto OpenSSL::SSL opus)
if(ShackCQHamlib_FOUND)
    target_link_libraries(shackcq_desktop_services PUBLIC ShackCQ::Hamlib)
    target_compile_definitions(shackcq_desktop_services PRIVATE SHACKCQ_HAVE_HAMLIB=1)
endif()
if(WIN32)
    target_link_libraries(shackcq_desktop_services PUBLIC advapi32)
    file(TO_CMAKE_PATH "$ENV{OPENSSL_ROOT_DIR}" SHACKCQ_OPENSSL_ROOT)
    foreach(_openssl_dll IN ITEMS libcrypto-3-x64.dll libssl-3-x64.dll)
        set(_openssl_dll_path "${SHACKCQ_OPENSSL_ROOT}/bin/${_openssl_dll}")
        if(NOT EXISTS "${_openssl_dll_path}")
            message(FATAL_ERROR "Required OpenSSL runtime DLL is missing: ${_openssl_dll_path}")
        endif()
        configure_file("${_openssl_dll_path}" "${CMAKE_CURRENT_BINARY_DIR}/${_openssl_dll}" COPYONLY)
        install(FILES "${_openssl_dll_path}" DESTINATION .)
    endforeach()
elseif(APPLE)
    target_link_libraries(shackcq_desktop_services PUBLIC "-framework Security")
elseif(UNIX)
    target_link_libraries(shackcq_desktop_services PUBLIC PkgConfig::LIBSECRET)
endif()
shackcq_target_warnings(shackcq_desktop_services)
target_compile_definitions(shackcq_desktop_services PRIVATE
    SHACKCQ_BUILD_SHA="$ENV{GITHUB_SHA}"
    SHACKCQ_DESKTOP_SCHEMA=16)

if(SHACKCQ_BUILD_NATIVE_DIGI)
    find_program(SHACKCQ_CARGO_EXECUTABLE cargo REQUIRED)
    get_filename_component(SHACKCQ_RUST_BIN_DIRECTORY "${SHACKCQ_CARGO_EXECUTABLE}" DIRECTORY)
    if(WIN32)
        set(SHACKCQ_RUST_TARGET x86_64-pc-windows-gnu)
        set(SHACKCQ_RUST_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/target/${SHACKCQ_RUST_TARGET}/release/libshackcq_flex.a")
    elseif(APPLE AND CMAKE_SYSTEM_PROCESSOR MATCHES "arm64|aarch64")
        set(SHACKCQ_RUST_TARGET aarch64-apple-darwin)
        set(SHACKCQ_RUST_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/target/${SHACKCQ_RUST_TARGET}/release/libshackcq_flex.a")
    elseif(APPLE)
        set(SHACKCQ_RUST_TARGET x86_64-apple-darwin)
        set(SHACKCQ_RUST_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/target/${SHACKCQ_RUST_TARGET}/release/libshackcq_flex.a")
    else()
        set(SHACKCQ_RUST_TARGET x86_64-unknown-linux-gnu)
        set(SHACKCQ_RUST_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/target/${SHACKCQ_RUST_TARGET}/release/libshackcq_flex.a")
    endif()
    add_custom_command(
        OUTPUT "${SHACKCQ_RUST_LIBRARY}"
        COMMAND "${CMAKE_COMMAND}" -E env
                "RUSTC=${SHACKCQ_RUST_BIN_DIRECTORY}/rustc${CMAKE_EXECUTABLE_SUFFIX}"
                "${SHACKCQ_CARGO_EXECUTABLE}" build --manifest-path
                "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/Cargo.toml"
                --release --target "${SHACKCQ_RUST_TARGET}" --locked
        DEPENDS
            "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/Cargo.toml"
            "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/Cargo.lock"
        WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex"
        COMMENT "Building the existing ShackCQ native Digi/Flex engine")
    add_custom_target(shackcq_desktop_native_digi DEPENDS "${SHACKCQ_RUST_LIBRARY}")
    add_dependencies(shackcq_desktop_services shackcq_desktop_native_digi)
    target_include_directories(shackcq_desktop_services PRIVATE
        "${CMAKE_CURRENT_SOURCE_DIR}/../rust/shackcq-flex/include")
    target_link_libraries(shackcq_desktop_services PUBLIC "${SHACKCQ_RUST_LIBRARY}")
    target_compile_definitions(shackcq_desktop_services PRIVATE SHACKCQ_HAVE_NATIVE_DIGI=1)
    if(WIN32)
        target_link_libraries(shackcq_desktop_services PUBLIC bcrypt ntdll userenv ws2_32)
    elseif(APPLE)
        target_link_libraries(shackcq_desktop_services PUBLIC "-framework Security" "-framework CoreFoundation")
    else()
        target_link_libraries(shackcq_desktop_services PUBLIC dl pthread)
    endif()
endif()

qt_add_executable(ShackCQDesktop WIN32 MACOSX_BUNDLE src/app/main.cpp)
if(WIN32)
    target_sources(ShackCQDesktop PRIVATE resources/windows-icon.rc)
elseif(APPLE)
    set_source_files_properties(resources/app-icon.icns PROPERTIES
        MACOSX_PACKAGE_LOCATION "Resources")
    target_sources(ShackCQDesktop PRIVATE resources/app-icon.icns)
    set_target_properties(ShackCQDesktop PROPERTIES
        MACOSX_BUNDLE_ICON_FILE "app-icon.icns")
endif()
target_link_libraries(ShackCQDesktop PRIVATE shackcq_desktop_services)
shackcq_target_warnings(ShackCQDesktop)
if(APPLE AND CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    # Qt 6.11's generated QML cache loader uses an intentionally empty
    # variadic macro invocation; keep warnings-as-errors for owned sources.
    target_compile_options(ShackCQDesktop PRIVATE -Wno-variadic-macro-arguments-omitted)
endif()

qt_add_executable(shackcq-stationd src/app/stationd_main.cpp)
target_link_libraries(shackcq-stationd PRIVATE shackcq_desktop_services)
shackcq_target_warnings(shackcq-stationd)

qt_add_executable(ShackCQAgent src/app/stationd_main.cpp)
target_link_libraries(ShackCQAgent PRIVATE shackcq_desktop_services)
shackcq_target_warnings(ShackCQAgent)

file(GLOB_RECURSE SHACKCQ_QML_FILES CONFIGURE_DEPENDS "qml/*.qml")
file(GLOB SHACKCQ_ICON_FILES CONFIGURE_DEPENDS "resources/icons/*.svg")
set(SHACKCQ_APP_ICON "${CMAKE_CURRENT_SOURCE_DIR}/../assets/branding/shackcq-app-icon-1024.png")
set_source_files_properties(${SHACKCQ_APP_ICON} PROPERTIES QT_RESOURCE_ALIAS "AppIcon.png")
foreach(qml_file IN LISTS SHACKCQ_QML_FILES)
    if(qml_file MATCHES "/qml/ShackCQ/App/Main\\.qml$")
        set_source_files_properties(${qml_file} PROPERTIES QT_RESOURCE_ALIAS "Main.qml")
    else()
        file(RELATIVE_PATH qml_alias "${CMAKE_CURRENT_SOURCE_DIR}/qml/ShackCQ" "${qml_file}")
        set_source_files_properties(${qml_file} PROPERTIES QT_RESOURCE_ALIAS "${qml_alias}")
    endif()
endforeach()
foreach(icon_file IN LISTS SHACKCQ_ICON_FILES)
    get_filename_component(icon_name "${icon_file}" NAME)
    set_source_files_properties(${icon_file} PROPERTIES QT_RESOURCE_ALIAS "Icons/${icon_name}")
endforeach()
qt_add_qml_module(ShackCQDesktop
    URI ShackCQ.App
    VERSION 1.0
    QML_FILES ${SHACKCQ_QML_FILES}
    RESOURCES resources/default-band-plan.json resources/THIRD_PARTY_NOTICES.txt
              ${SHACKCQ_ICON_FILES} ${SHACKCQ_APP_ICON})

install(TARGETS ShackCQDesktop
    BUNDLE DESTINATION .
    RUNTIME DESTINATION .)
if(WIN32)
    install(TARGETS shackcq-stationd ShackCQAgent RUNTIME DESTINATION .)
else()
    install(TARGETS shackcq-stationd ShackCQAgent RUNTIME DESTINATION stationd)
endif()

if(WIN32)
    qt_generate_deploy_qml_app_script(
        TARGET ShackCQDesktop
        OUTPUT_SCRIPT deploy_script
        NO_UNSUPPORTED_PLATFORM_ERROR)
    install(SCRIPT ${deploy_script})
endif()

if(SHACKCQ_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()

set(CPACK_PACKAGE_NAME "ShackCQ")
set(CPACK_PACKAGE_VENDOR "ShackCQ")
set(CPACK_PACKAGE_VERSION "0.1.0")
set(CPACK_PACKAGE_INSTALL_DIRECTORY "ShackCQ")
if(WIN32)
    set(CPACK_PACKAGE_FILE_NAME "ShackCQ-Windows-x64-setup-v0.1.0-rc.1")
    set(CPACK_GENERATOR "NSIS")
elseif(UNIX AND NOT APPLE)
    set(CPACK_PACKAGE_FILE_NAME "ShackCQ-Linux-x86_64-v0.1.0-rc.1")
    set(CPACK_GENERATOR "TGZ;DEB")
    set(CPACK_DEBIAN_PACKAGE_MAINTAINER "ShackCQ")
    set(CPACK_DEBIAN_PACKAGE_SECTION "hamradio")
    set(CPACK_DEBIAN_PACKAGE_SHLIBDEPS ON)
endif()
set(CPACK_RESOURCE_FILE_LICENSE "${CMAKE_CURRENT_SOURCE_DIR}/../COPYING")
set(CPACK_NSIS_DISPLAY_NAME "ShackCQ Windows Desktop")
set(CPACK_NSIS_PACKAGE_NAME "ShackCQ Windows Desktop")
set(CPACK_NSIS_INSTALL_ROOT "$LOCALAPPDATA")
set(CPACK_NSIS_ENABLE_UNINSTALL_BEFORE_INSTALL ON)
set(CPACK_NSIS_MODIFY_PATH OFF)
set(CPACK_NSIS_MUI_FINISHPAGE_RUN "ShackCQDesktop.exe")
set(CPACK_NSIS_CREATE_ICONS_EXTRA
    "CreateShortCut '$SMPROGRAMS\\\\ShackCQ Windows Desktop.lnk' '$INSTDIR\\\\ShackCQDesktop.exe'")
set(CPACK_NSIS_DELETE_ICONS_EXTRA
    "Delete '$SMPROGRAMS\\\\ShackCQ Windows Desktop.lnk'")
include(CPack)
