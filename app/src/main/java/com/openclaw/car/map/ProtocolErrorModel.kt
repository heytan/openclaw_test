package com.openclaw.car.map

import android.os.Parcel

class ProtocolErrorModel : ProtocolBaseModel {

    var err: Int = 0
    var errorMessage: String = ""
    var count: Int = 0

    constructor()

    constructor(error: Int, protocolID: Int, callbackId: Int, packageName: String, timeStamp: Long) {
        this.err = error
        this.protocolID = protocolID
        this.callbackId = callbackId
        this.packageName = packageName
        this.timeStamp = timeStamp
    }

    constructor(parcel: Parcel) : super(parcel) {
        err = parcel.readInt()
        errorMessage = parcel.readString() ?: ""
        count = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        super.writeToParcel(parcel, flags)
        parcel.writeInt(err)
        parcel.writeString(errorMessage)
        parcel.writeInt(count)
    }

    companion object CREATOR : android.os.Parcelable.Creator<ProtocolErrorModel> {
        override fun createFromParcel(parcel: Parcel): ProtocolErrorModel = ProtocolErrorModel(parcel)
        override fun newArray(size: Int): Array<ProtocolErrorModel?> = arrayOfNulls(size)
    }
}
