# SPDX-License-Identifier: GPL-2.0-or-later

function(twinquill_replace_exact FILE_PATH EXPECTED REPLACEMENT)
    file(READ "${FILE_PATH}" CONTENTS)
    string(FIND "${CONTENTS}" "${EXPECTED}" MATCH_OFFSET)
    if(MATCH_OFFSET EQUAL -1)
        message(FATAL_ERROR "ONS patch context not found in ${FILE_PATH}")
    endif()
    string(REPLACE "${EXPECTED}" "${REPLACEMENT}" CONTENTS "${CONTENTS}")
    file(WRITE "${FILE_PATH}" "${CONTENTS}")
endfunction()

function(twinquill_prepare_ons_patched_sources
    UPSTREAM_DIR
    OUTPUT_DIR
    OUT_COMMAND_SOURCE
    OUT_MAIN_SOURCE
)
    file(MAKE_DIRECTORY "${OUTPUT_DIR}")

    set(COMMAND_SOURCE "${OUTPUT_DIR}/ONScripter_command.cpp")
    set(MAIN_SOURCE "${OUTPUT_DIR}/onscripter_main.cpp")
    configure_file(
        "${UPSTREAM_DIR}/src/onsyuri/ONScripter_command.cpp"
        "${COMMAND_SOURCE}"
        COPYONLY
    )
    configure_file(
        "${UPSTREAM_DIR}/src/onsyuri/onscripter_main.cpp"
        "${MAIN_SOURCE}"
        COPYONLY
    )

    twinquill_replace_exact(
        "${COMMAND_SOURCE}"
        [=[#include "ONScripter.h"]=]
        [=[#include "ONScripter.h"
#include "twinquill_ons_exit.h"]=]
    )
    twinquill_replace_exact(
        "${COMMAND_SOURCE}"
        [=[    exit(0);
    return RET_CONTINUE; // dummy]=]
        [=[#if defined(ANDROID)
    twinquillOnsExit(0);
#else
    exit(0);
#endif
    return RET_CONTINUE; // dummy]=]
    )

    twinquill_replace_exact(
        "${MAIN_SOURCE}"
        [=[#include "ONScripter.h"]=]
        [=[#include "ONScripter.h"
#include "twinquill_ons_exit.h"]=]
    )
    twinquill_replace_exact(
        "${MAIN_SOURCE}"
        [=[    if (ons.openScript()) exit(-1);
    if (ons.init()) exit(-1);]=]
        [=[#if defined(ANDROID)
    if (ons.openScript()) return -1;
    if (ons.init()) return -1;
#else
    if (ons.openScript()) exit(-1);
    if (ons.init()) exit(-1);
#endif]=]
    )
    twinquill_replace_exact(
        "${MAIN_SOURCE}"
        [=[    ons.executeLabel();
    exit(0);
}]=]
        [=[#if defined(ANDROID)
    try {
        ons.executeLabel();
    }
    catch (const TwinQuillOnsExit &request) {
        return request.status();
    }
    return 0;
#else
    ons.executeLabel();
    exit(0);
#endif
}]=]
    )

    set("${OUT_COMMAND_SOURCE}" "${COMMAND_SOURCE}" PARENT_SCOPE)
    set("${OUT_MAIN_SOURCE}" "${MAIN_SOURCE}" PARENT_SCOPE)
endfunction()
