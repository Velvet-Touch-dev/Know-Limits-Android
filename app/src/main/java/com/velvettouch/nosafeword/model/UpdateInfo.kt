package com.velvettouch.nosafeword.model

import com.google.gson.annotations.SerializedName

data class UpdateInfo(
    @SerializedName("latestVersionCode")
    val latestVersionCode: Int,

    @SerializedName("latestVersionName")
    val latestVersionName: String,

    @SerializedName("apkUrl")
    val apkUrl: String,

    @SerializedName("releaseNotes")
    val releaseNotes: String?, // Optional

    @SerializedName("minRequiredVersionCode")
    val minRequiredVersionCode: Int? // Optional
)
