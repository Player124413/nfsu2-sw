# Xbox Input → XInput Compatibility Layer

add_library(xbox_input STATIC
    xinput_device.c
)
if(NINTENDO_SWITCH)
    # The Switch's controllers, read through libnx and mapped by position.
    target_sources(xbox_input PRIVATE xinput_nx.c)
elseif(ANDROID)
    # The Java overlay is a virtual Xbox pad. Physical Android controllers
    # still come through SDL2 and are merged in xinput_device.c.
    target_sources(xbox_input PRIVATE android_input.c)
    target_compile_definitions(xbox_input PRIVATE NFSU2_ANDROID=1)
endif()

target_include_directories(xbox_input PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})

if(WIN32)
    target_link_libraries(xbox_input PUBLIC platform xinput)
else()
    if(ANDROID AND TARGET SDL2::SDL2)
        target_link_libraries(xbox_input PUBLIC platform SDL2::SDL2)
    else()
        find_package(SDL2 REQUIRED)
        target_link_libraries(xbox_input PUBLIC platform SDL2::SDL2)
    endif()
endif()

if(MSVC)
    target_compile_options(xbox_input PRIVATE /W4 /O2)
else()
    target_compile_options(xbox_input PRIVATE -Wall -Wextra -O2)
endif()
