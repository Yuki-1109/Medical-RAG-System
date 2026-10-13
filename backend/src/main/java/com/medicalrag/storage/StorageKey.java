package com.medicalrag.storage;

import java.util.UUID;
import java.util.regex.Pattern;

record StorageKey(String namespace, long knowledgeBaseId, UUID token, OriginalType type) {
    private static final Pattern KEY = Pattern.compile("(documents|images)/([1-9][0-9]{0,18})/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/original\\.(pdf|jpg|png)");
    static StorageKey parse(String value) {
        if (value == null) throw StorageException.unsafe();
        var match = KEY.matcher(value);
        if (!match.matches()) throw StorageException.unsafe();
        try {
            OriginalType type = switch (match.group(4)) { case "pdf" -> OriginalType.PDF; case "jpg" -> OriginalType.JPEG; default -> OriginalType.PNG; };
            if (!match.group(1).equals(type.namespace())) throw StorageException.unsafe();
            return new StorageKey(match.group(1), Long.parseLong(match.group(2)), UUID.fromString(match.group(3)), type);
        } catch (NumberFormatException exception) { throw StorageException.unsafe(); }
    }
    String value() { return namespace + "/" + knowledgeBaseId + "/" + token + "/original." + type.extension(); }
}
