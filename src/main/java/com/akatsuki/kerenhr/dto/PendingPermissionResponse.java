package com.akatsuki.kerenhr.dto;

import java.util.List;

public record PendingPermissionResponse(
    String id,
    String permission,
    List<String> patterns
) {
}
