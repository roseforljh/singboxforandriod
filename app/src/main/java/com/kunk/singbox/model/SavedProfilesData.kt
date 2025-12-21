package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SavedProfilesData(
    @SerializedName("profiles") val profiles: List<ProfileUi>,
    @SerializedName("activeProfileId") val activeProfileId: String?
)