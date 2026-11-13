package io.pskenny.pkspkms.repo;

/**
 * When base/luabase embeds render relative to a vault load. Embed passes are
 * global over every mounted vault, so the server pattern is: mount each vault
 * with {@link #DEFERRED}, then call {@code processEmbeds()} once.
 */
public enum EmbedMode {
    /** Render embeds at the end of this load (CLI export path). */
    RENDER_NOW,
    /** Seed EMBEDS rows only; the caller renders later via processEmbeds(). */
    DEFERRED,
}
