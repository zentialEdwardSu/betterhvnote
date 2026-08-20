#include "notelink_windows.h"

#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <objbase.h>
#include <wincrypt.h>
#include <wlanapi.h>
#include <iphlpapi.h>
#include <winrt/Windows.Devices.Bluetooth.h>
#include <winrt/Windows.Devices.Bluetooth.Advertisement.h>
#include <winrt/Windows.Devices.Bluetooth.GenericAttributeProfile.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.Storage.Streams.h>
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using namespace winrt;
using namespace Windows::Devices::Bluetooth;
using namespace Windows::Devices::Bluetooth::Advertisement;
using namespace Windows::Devices::Bluetooth::GenericAttributeProfile;
using namespace Windows::Storage::Streams;

namespace {
constexpr uint16_t kManufacturerId = 0x0B17;
guid const kServiceUuid{L"b6e4f100-77b7-4d35-9f52-3b4be45c3010"};
guid const kIdentityUuid{L"b6e4f101-77b7-4d35-9f52-3b4be45c3010"};
guid const kCommandUuid{L"b6e4f102-77b7-4d35-9f52-3b4be45c3010"};
guid const kResponseUuid{L"b6e4f103-77b7-4d35-9f52-3b4be45c3010"};

std::mutex g_mutex;
std::condition_variable g_commands_changed;
std::deque<std::vector<uint8_t>> g_commands;
std::string g_last_error;
std::vector<uint8_t> g_identity_payload;
std::vector<uint8_t> g_advertisement_payload;

GattServiceProvider g_service_provider{nullptr};
GattLocalCharacteristic g_identity{nullptr};
GattLocalCharacteristic g_command{nullptr};
GattLocalCharacteristic g_response{nullptr};
BluetoothLEAdvertisementPublisher g_advertisement{nullptr};

void trace(std::string const& message) noexcept {
    auto line = std::string("[NoteLink native] ") + message + "\n";
    std::fwrite(line.data(), 1, line.size(), stderr);
    std::fflush(stderr);
    OutputDebugStringA(line.c_str());
}

struct ApartmentLifetime {
    bool owns_initialization = false;

    ApartmentLifetime() {
        auto result = CoInitializeEx(nullptr, COINIT_MULTITHREADED);
        if (result == RPC_E_CHANGED_MODE) {
            // GUI and scripting hosts may have initialized this thread as STA.
            // WinRT is already available; changing the apartment is neither
            // necessary nor legal.
            trace("using existing COM apartment");
            return;
        }
        check_hresult(result);
        owns_initialization = true;
    }

    ~ApartmentLifetime() {
        if (owns_initialization) CoUninitialize();
    }
};

void ensure_apartment() {
    thread_local ApartmentLifetime apartment;
    static_cast<void>(apartment);
}

std::wstring widen(const char* value) {
    if (value == nullptr || *value == 0) return {};
    int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, nullptr, 0);
    if (length <= 0) throw std::runtime_error("Invalid UTF-8 input");
    std::wstring result(static_cast<size_t>(length), L'\0');
    if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value, -1, result.data(), length) <= 0) {
        throw std::runtime_error("Unable to convert UTF-8 input");
    }
    result.pop_back();
    return result;
}

std::string narrow(hstring const& value) {
    if (value.empty()) return {};
    int length = WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, nullptr, 0, nullptr, nullptr);
    if (length <= 0) throw std::runtime_error("Invalid UTF-16 input");
    std::string result(static_cast<size_t>(length), '\0');
    if (WideCharToMultiByte(CP_UTF8, 0, value.c_str(), -1, result.data(), length, nullptr, nullptr) <= 0) {
        throw std::runtime_error("Unable to convert UTF-16 input");
    }
    result.pop_back();
    return result;
}

IBuffer buffer_from(std::vector<uint8_t> const& value) {
    DataWriter writer;
    writer.WriteBytes(value);
    return writer.DetachBuffer();
}

std::vector<uint8_t> bytes_from(IBuffer const& buffer) {
    DataReader reader = DataReader::FromBuffer(buffer);
    std::vector<uint8_t> result(reader.UnconsumedBufferLength());
    if (!result.empty()) reader.ReadBytes(result);
    return result;
}

std::vector<uint8_t> identity_bytes() {
    std::lock_guard lock(g_mutex);
    return g_identity_payload;
}

std::vector<uint8_t> advertisement_bytes() {
    std::lock_guard lock(g_mutex);
    return g_advertisement_payload;
}

