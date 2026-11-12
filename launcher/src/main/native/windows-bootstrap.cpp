#define UNICODE
#define _UNICODE
#include <windows.h>
#include <string>
#include <vector>

static std::wstring environment(const wchar_t* name) {
    DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (!size) return L"";
    std::vector<wchar_t> buffer(size);
    GetEnvironmentVariableW(name, buffer.data(), size);
    return buffer.data();
}

static bool fileExists(const std::wstring& path) {
    DWORD attributes = GetFileAttributesW(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && !(attributes & FILE_ATTRIBUTE_DIRECTORY);
}

// Runs before loading the JVM, so inherited Java installations cannot supply AWT DLLs.
int WINAPI wWinMain(HINSTANCE, HINSTANCE, PWSTR arguments, int) {
    std::vector<wchar_t> path(32768);
    DWORD length = GetModuleFileNameW(nullptr, path.data(), static_cast<DWORD>(path.size()));
    if (!length || length >= path.size()) return 1;
    std::wstring executable(path.data(), length);
    std::wstring root = executable.substr(0, executable.find_last_of(L"\\/"));
    std::wstring stem = executable.substr(0, executable.size() - 4);
    std::wstring host = stem + L"-host.exe";
    std::wstring runtime = root + L"\\runtime\\bin";
    std::wstring systemRoot = environment(L"SystemRoot");
    if (systemRoot.empty()) systemRoot = L"C:\\Windows";
    std::wstring safePath = runtime + L";" + systemRoot + L"\\System32;" + systemRoot;
    std::wstring inherited = environment(L"PATH");
    size_t start = 0;
    while (start < inherited.size()) {
        size_t end = inherited.find(L';', start);
        std::wstring entry = inherited.substr(start, end == std::wstring::npos ? end : end - start);
        if (!entry.empty() && !fileExists(entry + L"\\java.exe") &&
                !fileExists(entry + L"\\javaw.exe") && !fileExists(entry + L"\\glass.dll")) {
            safePath += L";" + entry;
        }
        if (end == std::wstring::npos) break;
        start = end + 1;
    }
    SetEnvironmentVariableW(L"PATH", safePath.c_str());
    SetEnvironmentVariableW(L"JAVA_HOME", (root + L"\\runtime").c_str());
    for (auto name : {L"JAVAFX_HOME", L"JAVA_TOOL_OPTIONS", L"_JAVA_OPTIONS", L"JDK_JAVA_OPTIONS"}) {
        SetEnvironmentVariableW(name, nullptr);
    }
    SetEnvironmentVariableW(L"JLSHELL_RUNTIME_ISOLATED", L"true");
    std::wstring command = L"\"" + host + L"\" " + arguments;
    STARTUPINFOW startup = {};
    startup.cb = sizeof(startup);
    PROCESS_INFORMATION process = {};
    if (!CreateProcessW(host.c_str(), command.data(), nullptr, nullptr, FALSE, 0,
            nullptr, root.c_str(), &startup, &process)) {
        MessageBoxW(nullptr, L"Unable to start JLShell. Please check the installation files.",
                L"JLShell", MB_OK | MB_ICONERROR);
        return 1;
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return 0;
}
