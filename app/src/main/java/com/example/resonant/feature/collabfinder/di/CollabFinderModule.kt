package com.example.resonant.feature.collabfinder.di

import com.example.resonant.feature.collabfinder.data.remote.CollabFinderApiService
import com.example.resonant.feature.collabfinder.data.repository.CollabFinderRepositoryImpl
import com.example.resonant.feature.collabfinder.domain.repository.CollabFinderRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CollabFinderModule {

    @Provides
    @Singleton
    fun provideCollabFinderApiService(@dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context): CollabFinderApiService =
        com.example.resonant.data.network.ApiClient.getRetrofitInstance(context).create(CollabFinderApiService::class.java)

    @Provides
    @Singleton
    fun provideCollabFinderRepository(
        apiService: CollabFinderApiService
    ): CollabFinderRepository = CollabFinderRepositoryImpl(apiService)
}
