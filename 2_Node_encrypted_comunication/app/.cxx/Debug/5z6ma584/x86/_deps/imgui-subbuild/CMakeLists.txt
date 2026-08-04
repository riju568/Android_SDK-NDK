# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.22.1-g37088a8-dirty)

# We name the project and the target for the ExternalProject_Add() call
# to something that will highlight to the user what we are working on if
# something goes wrong and an error message is produced.

project(imgui-populate NONE)


# Pass through things we've already detected in the main project to avoid
# paying the cost of redetecting them again in ExternalProject_Add()
set(GIT_EXECUTABLE [==[GIT_EXECUTABLE-NOTFOUND]==])
set(GIT_VERSION_STRING [==[]==])
set_property(GLOBAL PROPERTY _CMAKE_FindGit_GIT_EXECUTABLE_VERSION
  [==[GIT_EXECUTABLE-NOTFOUND;]==]
)


include(ExternalProject)
ExternalProject_Add(imgui-populate
                     "UPDATE_DISCONNECTED" "False" "GIT_REPOSITORY" "https://github.com/ocornut/imgui.git" "GIT_TAG"
                    SOURCE_DIR          "C:/Users/tanmo/Documents/GitHub/Android_SDK+NDK/2_Node_encrypted_comunication/app/.cxx/Debug/5z6ma584/x86/_deps/imgui-src"
                    BINARY_DIR          "C:/Users/tanmo/Documents/GitHub/Android_SDK+NDK/2_Node_encrypted_comunication/app/.cxx/Debug/5z6ma584/x86/_deps/imgui-build"
                    CONFIGURE_COMMAND   ""
                    BUILD_COMMAND       ""
                    INSTALL_COMMAND     ""
                    TEST_COMMAND        ""
                    USES_TERMINAL_DOWNLOAD  YES
                    USES_TERMINAL_UPDATE    YES
)


