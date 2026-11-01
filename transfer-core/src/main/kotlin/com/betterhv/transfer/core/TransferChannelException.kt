package com.betterhv.transfer.core

class TransferChannelException(val failure: TransferFailure, cause: Throwable? = null) :
  IllegalStateException(failure.message, cause)
