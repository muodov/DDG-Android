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

package com.duckduckgo.privacy.config.impl.network

import com.duckduckgo.anvil.annotations.ContributesServiceApi
import com.duckduckgo.di.scopes.AppScope
import com.duckduckgo.privacy.config.impl.models.JsonPrivacyConfig
import retrofit2.http.GET

@ContributesServiceApi(AppScope::class)
interface PrivacyConfigService {
    @GET("https://staticcdn.duckduckgo.com/trackerblocking/config/v2/android-config.json")
    suspend fun privacyConfig(): JsonPrivacyConfig
}
