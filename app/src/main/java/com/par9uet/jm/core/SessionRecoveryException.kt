package com.par9uet.jm.core

import com.par9uet.jm.core.network.NetWorkResult

/** Keeps structured recovery errors intact across the throwing SDK boundary. */
class SessionRecoveryException(val error: NetWorkResult.Error) :
    IllegalStateException(error.message, error.cause)
