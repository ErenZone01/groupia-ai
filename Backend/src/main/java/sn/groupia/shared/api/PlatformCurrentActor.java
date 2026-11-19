package sn.groupia.shared.api;

import java.util.UUID;

/** Active platform actor, verified on the server; never accept an actor ID from a request. */
public interface PlatformCurrentActor {
    UUID requireActiveUserId();
}
