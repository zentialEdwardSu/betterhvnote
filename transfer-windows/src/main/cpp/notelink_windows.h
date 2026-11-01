#pragma once

#include <stdint.h>

#ifdef NOTELINK_WINDOWS_EXPORTS
#define NL_API extern "C" __declspec(dllexport)
#else
#define NL_API extern "C" __declspec(dllimport)
#endif

enum nl_capability {
    NL_CAP_BLE_PERIPHERAL = 1,
    NL_CAP_LAN = 2,
    NL_CAP_DPAPI = 4
};

NL_API int32_t nl_initialize();
NL_API int32_t nl_capabilities();
NL_API int32_t nl_ble_start(const uint8_t* advertisement, int32_t advertisement_length);
NL_API int32_t nl_ble_update(const uint8_t* advertisement, int32_t advertisement_length);
enum nl_ble_event_type {
    NL_BLE_EVENT_RECORD = 1,
    NL_BLE_EVENT_CONNECTED = 2,
    NL_BLE_EVENT_DISCONNECTED = 3,
    NL_BLE_EVENT_MTU = 4
};

/*
 * Waits up to timeout_ms for the next control event. Returns 1 when an event was written,
 * 0 on timeout, -1 on error and -(needed) when payload_capacity is too small (the event stays
 * queued). client receives the GATT session device id (UTF-8, NUL-terminated). For
 * NL_BLE_EVENT_MTU the payload is the ATT MTU as a little-endian u16.
 */
NL_API int32_t nl_ble_poll_event(int32_t* type, char* client, int32_t client_capacity,
    uint8_t* payload, int32_t payload_capacity, int32_t* payload_length, int32_t timeout_ms);
/* Notifies one subscribed client; blocks until Windows reports the notification result. */
NL_API int32_t nl_ble_notify(const char* client, const uint8_t* value, int32_t length);
NL_API void nl_ble_stop();
NL_API int32_t nl_lan_info(char* ipv4, int32_t ipv4_capacity, uint8_t* ssid, int32_t ssid_capacity);
NL_API int32_t nl_protect(const uint8_t* input, int32_t input_length, uint8_t* output, int32_t output_capacity);
NL_API int32_t nl_unprotect(const uint8_t* input, int32_t input_length, uint8_t* output, int32_t output_capacity);
NL_API int32_t nl_last_error(char* output, int32_t capacity);
NL_API void nl_shutdown();
