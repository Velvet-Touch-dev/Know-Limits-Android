package com.velvettouch.nosafeword

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class PositionsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PositionsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PositionsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}