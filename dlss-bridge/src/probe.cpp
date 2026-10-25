// Stage-1 check outside the game: can this machine run DLSS through the bridge?
#include <cstdio>
#include <string>
#include <windows.h>

#include "bridge.h"

// Shows a window for a moment and pushes frames through the presenter, with frame generation.
static void presentTest() {
    WNDCLASSW wc{};
    wc.lpfnWndProc = DefWindowProcW;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.lpszClassName = L"IrisDlssProbe";
    RegisterClassW(&wc);
    HWND parent = CreateWindowW(L"IrisDlssProbe", L"Iris DLSS present test", WS_OVERLAPPEDWINDOW | WS_VISIBLE,
        100, 100, 1920 / 2, 1080 / 2, nullptr, nullptr, wc.hInstance, nullptr);
    RECT rect;
    GetClientRect(parent, &rect);
    // Frame generation needs the hudless size to match: recreate the DLSS resources at the client size.
    uint32_t w = rect.right - rect.left, h = rect.bottom - rect.top;
    IrisDlssResources resources{};
    irisdlss_create_resources(w * 2 / 3, h * 2 / 3, w, h, 2, 1, 11, &resources);
    IrisDlssPresentResources present{};
    int32_t status = irisdlss_presenter_create(reinterpret_cast<uint64_t>(parent), w, h, 0, 0, &present);
    std::printf("presenter_create=%d %s\n", status, irisdlss_last_message());
    if (status == IRISDLSS_OK) {
        float params[IRISDLSS_FG_PARAM_COUNT] = {};
        for (int i = 0; i < 4; i++) {
            params[IRISDLSS_FG_VIEW_TO_CLIP + i * 5] = 1.0f;
            params[IRISDLSS_FG_CLIP_TO_VIEW + i * 5] = 1.0f;
            params[IRISDLSS_FG_CLIP_TO_PREV_CLIP + i * 5] = 1.0f;
            params[IRISDLSS_FG_PREV_CLIP_TO_CLIP + i * 5] = 1.0f;
        }
        params[IRISDLSS_FG_MVEC_SCALE] = 1.0f / (w * 2 / 3);
        params[IRISDLSS_FG_MVEC_SCALE + 1] = 1.0f / (h * 2 / 3);
        params[IRISDLSS_FG_CAMERA_UP + 1] = 1.0f;
        params[IRISDLSS_FG_CAMERA_RIGHT] = 1.0f;
        params[IRISDLSS_FG_CAMERA_FWD + 2] = -1.0f;
        params[IRISDLSS_FG_NEAR] = 0.05f;
        params[IRISDLSS_FG_FAR] = 1000.0f;
        params[IRISDLSS_FG_FOV] = 1.2f;
        params[IRISDLSS_FG_ASPECT] = static_cast<float>(w) / h;
        int failures = 0;
        for (int frame = 0; frame < 240; frame++) {
            MSG msg;
            while (PeekMessageW(&msg, nullptr, 0, 0, PM_REMOVE)) {
                DispatchMessageW(&msg);
            }
            params[IRISDLSS_FG_RESET] = frame == 0 ? 1.0f : 0.0f;
            if (irisdlss_present(params) != IRISDLSS_OK && failures++ < 3) {
                std::printf("  present frame %d: %s\n", frame, irisdlss_last_message());
            }
            Sleep(8);
        }
        float stats[3];
        irisdlss_presenter_stats(stats);
        std::printf("presented fps=%.1f frameGeneration=%d threadError=%d %s\n", stats[0], int(stats[1]),
            int(stats[2]), irisdlss_last_message());
        irisdlss_presenter_destroy();
        CloseHandle(reinterpret_cast<HANDLE>(present.frame.memoryHandle));
    }
    DestroyWindow(parent);
}

int wmain() {
    wchar_t exePath[MAX_PATH];
    GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    std::wstring dir(exePath);
    dir = dir.substr(0, dir.find_last_of(L"\\/"));

    int32_t status = irisdlss_init(nullptr, dir.c_str());
    std::printf("status=%d %s\n", status, irisdlss_last_message());
    if (status == IRISDLSS_OK) {
        const char* names[] = {"Performance", "Balanced", "Quality", "UltraPerformance", "UltraQuality", "DLAA"};
        for (int quality = 0; quality <= 5; quality++) {
            uint32_t w = 0, h = 0;
            float sharpness = 0;
            if (irisdlss_optimal_settings(1920, 1080, quality, &w, &h, &sharpness) == 0) {
                std::printf("  %-16s 1920x1080 <- %ux%u\n", names[quality], w, h);
            }
        }
        IrisDlssResources resources{};
        int32_t created = irisdlss_create_resources(1280, 720, 1920, 1080, 2, 1, 11, &resources);
        std::printf("create_resources=%d %s\n", created, irisdlss_last_message());
        for (int i = 0; created == IRISDLSS_OK && i < IRISDLSS_IMAGE_COUNT; i++) {
            std::printf("  image %d: %ux%u, %llu bytes, handle %llx\n", i, resources.images[i].width,
                resources.images[i].height, resources.images[i].memorySize, resources.images[i].memoryHandle);
            CloseHandle(reinterpret_cast<HANDLE>(resources.images[i].memoryHandle));
        }
        if (created == IRISDLSS_OK) {
            CloseHandle(reinterpret_cast<HANDLE>(resources.glDoneSemaphore));
            CloseHandle(reinterpret_cast<HANDLE>(resources.vkDoneSemaphore));
        }
        int32_t fg = irisdlss_probe_frame_generation(1920, 1080);
        std::printf("frame_generation=%d %s\n", fg, irisdlss_last_message());
        if (created == IRISDLSS_OK) {
            presentTest();
        }
    }
    irisdlss_shutdown();
    return status;
}
