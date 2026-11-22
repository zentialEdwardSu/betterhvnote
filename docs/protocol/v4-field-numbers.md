# NoteLink v4 field-number registry

Source of truth for `transfer-core/src/main/proto/notelink/v4/*.proto`.

Rules (see `design.md` §4.6):

1. Numbers and names are append-only. A removed field is `reserved` by both number and name
   **before** the next release.
2. `1-15` are hot fields (one-byte tags); `16-127` optional/feature fields; `128+` diagnostic.
3. Enum zero is always `*_UNSPECIFIED`; enum values are never renumbered or reused.
4. `Frame` uses one field number per message type. New messages start at 16 (two-byte tag).

## Envelope

| Field | Number | Type |
|---|---|---|
| `client_hello` | 1 | `ClientHello` |
| `server_hello` | 2 | `ServerHello` |
| `fetch_request` | 3 | `FetchRequest` |
| `fetch_result` | 4 | `FetchResult` |
| `lease_update` | 5 | `LeaseUpdate` |
| `stream_open` | 6 | `StreamOpen` |
| `stream_data` | 7 | `StreamData` |
| `stream_close` | 8 | `StreamClose` |
| `stream_reset` | 9 | `StreamReset` |
| `ack` | 10 | `Ack` |
| `ping` | 11 | `Ping` |
| `error` | 12 | `ProtocolError` |
| `push_offer` | 13 | `PushOffer` |
| `capability_update` | 14 | `CapabilityUpdate` |
| `stream_complete` | 15 | `StreamComplete` |
| `push_status` | 16 | `PushStatus` |
| *next* | 17+ | reserved for future message types |

## Handshake

| Message | Field | Number |
|---|---|---|
| `ClientHello` | `major` / `minor_min` / `minor_max` | 1 / 2 / 3 |
| | `device_id` / `client_time_ms` | 4 / 5 |
| | `capabilities` / `request` | 6 / 7 |
| | `resume_token` / `max_record_bytes` | 8 / 9 |
| | `listener_ticket` | 10 |
| `Request` | `fetch` / `push` / `ping` / `resume` | 1 / 2 / 3 / 4 |
| `PushIntent` | `item_id` / `kind` / `mime_type` | 1 / 2 / 3 |
| | `byte_length` / `sha256` / `display_name` | 4 / 5 / 6 |
| `ResumeRequest` | `session_id` / `received_bytes` | 1 / 2 |
| `ServerHello` | `major` / `minor` / `session_id` | 1 / 2 / 3 |
| | `server_nonce` / `capabilities` | 4 / 5 |
| | `offers` / `accepted_features` | 6 / 7 |
| | `keepalive_ms` / `server_time_ms` | 8 / 9 |
| | `listener_ticket` / `reject` | 10 / 11 |
| `Ping` / `Ack` / `CapabilityUpdate` | `sent_at_ms` / `message_id` / `capabilities` | 1 |

## Capabilities

| Message | Field | Number |
|---|---|---|
| `CapabilitySet` | `transports` / `lan` / `p2p` | 1 / 2 / 3 |
| | `ssid_fingerprint` / `max_streams` | 4 / 5 |
| | `max_record_bytes` / `max_inflight_bytes` | 6 / 7 |
| | `kinds` / `features` / `app_version` | 8 / 9 / 10 |
| `P2pGroup` | `ssid` / `group_owner_ip` / `port` / `band` / `state` | 1 / 2 / 3 / 4 / 5 |
| `Endpoint` | `host` / `port` | 1 / 2 |
| `SessionTicket` | `version` / `session_id` | 1 / 2 |
| | `listener_device_id` / `peer_device_id` | 3 / 4 |
| | `expires_at_ms` / `listener_port` / `connection_nonce` | 5 / 6 / 7 |

## Queue

| Message | Field | Number |
|---|---|---|
| `Offer` | `item_id` / `kind` / `mime_type` / `byte_length` | 1 / 2 / 3 / 4 |
| | `sha256` / `display_name` | 5 / 6 |
| | `lease_ttl_ms` / `queue_position` / `flags` | 7 / 8 / 9 |
| `FetchRequest` | `kind` / `max_items` / `flags` | 1 / 2 / 3 |
| `FetchResult` | `offers` / `queue_revision` / `more` | 1 / 2 / 3 |
| `LeaseUpdate` | `actions` | 1 |
| `LeaseAction` | `item_id` / `action` / `result` | 1 / 2 / 3 |

## Streams

| Message | Field | Number |
|---|---|---|
| `StreamOpen` | `item_id` / `total_bytes` / `sha256` | 1 / 2 / 3 |
| | `offset` / `kind` / `priority` / `initial_credit` | 4 / 5 / 6 / 7 |
| | `display_name` / `mime_type` / `flags` | 8 / 9 / 10 |
| `StreamData` | `data` / `offset` | 1 / 2 |
| `StreamAck` | `received_bytes` / `credit` | 1 / 2 |
| `StreamClose` / `StreamComplete` | `sha256` / `total_bytes` | 1 / 2 |
| `StreamReset` | `code` / `reason` / `retryable` | 1 / 2 / 3 |

## Push and discovery

| Message | Field | Number |
|---|---|---|
| `PushOffer` | `item_id` / `kind` / `mime_type` | 1 / 2 / 3 |
| | `byte_length` / `sha256` / `display_name` / `flags` | 4 / 5 / 6 / 7 |
| `PushStatus` | `item_id` / `state` / `bytes_transferred` | 1 / 2 / 3 |
| `UpdateIntent` | `current_version` / `operation_id` | 1 / 2 |
| `Discovery` | `major` / `identity_hash` / `flags` | 1 / 2 / 3 |
| | `image_count` / `text_count` / `pdf_count` | 4 / 5 / 6 |
| | `connectable_address` / `features` | 7 / 8 |

## Enums

| Enum | Values |
|---|---|
| `ContentKind` | 0 unspecified, 1 image, 2 text, 3 pdf, 4 app package |
| `ErrorCode` | 0 unspecified; 1–28 stable codes (`ERROR_*`) |
| `Feature` | bit masks: 1 LAN ticket, 2 multi-stream, 4 batch lease, 8 P2P 5 GHz, 16 P2P concurrent, 32 P2P credential join, 64 standalone ticket, 128 ECDH resume |
| `Band` | 0 unspecified, 1 2.4 GHz, 2 5 GHz, 3 auto |
| `P2pState` | 0 unspecified, 1 starting, 2 ready |
| `LeaseKind` | 0 unspecified, 1 heartbeat, 2 commit, 3 release |
| `StreamKind` | 0 unspecified, 1 file, 2 text, 3 control |
| `PushState` | 0 unspecified, 1 pending, 2 accepted, 3 complete, 4 rejected |

## Reserved ranges

- Most messages reserve a tail range for future fields (`reserved N to M`), so an accidental
  field number cannot be added without editing the reservation. `Frame` is the exception: new
  message types append to its `oneof` starting at 16.
- File-level `reserved 19000 to 19999` is inherited from protobuf; do not use it.
