package com.ditchoom.socket.udp

import com.ditchoom.buffer.flow.AddressedDatagramChannel
import com.ditchoom.buffer.flow.ExperimentalDatagramApi

@ExperimentalDatagramApi
actual fun AddressedDatagramChannel.requestKernelReceiveBuffer(bytes: Int): KernelBufferGrant = KernelBufferGrant.Unsupported
