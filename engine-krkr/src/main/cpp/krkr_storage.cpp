/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_storage.h"

#include <algorithm>
#include <cerrno>
#include <cstddef>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <utility>

namespace twinquill::krkr {
namespace {

bool add_fits(std::uint64_t left, std::uint64_t right, std::uint64_t limit) {
    return left <= limit && right <= limit - left;
}

bool fits_size_t(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<std::size_t>::max());
}

bool fits_off_t(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<off_t>::max());
}

bool fits_tjs_int64(std::uint64_t value) {
    return value <= static_cast<std::uint64_t>(std::numeric_limits<TJS::tjs_int64>::max());
}

bool compute_seek_position(
    std::uint64_t current,
    std::uint64_t size,
    TJS::tjs_int64 offset,
    TJS::tjs_int whence,
    std::uint64_t* output) {
    if (output == nullptr) {
        return false;
    }

    std::uint64_t base = 0;
    switch (whence) {
        case TJS_BS_SEEK_SET:
            base = 0;
            break;
        case TJS_BS_SEEK_CUR:
            base = current;
            break;
        case TJS_BS_SEEK_END:
            base = size;
            break;
        default:
            return false;
    }

    std::uint64_t position = 0;
    if (offset < 0) {
        const std::uint64_t magnitude =
            static_cast<std::uint64_t>(-(offset + 1)) + 1;
        if (magnitude > base) {
            return false;
        }
        position = base - magnitude;
    } else {
        const std::uint64_t magnitude = static_cast<std::uint64_t>(offset);
        if (magnitude > std::numeric_limits<std::uint64_t>::max() - base) {
            return false;
        }
        position = base + magnitude;
    }

    if (position > size || !fits_tjs_int64(position)) {
        return false;
    }
    *output = position;
    return true;
}

void close_ignoring_result(int descriptor) {
    if (descriptor < 0) {
        return;
    }
    static_cast<void>(close(descriptor));
}

class ScopedDescriptor final {
public:
    explicit ScopedDescriptor(int descriptor) : descriptor_(descriptor) {
    }

    ~ScopedDescriptor() {
        close_ignoring_result(descriptor_);
    }

    ScopedDescriptor(const ScopedDescriptor&) = delete;
    ScopedDescriptor& operator=(const ScopedDescriptor&) = delete;

    int get() const {
        return descriptor_;
    }

    int release() {
        const int descriptor = descriptor_;
        descriptor_ = -1;
        return descriptor;
    }

private:
    int descriptor_;
};

class FileDescriptorReadOnlyStream final : public ReadOnlyStream {
public:
    FileDescriptorReadOnlyStream(int descriptor, std::uint64_t size)
        : descriptor_(descriptor), size_(size), cursor_(0) {
    }

    ~FileDescriptorReadOnlyStream() override {
        close_ignoring_result(descriptor_);
    }

    FileDescriptorReadOnlyStream(const FileDescriptorReadOnlyStream&) = delete;
    FileDescriptorReadOnlyStream& operator=(const FileDescriptorReadOnlyStream&) = delete;

    TJS::tjs_uint64 TJS_INTF_METHOD Seek(
        TJS::tjs_int64 offset,
        TJS::tjs_int whence) override {
        std::uint64_t position = 0;
        if (compute_seek_position(cursor_, size_, offset, whence, &position)) {
            cursor_ = position;
        }
        return static_cast<TJS::tjs_uint64>(cursor_);
    }

    TJS::tjs_uint TJS_INTF_METHOD Read(void* buffer, TJS::tjs_uint read_size) override {
        if ((buffer == nullptr && read_size != 0) || read_size == 0 || cursor_ >= size_) {
            return 0;
        }

        const std::uint64_t bytes_available = size_ - cursor_;
        TJS::tjs_uint bytes_requested = read_size;
        if (bytes_available < static_cast<std::uint64_t>(bytes_requested)) {
            bytes_requested = static_cast<TJS::tjs_uint>(bytes_available);
        }

        auto* output = static_cast<unsigned char*>(buffer);
        std::uint64_t position = cursor_;
        TJS::tjs_uint bytes_read = 0;
        TJS::tjs_uint remaining = bytes_requested;
        while (remaining > 0) {
            if (!fits_off_t(position)) {
                break;
            }
            const std::size_t request = std::min(
                static_cast<std::size_t>(remaining),
                static_cast<std::size_t>(std::numeric_limits<ssize_t>::max()));
            const ssize_t count = pread(
                descriptor_,
                output,
                request,
                static_cast<off_t>(position));
            if (count < 0) {
                if (errno == EINTR) {
                    continue;
                }
                break;
            }
            if (count == 0 || static_cast<std::size_t>(count) > request) {
                break;
            }
            output += count;
            position += static_cast<std::uint64_t>(count);
            bytes_read += static_cast<TJS::tjs_uint>(count);
            remaining -= static_cast<TJS::tjs_uint>(count);
        }
        cursor_ = position;
        return bytes_read;
    }