void wait_for_gatt_advertisement_start() {
    for (int attempt = 0; attempt < 40; ++attempt) {
        auto status = g_service_provider.AdvertisementStatus();
        if (status == GattServiceProviderAdvertisementStatus::Started ||
            status == GattServiceProviderAdvertisementStatus::StartedWithoutAllAdvertisementData) {
            trace("GATT advertising status=" + std::to_string(static_cast<int32_t>(status)));
            return;
        }
        if (status == GattServiceProviderAdvertisementStatus::Aborted) {
            // Some desktop adapters report Aborted while the provider remains
            // discoverable and continues serving reads/writes. Keep the GATT
            // objects alive and let the command path prove availability.
            trace("GATT advertising reported aborted; continuing in degraded mode");
            return;
        }
        Sleep(25);
    }
    trace("GATT advertising status did not settle; continuing in degraded mode");
}

void wait_for_advertisement_start() {
    for (int attempt = 0; attempt < 40; ++attempt) {
        auto status = g_advertisement.Status();
        if (status == BluetoothLEAdvertisementPublisherStatus::Started) return;
        if (status == BluetoothLEAdvertisementPublisherStatus::Aborted) {
            throw std::runtime_error("Windows BLE advertisement was aborted");
        }
        Sleep(25);
    }
    throw std::runtime_error("Windows BLE advertisement did not start");
}

void start_optional_summary_advertisement() noexcept {
    try {
        g_advertisement = BluetoothLEAdvertisementPublisher();
        BluetoothLEManufacturerData manufacturer;
        manufacturer.CompanyId(kManufacturerId);
        manufacturer.Data(buffer_from(advertisement_bytes()));
        g_advertisement.Advertisement().ManufacturerData().Append(manufacturer);
        g_advertisement.Start();
        wait_for_advertisement_start();
    } catch (std::exception const& error) {
        // The connectable GATT provider remains usable on adapters that reject
        // a second publisher. Android can read identity and queue counts from it.
        trace(std::string("BLE summary advertisement unavailable: ") + error.what());
        try { if (g_advertisement) g_advertisement.Stop(); } catch (...) {}
        g_advertisement = nullptr;
    } catch (...) {
        trace("BLE summary advertisement unavailable: unknown error");
        try { if (g_advertisement) g_advertisement.Stop(); } catch (...) {}
        g_advertisement = nullptr;
    }
}

void start_gatt_advertising() {
    GattServiceProviderAdvertisingParameters advertising;
    advertising.IsConnectable(true);
    advertising.IsDiscoverable(true);
    g_service_provider.StartAdvertising(advertising);
    wait_for_gatt_advertisement_start();
    // Windows 10 IoT does not support GattServiceProvider service data on all
    // adapters. Keep the queue summary in a scan response while the provider
    // owns the connectable service advertisement.
    start_optional_summary_advertisement();
}

void set_error(std::string value) {
    std::lock_guard lock(g_mutex);
    g_last_error = std::move(value);
}

template <typename Function>
int guarded(Function&& function) noexcept {
    try {
        function();
        set_error({});
        return 0;
    } catch (hresult_error const& error) {
        set_error(narrow(error.message()));
    } catch (std::exception const& error) {
        set_error(error.what());
    } catch (...) {
        set_error("Unknown Windows native error");
    }
    return -1;
}

GattLocalCharacteristic create_characteristic(
    guid const& uuid,
    GattCharacteristicProperties properties,
    GattProtectionLevel read_level,
    GattProtectionLevel write_level
) {
    GattLocalCharacteristicParameters parameters;
    parameters.CharacteristicProperties(properties);
    // Windows validates protection settings against the characteristic's
    // operations. Setting a write level on a read-only characteristic (or
    // read/write levels on a notify-only characteristic) returns E_INVALIDARG.
    const auto raw_properties = static_cast<uint32_t>(properties);
    const auto read_bit = static_cast<uint32_t>(GattCharacteristicProperties::Read);
    const auto write_bits = static_cast<uint32_t>(
        GattCharacteristicProperties::Write | GattCharacteristicProperties::WriteWithoutResponse
    );
    if ((raw_properties & read_bit) != 0) parameters.ReadProtectionLevel(read_level);
    if ((raw_properties & write_bits) != 0) parameters.WriteProtectionLevel(write_level);
    auto result = g_service_provider.Service().CreateCharacteristicAsync(uuid, parameters).get();
    if (result.Error() != BluetoothError::Success) throw std::runtime_error("Unable to create BLE characteristic");
    return result.Characteristic();
}

