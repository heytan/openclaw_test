package com.openclaw.car.map

import android.annotation.SuppressLint
import android.os.Parcel

class VoiceDeepSearchModel : ProtocolBaseModel {

    private var voiceDeepSearchFilterList: ArrayList<VoiceDeepSearchFilterBean> = arrayListOf()
    var hasFilters: Boolean = false
        private set
    private var isOnlyDoFilter: Boolean = false
    var sessionId: String = ""
        private set
    var query: String = ""
        private set
    private var classifyDataList: ArrayList<String> = arrayListOf()
    private var val1: String = ""

    constructor()

    constructor(protocolID: Int, searchKey: String) : super(protocolID, searchKey)

    constructor(
        protocolID: Int,
        searchKey: String,
        filters: List<VoiceDeepSearchFilterBean>,
        hasFilters: Boolean
    ) : super(protocolID, searchKey) {
        this.voiceDeepSearchFilterList = ArrayList(filters)
        this.hasFilters = hasFilters
    }

    @SuppressLint("NewApi")
    constructor(parcel: Parcel) : super(parcel) {
        voiceDeepSearchFilterList = parcel.createTypedArrayList(VoiceDeepSearchFilterBean.CREATOR) ?: arrayListOf()
        hasFilters = parcel.readBoolean()
        isOnlyDoFilter = parcel.readBoolean()
        sessionId = parcel.readString() ?: ""
        query = parcel.readString() ?: ""
        classifyDataList = parcel.createStringArrayList() ?: arrayListOf()
        val1 = parcel.readString() ?: ""
    }

    fun setFilters(filters: List<VoiceDeepSearchFilterBean>) {
        voiceDeepSearchFilterList = ArrayList(filters)
        hasFilters = true
    }

    fun setQuery(q: String) { query = q }
    fun setSessionId(id: String) { sessionId = id }

    @SuppressLint("NewApi")
    override fun writeToParcel(parcel: Parcel, flags: Int) {
        super.writeToParcel(parcel, flags)
        parcel.writeTypedList(voiceDeepSearchFilterList)
        parcel.writeBoolean(hasFilters)
        parcel.writeBoolean(isOnlyDoFilter)
        parcel.writeString(sessionId)
        parcel.writeString(query)
        parcel.writeStringList(classifyDataList)
        parcel.writeString(val1)
    }

    companion object CREATOR : android.os.Parcelable.Creator<VoiceDeepSearchModel> {
        override fun createFromParcel(parcel: Parcel) = VoiceDeepSearchModel(parcel)
        override fun newArray(size: Int) = arrayOfNulls<VoiceDeepSearchModel>(size)
    }
}
