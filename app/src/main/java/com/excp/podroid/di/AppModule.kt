/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024 Podroid contributors
 *
 * Hilt module for dependency injection.
 */
package com.excp.podroid.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    // Podroid doesn't need any provided dependencies.
    // All dependencies use constructor injection.
}
