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
#include <map>
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
guid const kControlUuid{L"b6e4f110-77b7-4d35-9f52-3b4be45c3010"};

struct ControlEvent {
    int32_t type;
    std::string client;
    std::vector<uint8_t> payload;
};

struct Subscriber {
    GattSubscribedClient client{nullptr};
    GattSession session{nullptr};
    event_token mtu_token{};
};

// g_mutex guards every global below; WinRT objects are copied out under the lock and used
// outside it, so a blocking WinRT call never holds the lock.
std::mutex g_mutex;
std::condition_variable g_events_changed;
std::deque<ControlEvent> g_events;
std::map<std::string, Subscriber> g_subscribers;
std::string g_last_error;
std::vector<uint8_t> g_advertisement_payload;

GattServiceProvider g_service_provider{nullptr};
GattLocalCharacteristic g_control{nullptr};
BluetoothLEAdvertisementPublisher g_advertisement{nullptr};

constexpr size_t kMaxQueuedEvents = 4096;

void push_event(int32_t type, std::string client, std::vector<uint8_t> payload = {}) {
    {
        std::lock_guard lock(g_mutex);
        if (g_events.size() >= kMaxQueuedEvents) g_events.pop_front();
        g_events.push_back(ControlEvent{type, std::move(client), std::move(payload)});
    }
    g_events_changed.notify_one();
}

std::vector<uint8_t> mtu_payload(uint16_t mtu) {
    return {static_cast<uint8_t>(mtu & 0xff), static_cast<uint8_t>(mtu >> 8)};
}

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
    // Windows 10 2004+ can carry service data in the connectable advertisement, so the Note
    // finds the identity hash on the same radio address it must connect to.
    try {
        advertising.ServiceData(buffer_from(advertisement_bytes()));
    } catch (...) {
        trace("GATT service data unsupported; identity only in the summary advertisement");
    }
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

int32_t nl_ble_start(const uint8_t* advertisement, int32_t advertisement_length) {
    return guarded([&] {
        ensure_apartment();
        trace("BLE start requested");
        nl_ble_stop();
        {
            std::lock_guard lock(g_mutex);
            if (advertisement == nullptr || advertisement_length <= 0) {
                throw std::runtime_error("Invalid encoded BLE advertisement");
            }
            g_advertisement_payload.assign(advertisement, advertisement + advertisement_length);
        }
        auto created = GattServiceProvider::CreateAsync(kServiceUuid).get();
        if (created.Error() != BluetoothError::Success) throw std::runtime_error("Unable to create NoteLink GATT service");
        g_service_provider = created.ServiceProvider();
        g_control = create_characteristic(
            kControlUuid,
            GattCharacteristicProperties::WriteWithoutResponse | GattCharacteristicProperties::Notify,
            GattProtectionLevel::Plain, GattProtectionLevel::Plain
        );
        g_control.WriteRequested([](GattLocalCharacteristic const&, GattWriteRequestedEventArgs const& args) {
            auto deferral = args.GetDeferral();
            try {
                auto client = narrow(args.Session().DeviceId().Id());
                auto request = args.GetRequestAsync().get();
                if (request) {
                    push_event(NL_BLE_EVENT_RECORD, client, bytes_from(request.Value()));
                    if (request.Option() == GattWriteOption::WriteWithResponse) request.Respond();
                }
            } catch (std::exception const& error) {
                trace(std::string("BLE control write failed: ") + error.what());
            } catch (...) {
                trace("BLE control write failed: unknown error");
            }
            deferral.Complete();
        });

        g_control.SubscribedClientsChanged([](GattLocalCharacteristic const& sender, auto const&) {
            try {
                std::map<std::string, GattSubscribedClient> current;
                for (auto const& client : sender.SubscribedClients()) {
                    current.emplace(narrow(client.Session().DeviceId().Id()), client);
                }
                std::vector<std::pair<std::string, uint16_t>> added;
                std::vector<std::string> removed;
                {
                    std::lock_guard lock(g_mutex);
                    for (auto it = g_subscribers.begin(); it != g_subscribers.end();) {
                        if (current.count(it->first) == 0) {
                            try { it->second.session.MaxPduSizeChanged(it->second.mtu_token); } catch (...) {}
                            removed.push_back(it->first);
                            it = g_subscribers.erase(it);
                        } else {
                            ++it;
                        }
                    }
                    for (auto& [id, client] : current) {
                        if (g_subscribers.count(id) != 0) continue;
                        Subscriber subscriber;
                        subscriber.client = client;
                        subscriber.session = client.Session();
                        auto key = id;
                        subscriber.mtu_token = subscriber.session.MaxPduSizeChanged(
                            [key](GattSession const& session, auto const&) {
                                push_event(NL_BLE_EVENT_MTU, key, mtu_payload(session.MaxPduSize()));
                            });
                        added.emplace_back(id, subscriber.session.MaxPduSize());
                        g_subscribers.emplace(id, std::move(subscriber));
                    }
                }
                for (auto& id : removed) {
                    trace("BLE client unsubscribed " + id);
                    push_event(NL_BLE_EVENT_DISCONNECTED, id);
                }
                for (auto& [id, mtu] : added) {
                    trace("BLE client subscribed " + id + " mtu=" + std::to_string(mtu));
                    push_event(NL_BLE_EVENT_CONNECTED, id);
                    push_event(NL_BLE_EVENT_MTU, id, mtu_payload(mtu));
                }
            } catch (...) {
                trace("BLE subscriber tracking failed");
            }
        });

        start_gatt_advertising();
        trace("BLE advertising started");
    });
}

