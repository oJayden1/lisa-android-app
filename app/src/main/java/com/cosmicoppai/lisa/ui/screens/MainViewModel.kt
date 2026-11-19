package com.cosmicoppai.lisa.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cosmicoppai.lisa.data.local.LisaSettings
import com.cosmicoppai.lisa.data.remote.CookieChallengeRequest
import com.cosmicoppai.lisa.data.repository.MediaRepository
import com.cosmicoppai.lisa.model.AppMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    val settings: StateFlow<LisaSettings> = repository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LisaSettings()
    )

    val activeDownloadCount: StateFlow<Int> = repository.observeActiveDownloads()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val interactiveChallenge: StateFlow<CookieChallengeRequest?> = repository.interactiveChallenge

    fun setAppMode(mode: AppMode) {
        viewModelScope.launch { repository.setAppMode(mode) }
    }

    fun completeInteractiveChallenge(
        req: CookieChallengeRequest,
        finalUrl: String,
        userAgent: String
    ) {
        viewModelScope.launch {
            repository.persistInteractiveChallenge(
                siteName = req.siteName,
                siteUrl = finalUrl.ifBlank { req.siteUrl },
                userAgentOverride = userAgent
            )
            req.onCompleted(true)
        }
    }

    fun dismissInteractiveChallenge() {
        repository.dismissInteractiveChallenge()
    }
}
