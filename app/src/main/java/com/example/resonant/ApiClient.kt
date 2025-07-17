package com.example.resonant

import android.content.Context
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ApiClient {
    private const val BASE_URL = "https://resonantapp.ddns.net:8443/"

    private var retrofit: Retrofit? = null

    fun getService(context: Context): ApiResonantService {
        val appContext = context.applicationContext
        if (retrofit == null) {
            val okHttpClient = OkHttpClient.Builder()
                .addInterceptor(AuthInterceptor(appContext))
                .authenticator(TokenAuthenticator(appContext))
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }

        return retrofit!!.create(ApiResonantService::class.java)
    }

}

