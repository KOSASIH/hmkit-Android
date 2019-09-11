package com.highmobility.hmkit

import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.webkit.*
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import kotlinx.android.synthetic.main.fragment_oauth_web_view.*
import timber.log.Timber.d

internal class WebViewFragment : Fragment() {
    private lateinit var iWebView: IWebView
    private lateinit var url: String

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_oauth_web_view, container, false)
        return view!!
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webView.webViewClient = null
        webView.webChromeClient = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient
        webView.loadUrl(url)
    }

    private val webChromeClient: WebChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            if (newProgress == 100) progressBar.visibility = GONE
        }
    }

    private val webViewClient: WebViewClient = object : WebViewClient() {
        @SuppressWarnings("deprecation")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (url == null) return false
            return shouldOverrideUrlLoading(url)
        }

        @RequiresApi(Build.VERSION_CODES.N)
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return shouldOverrideUrlLoading(request?.url.toString())
        }

        fun shouldOverrideUrlLoading(url:String) : Boolean {
            if (URLUtil.isNetworkUrl(url) == false) {
                iWebView.onStartedLoadingUrl(url)
                return true
            }

            return false
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                d("error ${error?.description}")
                iWebView.onReceivedError(error?.description as String?)
            }
            else {
                iWebView.onReceivedError(error.toString())
            }
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(webView: IWebView, url: String): WebViewFragment {
            val fragment = WebViewFragment()
            fragment.iWebView = webView
            fragment.url = url
            return fragment
        }
    }
}

interface IWebView {
    fun onStartedLoadingUrl(url: String?)
    fun onReceivedError(error: String?)
}