package com.openclaw.car.map

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

interface IProtocolCallback : IInterface {

    fun onFail(error: ProtocolErrorModel?)
    fun onJSONResult(result: String?)
    fun onSuccess(result: String?)

    abstract class Stub : Binder(), IProtocolCallback {

        companion object {
            const val DESCRIPTOR = "com.autosdk.protocol.listener.IProtocolCallback"
            const val TRANSACTION_onFail = 1
            const val TRANSACTION_onJSONResult = 2
            const val TRANSACTION_onSuccess = 3

            fun asInterface(binder: IBinder?): IProtocolCallback? {
                if (binder == null) return null
                val local = binder.queryLocalInterface(DESCRIPTOR)
                if (local is IProtocolCallback) return local
                return Proxy(binder)
            }
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                TRANSACTION_onFail -> {
                    data.enforceInterface(DESCRIPTOR)
                    val error = if (data.readInt() != 0) ProtocolErrorModel.CREATOR.createFromParcel(data) else null
                    onFail(error)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_onJSONResult -> {
                    data.enforceInterface(DESCRIPTOR)
                    onJSONResult(data.readString())
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_onSuccess -> {
                    data.enforceInterface(DESCRIPTOR)
                    onSuccess(data.readString())
                    reply?.writeNoException()
                    return true
                }
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }

        private class Proxy(private val remote: IBinder) : IProtocolCallback {

            override fun asBinder(): IBinder = remote

            override fun onFail(error: ProtocolErrorModel?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    if (error != null) {
                        data.writeInt(1)
                        error.writeToParcel(data, 0)
                    } else {
                        data.writeInt(0)
                    }
                    remote.transact(TRANSACTION_onFail, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun onJSONResult(result: String?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(result)
                    remote.transact(TRANSACTION_onJSONResult, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            override fun onSuccess(result: String?) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    data.writeString(result)
                    remote.transact(TRANSACTION_onSuccess, data, reply, 0)
                    reply.readException()
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }
        }
    }
}