int32_t nl_ble_update(const uint8_t* advertisement, int32_t advertisement_length) {
    return guarded([&] {
        {
            std::lock_guard lock(g_mutex);
            if (advertisement == nullptr || advertisement_length <= 0) {
                throw std::runtime_error("Invalid encoded BLE advertisement");
            }
            g_advertisement_payload.assign(advertisement, advertisement + advertisement_length);
        }
        if (g_advertisement) {
            g_advertisement.Stop();
            g_advertisement = nullptr;
        }
        // Service data is fixed while the provider advertises, so it is restarted with the new
        // payload. Existing GATT connections are not affected.
        if (g_service_provider) {
            try { g_service_provider.StopAdvertising(); } catch (...) {}
            start_gatt_advertising();
        } else {
            start_optional_summary_advertisement();
        }
    });
}

int32_t nl_ble_poll_event(int32_t* type, char* client, int32_t client_capacity,
    uint8_t* payload, int32_t payload_capacity, int32_t* payload_length, int32_t timeout_ms) {
    if (type == nullptr || client == nullptr || client_capacity <= 0 || payload_length == nullptr) {
        set_error("Invalid BLE event output");
        return -1;
    }
    std::unique_lock lock(g_mutex);
    g_events_changed.wait_for(
        lock,
        std::chrono::milliseconds(std::max(0, timeout_ms)),
        [] { return !g_events.empty(); }
    );
    if (g_events.empty()) return 0;
    auto& event = g_events.front();
    auto needed = static_cast<int32_t>(event.payload.size());
    if (needed > payload_capacity || (needed > 0 && payload == nullptr) ||
        static_cast<int32_t>(event.client.size()) >= client_capacity) {
        return -std::max<int32_t>(1, needed);
    }
    *type = event.type;
    std::memcpy(client, event.client.c_str(), event.client.size() + 1);
    if (needed > 0) std::memcpy(payload, event.payload.data(), event.payload.size());
    *payload_length = needed;
    g_events.pop_front();
    return 1;
}

int32_t nl_ble_notify(const char* client, const uint8_t* value, int32_t length) {
    return guarded([&] {
        ensure_apartment();
        if (client == nullptr) throw std::runtime_error("Missing BLE client id");
        if (length < 0 || (length > 0 && value == nullptr)) throw std::runtime_error("Invalid control record");
        GattLocalCharacteristic control{nullptr};
        GattSubscribedClient target{nullptr};
        {
            std::lock_guard lock(g_mutex);
            control = g_control;
            auto found = g_subscribers.find(client);
            if (found != g_subscribers.end()) target = found->second.client;
        }
        if (!control) throw std::runtime_error("BLE control characteristic is not running");
        if (!target) throw std::runtime_error("BLE client is not subscribed");
        std::vector<uint8_t> bytes(value, value + length);
        auto result = control.NotifyValueAsync(buffer_from(bytes), target).get();
        if (result.Status() != GattCommunicationStatus::Success) {
            throw std::runtime_error("BLE notification failed status=" +
                std::to_string(static_cast<int32_t>(result.Status())));
        }
    });
}

void nl_ble_stop() {
    GattServiceProvider provider{nullptr};
    BluetoothLEAdvertisementPublisher publisher{nullptr};
    {
        std::lock_guard lock(g_mutex);
        provider = g_service_provider;
        publisher = g_advertisement;
        g_service_provider = nullptr;
        g_advertisement = nullptr;
        g_control = nullptr;
        for (auto& [id, subscriber] : g_subscribers) {
            try { subscriber.session.MaxPduSizeChanged(subscriber.mtu_token); } catch (...) {}
        }
        g_subscribers.clear();
        g_events.clear();
        g_advertisement_payload.clear();
    }
    try {
        if (provider) provider.StopAdvertising();
        if (publisher) publisher.Stop();
    } catch (...) {
    }
    g_events_changed.notify_all();
    trace("BLE stopped");
}

