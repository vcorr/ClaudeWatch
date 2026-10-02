package com.vcorr.claudewatch

/** Wearable Data Layer message paths used to send the API key from the phone to the watch. */
object KeySync {
    const val PATH_KEY = "/claudewatch/api_key"
    const val PATH_SAVED = "/claudewatch/api_key_saved"
    const val PATH_FAILED = "/claudewatch/api_key_failed"
}
