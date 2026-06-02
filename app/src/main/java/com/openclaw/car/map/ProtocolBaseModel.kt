package com.openclaw.car.map

import android.os.Parcel
import android.os.Parcelable

open class ProtocolBaseModel : Parcelable {

    open var protocolID: Int = 0
    open var timeStamp: Long = 0
    open var callbackId: Int = 0
    var protocolVersion: String = "0"
    open var packageName: String = ""
    var var1: String = ""
    var actionType: Int = Int.MIN_VALUE
    var operaType: Int = Int.MIN_VALUE
    var searchKey: String = ""
    var destPoiName: String = ""
    var errorCode: Int = 0
    var destLatitude: String = ""
    var destLongitude: String = ""
    var passPoiName: String = ""
    var passLatitude: String = ""
    var passLongitude: String = ""
    var isMainCab: Boolean = true
    var isNavi: Boolean = false
    var isWaypoint: Boolean = false
    var searchQueryType: Int = 1

    constructor()

    constructor(protocolID: Int) {
        this.protocolID = protocolID
    }

    constructor(protocolID: Int, searchKey: String) {
        this.protocolID = protocolID
        this.searchKey = searchKey
    }

    constructor(parcel: Parcel) {
        protocolID = parcel.readInt()
        timeStamp = parcel.readLong()
        callbackId = parcel.readInt()
        protocolVersion = parcel.readString() ?: "0"
        packageName = parcel.readString() ?: ""
        var1 = parcel.readString() ?: ""
        actionType = parcel.readInt()
        operaType = parcel.readInt()
        searchKey = parcel.readString() ?: ""
        destPoiName = parcel.readString() ?: ""
        errorCode = parcel.readInt()
        destLatitude = parcel.readString() ?: ""
        destLongitude = parcel.readString() ?: ""
        passPoiName = parcel.readString() ?: ""
        passLatitude = parcel.readString() ?: ""
        passLongitude = parcel.readString() ?: ""
        isMainCab = parcel.readInt() != 0
        isNavi = parcel.readInt() != 0
        isWaypoint = parcel.readInt() != 0
        searchQueryType = parcel.readInt()
    }

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(protocolID)
        parcel.writeLong(timeStamp)
        parcel.writeInt(callbackId)
        parcel.writeString(protocolVersion)
        parcel.writeString(packageName)
        parcel.writeString(var1)
        parcel.writeInt(actionType)
        parcel.writeInt(operaType)
        parcel.writeString(searchKey)
        parcel.writeString(destPoiName)
        parcel.writeInt(errorCode)
        parcel.writeString(destLatitude)
        parcel.writeString(destLongitude)
        parcel.writeString(passPoiName)
        parcel.writeString(passLatitude)
        parcel.writeString(passLongitude)
        parcel.writeInt(if (isMainCab) 1 else 0)
        parcel.writeInt(if (isNavi) 1 else 0)
        parcel.writeInt(if (isWaypoint) 1 else 0)
        parcel.writeInt(searchQueryType)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<ProtocolBaseModel> {
        override fun createFromParcel(parcel: Parcel): ProtocolBaseModel = ProtocolBaseModel(parcel)
        override fun newArray(size: Int): Array<ProtocolBaseModel?> = arrayOfNulls(size)

        // ProtocolID constants
        const val ID_MAP_OPERA = 30000
        const val ID_BACK_TO_MAP = 30001
        const val ID_CLOSE_MAP = 30002
        const val ID_ALONG_WAY_SEARCH = 30003
        const val ID_GOTO_HOME_COMPANY = 30010
        const val ID_NAVI_OPERA = 30011
        const val ID_CHECK_HOME_COMPANY = 30020
        const val ID_KEYWORD_SEARCH = 30300
        const val ID_AROUND_SEARCH = 30301
        const val ID_SEARCH_EN_ROUTE = 30302
        const val ID_TRAFFIC_INFO = 30404
        const val ID_NAVI_VIEW_OPERA = 30406
        const val ID_SEND_TO_CAR = 30411
        const val ID_CONTINUE_NAVI = 30422
        const val ID_NAVI_TTS = 30423
        const val ID_MY_LOCATION = 30509
        const val ID_PAGE_JUMP = 31001
        const val ID_SHOW_MY_LOCATION = 31002
        const val ID_SEARCH_RESULT_SELECT = 31003
        const val ID_NAVI_TO_POI = 31004
        const val ID_NAVI_TO_POI_VIA_PASS = 31005
        const val ID_AROUND_SEARCH_POI = 31006
        const val ID_CLOSE_PAGE = 31007
        const val ID_PREFERENCE = 31008
        const val ID_VOLUME = 31009
        const val ID_ADD_FAVOURITE = 31013
        const val ID_ADD_VIA_POI = 31014
        const val ID_DEL_VIA_PASS = 31015
        const val ID_REGISTER_ROAD_TYPE = 32001
        const val ID_DISMISS = 34000
        const val ID_STATUS_QUERY = 34005
        const val ID_IS_APP_ACTIVATED = 34006
        const val ID_IS_APP_PERMIT = 34007
        const val ID_HOME_COMPANY_VIEW = 34008
        const val ID_TTS_BACK = 34011
        const val ID_TTS_PLAYING = 34012

        // actionType for GOTO_HOME_COMPANY
        const val ACTION_NAVI_HOME = 60002
        const val ACTION_NAVI_COMPANY = 60003
        const val ACTION_NAVI_HOME_NOT_SET = 60004
        const val ACTION_NAVI_COMPANY_NOT_SET = 60005
        const val ACTION_SET_HOME = 60000
        const val ACTION_SET_COMPANY = 60001
    }
}