int copy_blob(DATA_BLOB const& blob, uint8_t* output, int capacity) {
    if (output == nullptr || capacity < static_cast<int>(blob.cbData)) {
        return blob.cbData == 0 ? 0 : -static_cast<int>(blob.cbData);
    }
    std::memcpy(output, blob.pbData, blob.cbData);
    return static_cast<int>(blob.cbData);
}
}

int32_t nl_initialize() {
    return guarded([] {
        ensure_apartment();
        trace("initialized");
    });
}

int32_t nl_capabilities() {
    int result = NL_CAP_DPAPI;
    try {
        ensure_apartment();
        auto adapter = BluetoothAdapter::GetDefaultAsync().get();
        if (adapter && adapter.IsPeripheralRoleSupported()) result |= NL_CAP_BLE_PERIPHERAL;
    } catch (...) {
    }
    char address[64]{};
    uint8_t ssid[DOT11_SSID_MAX_LENGTH]{};
    if (nl_lan_info(address, static_cast<int32_t>(sizeof(address)), ssid, static_cast<int32_t>(sizeof(ssid))) >= 0) {
        result |= NL_CAP_LAN;
    }
    return result;
}

int32_t nl_ble_start(const uint8_t* identity, int32_t identity_length, const uint8_t* advertisement, int32_t advertisement_length) {
    return guarded([&] {
        ensure_apartment();
        trace("BLE start requested");
        nl_ble_stop();
        {
            std::lock_guard lock(g_mutex);
            if (identity == nullptr || identity_length <= 0 || advertisement == nullptr || advertisement_length <= 0) {
                throw std::runtime_error("Invalid encoded BLE payload");
            }
            g_identity_payload.assign(identity, identity + identity_length);
            g_advertisement_payload.assign(advertisement, advertisement + advertisement_length);
            g_commands.clear();
        }
        auto created = GattServiceProvider::CreateAsync(kServiceUuid).get();
        if (created.Error() != BluetoothError::Success) throw std::runtime_error("Unable to create NoteLink GATT service");
        g_service_provider = created.ServiceProvider();
        g_identity = create_characteristic(
            kIdentityUuid, GattCharacteristicProperties::Read,
            GattProtectionLevel::Plain, GattProtectionLevel::Plain
        );
        g_command = create_characteristic(
            kCommandUuid, GattCharacteristicProperties::Write,
            GattProtectionLevel::Plain, GattProtectionLevel::Plain
        );
        g_response = create_characteristic(
            kResponseUuid, GattCharacteristicProperties::Notify | GattCharacteristicProperties::Indicate,
            GattProtectionLevel::Plain, GattProtectionLevel::Plain
        );

        g_identity.ReadRequested([](GattLocalCharacteristic const&, GattReadRequestedEventArgs const& args) {
            auto deferral = args.GetDeferral();
            try {
                auto request = args.GetRequestAsync().get();
                if (request) {
                    auto value = identity_bytes();
                    request.RespondWithValue(buffer_from(value));
                    trace("BLE identity read bytes=" + std::to_string(value.size()));
                }
            } catch (std::exception const& error) {
                trace(std::string("BLE identity read failed: ") + error.what());
            } catch (...) {
                trace("BLE identity read failed: unknown error");
            }
            deferral.Complete();
        });
        g_command.WriteRequested([](GattLocalCharacteristic const&, GattWriteRequestedEventArgs const& args) {
            auto deferral = args.GetDeferral();
            try {
                auto request = args.GetRequestAsync().get();
                if (request) {
                    auto value = bytes_from(request.Value());
                    {
                        std::lock_guard lock(g_mutex);
                        g_commands.push_back(std::move(value));
                    }
                    trace("BLE command queued");
                    g_commands_changed.notify_one();
                    if (request.Option() == GattWriteOption::WriteWithResponse) request.Respond();
                }
            } catch (std::exception const& error) {
                trace(std::string("BLE command write failed: ") + error.what());
            } catch (...) {
                trace("BLE command write failed: unknown error");
            }
            deferral.Complete();
        });
        g_response.SubscribedClientsChanged([](GattLocalCharacteristic const& characteristic, auto const&) {
            trace("BLE response subscribers=" + std::to_string(characteristic.SubscribedClients().Size()));
        });

        start_gatt_advertising();
        trace("BLE advertising started");
    });
}

