/*
 * Copyright (c) 2019 DuckDuckGo
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

package com.duckduckgo.app.onboarding.ui.page

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.ViewPropertyAnimatorCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.duckduckgo.anvil.annotations.InjectWith
import com.duckduckgo.app.browser.R
import com.duckduckgo.app.global.extensions.html
import com.duckduckgo.appbuildconfig.api.AppBuildConfig
import com.duckduckgo.di.scopes.FragmentScope
import com.duckduckgo.mobile.android.R as CommonR
import javax.inject.Inject
import kotlinx.android.synthetic.main.content_onboarding_welcome.*
import kotlinx.android.synthetic.main.include_dax_dialog_cta.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
@InjectWith(FragmentScope::class)
class WelcomePage : OnboardingPageFragment() {

    @Inject
    lateinit var viewModelFactory: WelcomePageViewModelFactory

    @Inject
    lateinit var appBuildConfig: AppBuildConfig

    private var ctaText: String = ""
    private var welcomeAnimation: ViewPropertyAnimatorCompat? = null
    private var typingAnimation: ViewPropertyAnimatorCompat? = null
    private var welcomeAnimationFinished = false

    // we use a BroadcastChannel because we don't want to emit the last value upon subscription
    private val events = BroadcastChannel<WelcomePageView.Event>(1)

    private val welcomePageViewModel: WelcomePageViewModel by lazy {
        ViewModelProvider(this, viewModelFactory).get(WelcomePageViewModel::class.java)
    }

    override fun layoutResource(): Int = R.layout.content_onboarding_welcome

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        configureDaxCta()
        scheduleWelcomeAnimation()
        setSkipAnimationListener()

        lifecycleScope.launch {
            events.asFlow()
                .flatMapLatest { welcomePageViewModel.reduce(it) }
                .collect(::render)
        }
    }

    private fun render(state: WelcomePageView.State) {
        when (state) {
            WelcomePageView.State.Idle -> {}
            is WelcomePageView.State.ShowDefaultBrowserDialog -> {
                showDefaultBrowserDialog(state.intent)
            }
            WelcomePageView.State.Finish -> {
                onContinuePressed()
            }
        }
    }

    private fun event(event: WelcomePageView.Event) {
        lifecycleScope.launch {
            events.send(event)
        }
    }

    private fun showDefaultBrowserDialog(intent: Intent) {
        startActivityForResult(intent, DEFAULT_BROWSER_ROLE_MANAGER_DIALOG)
    }

    override fun onResume() {
        super.onResume()
        applyFullScreenFlags()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        welcomeAnimation?.cancel()
        typingAnimation?.cancel()
    }

    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        if (requestCode == DEFAULT_BROWSER_ROLE_MANAGER_DIALOG) {
            if (resultCode == RESULT_OK) {
                event(WelcomePageView.Event.OnDefaultBrowserSet)
            } else {
                event(WelcomePageView.Event.OnDefaultBrowserNotSet)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun applyFullScreenFlags() {
        activity?.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            decorView.systemUiVisibility += View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.BLACK
        }
        ViewCompat.requestApplyInsets(longDescriptionContainer)
    }

    private fun configureDaxCta() {
        context?.let {
            ctaText = it.getString(R.string.onboardingDaxText)
            hiddenTextCta.text = ctaText.html(it)
            dialogTextCta.textInDialog = ctaText.html(it)
            dialogTextCta.setTextColor(ContextCompat.getColor(it, CommonR.color.grayishBrown))
            cardView.backgroundTintList = ContextCompat.getColorStateList(it, CommonR.color.white)
        }
        triangle.setImageResource(R.drawable.ic_triangle_bubble_white)
    }

    private fun setSkipAnimationListener() {
        longDescriptionContainer.setOnClickListener {
            if (dialogTextCta.hasAnimationStarted()) {
                finishTypingAnimation()
            } else if (!welcomeAnimationFinished) {
                welcomeAnimation?.cancel()
                scheduleWelcomeAnimation(0L)
            }
            welcomeAnimationFinished = true
        }
    }

    private fun scheduleWelcomeAnimation(startDelay: Long = ANIMATION_DELAY) {
        welcomeAnimation = ViewCompat.animate(welcomeContent as View)
            .alpha(MIN_ALPHA)
            .setDuration(ANIMATION_DURATION)
            .setStartDelay(startDelay)
            .withEndAction {
                typingAnimation = ViewCompat.animate(daxCtaContainer)
                    .alpha(MAX_ALPHA)
                    .setDuration(ANIMATION_DURATION)
                    .withEndAction {
                        welcomeAnimationFinished = true
                        dialogTextCta.startTypingAnimation(ctaText)
                        setPrimaryCtaListenerAfterWelcomeAlphaAnimation()
                    }
            }
    }

    private fun finishTypingAnimation() {
        welcomeAnimation?.cancel()
        dialogTextCta.finishAnimation()
        setPrimaryCtaListenerAfterWelcomeAlphaAnimation()
    }

    private fun setPrimaryCtaListenerAfterWelcomeAlphaAnimation() {
        primaryCta.setOnClickListener { event(WelcomePageView.Event.OnPrimaryCtaClicked) }
    }

    companion object {
        private const val MIN_ALPHA = 0f
        private const val MAX_ALPHA = 1f
        private const val ANIMATION_DURATION = 400L
        private const val ANIMATION_DELAY = 1400L

        private const val DEFAULT_BROWSER_ROLE_MANAGER_DIALOG = 101
    }
}
