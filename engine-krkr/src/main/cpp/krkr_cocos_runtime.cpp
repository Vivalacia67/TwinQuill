/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_cocos_runtime.h"
#include "krkr_runtime_state.h"

#include <GLES2/gl2.h>

#include <cstddef>
#include <string>

namespace twinquill::krkr_runtime {
namespace {

// These are the exact admitted source files. They contain the upstream
// license text and shader bodies; this seam supplies only the missing uniform
// declaration required by the standalone proof.
#include "cocos/renderer/ccShader_PositionColor.vert"
#include "cocos/renderer/ccShader_PositionColor.frag"

constexpr char kVertexUniformPrefix[] = "uniform mat4 CC_MVPMatrix;\n";
constexpr float kIdentityMvp[16] = {
    1.0F, 0.0F, 0.0F, 0.0F,
    0.0F, 1.0F, 0.0F, 0.0F,
    0.0F, 0.0F, 1.0F, 0.0F,
    0.0F, 0.0F, 0.0F, 1.0F,
};

// GLES error state is consumed at each seam. This prevents an error from a
// prior caller from being attributed to a later operation while retaining a
// deterministic failure result for every operation below.
void clear_gl_errors() noexcept {
    while (glGetError() != GL_NO_ERROR) {
    }
}

bool gl_ok() noexcept {
    return glGetError() == GL_NO_ERROR;
}

GLuint compile_shader(GLenum type, const char* source) noexcept {
    clear_gl_errors();
    const GLuint shader = glCreateShader(type);
    if (shader == 0U || !gl_ok()) {
        if (shader != 0U) {
            glDeleteShader(shader);
        }
        return 0U;
    }

    const GLchar* sources[2] = {
        type == GL_VERTEX_SHADER ? kVertexUniformPrefix : "",
        source,
    };
    const GLint lengths[2] = {
        type == GL_VERTEX_SHADER
            ? static_cast<GLint>(sizeof(kVertexUniformPrefix) - 1U)
            : 0,
        static_cast<GLint>(std::char_traits<char>::length(source)),
    };
    glShaderSource(shader, 2, sources, lengths);
    glCompileShader(shader);
    if (!gl_ok()) {
        glDeleteShader(shader);
        return 0U;
    }

    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!gl_ok() || compiled != GL_TRUE) {
        glDeleteShader(shader);
        return 0U;
    }
    return shader;
}

void release_program(GLuint* program,
                     int* position_attribute,
                     int* color_attribute,
                     int* mvp_uniform) noexcept {
    if (program != nullptr && *program != 0U) {
        glDeleteProgram(*program);
        *program = 0U;
    }
    if (position_attribute != nullptr) {
        *position_attribute = -1;
    }
    if (color_attribute != nullptr) {
        *color_attribute = -1;
    }
    if (mvp_uniform != nullptr) {
        *mvp_uniform = -1;
    }
}

}  // namespace

CocosRuntime::~CocosRuntime() {
    destroy();
}

int CocosRuntime::surface_created() {
    // Every surface-created callback denotes a new context generation.  The
    // previous context may already be gone, so never issue GL cleanup here;
    // just abandon its names before compiling into the new current context.
    (void)abandon_context();

    clear_gl_errors();
    const GLuint vertex = compile_shader(GL_VERTEX_SHADER, ccPositionColor_vert);
    if (vertex == 0U) {
        return kRuntimeGraphicsError;
    }
    const GLuint fragment = compile_shader(GL_FRAGMENT_SHADER, ccPositionColor_frag);
    if (fragment == 0U) {
        glDeleteShader(vertex);
        return kRuntimeGraphicsError;
    }

    const GLuint program = glCreateProgram();
    if (program == 0U || !gl_ok()) {
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return kRuntimeGraphicsError;
    }
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    if (!gl_ok()) {
        glDeleteProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return kRuntimeGraphicsError;
    }

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!gl_ok() || linked != GL_TRUE) {
        glDeleteProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return kRuntimeGraphicsError;
    }

    const GLint position = glGetAttribLocation(program, "a_position");
    const GLint color = glGetAttribLocation(program, "a_color");
    const GLint mvp = glGetUniformLocation(program, "CC_MVPMatrix");
    if (!gl_ok() || position < 0 || color < 0 || mvp < 0) {
        glDeleteProgram(program);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return kRuntimeGraphicsError;
    }

    // Shader objects are no longer needed after a successful link. Deleting
    // them here also makes all failure paths above explicit and bounded.
    glDeleteShader(vertex);
    glDeleteShader(fragment);
    if (!gl_ok()) {
        glDeleteProgram(program);
        return kRuntimeGraphicsError;
    }

    program_ = static_cast<unsigned int>(program);
    position_attribute_ = static_cast<int>(position);
    color_attribute_ = static_cast<int>(color);
    mvp_uniform_ = static_cast<int>(mvp);
    return kRuntimeOk;
}

