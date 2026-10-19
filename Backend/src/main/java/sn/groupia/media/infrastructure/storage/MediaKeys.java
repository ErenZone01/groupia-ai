package sn.groupia.media.infrastructure.storage;

import sn.groupia.media.domain.service.MediaStorageException;

final class MediaKeys {
    private MediaKeys() {}

    static String require(String key) {
        if (key == null
                || !key.matches(
                        "organizations/[0-9a-f-]{36}/products/[0-9a-f-]{36}/[0-9a-f-]{36}/v1/(display|thumbnail)\\.(jpg|png)"))
            throw new MediaStorageException();
        return key;
    }
}
