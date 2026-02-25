package com.openclaw.android.ui

import androidx.lifecycle.ViewModel
import com.openclaw.android.data.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    val preferencesManager: PreferencesManager,
) : ViewModel()
