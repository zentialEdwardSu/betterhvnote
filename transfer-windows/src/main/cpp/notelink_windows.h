#pragma once

#include <stdint.h>

#ifdef NOTELINK_WINDOWS_EXPORTS
#define NL_API extern "C" __declspec(dllexport)
#else
#define NL_API extern "C" __declspec(dllimport)
#endif

enum nl_capability {
    NL_CAP_BLE_PERIPHERAL = 1,
    NL_CAP_WIFI_DIRECT = 2,
    NL_CAP_DPAPI = 4
};

NL_API int32_t nl_initialize();
NL_API int32_t nl_capabilities();
NL_API int32_t nl_ble_start(const uint8_t* identity, int32_t identity_length, const uint8_t* advertisement, int32_t advertisement_length);
NL_API int32_t nl_ble_update(const uint8_t* advertisement, int32_t advertisement_length);
NL_API int32_t nl_ble_poll(uint8_t* output, int32_t capacity, int32_t timeout_ms);
NL_API int32_t nl_ble_respond(const uint8_t* value, int32_t length);
NL_API void nl_ble_stop();
NL_API int32_t nl_wifi_start(
    const char* network_name,
    const char* passphrase,
    char* owner_ip,
    int32_t owner_ip_capacity
);
NL_API void nl_wifi_stop();
NL_API int32_t nl_protect(const uint8_t* input, int32_t input_length, uint8_t* output, int32_t output_capacity);
NL_API int32_t nl_unprotect(const uint8_t* input, int32_t input_length, uint8_t* output, int32_t output_capacity);
NL_API int32_t nl_last_error(char* output, int32_t capacity);
NL_API void nl_shutdown();
