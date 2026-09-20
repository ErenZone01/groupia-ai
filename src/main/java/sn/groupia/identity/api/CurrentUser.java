package sn.groupia.identity.api;

import java.util.UUID;

public interface CurrentUser {
    /** Requires an authenticated, still-active account. Never accepts an actor id from the client. */
    UUID requireActiveUserId();
}