int32_t nl_ble_update(
    const uint8_t* identity,
    int32_t identity_length,
    const uint8_t* advertisement,
    int32_t advertisement_length
) {
    return guarded([&] {
        {
            std::lock_guard lock(g_mutex);
            if (identity == nullptr || identity_length <= 0 || advertisement == nullptr || advertisement_length <= 0) {
                throw std::runtime_error("Invalid encoded BLE payload");
            }
            g_identity_payload.assign(identity, identity + identity_length);
            g_advertisement_payload.assign(advertisement, advertisement + advertisement_length);
        }
        // Identity reads use the shared payload above, so the connectable GATT
        // service does not need to be rebuilt when queue counts change.
        if (g_advertisement) {
            g_advertisement.Stop();
            g_advertisement = nullptr;
        }
        start_optional_summary_advertisement();
    });
}

int32_t nl_ble_poll(uint8_t* output, int32_t capacity, int32_t timeout_ms) {
    std::unique_lock lock(g_mutex);
    g_commands_changed.wait_for(lock, std::chrono::milliseconds(std::max(0, timeout_ms)), [] { return !g_commands.empty(); });
    if (g_commands.empty()) return 0;
    auto value = std::move(g_commands.front());
    g_commands.pop_front();
    if (capacity < static_cast<int32_t>(value.size()) || output == nullptr) return -static_cast<int32_t>(value.size());
    std::memcpy(output, value.data(), value.size());
    return static_cast<int32_t>(value.size());
}

int32_t nl_ble_respond(const uint8_t* value, int32_t length) {
    return guarded([&] {
        ensure_apartment();
        if (!g_response) throw std::runtime_error("BLE service is not running");
        if (length < 0 || (length > 0 && value == nullptr)) throw std::runtime_error("Invalid BLE response");
        std::vector<uint8_t> bytes(value, value + length);
        auto clients = g_response.NotifyValueAsync(buffer_from(bytes)).get();
        if (clients.Size() == 0) throw std::runtime_error("No BLE client is subscribed to responses");
        trace("BLE response notified bytes=" + std::to_string(bytes.size()) +
              " clients=" + std::to_string(clients.Size()));
    });
}

void nl_ble_stop() {
    try {
        if (g_service_provider) g_service_provider.StopAdvertising();
        if (g_advertisement) g_advertisement.Stop();
    } catch (...) {
    }
    g_response = nullptr;
    g_command = nullptr;
    g_identity = nullptr;
    g_service_provider = nullptr;
    {
        std::lock_guard lock(g_mutex);
        g_commands.clear();
        g_identity_payload.clear();
        g_advertisement_payload.clear();
    }
    g_commands_changed.notify_all();
    trace("BLE stopped");
}

