/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_private_storage.h"
#include <atomic>
#include <cerrno>
#include <fcntl.h>
#include <regex>
#include <sys/stat.h>
#include <unistd.h>

namespace twinquill::krkr {
namespace {
struct Fd {
    int fd;
    ~Fd() { if (fd >= 0) close(fd); }
};
void io_check(bool valid) { if (!valid) throw StorageError(41, "Private storage operation failed"); }
std::atomic<unsigned long> sequence{1};
}
PrivateStorage::PrivateStorage(const std::string& save) {
    if (save.empty()) return; // Legacy isolated entry has no write capability.
    Fd base{open(save.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)};
    io_check(base.fd >= 0);
    Fd lease{openat(base.fd, ".krkr-session.lock", O_RDWR | O_CREAT | O_CLOEXEC | O_NOFOLLOW, 0600)};
    io_check(lease.fd >= 0);
    struct stat lease_stat{};
    io_check(fstat(lease.fd, &lease_stat) == 0 && S_ISREG(lease_stat.st_mode));
    // Match Java FileChannel's byte-range lock across the launcher/runtime processes.
    // The Java owner table prevents another descriptor being opened by same-process
    // management while this session is live (POSIX locks are process-owned).
    struct flock lock{};
    lock.l_type = F_WRLCK; lock.l_whence = SEEK_SET; lock.l_len = 1;
    if (fcntl(lease.fd, F_SETLK, &lock) < 0)
        throw StorageError(41, ("Krkr save directory lock failed: " + std::to_string(errno)).c_str());
    struct stat marker{};
    if (fstatat(base.fd, ".krkr-restore.pending", &marker, AT_SYMLINK_NOFOLLOW) == 0) {
        io_check(S_ISREG(marker.st_mode) && marker.st_size > 0 && marker.st_size <= 128);
        Fd input{openat(base.fd, ".krkr-restore.pending", O_RDONLY | O_CLOEXEC | O_NOFOLLOW)};
        io_check(input.fd >= 0);
        std::string stage(static_cast<std::size_t>(marker.st_size), '\0');
        io_check(::read(input.fd, stage.data(), stage.size()) == static_cast<ssize_t>(stage.size()));
        io_check(stage.size() > 14 && stage.rfind(".krkr-restore-", 0) == 0 && stage.find_first_not_of(
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-") == std::string::npos);
        struct stat current{};
        if (fstatat(base.fd, "krkr", &current, AT_SYMLINK_NOFOLLOW) == 0) {
            io_check(S_ISDIR(current.st_mode));
        } else {
            io_check(errno == ENOENT);
            struct stat previous{};
            const char* candidate = ".krkr-previous";
            if (fstatat(base.fd, candidate, &previous, AT_SYMLINK_NOFOLLOW) < 0) {
                io_check(errno == ENOENT); candidate = stage.c_str();
                io_check(fstatat(base.fd, candidate, &previous, AT_SYMLINK_NOFOLLOW) == 0);
            }
            io_check(S_ISDIR(previous.st_mode));
            io_check(renameat(base.fd, candidate, base.fd, "krkr") == 0 && fsync(base.fd) == 0);
        }
        // Offline management removes obsolete staged/previous trees under its lease.
    } else io_check(errno == ENOENT);
    if (mkdirat(base.fd, "krkr", 0700) < 0 && errno != EEXIST) io_check(false);
    root_ = openat(base.fd, "krkr", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    io_check(root_ >= 0);
    lock_ = lease.fd; lease.fd = -1;
}
PrivateStorage::~PrivateStorage() {
    if (root_ >= 0) close(root_);
    if (lock_ >= 0) close(lock_);
}
bool PrivateStorage::owns(const std::string& name) { return name.rfind(kDataPath, 0) == 0; }
std::string PrivateStorage::relative(const std::string& name, bool folder) const {
    if (root_ < 0 || !owns(name)) throw StorageError(10, "Writes require System.dataPath");
    // KAG appends "/" to a directory URI that already ends in "/".
    // Normalize only separators inside the validated private URI namespace.
    auto suffix = name.substr(std::char_traits<char>::length(kDataPath));
    while (!suffix.empty() && suffix.front() == '/') suffix.erase(0, 1);
    auto path = storage_path(suffix, folder);
    if (path.find('>') != std::string::npos) throw StorageError(10, "Private archives are not writable");
    static const std::regex temporary_name(R"((^|/)\.tq-[0-9]+-[0-9]+($|/))");
    if (std::regex_search(path, temporary_name)) throw StorageError(10, "Reserved private temporary name");
    return path;
}
int PrivateStorage::parent(const std::string& path, bool create) {
    Fd current{dup(root_)};
    io_check(current.fd >= 0);
    std::size_t start = 0, depth = 0;
    for (;;) {
        const auto slash = path.find('/', start);
        if (slash == std::string::npos) break;
        if (++depth > 16) throw StorageError(10, "Private directory nesting exceeds 16");
        const auto part = path.substr(start, slash - start);
        if (create && mkdirat(current.fd, part.c_str(), 0700) < 0 && errno != EEXIST) io_check(false);
        int next = openat(current.fd, part.c_str(), O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
        io_check(next >= 0);
        close(current.fd); current.fd = next;
        start = slash + 1;
    }
    const int result = current.fd; current.fd = -1; return result;
}
std::string PrivateStorage::read(const std::string& name) {
    const auto path = relative(name);
    Fd directory{parent(path, false)};
    const auto file = path.substr(path.find_last_of('/') == std::string::npos ? 0 : path.find_last_of('/') + 1);
    Fd input{openat(directory.fd, file.c_str(), O_RDONLY | O_CLOEXEC | O_NOFOLLOW)};
    io_check(input.fd >= 0);
    struct stat stat{};
    io_check(fstat(input.fd, &stat) == 0 && S_ISREG(stat.st_mode));
    if (stat.st_size < 0 || stat.st_size > static_cast<off_t>(kResourceReadLimit))
        throw StorageError(20, "Private file exceeds 32 MiB read budget");
    std::string bytes(static_cast<std::size_t>(stat.st_size), '\0');
    std::size_t done = 0;
    while (done < bytes.size()) {
        const auto count = ::read(input.fd, bytes.data() + done, bytes.size() - done);
        if (count < 0 && errno == EINTR) continue;
        io_check(count > 0); done += static_cast<std::size_t>(count);
    }
    return bytes;
}
bool PrivateStorage::exists(const std::string& name) {
    const auto path = relative(name);
    // Do not swallow invalid names, symlinks or inaccessible parent directories.
    const auto slash = path.find_last_of('/');
    Fd directory{parent(path, false)};
    const auto file = path.substr(slash == std::string::npos ? 0 : slash + 1);
    struct stat stat{};
    if (fstatat(directory.fd, file.c_str(), &stat, AT_SYMLINK_NOFOLLOW) < 0) {
        if (errno == ENOENT) return false;
        io_check(false);
    }
    return S_ISREG(stat.st_mode);
}
void PrivateStorage::create_folders(const std::string& name) {
    const auto path = relative(name, true);
    Fd directory{parent(path, true)};
    io_check(fsync(directory.fd) == 0);
}
void PrivateStorage::write(const std::string& name, std::string_view bytes) {
    const auto path = relative(name);
    if (bytes.size() > kResourceReadLimit) throw StorageError(20, "Private write exceeds 32 MiB");
    Fd directory{parent(path, true)};
    const auto slash = path.find_last_of('/');
    const auto file = path.substr(slash == std::string::npos ? 0 : slash + 1);
    const std::string temporary = ".tq-" + std::to_string(getpid()) + "-" + std::to_string(sequence++);
    Fd output{openat(directory.fd, temporary.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC | O_NOFOLLOW, 0600)};
    io_check(output.fd >= 0);
    struct Remove {
        int directory; const std::string& name;
        ~Remove() { unlinkat(directory, name.c_str(), 0); }
    } remove{directory.fd, temporary};
    std::size_t done = 0;
    while (done < bytes.size()) {
        const auto count = ::write(output.fd, bytes.data() + done, bytes.size() - done);
        if (count < 0 && errno == EINTR) continue;
        io_check(count > 0); done += static_cast<std::size_t>(count);
    }
    io_check(fsync(output.fd) == 0);
    const int descriptor = output.fd; output.fd = -1;
    io_check(close(descriptor) == 0);
    // Rename is the sole commit point. Any prior failure preserves the old file.
    io_check(renameat(directory.fd, temporary.c_str(), directory.fd, file.c_str()) == 0);
    io_check(fsync(directory.fd) == 0);
}
}  // namespace twinquill::krkr
