package com.lagradost.shiro.ui
import com.lagradost.shiro.utils.fv

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import com.lagradost.shiro.R
import com.lagradost.shiro.utils.AniListApi.Companion.authenticateLogin
import com.lagradost.shiro.utils.Event
import com.lagradost.shiro.utils.MALApi.Companion.authenticateMalLogin
import com.lagradost.shiro.utils.NekomoApi.Companion.USER_AGENT

private const val URL = "URL"

class WebViewFragment : Fragment() {
    private var url: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            url = it.getString(URL)
        }
        url?.let {
            fv<android.webkit.WebView>(R.id.webView).webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                    if (url.startsWith("http://") || url.startsWith("https://")) return false
                    return try {
                        if (url.startsWith("shiroapp")) {
                            if (url.contains("/anilistlogin")) {
                                activity?.authenticateLogin(url)
                            } else if (url.contains("/mallogin")) {
                                activity?.authenticateMalLogin(url)
                            }
                            activity?.onBackPressed()
                        }

                        //val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        //view.context.startActivity(intent)
                        true
                    } catch (e: Exception) {
                        true
                    }
                }
            }
            fv<android.webkit.WebView>(R.id.webView).settings.domStorageEnabled = true
            fv<android.webkit.WebView>(R.id.webView).settings.userAgentString = USER_AGENT
            fv<android.webkit.WebView>(R.id.webView).settings.javaScriptEnabled = true
            fv<android.webkit.WebView>(R.id.webView).loadUrl(it)

        }
    }

    override fun onResume() {
        super.onResume()
        onWebViewNavigated.invoke(true)
        isInWebView = true
    }

    override fun onDestroy() {
        super.onDestroy()
        onWebViewNavigated.invoke(false)
        isInWebView = false
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_web_view, container, false)
    }

    companion object {
        var isInWebView = false
        val onWebViewNavigated = Event<Boolean>()
        fun newInstance(url: String) =
            WebViewFragment().apply {
                arguments = Bundle().apply {
                    putString(URL, url)
                }
            }
    }
}