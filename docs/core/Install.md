---
title: Install
---

Requires JDK 17+ and Maven 3.6+.

```shell
./build.sh      # checks tools, builds the fat JAR (pkspkms-desktop/target/pkspkms-desktop-0.1.0.jar)
./install.sh    # installs to ~/.local and creates the `pkspkms` command
```

`build.sh` checks your tools and their versions before compiling.

`install.sh`:

- Copies the JAR to `~/.local/share/pkspkms/pkspkms.jar`
- Creates a wrapper script at `~/.local/bin/pkspkms`

Options:

| Flag | Effect |
|------|--------|
| `--system` | install to `/usr/local` (needs sudo) |
| `--prefix <dir>` | install under a custom prefix |
| `--uninstall` | remove the installed files |
| `--no-build` | skip the build, use the existing JAR |
