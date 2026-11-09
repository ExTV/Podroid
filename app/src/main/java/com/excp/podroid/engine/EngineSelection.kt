/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine

/**
 * User-facing backend choice. Persisted in DataStore as the enum name string.
 * AUTO = detect AVF at startup, fall back to QEMU. 99% case.
 */
enum class EngineSelection { AUTO, AVF, QEMU, UML }
