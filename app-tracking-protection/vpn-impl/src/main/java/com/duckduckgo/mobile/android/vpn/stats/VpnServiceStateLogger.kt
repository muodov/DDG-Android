/*
 * Copyright (c) 2021 DuckDuckGo
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

package com.duckduckgo.mobile.android.vpn.stats

import android.annotation.SuppressLint
import com.duckduckgo.app.utils.ConflatedJob
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.di.scopes.VpnScope
import com.duckduckgo.mobile.android.vpn.model.AlwaysOnState
import com.duckduckgo.mobile.android.vpn.model.VpnServiceState
import com.duckduckgo.mobile.android.vpn.model.VpnServiceStateStats
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.ERROR
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.RESTART
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.REVOKED
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.SELF_STOP
import com.duckduckgo.mobile.android.vpn.model.VpnStoppingReason.UNKNOWN
import com.duckduckgo.mobile.android.vpn.pixels.DeviceShieldPixels
import com.duckduckgo.mobile.android.vpn.service.TrackerBlockingVpnService
import com.duckduckgo.mobile.android.vpn.service.VpnServiceCallbacks
import com.duckduckgo.mobile.android.vpn.state.VpnStateMonitor.VpnStopReason
import com.duckduckgo.mobile.android.vpn.store.VpnDatabase
import com.squareup.anvil.annotations.ContributesMultibinding
import dagger.SingleInstanceIn
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.logcat

@ContributesMultibinding(
    scope = VpnScope::class,
    boundType = VpnServiceCallbacks::class,
)
@SingleInstanceIn(VpnScope::class)
class VpnServiceStateLogger @Inject constructor(
    private val vpnDatabase: VpnDatabase,
    private val vpnService: Provider<TrackerBlockingVpnService>,
    private val appBuildConfig: AppBuildConfig,
    private val deviceShieldPixels: DeviceShieldPixels,
) : VpnServiceCallbacks {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val job = ConflatedJob()

    override fun onVpnStarting(coroutineScope: CoroutineScope) {
        logcat { "VpnServiceStateLogger, new state ENABLING" }
        vpnDatabase.vpnServiceStateDao().insert(VpnServiceStateStats(state = VpnServiceState.ENABLING))
    }

    override fun onVpnStartFailed(coroutineScope: CoroutineScope) {
        onVpnStopped(coroutineScope, VpnStopReason.ERROR)
    }

    override fun onVpnStarted(coroutineScope: CoroutineScope) {
        job.cancel()

        job += coroutineScope.launch(dispatcher) {
            logcat { "VpnServiceStateLogger, new state ENABLED" }
            vpnDatabase.vpnServiceStateDao().insert(VpnServiceStateStats(state = VpnServiceState.ENABLED))

            @SuppressLint("NewApi") // IDE doesn't get we use appBuildConfig
            if (appBuildConfig.sdkInt >= 29) {
                incrementalPeriodicChecks {
                    val isAlwaysOnEnabled = vpnService.get().isAlwaysOn
                    val isLockdownEnabled = vpnService.get().isLockdownEnabled
                    if (isAlwaysOnEnabled) deviceShieldPixels.reportAlwaysOnEnabledDaily()
                    if (isLockdownEnabled) deviceShieldPixels.reportAlwaysOnLockdownEnabledDaily()

                    vpnDatabase.vpnServiceStateDao().insert(
                        VpnServiceStateStats(
                            state = VpnServiceState.ENABLED,
                            alwaysOnState = AlwaysOnState(isAlwaysOnEnabled, isLockdownEnabled),
                        ).also {
                            logcat { "VpnServiceStateLogger, state: $it" }
                        },
                    )
                }
            }
        }
    }

    @SuppressLint("NewApi") // IDE doesn't get we use appBuildConfig
    override fun onVpnStopped(
        coroutineScope: CoroutineScope,
        vpnStopReason: VpnStopReason,
    ) {
        job.cancel()

        coroutineScope.launch(dispatcher) {
            logcat { "VpnServiceStateLogger, new state DISABLED, reason $vpnStopReason" }
            val alwaysOnState = if (appBuildConfig.sdkInt >= 29) {
                val isAlwaysOnEnabled = vpnService.get().isAlwaysOn
                val isLockdownEnabled = vpnService.get().isLockdownEnabled
                AlwaysOnState(isAlwaysOnEnabled, isLockdownEnabled)
            } else {
                AlwaysOnState.ALWAYS_ON_DISABLED
            }

            vpnDatabase.vpnServiceStateDao().insert(
                VpnServiceStateStats(
                    state = VpnServiceState.DISABLED,
                    stopReason = mapStopReason(vpnStopReason),
                    alwaysOnState = alwaysOnState,
                ),
            )
        }
    }

    private fun mapStopReason(vpnStopReason: VpnStopReason): VpnStoppingReason {
        return when (vpnStopReason) {
            VpnStopReason.RESTART -> RESTART
            VpnStopReason.SELF_STOP -> SELF_STOP
            VpnStopReason.REVOKED -> REVOKED
            VpnStopReason.ERROR -> ERROR
            VpnStopReason.UNKNOWN -> UNKNOWN
        }
    }

    private suspend fun incrementalPeriodicChecks(
        times: Int = Int.MAX_VALUE,
        initialDelay: Long = 500, // 0.5 second
        maxDelay: Long = 300_000, // 5 minutes
        factor: Double = 1.05, // 5% increase
        block: suspend () -> Unit,
    ) {
        var currentDelay = initialDelay
        repeat(times - 1) {
            try {
                block()
            } catch (t: Throwable) {
                // you can log an error here and/or make a more finer-grained
                // analysis of the cause to see if retry is needed
            }
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
}