namespace {
std::string connected_ssid() {
    HANDLE client = nullptr;
    DWORD negotiated = 0;
    PWLAN_INTERFACE_INFO_LIST interfaces = nullptr;
    std::string result;
    if (WlanOpenHandle(2, nullptr, &negotiated, &client) != ERROR_SUCCESS) return result;
    if (WlanEnumInterfaces(client, nullptr, &interfaces) == ERROR_SUCCESS) {
        for (DWORD index = 0; index < interfaces->dwNumberOfItems && result.empty(); ++index) {
            auto& item = interfaces->InterfaceInfo[index];
            if (item.isState != wlan_interface_state_connected) continue;
            PVOID data = nullptr;
            DWORD size = 0;
            WLAN_OPCODE_VALUE_TYPE opcode = wlan_opcode_value_type_invalid;
            if (WlanQueryInterface(client, &item.InterfaceGuid, wlan_intf_opcode_current_connection, nullptr,
                    &size, &data, &opcode) == ERROR_SUCCESS) {
                auto connection = static_cast<WLAN_CONNECTION_ATTRIBUTES*>(data);
                auto& ssid = connection->wlanAssociationAttributes.dot11Ssid;
                result.assign(reinterpret_cast<char*>(ssid.ucSSID), ssid.uSSIDLength);
                WlanFreeMemory(data);
            }
        }
        WlanFreeMemory(interfaces);
    }
    WlanCloseHandle(client, nullptr);
    return result;
}
}

int32_t nl_lan_info(char* ipv4, int32_t ipv4_capacity, uint8_t* ssid, int32_t ssid_capacity) {
    // ipv4 receives a comma-separated list: Wi-Fi addresses first, then Ethernet.
    if (ipv4 == nullptr || ipv4_capacity < INET_ADDRSTRLEN || ssid == nullptr || ssid_capacity < DOT11_SSID_MAX_LENGTH) {
        set_error("LAN info output buffer is too small");
        return -1;
    }
    ULONG adapter_size = 16 * 1024;
    std::vector<uint8_t> adapter_buffer(adapter_size);
    auto adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(adapter_buffer.data());
    ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    DWORD adapter_result = GetAdaptersAddresses(AF_INET, flags, nullptr, adapters, &adapter_size);
    if (adapter_result == ERROR_BUFFER_OVERFLOW) {
        adapter_buffer.resize(adapter_size);
        adapters = reinterpret_cast<IP_ADAPTER_ADDRESSES*>(adapter_buffer.data());
        adapter_result = GetAdaptersAddresses(AF_INET, flags, nullptr, adapters, &adapter_size);
    }
    if (adapter_result != NO_ERROR) {
        set_error("Unable to enumerate LAN addresses");
        return -1;
    }
    std::vector<std::string> wifi;
    std::vector<std::string> ethernet;
    for (auto adapter = adapters; adapter != nullptr; adapter = adapter->Next) {
        if (adapter->OperStatus != IfOperStatusUp) continue;
        bool is_wifi = adapter->IfType == IF_TYPE_IEEE80211;
        bool is_ethernet = adapter->IfType == IF_TYPE_ETHERNET_CSMACD;
        if (!is_wifi && !is_ethernet) continue;
        // Skip Hyper-V/WSL/VPN virtual switches; they are not reachable from the Note.
        std::wstring description = adapter->Description ? adapter->Description : L"";
        if (description.find(L"Hyper-V") != std::wstring::npos ||
            description.find(L"Virtual") != std::wstring::npos ||
            description.find(L"VPN") != std::wstring::npos) {
            continue;
        }
        if (adapter->FirstGatewayAddress == nullptr) continue;
        for (auto address = adapter->FirstUnicastAddress; address != nullptr; address = address->Next) {
            if (address->Address.lpSockaddr->sa_family != AF_INET) continue;
            auto value = reinterpret_cast<sockaddr_in*>(address->Address.lpSockaddr);
            char text[INET_ADDRSTRLEN]{};
            if (InetNtopA(AF_INET, &value->sin_addr, text, sizeof(text)) == nullptr) continue;
            std::string host(text);
            if (host.rfind("169.254.", 0) == 0) continue;
            (is_wifi ? wifi : ethernet).push_back(host);
        }
    }
    std::string joined;
    for (auto const* list : {&wifi, &ethernet}) {
        for (auto const& host : *list) {
            if (!joined.empty()) joined += ',';
            joined += host;
        }
    }
    if (joined.empty()) {
        set_error("No LAN IPv4 address is available");
        return -1;
    }
    if (static_cast<int32_t>(joined.size()) >= ipv4_capacity) {
        joined.resize(static_cast<size_t>(ipv4_capacity - 1));
        auto comma = joined.rfind(',');
        if (comma != std::string::npos) joined.resize(comma);
    }
    std::memcpy(ipv4, joined.c_str(), joined.size() + 1);
    auto name = connected_ssid();
    auto ssid_length = static_cast<int32_t>(std::min<size_t>(name.size(), static_cast<size_t>(ssid_capacity)));
    if (ssid_length > 0) std::memcpy(ssid, name.data(), static_cast<size_t>(ssid_length));
    return ssid_length;
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
