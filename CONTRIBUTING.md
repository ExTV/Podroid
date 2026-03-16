# Contributing to Podroid

Thank you for your interest in contributing! Podroid is a fully open source project and
welcomes contributions of all kinds.

---

## Ways to Contribute

- **Bug reports** — Open an issue with steps to reproduce
- **Feature requests** — Open an issue describing the feature and its motivation
- **Code** — Submit a pull request (see below)
- **Documentation** — Improve README, inline docs, or wiki pages
- **Testing** — Test on different devices and report results

---

## Development Setup

1. Clone the repo:
   ```bash
   git clone https://github.com/excp/Podroid
   cd Podroid
   ```

2. Build QEMU:
   ```bash
   export ANDROID_NDK_HOME=/path/to/ndk
   ./scripts/build-qemu.sh
   ```

3. Open in Android Studio (Ladybug or newer)

4. Build and run on an ARM64 Android device (API 34+)

---

## Code Style

- **Kotlin**: Follow the official [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **C/JNI**: Follow the existing style in `app/src/main/jni/`
- Use `ktlint` for formatting: `./gradlew ktlintFormat`
- All public functions and classes must have KDoc documentation

---

## Pull Request Guidelines

1. Fork the repository and create a branch: `feature/your-feature` or `fix/your-bug`
2. Write tests for new functionality where applicable
3. Ensure all existing tests pass: `./gradlew test`
4. Keep pull requests focused — one feature or fix per PR
5. Update documentation if your change affects user-facing behavior
6. Add yourself to CREDITS.md if you haven't contributed before

---

## Commit Message Format

```
type(scope): short description

Longer description if needed. Explain WHY, not WHAT.

Fixes #123
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `build`, `chore`

Examples:
```
feat(vnc): add ZRLE encoding support for faster display updates
fix(engine): prevent crash when QEMU exits before VNC connects
docs(readme): update build instructions for NDK r27
```

---

## License

By contributing to Podroid, you agree that your contributions will be licensed under the
**GNU General Public License v2.0** (or later), the same license as the project.

---

## Attribution

All contributors are credited in [CREDITS.md](CREDITS.md). Please add your name in your
first pull request.

---

## Code of Conduct

Be respectful and constructive. We are all here to build something great together.
