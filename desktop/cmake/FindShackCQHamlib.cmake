set(_shackcq_hamlib_hints "${SHACKCQ_HAMLIB_ROOT}" "$ENV{SHACKCQ_HAMLIB_ROOT}")
find_path(ShackCQHamlib_INCLUDE_DIR hamlib/rig.h HINTS ${_shackcq_hamlib_hints} PATH_SUFFIXES include)
find_library(ShackCQHamlib_LIBRARY NAMES hamlib libhamlib HINTS ${_shackcq_hamlib_hints} PATH_SUFFIXES lib lib64)
include(FindPackageHandleStandardArgs)
find_package_handle_standard_args(ShackCQHamlib REQUIRED_VARS ShackCQHamlib_INCLUDE_DIR ShackCQHamlib_LIBRARY)
if(ShackCQHamlib_FOUND AND NOT TARGET ShackCQ::Hamlib)
    add_library(ShackCQ::Hamlib UNKNOWN IMPORTED)
    set_target_properties(ShackCQ::Hamlib PROPERTIES
        IMPORTED_LOCATION "${ShackCQHamlib_LIBRARY}"
        INTERFACE_INCLUDE_DIRECTORIES "${ShackCQHamlib_INCLUDE_DIR}")
    if(WIN32)
        set_property(TARGET ShackCQ::Hamlib APPEND PROPERTY INTERFACE_LINK_LIBRARIES ws2_32 winmm setupapi iphlpapi version)
    endif()
endif()
