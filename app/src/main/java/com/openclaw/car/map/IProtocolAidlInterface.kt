package com.openclaw.car.map

import android.os.Binder
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.RemoteException

interface IProtocolAidlInterface : IInterface {

    fun setProtocolModelData(model: ProtocolBaseModel?)
    fun registCallBack(callback: IProtocolCallback?)
    fun registerCallBack(callback: IProtocolCallback?, id: Int)
    fun getNaviState(): Boolean
    fun isForegroundState(): Boolean
    fun getMapState(type: Int): String?
    fun setICompatibleIDVersion(version: Int)
    fun setVoiceDeepSearchModelData(model: VoiceDeepSearchModel?)
    companion object {
        const val DESCRIPTOR = "com.autosdk.protocol.IProtocolAidlInterface"
        const val TRANSACTION_setProtocolModelData = 1
        const val TRANSACTION_registCallBack = 2
        const val TRANSACTION_registerCallBack = 3
        const val TRANSACTION_getNaviState = 4
        const val TRANSACTION_isForegroundState = 5
        const val TRANSACTION_setICompatibleIDVersion = 6
        const val TRANSACTION_getMapState = 7
        const val TRANSACTION_setVoiceDeepSearchModelData = 8

        fun asInterface(binder: IBinder?): IProtocolAidlInterface? {
            if (binder == null) return null
            val local = binder.queryLocalInterface(DESCRIPTOR)
            if (local is IProtocolAidlInterface) return local
            return Proxy(binder)
        }
    }

    class Proxy(private val remote: IBinder) : IProtocolAidlInterface {

        override fun asBinder(): IBinder = remote

        override fun setProtocolModelData(model: ProtocolBaseModel?) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                if (model != null) {
                    data.writeInt(1)
                    model.writeToParcel(data, 0)
                } else {
                    data.writeInt(0)
                }
                remote.transact(TRANSACTION_setProtocolModelData, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun registCallBack(callback: IProtocolCallback?) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(callback?.asBinder())
                remote.transact(TRANSACTION_registCallBack, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun registerCallBack(callback: IProtocolCallback?, id: Int) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeStrongBinder(callback?.asBinder())
                data.writeInt(id)
                remote.transact(TRANSACTION_registerCallBack, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun getNaviState(): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(TRANSACTION_getNaviState, data, reply, 0)
                reply.readException()
                return reply.readInt() != 0
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun isForegroundState(): Boolean {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(TRANSACTION_isForegroundState, data, reply, 0)
                reply.readException()
                return reply.readInt() != 0
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun getMapState(type: Int): String? {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(type)
                remote.transact(TRANSACTION_getMapState, data, reply, 0)
                reply.readException()
                return reply.readString()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun setICompatibleIDVersion(version: Int) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeInt(version)
                remote.transact(TRANSACTION_setICompatibleIDVersion, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        override fun setVoiceDeepSearchModelData(model: VoiceDeepSearchModel?) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                if (model != null) {
                    data.writeInt(1)
                    model.writeToParcel(data, 0)
                } else {
                    data.writeInt(0)
                }
                remote.transact(TRANSACTION_setVoiceDeepSearchModelData, data, reply, 0)
                reply.readException()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }
}
