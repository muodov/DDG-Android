/*
 * Copyright (c) 2022 DuckDuckGo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.duckduckgo.site.permissions.impl

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import android.webkit.PermissionRequest
import androidx.activity.result.ActivityResultCaller
import androidx.annotation.StringRes
import com.duckduckgo.app.global.extractDomain
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.mobile.android.ui.view.toPx
import com.duckduckgo.site.permissions.api.SitePermissionsDialogLauncher
import com.duckduckgo.site.permissions.api.SitePermissionsGrantedListener
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.SnackbarLayout
import com.squareup.anvil.annotations.ContributesBinding
import dagger.SingleInstanceIn
import javax.inject.Inject

@ContributesBinding(FragmentScope::class)
@SingleInstanceIn(FragmentScope::class)
class SitePermissionsDialogActivityLauncher @Inject constructor(
    private val systemPermissionsHelper: SystemPermissionsHelper,
    private val sitePermissionsRepository: SitePermissionsRepository,
) : SitePermissionsDialogLauncher {

    private lateinit var sitePermissionRequest: PermissionRequest
    private lateinit var activity: Activity
    private lateinit var permissionRequested: SitePermissionsRequestedType
    private lateinit var permissionsGrantedListener: SitePermissionsGrantedListener
    private var siteURL: String = ""
    private var tabId: String = ""

    override fun registerPermissionLauncher(caller: ActivityResultCaller) {
        systemPermissionsHelper.registerPermissionLaunchers(
            caller,
            this::onResultSystemPermissionRequest,
            this::onResultMultipleSystemPermissionsRequest,
        )
    }

    override fun askForSitePermission(
        activity: Activity,
        url: String,
        tabId: String,
        permissionsRequested: Array<String>,
        request: PermissionRequest,
        permissionsGrantedListener: SitePermissionsGrantedListener,
    ) {
        sitePermissionRequest = request
        siteURL = url
        this.tabId = tabId
        this.activity = activity
        this.permissionsGrantedListener = permissionsGrantedListener

        when {
            permissionsRequested.size == 2 -> {
                showSitePermissionsRationaleDialog(R.string.sitePermissionsMicAndCameraDialogTitle, url, this::askForMicAndCameraPermissions)
            }
            permissionsRequested.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) -> {
                showSitePermissionsRationaleDialog(R.string.sitePermissionsMicDialogTitle, url, this::askForMicPermissions)
            }
            permissionsRequested.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) -> {
                showSitePermissionsRationaleDialog(R.string.sitePermissionsCameraDialogTitle, url, this::askForCameraPermissions)
            }
        }
    }

    private fun showSitePermissionsRationaleDialog(
        @StringRes titleRes: Int,
        url: String,
        onPermissionAllowed: () -> Unit,
    ) {
        AlertDialog.Builder(activity).apply {
            setTitle(String.format(activity.getString(titleRes), url.websiteFromGeoLocationsApiOrigin()))
            setPositiveButton(R.string.sitePermissionsDialogAllowButton) { _, _ ->
                onPermissionAllowed()
            }
            setNegativeButton(R.string.sitePermissionsDialogDenyButton) { _, _ ->
                sitePermissionRequest.deny()
            }
            setCancelable(false)
            show()
        }
    }

    private fun askForMicAndCameraPermissions() {
        permissionRequested = SitePermissionsRequestedType.CAMERA_AND_AUDIO
        when {
            systemPermissionsHelper.hasMicPermissionsGranted() && systemPermissionsHelper.hasCameraPermissionsGranted() -> {
                systemPermissionGranted()
            }
            systemPermissionsHelper.hasMicPermissionsGranted() -> {
                systemPermissionsHelper.requestPermission(Manifest.permission.CAMERA)
            }
            systemPermissionsHelper.hasCameraPermissionsGranted() -> {
                systemPermissionsHelper.requestMultiplePermissions(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS,
                    ),
                )
            }
            else -> {
                systemPermissionsHelper.requestMultiplePermissions(
                    arrayOf(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.MODIFY_AUDIO_SETTINGS,
                        Manifest.permission.CAMERA,
                    ),
                )
            }
        }
    }

    private fun askForMicPermissions() {
        permissionRequested = SitePermissionsRequestedType.AUDIO
        if (systemPermissionsHelper.hasMicPermissionsGranted()) {
            systemPermissionGranted()
        } else {
            systemPermissionsHelper.requestMultiplePermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.MODIFY_AUDIO_SETTINGS))
        }
    }

    private fun askForCameraPermissions() {
        permissionRequested = SitePermissionsRequestedType.CAMERA
        if (systemPermissionsHelper.hasCameraPermissionsGranted()) {
            systemPermissionGranted()
        } else {
            systemPermissionsHelper.requestPermission(Manifest.permission.CAMERA)
        }
    }

    private fun onResultSystemPermissionRequest(granted: Boolean) {
        when (granted) {
            true -> systemPermissionGranted()
            false -> systemPermissionDenied()
        }
    }

    private fun onResultMultipleSystemPermissionsRequest(grantedPermissions: Map<String, Boolean>) {
        if (grantedPermissions.values.contains(false)) {
            systemPermissionDenied()
        } else {
            systemPermissionGranted()
        }
    }

    private fun systemPermissionGranted() {
        when (permissionRequested) {
            SitePermissionsRequestedType.CAMERA -> {
                sitePermissionRequest.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                sitePermissionsRepository.sitePermissionGranted(siteURL, tabId, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            }
            SitePermissionsRequestedType.AUDIO -> {
                sitePermissionRequest.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                sitePermissionsRepository.sitePermissionGranted(siteURL, tabId, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            }
            SitePermissionsRequestedType.CAMERA_AND_AUDIO -> {
                sitePermissionRequest.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE, PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                sitePermissionsRepository.sitePermissionGranted(siteURL, tabId, PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                sitePermissionsRepository.sitePermissionGranted(siteURL, tabId, PermissionRequest.RESOURCE_AUDIO_CAPTURE)
            }
        }
        checkIfActionNeeded()
    }

    private fun checkIfActionNeeded() {
        when (siteURL.extractDomain().orEmpty()) {
            "whereby.com" -> permissionsGrantedListener.permissionsGrantedOnWhereby()
            else -> {
                // No action needed
            }
        }
    }

    private fun systemPermissionDenied() {
        when (systemPermissionsHelper.isPermissionsRejectedForever(activity)) {
            true -> showSystemPermissionsDeniedDialog()
            false -> showPermissionsDeniedSnackBar()
        }
    }

    private fun showPermissionsDeniedSnackBar() {
        val onPermissionAllowed: () -> Unit
        val message =
            when (permissionRequested) {
                SitePermissionsRequestedType.CAMERA -> {
                    onPermissionAllowed = this::askForCameraPermissions
                    R.string.sitePermissionsCameraDeniedSnackBarMessage
                }
                SitePermissionsRequestedType.AUDIO -> {
                    onPermissionAllowed = this::askForMicPermissions
                    R.string.sitePermissionsMicDeniedSnackBarMessage
                }
                SitePermissionsRequestedType.CAMERA_AND_AUDIO -> {
                    onPermissionAllowed = this::askForMicAndCameraPermissions
                    R.string.sitePermissionsCameraAndMicDeniedSnackBarMessage
                }
            }

        val snackbar = Snackbar.make(activity.window.decorView.rootView, message, Snackbar.LENGTH_LONG)
        val layout = snackbar.view as SnackbarLayout
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            layout.setPadding(0, 0, 0, 40.toPx())
        }
        snackbar.apply {
            setAction(R.string.sitePermissionsDeniedSnackBarAction) {
                onPermissionAllowed()
            }
            show()
        }
    }

    private fun showSystemPermissionsDeniedDialog() {
        val titleRes = when (permissionRequested) {
            SitePermissionsRequestedType.CAMERA -> R.string.systemPermissionDialogCameraDeniedTitle
            SitePermissionsRequestedType.AUDIO -> R.string.systemPermissionDialogAudioDeniedTitle
            SitePermissionsRequestedType.CAMERA_AND_AUDIO -> R.string.systemPermissionDialogCameraAndAudioDeniedTitle
        }
        val contentRes = when (permissionRequested) {
            SitePermissionsRequestedType.CAMERA -> R.string.systemPermissionDialogCameraDeniedContent
            SitePermissionsRequestedType.AUDIO -> R.string.systemPermissionDialogAudioDeniedContent
            SitePermissionsRequestedType.CAMERA_AND_AUDIO -> R.string.systemPermissionDialogCameraAndAudioDeniedContent
        }

        AlertDialog.Builder(activity).apply {
            setTitle(titleRes)
            setMessage(contentRes)
            setPositiveButton(R.string.systemPermissionsDeniedDialogPositiveButton) { _, _ ->
                val intent = Intent()
                intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                val uri = Uri.fromParts("package", activity.packageName, null)
                intent.data = uri
                context.startActivity(intent)
            }
            setNegativeButton(R.string.systemPermissionsDeniedDialogNegativeButton) { _, _ -> }
            show()
        }
    }
}

fun String.websiteFromGeoLocationsApiOrigin(): String {
    val webPrefix = "www."
    val uri = Uri.parse(this)
    val host = uri.host ?: return this

    return host
        .takeIf { it.startsWith(webPrefix, ignoreCase = true) }
        ?.drop(webPrefix.length)
        ?: host
}

enum class SitePermissionsRequestedType {
    CAMERA,
    AUDIO,
    CAMERA_AND_AUDIO,
}
