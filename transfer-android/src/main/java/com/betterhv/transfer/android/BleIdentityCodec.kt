package com.betterhv.transfer.android

import com.betterhv.transfer.core.DeviceId
import com.betterhv.transfer.core.NoteLinkIdentityCodec

object BleIdentityCodec {
    fun encode(deviceId: String, deviceName: String, imageCount: Int, textCount: Int): ByteArray {
        return NoteLinkIdentityCodec.encode(DeviceId(deviceId), deviceName, imageCount, textCount)
    }

    fun decode(bytes: ByteArray): BleIdentity {
        val identity = NoteLinkIdentityCodec.decode(bytes)
        return BleIdentity(
            identity.protocolVersion,
            identity.imageCount,
            identity.textCount,
            identity.deviceId.value,
            identity.deviceName
        )
    }
}
