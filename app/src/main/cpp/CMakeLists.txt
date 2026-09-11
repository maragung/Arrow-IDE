cmake_minimum_required(VERSION 3.22.1)
project(arrowpty)

add_library(pty SHARED pty.c)

target_link_libraries(pty log)