    TJS::tjs_uint TJS_INTF_METHOD Write(
        const void* /*buffer*/,
        TJS::tjs_uint /*write_size*/) override {
        return 0;
    }

    TJS::tjs_uint64 TJS_INTF_METHOD GetSize() override {
        return static_cast<TJS::tjs_uint64>(size_);
    }

private:
    int descriptor_;
    std::uint64_t size_;
    std::uint64_t cursor_;
};

bool read_exact_at(
    TJS::tTJSBinaryStream* input,
    std::uint64_t offset,
    void* output,
    std::size_t size) {
    if (input == nullptr || (output == nullptr && size != 0) || !fits_tjs_int64(offset)) {
        return false;
    }
    if (input->Seek(static_cast<TJS::tjs_int64>(offset), TJS_BS_SEEK_SET) != offset) {
        return false;
    }

    auto* cursor = static_cast<unsigned char*>(output);
    std::size_t remaining = size;
    while (remaining > 0) {
        const std::size_t request = std::min(
            remaining,
            static_cast<std::size_t>(std::numeric_limits<TJS::tjs_uint>::max()));
        const TJS::tjs_uint count = input->Read(cursor, static_cast<TJS::tjs_uint>(request));
        if (count == 0 || static_cast<std::size_t>(count) > request) {
            return false;
        }
        cursor += count;
        remaining -= static_cast<std::size_t>(count);
    }
    return true;
}

int descriptor_size(int descriptor, bool require_regular_file, std::uint64_t* output) {
    if (descriptor < 0 || output == nullptr) {
        return -1;
    }
    struct stat stat_buffer {};
    if (fstat(descriptor, &stat_buffer) != 0) {
        return -1;
    }
    if (S_ISREG(stat_buffer.st_mode)) {
        if (stat_buffer.st_size < 0 || !fits_off_t(static_cast<std::uint64_t>(stat_buffer.st_size))) {
            return -1;
        }
        *output = static_cast<std::uint64_t>(stat_buffer.st_size);
        return 0;
    }
    if (require_regular_file) {
        return -1;
    }

    errno = 0;
    const off_t current = lseek(descriptor, 0, SEEK_CUR);
    if (current < 0) {
        return -1;
    }
    const off_t end = lseek(descriptor, 0, SEEK_END);
    const int seek_errno = errno;
    if (lseek(descriptor, current, SEEK_SET) < 0) {
        return -1;
    }
    errno = seek_errno;
    if (end < 0) {
        return -1;
    }
    *output = static_cast<std::uint64_t>(end);
    return 0;
}

int open_descriptor_stream(
    int descriptor_value,
    bool require_regular_file,
    std::unique_ptr<ReadOnlyStream>* output) {
    ScopedDescriptor descriptor(descriptor_value);
    if (descriptor.get() < 0 || output == nullptr) {
        return -1;
    }
    std::uint64_t file_size = 0;
    if (descriptor_size(descriptor.get(), require_regular_file, &file_size) != 0) {
        return -1;
    }
    output->reset(new FileDescriptorReadOnlyStream(descriptor.release(), file_size));
    return 0;
}

int open_local_file(const StorageSpec& spec, std::unique_ptr<ReadOnlyStream>* output) {
    if (spec.path.empty() || output == nullptr) {
        return -1;
    }
    const int descriptor = open(spec.path.c_str(), O_RDONLY | O_CLOEXEC);
    if (descriptor < 0) {
        return -1;
    }
    return open_descriptor_stream(descriptor, true, output);
}

int open_owned_descriptor(const StorageSpec& spec, std::unique_ptr<ReadOnlyStream>* output) {
    return open_descriptor_stream(spec.descriptor, false, output);
}

}  // namespace

StorageSpec StorageSpec::LocalFile(std::string path) {
    return StorageSpec{StorageBackend::kLocalFile, std::move(path), -1};
}

StorageSpec StorageSpec::OwnedFileDescriptor(int descriptor) {
    return StorageSpec{StorageBackend::kOwnedFileDescriptor, std::string(), descriptor};
}

int open_read_only_stream(
    const StorageSpec& spec,
    std::unique_ptr<ReadOnlyStream>* output) {
    if (output == nullptr) {
        return -1;
    }
    output->reset();
    return spec.backend == StorageBackend::kLocalFile
        ? open_local_file(spec, output)
        : open_owned_descriptor(spec, output);
}

int read_storage_file(
    const StorageSpec& spec,
    std::uint64_t maximum_size,
    std::string* output) {
    if (output == nullptr) {
        return -1;
    }
    std::unique_ptr<ReadOnlyStream> input;
    const int open_result = open_read_only_stream(spec, &input);
    if (open_result != 0) {
        return open_result;
    }
    const std::uint64_t input_size = input->GetSize();
    if (input_size > maximum_size || !fits_size_t(input_size)) {
        return -1;
    }
    output->assign(static_cast<std::size_t>(input_size), '\0');
    if (!output->empty() &&
        !read_exact_at(input.get(), 0, output->data(), output->size())) {
        return -1;
    }
    return 0;
}

}  // namespace twinquill::krkr
