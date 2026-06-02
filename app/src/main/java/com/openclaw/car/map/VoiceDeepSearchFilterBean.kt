package com.openclaw.car.map

import android.os.Parcel
import android.os.Parcelable

class VoiceDeepSearchFilterBean : Parcelable {

    var type: String = ""
        private set
    var operator: String = ""
        private set
    var value: String = ""
        private set
    var val1: String = ""
        private set

    constructor()

    constructor(type: String, operator: String, value: String, val1: String = "") {
        this.type = type
        this.operator = operator
        this.value = value
        this.val1 = val1
    }

    constructor(parcel: Parcel) {
        type = parcel.readString() ?: ""
        operator = parcel.readString() ?: ""
        value = parcel.readString() ?: ""
        val1 = parcel.readString() ?: ""
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(type)
        parcel.writeString(operator)
        parcel.writeString(value)
        parcel.writeString(val1)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VoiceDeepSearchFilterBean> {
        override fun createFromParcel(parcel: Parcel) = VoiceDeepSearchFilterBean(parcel)
        override fun newArray(size: Int) = arrayOfNulls<VoiceDeepSearchFilterBean>(size)
    }
}
