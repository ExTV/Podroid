# Credits & Acknowledgements

Podroid is built upon the work of many talented open source developers and communities.
We are deeply grateful to everyone listed here.

---

## Core Engine: QEMU

**QEMU** is the heart of Podroid. It provides the machine emulation and virtualization.

- **Original Author:** Fabrice Bellard
- **Website:** https://www.qemu.org
- **Repository:** https://gitlab.com/qemu-project/qemu
- **License:** GNU General Public License v2.0 (and later)
- **Copyright:** The QEMU Project developers

Notable QEMU contributors whose work this project relies on:
- Fabrice Bellard — original author
- The QEMU community — https://www.qemu.org/contributors/

---

## Android Integration Foundation: Limbo PC Emulator

**Limbo** pioneered running QEMU on Android and solved countless platform-specific challenges
that Podroid builds upon. Much of the JNI layer, SDL integration, and Android lifecycle
management in this project is derived from Limbo's work.

- **Author:** Max Kastanas (max2idea)
- **Repository:** https://github.com/limboemu/limbo
- **License:** GNU General Public License v2.0
- **Copyright:** Copyright (C) Max Kastanas 2012 and Limbo contributors

Limbo contributors whose work influenced this project:
- Max Kastanas — principal developer
- All contributors at https://github.com/limboemu/limbo/graphs/contributors

---

## SDL2 (Simple DirectMedia Layer)

SDL2 is used as the graphics/input backend.

- **Author:** Sam Lantinga and SDL contributors
- **Website:** https://libsdl.org
- **License:** zlib License
- **Copyright:** Copyright (C) 1997-2024 Sam Lantinga

---

## musl libc

musl is a lightweight C standard library used for Android compatibility shims.

- **Author:** Rich Felker and musl contributors
- **Website:** https://musl.libc.org
- **License:** MIT License
- **Copyright:** Copyright © 2005-2020 Rich Felker, et al.

---

## Android Jetpack

Podroid uses the following AndroidX / Jetpack libraries:

| Library | Copyright |
|---------|-----------|
| Jetpack Compose | Copyright (C) Google LLC |
| Room | Copyright (C) Google LLC |
| Navigation Compose | Copyright (C) Google LLC |
| ViewModel / Lifecycle | Copyright (C) Google LLC |
| DataStore | Copyright (C) Google LLC |
| Hilt (Dagger) | Copyright (C) Google LLC / The Dagger Authors |

All licensed under the Apache License 2.0.
See https://source.android.com/setup/start/licenses

---

## Kotlin

The application is written in **Kotlin**.

- **Developer:** JetBrains s.r.o.
- **Website:** https://kotlinlang.org
- **License:** Apache License 2.0

---

## Podroid

Podroid itself is developed by **excp** and contributors.

- **Repository:** https://github.com/excp/Podroid *(update with actual URL)*
- **License:** GNU General Public License v2.0 (or later)

If you have contributed to Podroid and are not listed here, please open an issue or pull
request to have your name added.

---

## How to Attribute

If you distribute a modified version of Podroid, you must:

1. Retain this CREDITS.md and all upstream license notices
2. Credit the original QEMU and Limbo projects
3. Make your modifications available under GPL v2.0 or later
4. Clearly indicate that your version is modified

---

## Reporting Missing Credits

If you believe your work is used in Podroid but not properly credited, please open an issue
at the project repository. We take attribution seriously.
