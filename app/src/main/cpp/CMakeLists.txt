cmake_minimum_required(VERSION 3.22.1)
project(earam_native)

add_library(earam_native SHARED earam_native.cpp)

target_compile_features(earam_native PRIVATE cxx_std_17)
