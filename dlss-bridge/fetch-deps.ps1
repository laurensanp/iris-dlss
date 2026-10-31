# Downloads the third-party pieces the bridge builds against (not in the repository):
#   third_party/ngx            NVIDIA DLSS SDK (headers, nvsdk_ngx_d.lib, nvngx_dlss.dll, nvngx_dlssg.dll)
#   third_party/vulkan-headers Khronos Vulkan headers
# Tested with DLSS SDK 310.x. Usage: powershell -ExecutionPolicy Bypass -File fetch-deps.ps1
$ErrorActionPreference = "Stop"
$root = Join-Path $PSScriptRoot "third_party"
$tmp = Join-Path $env:TEMP "siris-deps"
if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
New-Item -ItemType Directory -Force $tmp, $root | Out-Null

git clone --depth 1 https://github.com/NVIDIA/DLSS.git (Join-Path $tmp "DLSS")
git clone --depth 1 https://github.com/KhronosGroup/Vulkan-Headers.git (Join-Path $tmp "Vulkan-Headers")

$ngx = Join-Path $root "ngx"
New-Item -ItemType Directory -Force (Join-Path $ngx "lib"), (Join-Path $ngx "bin") | Out-Null
Copy-Item -Recurse -Force (Join-Path $tmp "DLSS\include") $ngx
Copy-Item -Force (Join-Path $tmp "DLSS\LICENSE.txt") $ngx
function Find-One($name) {
    # x64 only (the SDK also ships aarch64 / arm64ec builds); release DLLs, not the dev ones.
    $file = Get-ChildItem -Recurse (Join-Path $tmp "DLSS\lib\Windows_x86_64") -Filter $name |
        Where-Object { $_.FullName -notmatch "\\dev\\|\\vs20\d\d\\|\\khr\\|\\uwp\\" } | Select-Object -First 1
    if (-not $file) { throw "$name not found in the DLSS SDK" }
    return $file.FullName
}
Copy-Item -Force (Find-One "nvsdk_ngx_d.lib") (Join-Path $ngx "lib")
Copy-Item -Force (Find-One "nvngx_dlss.dll") (Join-Path $ngx "bin")
Copy-Item -Force (Find-One "nvngx_dlssg.dll") (Join-Path $ngx "bin")

$vk = Join-Path $root "vulkan-headers"
New-Item -ItemType Directory -Force $vk | Out-Null
Copy-Item -Recurse -Force (Join-Path $tmp "Vulkan-Headers\include") $vk

Remove-Item -Recurse -Force $tmp
Write-Host "third_party ready"
