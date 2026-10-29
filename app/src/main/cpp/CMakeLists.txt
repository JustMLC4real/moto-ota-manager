cmake_minimum_required(VERSION 3.22.1)
project(moto_stage_publish C)
add_library(moto_stage_publish SHARED moto_stage_publish.c)
target_compile_options(moto_stage_publish PRIVATE -Wall -Wextra -Werror -fstack-protector-strong -fvisibility=hidden)
target_compile_definitions(moto_stage_publish PRIVATE _FORTIFY_SOURCE=2)
# NDK r27 needs both flags for LOAD and RELRO alignment on 4 KiB/16 KiB Android.
# https://developer.android.com/guide/practices/page-sizes
target_link_options(moto_stage_publish PRIVATE -Wl,-z,relro,-z,now
    -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
