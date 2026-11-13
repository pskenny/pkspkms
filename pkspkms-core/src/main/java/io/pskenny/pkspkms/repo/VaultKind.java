package io.pskenny.pkspkms.repo;

/** What a registered vault alias points at (BUGS.md P3: boolean params → enums). */
public enum VaultKind {
    MAIN,
    VIRTUAL;

    public boolean isVirtual() {
        return this == VIRTUAL;
    }
}
