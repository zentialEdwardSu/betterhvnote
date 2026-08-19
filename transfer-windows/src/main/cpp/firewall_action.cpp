#include <windows.h>
#include <netfw.h>
#include <oleauto.h>
#include <string_view>

namespace {
constexpr wchar_t kRuleName[] = L"NoteLink TCP 39817";

HRESULT set_text(HRESULT (INetFwRule::*setter)(BSTR), wchar_t const* value, INetFwRule* rule) {
    BSTR text = SysAllocString(value);
    if (!text) return E_OUTOFMEMORY;
    HRESULT result = (rule->*setter)(text);
    SysFreeString(text);
    return result;
}

HRESULT add_rule(INetFwRules* rules) {
    INetFwRule* rule = nullptr;
    HRESULT result = CoCreateInstance(__uuidof(NetFwRule), nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&rule));
    if (FAILED(result)) return result;
    if (SUCCEEDED(result)) result = set_text(&INetFwRule::put_Name, kRuleName, rule);
    if (SUCCEEDED(result)) result = set_text(&INetFwRule::put_Description, L"Allows encrypted NoteLink transfers from N10Pro.", rule);
    if (SUCCEEDED(result)) result = rule->put_Protocol(NET_FW_IP_PROTOCOL_TCP);
    if (SUCCEEDED(result)) result = set_text(&INetFwRule::put_LocalPorts, L"39817", rule);
    if (SUCCEEDED(result)) result = rule->put_Direction(NET_FW_RULE_DIR_IN);
    if (SUCCEEDED(result)) result = rule->put_Action(NET_FW_ACTION_ALLOW);
    if (SUCCEEDED(result)) result = rule->put_Profiles(NET_FW_PROFILE2_ALL);
    if (SUCCEEDED(result)) result = rule->put_Enabled(VARIANT_TRUE);
    if (SUCCEEDED(result)) {
        BSTR name = SysAllocString(kRuleName);
        if (!name) result = E_OUTOFMEMORY;
        else {
            // Make repair and upgrade idempotent.
            rules->Remove(name);
            result = rules->Add(rule);
            SysFreeString(name);
        }
    }
    rule->Release();
    return result;
}

HRESULT remove_rule(INetFwRules* rules) {
    BSTR name = SysAllocString(kRuleName);
    if (!name) return E_OUTOFMEMORY;
    HRESULT result = rules->Remove(name);
    SysFreeString(name);
    // An already absent rule must not block uninstall.
    return FAILED(result) ? S_OK : result;
}
}

int main(int argc, char** argv) {
    HRESULT result = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    bool uninitialize = SUCCEEDED(result);
    if (result == RPC_E_CHANGED_MODE) result = S_OK;
    INetFwPolicy2* policy = nullptr;
    INetFwRules* rules = nullptr;
    if (SUCCEEDED(result)) result = CoCreateInstance(__uuidof(NetFwPolicy2), nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&policy));
    if (SUCCEEDED(result)) result = policy->get_Rules(&rules);
    if (SUCCEEDED(result)) {
        result = argc > 1 && std::string_view(argv[1]) == "remove" ? remove_rule(rules) : add_rule(rules);
    }
    if (rules) rules->Release();
    if (policy) policy->Release();
    if (uninitialize) CoUninitialize();
    return SUCCEEDED(result) ? 0 : 1;
}