int CocosRuntime::surface_changed(int width, int height) {
    if (width <= 0 || height <= 0) {
        return kRuntimeInvalidArgument;
    }
    if (!ready()) {
        return kRuntimeSurfaceNotReady;
    }
    width_ = width;
    height_ = height;
    return kRuntimeOk;
}

int CocosRuntime::draw_frame(bool alternate_color, std::int64_t override_color) {
    if (!ready() || width_ <= 0 || height_ <= 0) {
        return kRuntimeSurfaceNotReady;
    }

    clear_gl_errors();
    glViewport(0, 0, width_, height_);
    if (!gl_ok()) {
        return kRuntimeGraphicsError;
    }
    glClearColor(0.02F, 0.02F, 0.02F, 1.0F);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!gl_ok()) {
        return kRuntimeGraphicsError;
    }

    glUseProgram(static_cast<GLuint>(program_));
    if (!gl_ok()) {
        return kRuntimeGraphicsError;
    }
    glUniformMatrix4fv(mvp_uniform_, 1, GL_FALSE, kIdentityMvp);
    if (!gl_ok()) {
        glUseProgram(0U);
        return kRuntimeGraphicsError;
    }

    // A centered, two-triangle quad is sufficient to prove the shader seam.
    constexpr GLfloat vertices[] = {
        -0.5F, -0.5F, 0.0F, 1.0F,
         0.5F, -0.5F, 0.0F, 1.0F,
        -0.5F,  0.5F, 0.0F, 1.0F,
         0.5F,  0.5F, 0.0F, 1.0F,
    };
    GLfloat color[] = {
        alternate_color ? 0.95F : 0.18F,
        alternate_color ? 0.25F : 0.62F,
        alternate_color ? 0.20F : 0.95F,
        1.0F,
    };
    if (override_color >= 0) {
        color[0] = static_cast<float>((override_color >> 16) & 255) / 255.0F;
        color[1] = static_cast<float>((override_color >> 8) & 255) / 255.0F;
        color[2] = static_cast<float>(override_color & 255) / 255.0F;
    }
    glEnableVertexAttribArray(static_cast<GLuint>(position_attribute_));
    if (!gl_ok()) {
        glUseProgram(0U);
        return kRuntimeGraphicsError;
    }
    glVertexAttribPointer(static_cast<GLuint>(position_attribute_), 4, GL_FLOAT,
                          GL_FALSE, 0, vertices);
    // The color is a single constant for the proof quad.  Do not describe a
    // four-float array: GLES would read past the four-value constant as if it
    // contained one value per vertex.
    glDisableVertexAttribArray(static_cast<GLuint>(color_attribute_));
    if (!gl_ok()) {
        glDisableVertexAttribArray(static_cast<GLuint>(position_attribute_));
        glUseProgram(0U);
        return kRuntimeGraphicsError;
    }
    glVertexAttrib4fv(static_cast<GLuint>(color_attribute_), color);
    if (!gl_ok()) {
        glDisableVertexAttribArray(static_cast<GLuint>(position_attribute_));
        glUseProgram(0U);
        return kRuntimeGraphicsError;
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    const bool draw_ok = gl_ok();
    glDisableVertexAttribArray(static_cast<GLuint>(position_attribute_));
    glUseProgram(0U);
    if (!draw_ok || !gl_ok()) {
        return kRuntimeGraphicsError;
    }
    return kRuntimeOk;
}

int CocosRuntime::surface_lost() {
    if (!ready()) {
        width_ = 0;
        height_ = 0;
        return kRuntimeOk;
    }
    clear_gl_errors();
    GLuint program = static_cast<GLuint>(program_);
    release_program(&program, &position_attribute_, &color_attribute_,
                    &mvp_uniform_);
    program_ = static_cast<unsigned int>(program);
    width_ = 0;
    height_ = 0;
    // Deletion is deterministic even if the context reports an error. The
    // next surface-created callback will build a fresh program.
    return gl_ok() ? kRuntimeOk : kRuntimeGraphicsError;
}

int CocosRuntime::abandon_context() noexcept {
    // This path is deliberately GL-free.  It is used by context replacement,
    // native destroy, and C++ teardown where no current context is guaranteed.
    program_ = 0U;
    position_attribute_ = -1;
    color_attribute_ = -1;
    mvp_uniform_ = -1;
    width_ = 0;
    height_ = 0;
    return kRuntimeOk;
}

int CocosRuntime::destroy() {
    return abandon_context();
}

}  // namespace twinquill::krkr_runtime
