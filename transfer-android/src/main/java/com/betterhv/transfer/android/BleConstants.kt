package com.betterhv.transfer.android

import java.util.UUID

object BleConstants {
  val SERVICE_UUID: UUID = UUID.fromString("b6e4f100-77b7-4d35-9f52-3b4be45c3010")
  val CONTROL_UUID: UUID = UUID.fromString("b6e4f110-77b7-4d35-9f52-3b4be45c3010")
  val INFO_UUID: UUID = UUID.fromString("b6e4f111-77b7-4d35-9f52-3b4be45c3010")
  val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
