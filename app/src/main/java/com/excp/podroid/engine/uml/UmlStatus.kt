/*
 * Podroid - Rootless Podman for Android
 * Copyright (C) 2024-2026 Podroid contributors
 */
package com.excp.podroid.engine.uml

/** Outcome of the on-device UML compatibility probe, as seen by backend selection. */
enum class UmlStatus { NOT_PROBED, PASSED, FAILED, DEMOTED }
