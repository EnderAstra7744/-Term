cmake_minimum_required(VERSION 3.22.1)
project("dtfapty")

add_library(dtfapty SHARED pty.c)

find_library(log-lib log)

target_link_libraries(dtfapty ${log-lib})
