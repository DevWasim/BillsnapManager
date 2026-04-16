package com.billsnap.manager.ui.camera

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/**
 * ViewModel for camera state management.
 * Retains camera facing direction across configuration changes.
 */
class CameraViewModel : ViewModel() {

    private val _isBackCamera = MutableLiveData(true)
    val isBackCamera: LiveData<Boolean> = _isBackCamera

    fun toggleCameraFacing() {
        _isBackCamera.value = _isBackCamera.value?.not()
    }
}
