/* SPDX-License-Identifier: GPL-2.0-or-later
 * Copyright (C) 2026 TwinQuill contributors
 */
#include "krkr_resource.h"
#include "krkr_xp3.h"
#include "krkr_private_storage.h"
#include <sys/wait.h>
#include "krkr_game_text.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sys/stat.h>
#include <sys/resource.h>
#include <signal.h>
#include <unistd.h>
using namespace twinquill::krkr;
namespace {
void check(bool okay, const char* message) { if (!okay) throw std::runtime_error(message); }
template<class Call> void error(int expected, Call call) {
    try { call(); } catch (const StorageError& e) { check(e.status == expected, e.what()); return; }
    throw std::runtime_error("Expected storage failure");
}
class Backend final : public ResourceBackend {
public:
    explicit Backend(std::string root) : root(std::move(root)) {}
    bool revoked = false;
    int opens = 0;
    int stats = 0;
    int lists = 0;
    int lookups = 0, lookup_ends = 0;
    void begin_lookup() override { ++lookups; }
    void end_lookup() noexcept override { ++lookup_ends; }
    StorageStat stat(const std::string& path) override {
        ++stats;
        if (revoked) throw StorageError(40, "Revoked test medium");
        auto file = std::filesystem::path(root) / path;
        if (!std::filesystem::exists(file)) return {};
        const bool directory = std::filesystem::is_directory(file);
        return {true, directory, directory ? -1 : static_cast<std::int64_t>(std::filesystem::file_size(file)), 1};
    }
    std::vector<std::string> list(const std::string& path) override {
        ++lists;
        if (revoked) throw StorageError(40, "Revoked test medium");
        std::vector<std::string> names;
        for (const auto& file : std::filesystem::directory_iterator(std::filesystem::path(root)/path))
            if (file.is_regular_file()) names.push_back(file.path().filename().string());
        return names;
    }
    std::shared_ptr<ByteSource> open(const std::string& path) override {
        if (revoked) throw StorageError(40, "Revoked test medium");
        ++opens;
        return open_local_source((std::filesystem::path(root)/path).string());
    }
    std::string root;
};
}
int main(int count, char** args) {
    try {
        check(count == 3, "Usage: storage_test <fixture-root> <private-test-directory>");
        const std::filesystem::path fixtures(args[1]), saves(args[2]);
        std::ifstream matrix(fixtures/"m3-vectors/vectors.tsv");
        check(matrix.good(), "Missing shared XP3 fixture matrix");
        std::string line, source;
        int vectors = 0;
        while (std::getline(matrix, line)) {
            const auto tab = line.find('\t');
            const auto path = fixtures/"m3-vectors"/line.substr(0, tab);
            const int expected = std::stoi(line.substr(tab + 1));
            const int actual = read_raw_xp3_startup(path.c_str(), &source);
            if (actual != expected) std::cerr << path << " expected " << expected << " actual " << actual << '\n';
            check(actual == expected, "XP3 shared vector failure");
            if (actual == 0) check(source == "var a=1;", "XP3 startup content mismatch");
            ++vectors;
        }
        check(vectors >= 14, "Missing XP3 vectors");
        auto packed = open_local_source((fixtures/"m3-vectors/compressed.xp3").string());
        Xp3Archive archive(packed);
        auto resource = archive.open("SUB\\中文日本語.BIN", packed);
        char slice[4]; resource->read(2, slice, sizeof(slice));
        check(std::string(slice, 4) == "SOUR", "Segment-boundary seek failure");
        auto empty = archive.open("empty.bin", packed);
        check(read_source(*empty).empty(), "Empty XP3 member failure");
        auto corrupt = open_local_source((fixtures/"m3-vectors/bad-resource-zlib.xp3").string());
        Xp3Archive corrupted(corrupt);
        error(34, [&] { (void)read_source(*corrupted.open("sub/中文日本語.bin", corrupt)); });
        auto media = std::make_unique<Backend>((fixtures/"m3-patches").string());
        auto* probe = media.get();
        ResourceStore store(std::move(media));
        check(read_source(*store.open("marker.txt")) == "PATCH", "Patch precedence failure");
        check(read_source(*store.open("loose.txt")) == "LOOSE", "Loose precedence failure");
        check(store.placed("startup.tjs") == "patch.xp3>startup.tjs", "Startup selected wrong archive");
        check(store.list("data.xp3>images/").size() == 2, "Archive member listing failure");
        check(store.list("images/").size() == 2, "Mounted root archive directory listing failed");
        store.add_path("images/");
        check(store.exists("checker.png"), "KAG virtual directory search failed");
        check(store.placed("checker.png").find(">images/checker.png") != std::string::npos,
            "KAG virtual directory selected wrong archive member");
        check(!store.exists("./checker.png"), "Explicit root fell back to a virtual search directory");
        check(read_source(*store.open("./marker.txt")) == "PATCH", "Root qualifier lost XP3 patch precedence");
        check(store.exists("./images/checker.png"), "Root qualifier lost XP3 member subdirectories");
        store.remove_path("images/");
        error(10, [&] { store.list("../images/"); });
        store.add_path("data.xp3>scripts/");
        check(store.exists("日本語.tjs"), "Archive search path failure");
        store.remove_path("data.xp3>scripts/");
        check(!store.exists("日本語.tjs"), "Removed archive search path still active");
        error(10, [&] { store.open("../escape"); });
        probe->revoked = true;
        error(40, [&] { store.open("marker.txt"); });
        error(40, [&] { store.list("images/"); });
        error(40, [&] { store.exists("./marker.txt"); });
        std::filesystem::create_directories(saves/"lookup/search");
        std::ofstream(saves/"lookup/search/shadow.xp3") << "NOT-AN-ARCHIVE";
        auto lookup_backend = std::make_unique<Backend>((saves/"lookup").string());
        auto* lookup_probe = lookup_backend.get();
        ResourceStore lookup(std::move(lookup_backend));
        const int before_register_lists = lookup_probe->lists;
        lookup.add_path("search/");
        check(lookup_probe->lists == before_register_lists, "Registering a physical directory enumerated its children");
        check(lookup.list("search/").size() == 1, "Registered directory lost explicit enumeration");
        error(10, [&] { lookup.add_path("search/shadow.xp3/"); });
        error(11, [&] { lookup.add_path("missing/"); });
        check(lookup.exists("shadow.xp3"), "Unqualified auto-search stopped working");
        const int before_root_probe = lookup_probe->stats;
        check(!lookup.exists("./shadow.xp3"), "Root archive probe selected a search-folder file");
        check(lookup_probe->stats == before_root_probe + 1, "Root archive probe performed unrelated metadata queries");
        check(!lookup.exists(".\\shadow.xp3"), "Backslash root qualifier selected a search-folder file");
        error(10, [&] { lookup.exists("./../escape"); });
        std::ofstream(saves/"lookup/shadow.xp3") << "ROOT";
        check(lookup.exists("./shadow.xp3"), "A negative root probe concealed a newly created file");
        lookup_probe->revoked = true;
        error(40, [&] { lookup.exists("./shadow.xp3"); });
        error(40, [&] { lookup.add_path("unregistered/"); });
        check(lookup_probe->lookups == lookup_probe->lookup_ends,
            "Failed or successful storage lookup retained its directory snapshot");
        check(probe->lookups == probe->lookup_ends, "Archive lookup retained its directory snapshot");
        check(game_text_encoding("# sample\ntextEncoding=CP932\n") == "cp932", "Explicit encoding configuration failure");
        bool invalid = false;
        try { game_text_encoding("textEncoding=cp932\ntextEncoding=utf-8"); }
        catch (const std::invalid_argument&) { invalid = true; }
        check(invalid, "Duplicate encoding configuration accepted");
        check(decode_game_text("\xef\xbb\xbfOK", "cp932") == u"OK", "Unicode BOM override failure");
        std::filesystem::create_directories(saves/"one");
        std::filesystem::create_directories(saves/"two");
        std::ofstream(saves/"one/ons-legacy.dat") << "ONS";
        PrivateStorage one((saves/"one").string()), two((saves/"two").string());
        const pid_t contender = fork();
        check(contender >= 0, "Cannot create the lock contender");
        if (contender == 0) {
            try { PrivateStorage unexpected((saves/"one").string()); _exit(1); }
            catch (const StorageError& busy) { _exit(busy.status == 41 ? 0 : 2); }
            catch (...) { _exit(3); }
        }
        int contender_status = 0;
        check(waitpid(contender, &contender_status, 0) == contender
            && WIFEXITED(contender_status) && WEXITSTATUS(contender_status) == 0,
            "Another process entered a live private save directory");
        const std::string name = std::string(kDataPath)+"nested/state";
        one.write(name, "OLD");
        check(one.read(name) == "OLD", "Private round trip failure");
        check(!two.exists(std::string(kDataPath)+"state"), "Game save isolation failure");
        error(10, [&] { one.write("../escape", "BAD"); });
        error(10, [&] { one.write(std::string(kDataPath)+"nested/.tq-1-2", "BAD"); });
        error(20, [&] { one.write(name, std::string(kResourceReadLimit + 1, 'X')); });
        check(one.read(name) == "OLD", "Failed oversized write corrupted previous data");
        // Force an actual partial temporary-file write, not just a preflight
        // rejection, without filling the emulator disk or changing app storage.
        struct rlimit prior_limit{}, partial_limit{};
        check(getrlimit(RLIMIT_FSIZE, &prior_limit) == 0, "Cannot inspect file-size limit");
        partial_limit = prior_limit; partial_limit.rlim_cur = 4;
        struct sigaction ignore{}, prior_signal{};
        ignore.sa_handler = SIG_IGN;
        check(sigaction(SIGXFSZ, &ignore, &prior_signal) == 0, "Cannot suppress test-only SIGXFSZ");
        check(setrlimit(RLIMIT_FSIZE, &partial_limit) == 0, "Cannot set test-only partial-write limit");
        error(41, [&] { one.write(name, "PARTIAL-CONTENT"); });
        check(setrlimit(RLIMIT_FSIZE, &prior_limit) == 0, "Cannot restore file-size limit");
        check(sigaction(SIGXFSZ, &prior_signal, nullptr) == 0, "Cannot restore SIGXFSZ");
        check(one.read(name) == "OLD", "Partial write corrupted previous valid save");
        std::filesystem::create_directory(saves/"one/krkr/blocked");
        error(41, [&] { one.write(std::string(kDataPath)+"blocked", "BAD"); });
        check(one.read(name) == "OLD", "Failed rename corrupted previous data");
        check(symlink((saves/"two").c_str(), (saves/"one/krkr/link").c_str()) == 0, "Unable to set up symlink fixture");
        error(41, [&] { one.write(std::string(kDataPath)+"link/escape", "BAD"); });
        check(!std::filesystem::exists(saves/"two/escape"), "Private symlink escape");
        one.write(name, "NEW");
        check(one.read(name) == "NEW", "Atomic replacement failure");
        check(read_source(*open_local_source((saves/"one/ons-legacy.dat").string())) == "ONS", "ONS layout changed");
        for (const auto& item : std::filesystem::recursive_directory_iterator(saves/"one/krkr"))
            check(item.path().filename().string().rfind(".tq-", 0) != 0, "Atomic temporary file leaked");
        auto recovery = saves/"recovery";
        std::filesystem::create_directories(recovery/".krkr-previous");
        std::filesystem::create_directories(recovery/".krkr-restore-probe");
        { std::ofstream previous(recovery/".krkr-previous/kept.tjs"); previous << "GOOD"; }
        { std::ofstream staged(recovery/".krkr-restore-probe/kept.tjs"); staged << "NEW"; }
        { std::ofstream marker(recovery/".krkr-restore.pending"); marker << ".krkr-restore-probe"; }
        PrivateStorage recovered(recovery.string());
        check(recovered.read("tqsave://./kept.tjs") == "GOOD", "Interrupted management restore lost the prior tree");
        check(std::filesystem::is_directory(recovery/"krkr"), "Runtime did not recover the private root");
        std::cout << "M3/M5 storage: " << vectors << " shared XP3 vectors, seeks, precedence, cache access, configuration, atomic writes and restore recovery passed\n";
        return 0;
    } catch (const std::exception& error) { std::cerr << error.what() << '\n'; return 1; }
}
