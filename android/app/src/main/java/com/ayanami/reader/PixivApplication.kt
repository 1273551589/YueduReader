package com.ayanami.reader

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.ayanami.reader.core.pixiv.PixivClient
import com.ayanami.reader.core.pixiv.PixivImageUrls
import com.ayanami.reader.core.net.DynamicProxySelector
import com.ayanami.reader.data.Settings
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * 全局应用类。
 *
 * ### 两个职责
 *
 * 1. **给 Coil 装图床拦截器** —— `i.pximg.net` 不带
 *    `Referer: https://www.pixiv.net/` 就是 403（实测）。
 *    拦截器必须挂全局：书封、头像、大图、Tag 墙全走 Coil，
 *    在每处 `ImageRequest` 单独加 header 漏一处就裂一张图。
 *
 * 2. **让 Coil 也走用户配的代理** —— v0.7.0 的 ImageLoader
 *    完全不读 `Settings.proxyUrl`，结果「配了代理，Pixiv 页文字能刷出来、
 *    图片全是灰块」。这里用 [DynamicProxySelector]：每次请求现读设置，
 *    改代理/清代理**立刻生效，不用重启**。
 */
class PixivApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    // 动态代理：每次请求读当前设置，改完即时生效
                    .proxySelector(DynamicProxySelector(applicationContext))
                    .addInterceptor(Interceptor { chain ->
                        val req = chain.request()
                        val url = req.url.toString()
                        val b = req.newBuilder()
                        if (PixivImageUrls.isPixivImage(url)) {
                            b.header("Referer", "https://www.pixiv.net/")
                            b.header("User-Agent", PixivClient.UA)
                        }
                        chain.proceed(b.build())
                    })
                    .build()
            }
            .crossfade(150)
            .build()
}
