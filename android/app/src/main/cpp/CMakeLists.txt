cmake_minimum_required(VERSION 3.10.2)
project("wkwhispercore")

add_library(
    wkwhispercore
    SHARED
    whispernet.cpp
    wk_whisper_preprocess.cpp
)

find_library(
    log-lib
    log
)

find_library(
    android-lib
    android
)

target_link_libraries(
    wkwhispercore
    ${log-lib}
    ${android-lib}   # 👈 추가
    atomic
    m
)
