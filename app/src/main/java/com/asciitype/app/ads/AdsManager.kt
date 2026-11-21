package com.asciitype.app.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.asciitype.app.BuildConfig
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Anúncio intersticial (AdMob) exibido enquanto o vídeo é gerado.
 *
 * Fluxo: consentimento (UMP, exigido por LGPD/GDPR) -> inicializa o SDK -> pré-carrega um
 * intersticial -> exibe ao tocar em "Gerar vídeo" -> pré-carrega o próximo.
 */
object AdsManager {

    /**
     * Intervalo mínimo entre dois anúncios. Evita mostrar anúncio atrás de anúncio quando a
     * pessoa gera vários vídeos seguidos ajustando as opções (o AdMob penaliza excesso).
     * Use 0 para exibir literalmente em toda geração.
     */
    private const val MIN_INTERVAL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initialized = AtomicBoolean(false)
    private var consent: ConsentInformation? = null
    private var interstitial: InterstitialAd? = null
    private var loading = false
    private var lastShownAt = 0L

    /** Se verdadeiro, a interface deve oferecer o botão "Privacidade" (exigência do UMP). */
    var privacyOptionsRequired by mutableStateOf(false)
        private set

    /** Chamar no onCreate da Activity. */
    fun gatherConsent(activity: Activity) {
        val info = UserMessagingPlatform.getConsentInformation(activity)
        consent = info
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    updatePrivacyState()
                    if (info.canRequestAds()) initialize(activity.applicationContext)
                }
            },
            { if (info.canRequestAds()) initialize(activity.applicationContext) },
        )
        // Consentimento de uma sessão anterior: já pode carregar anúncios.
        if (info.canRequestAds()) initialize(activity.applicationContext)
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { updatePrivacyState() }
    }

    /** Exibe o intersticial se houver um carregado e o intervalo mínimo já tiver passado. */
    fun maybeShow(activity: Activity) {
        val app = activity.applicationContext
        val ad = interstitial
        if (ad == null) {
            if (initialized.get()) load(app)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastShownAt != 0L && now - lastShownAt < MIN_INTERVAL_MS) return

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = load(app)
            override fun onAdFailedToShowFullScreenContent(error: AdError) = load(app)
        }
        interstitial = null
        lastShownAt = now
        ad.show(activity)
    }

    private fun updatePrivacyState() {
        privacyOptionsRequired = consent?.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    private fun initialize(app: Context) {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            withContext(Dispatchers.IO) { MobileAds.initialize(app) {} }
            load(app)
        }
    }

    private fun load(app: Context) {
        if (interstitial != null || loading) return
        loading = true
        InterstitialAd.load(
            app,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitial = ad
                    loading = false
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitial = null
                    loading = false
                }
            },
        )
    }
}
