cmake_minimum_required(VERSION 3.22)
project(wkwhispercore)

add_library(wkwhispercore SHARED
    whispernet.cpp
    wk_whisper_preprocess.cpp
)

find_library(log-lib log)
target_link_libraries(wkwhispercore ${log-lib})
