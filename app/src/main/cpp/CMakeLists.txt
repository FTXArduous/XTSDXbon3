cmake_minimum_required(VERSION 3.22.1)
project(vxbridge CXX)

add_library(vxbridge SHARED vxbridge.cpp)
target_link_options(vxbridge PRIVATE -Wl,-z,max-page-size=16384)