int32_t nl_lan_info(char* ipv4, int32_t ipv4_capacity, uint8_t* ssid, int32_t ssid_capacity) {
    if (ipv4 == nullptr || ipv4_capacity < INET_ADDRSTRLEN || ssid == nullptr || ssid_capacity < DOT11_SSID_MAX_LENGTH) {
        set_error("LAN info output buffer is too small");
        return -1;
    }
    HANDLE client = nullptr;
    DWORD negotiated = 0;
    PWLAN_INTERFACE_INFO_LIST interfaces = nullptr;
    PVOID connection_data = nullptr;
    DWORD connection_size = 0;
    WLAN_OPCODE_VALUE_TYPE opcode = wlan_opcode_value_type_invalid;
    ULONG adapter_size = 16 * 1024;
    std::vector<uint8_t> adapter_buffer(adapter_size);
    try {
        if (WlanOpenHandle(2, nullptr, &negotiated, &client) != ERROR_SUCCESS) {
            throw std::runtime_error("Unable to open Windows WLAN service");
        }
        if (WlanEnumInterfaces(client, nullptr, &interfaces) != ERROR_SUCCESS || interfaces->dwNumberOfItems == 0) {
            throw std::runtime_error("No WLAN interface is available");
        }
        WLAN_CONNECTION_ATTRIBUTES* connection = nullptr;
        for (DWORD index = 0; index < interfaces->dwNumberOfItems; ++index) {
            auto& item = interfaces->InterfaceInfo[index];
            if (item.isState != wlan_interface_state_connected) continue;
            if (WlanQueryInterface(client, &item.InterfaceGuid, wlan_intf_opcode_current_connection, nullptr,
                    &connection_size, &connection_data, &opcode) == ERROR_SUCCESS) {
                connection = static_cast<WLAN_CONNECTION_ATTRIBUTES*>(connection_data);
                break;
            }
        }
        if (connection == nullptr || connection->wlanAssociationAttributes.dot11Ssid.uSSIDLength == 0) {
            throw std::runtime_error("The active WLAN SSID is unavailable");
        }
        auto ssid_length = static_cast<int32_t>(connection->wlanAssociationAttributes.dot11Ssid.uSSIDLength);
        if (ssid_length > ssid_capacity) throw std::runtime_error("SSID output buffer is too small");
        std::memcpy(ssid, connection->wlanAssociationAttributes.dot11Ssid.ucSSID, ssid_length);

        auto adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(adapter_buffer.data());
        DWORD adapter_result = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
            GAA_FLAG_SKIP_DNS_SERVER, nullptr, adapters, &adapter_size);
        if (adapter_result == ERROR_BUFFER_OVERFLOW) {
            adapter_buffer.resize(adapter_size);
            adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(adapter_buffer.data());
            adapter_result = GetAdaptersAddresses(AF_INET, GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST |
                GAA_FLAG_SKIP_DNS_SERVER, nullptr, adapters, &adapter_size);
        }
        if (adapter_result != NO_ERROR) throw std::runtime_error("Unable to enumerate LAN addresses");
        bool found = false;
        for (auto adapter = adapters; adapter != nullptr && !found; adapter = adapter->Next) {
            if (adapter->OperStatus != IfOperStatusUp || adapter->IfType != IF_TYPE_IEEE80211) continue;
            for (auto address = adapter->FirstUnicastAddress; address != nullptr; address = address->Next) {
                if (address->Address.lpSockaddr->sa_family != AF_INET) continue;
                auto value = reinterpret_cast<sockaddr_in*>(address->Address.lpSockaddr);
                found = InetNtopA(AF_INET, &value->sin_addr, ipv4, ipv4_capacity) != nullptr;
                if (found) break;
            }
        }
        if (!found) throw std::runtime_error("The active WLAN IPv4 address is unavailable");
        if (connection_data) WlanFreeMemory(connection_data);
        if (interfaces) WlanFreeMemory(interfaces);
        if (client) WlanCloseHandle(client, nullptr);
        return ssid_length;
    } catch (std::exception const& error) {
        if (connection_data) WlanFreeMemory(connection_data);
        if (interfaces) WlanFreeMemory(interfaces);
        if (client) WlanCloseHandle(client, nullptr);
        set_error(error.what());
        return -1;
    }
}

int32_t nl_protect(const uint8_t* input, int32_t input_length, uint8_t* output, int32_t output_capacity) {
    if (input_length < 0 || (input_length > 0 && input == nullptr)) {
        set_error("Invalid DPAPI input");
        return -1;
    }
    DATA_BLOB source{static_cast<DWORD>(input_length), const_cast<BYTE*>(input)};
    DATA_BLOB encrypted{};
    if (!CryptProtectData(&source, L"NoteLink pairing key", nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &encrypted)) {
        set_error("CryptProtectData failed");
        return -1;
    }
    int result = copy_blob(encrypted, output, output_capacity);
    LocalFree(encrypted.pbData);
    return result;
}

int32_t nl_unprotect(const uint8_t* input, int32_t input_length, uint8_t* output, int32_t output_capacity) {
    if (input_length < 0 || (input_length > 0 && input == nullptr)) {
        set_error("Invalid DPAPI input");
        return -1;
    }
    DATA_BLOB source{static_cast<DWORD>(input_length), const_cast<BYTE*>(input)};
    DATA_BLOB decrypted{};
    if (!CryptUnprotectData(&source, nullptr, nullptr, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &decrypted)) {
        set_error("CryptUnprotectData failed");
        return -1;
    }
    int result = copy_blob(decrypted, output, output_capacity);
    LocalFree(decrypted.pbData);
    return result;
}

int32_t nl_last_error(char* output, int32_t capacity) {
    std::lock_guard lock(g_mutex);
    if (output == nullptr || capacity <= static_cast<int32_t>(g_last_error.size())) return -static_cast<int32_t>(g_last_error.size() + 1);
    std::memcpy(output, g_last_error.c_str(), g_last_error.size() + 1);
    return static_cast<int32_t>(g_last_error.size());
}

void nl_shutdown() {
    nl_ble_stop();
}
